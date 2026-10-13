package fr.hash014.universalshop;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Opt-in disposable-server integration suite. Never enabled on normal installations. */
final class ServerTests {
  private final UniversalShop p;
  private int checks;

  ServerTests(UniversalShop p) {
    this.p = p;
  }

  interface Checked {
    void run() throws Exception;
  }

  private void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
    checks++;
  }

  private void rejects(Checked work, String reason) throws Exception {
    try {
      work.run();
    } catch (IllegalArgumentException | IllegalStateException expected) {
      checks++;
      return;
    }
    throw new AssertionError("Expected rejection: " + reason);
  }

  void run(CommandSender sender, String[] args) {
    if (!(sender instanceof org.bukkit.command.ConsoleCommandSender))
      throw new IllegalArgumentException("Tests require the console.");
    try {
      if (args.length < 2) throw new IllegalArgumentException("Test phase required.");
      if (args[1].equals("protocol")) {
        protocol();
        return;
      }
      if (args[1].equals("self")) {
        self();
        p.getLogger().info("SELFTEST PASS checks=" + checks);
        return;
      }
      Player player = Bukkit.getPlayerExact("Hash014Test"),
          buyer = Bukkit.getPlayerExact("Hash014Buyer");
      check(player != null && buyer != null, "Both network clients must be logged in.");
      if (args[1].equals("restart")) {
        restart(player);
        p.getLogger().info("RESTART PASS checks=" + checks);
        return;
      }
      // Run outside the console command execution context, as GUI confirmations do.
      // Modern Brigadier queues nested vanilla commands until the outer command returns.
      Bukkit.getScheduler()
          .runTask(
              p,
              () -> {
                try {
                  live(player, buyer);
                } catch (Throwable error) {
                  p.getLogger().log(java.util.logging.Level.SEVERE, "TEST FAIL", error);
                }
              });
    } catch (Throwable e) {
      p.getLogger().log(java.util.logging.Level.SEVERE, "TEST FAIL", e);
    }
  }

  private void self() throws Exception {
    check(p.products.size() >= 21, "Default catalog loaded");
    check(Money.parse("1.01") == 101, "Exact decimal parsing");
    check(p.service.deals().size() == 3, "Rotation count");
    check(p.service.deals().equals(p.service.deals()), "Stable rotation");
    Path folder = p.getDataFolder().toPath().resolve("test-ledger-" + UUID.randomUUID());
    Store first = new Store(folder);
    YamlConfiguration next = first.copy();
    next.set("wallets.test", 12345);
    first.prepare(next, "test interrupted", "fixture");
    Store recovered = new Store(folder);
    check(recovered.blocked(), "Pending survives restart");
    check(!recovered.state().contains("wallets.test"), "Uncommitted state not applied");
    recovered.reconcile(true);
    Store committed = new Store(folder);
    check(committed.state().getLong("wallets.test") == 12345, "Recovery commit persisted");
    check(!committed.blocked(), "Commit unlocks");
    next = committed.copy();
    next.set("wallets.test", 999);
    committed.prepare(next, "abort fixture", "");
    new Store(folder).reconcile(false);
    check(
        new Store(folder).state().getLong("wallets.test") == 12345,
        "Recovery abort preserves prior state");
    Path corrupt = folder.resolve("state.yml");
    Files.write(corrupt, "schema: [unclosed".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    boolean failed = false;
    try {
      new Store(folder);
    } catch (Exception expected) {
      failed = true;
    }
    check(failed, "Corrupt state fails closed");
  }

  private void live(final Player player, final Player buyer) throws Exception {
    if (p.service.economy.external()) {
      vault(player);
      return;
    }
    p.getConfig().set("cooldown-milliseconds", 0);
    p.getConfig().set("permission-discounts", null);
    player.setOp(true);
    buyer.setOp(true);
    player.setGameMode(GameMode.SURVIVAL);
    buyer.setGameMode(GameMode.SURVIVAL);
    player.getInventory().clear();
    buyer.getInventory().clear();
    p.service.setBalance(player.getUniqueId(), 10000000);
    p.service.setBalance(buyer.getUniqueId(), 10000000);
    final Product stone = p.product("stone");
    long initial = p.service.balance(player), cost = p.service.quote(player, stone, 64, true);
    p.service.trade(player, stone, 64, true, cost);
    check(Inventories.count(Inventories.snapshot(player), stone.item) == 64, "Purchase delivery");
    check(p.service.balance(player) == initial - cost, "Purchase debit");
    long revenue = p.service.quote(player, stone, 32, false);
    p.service.trade(player, stone, 32, false, revenue);
    check(Inventories.count(Inventories.snapshot(player), stone.item) == 32, "Sale removal");
    check(p.service.balance(player) == initial - cost + revenue, "Sale credit");
    rejects(() -> p.service.trade(player, stone, 0, true, null), "Zero amount");
    rejects(() -> p.service.trade(player, stone, -1, true, null), "Negative amount");
    rejects(() -> p.service.trade(player, stone, 2305, true, null), "Oversized amount");
    rejects(() -> p.service.trade(player, stone, 1, true, 999999L), "Stale quote");
    player.getInventory().clear();
    final long before = p.service.balance(player);
    rejects(() -> p.service.trade(player, stone, 1, false, null), "Missing items");
    check(p.service.balance(player) == before, "Rejected sale unchanged balance");
    for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Material.DIRT, 64));
    rejects(() -> p.service.trade(player, stone, 1, true, null), "Inventory full");
    check(p.service.balance(player) == before, "Inventory full no debit");
    player.getInventory().clear();
    ItemStack named = stone.item.clone();
    ItemMeta meta = named.getItemMeta();
    meta.setDisplayName("Protected custom stone");
    named.setItemMeta(meta);
    player.getInventory().setItem(0, named);
    rejects(() -> p.service.trade(player, stone, 1, false, null), "Custom item protection");
    check(player.getInventory().getItem(0).hasItemMeta(), "Custom item unchanged");
    player.getInventory().clear();
    p.service.setBalance(player.getUniqueId(), 0);
    rejects(() -> p.service.trade(player, stone, 1, true, null), "Insufficient funds");
    p.service.setBalance(player.getUniqueId(), 10000000);
    final Product diamond = p.product("diamond");
    p.service.trade(player, diamond, 32, true, null);
    rejects(() -> p.service.trade(player, diamond, 1, true, null), "Daily limit");
    YamlConfiguration exhausted = p.store.copy();
    exhausted.set("products.diamond.stock", 0L);
    exhausted.set(
        "products.diamond.restock", System.currentTimeMillis() / (diamond.restockHours * 3600000L));
    p.store.save(exhausted, "TEST stock fixture");
    rejects(() -> p.service.trade(buyer, diamond, 1, true, null), "Stock exhausted");
    player.setGameMode(GameMode.CREATIVE);
    rejects(() -> p.service.trade(player, stone, 1, true, null), "Creative trading");
    player.setGameMode(GameMode.SURVIVAL);
    player.getInventory().clear();
    player.getInventory().setItem(0, new ItemStack(Material.STONE, 20));
    player.getInventory().setItem(1, new ItemStack(Material.DIRT, 12));
    player.getInventory().setItem(2, named);
    Map<String, Integer> basket = p.service.sellBasket(player);
    check(basket.get("stone") == 20 && basket.get("dirt") == 12, "Bulk preview contents");
    long bulk = p.service.basketQuote(player, basket), bal = p.service.balance(player);
    p.service.sellAll(player, basket, bulk);
    check(p.service.balance(player) == bal + bulk, "Atomic bulk credit");
    check(player.getInventory().getItem(2).isSimilar(named), "Bulk protects metadata");
    long buyerBefore = p.service.balance(buyer);
    p.service.pay(player, buyer, 123);
    check(p.service.balance(buyer) == buyerBefore + 123, "Player payment");
    player.getInventory().clear();
    player.getInventory().setItem(0, new ItemStack(Material.EMERALD, 8));
    player.getInventory().setHeldItemSlot(0);
    String listing = p.service.list(player, 5, 10000);
    check(
        Inventories.count(Inventories.snapshot(player), new ItemStack(Material.EMERALD)) == 3,
        "Market escrow removes items");
    long sellerBalance = p.service.balance(player);
    buyerBefore = p.service.balance(buyer);
    p.service.marketTake(buyer, listing, false);
    check(p.service.balance(buyer) == buyerBefore - 10000, "Market buyer debit");
    check(p.service.balance(player) == sellerBalance + 9800, "Market seller credit and tax");
    check(
        Inventories.count(Inventories.snapshot(buyer), new ItemStack(Material.EMERALD)) == 5,
        "Market delivery");
    final String sold = listing;
    rejects(() -> p.service.marketTake(buyer, sold, false), "Listing cannot be bought twice");
    listing = p.service.list(player, 2, 100);
    p.service.marketTake(player, listing, true);
    check(
        Inventories.count(Inventories.snapshot(player), new ItemStack(Material.EMERALD)) == 3,
        "Market cancellation returns escrow");
    final String expired = p.service.list(player, 1, 100);
    YamlConfiguration expiry = p.store.copy();
    expiry.set("market." + expired + ".expires", 1L);
    p.store.save(expiry, "TEST expired listing");
    rejects(() -> p.service.marketTake(buyer, expired, false), "Expired listing");
    p.service.marketTake(player, expired, true);
    org.bukkit.permissions.PermissionAttachment permissions = player.addAttachment(p);
    permissions.setPermission("universalshop.buy", false);
    rejects(() -> p.service.trade(player, stone, 1, true, null), "Buy permission");
    permissions.unsetPermission("universalshop.buy");
    p.getConfig().set("disabled-worlds", Arrays.asList(player.getWorld().getName()));
    rejects(() -> p.service.trade(player, stone, 1, true, null), "World restriction");
    p.getConfig().set("disabled-worlds", Collections.emptyList());
    p.getConfig().set("permission-discounts.universalshop.vip", 25);
    permissions.setPermission("universalshop.vip", true);
    check(p.service.quote(player, stone, 1, true) == 150, "Permission discount");
    p.getConfig().set("permission-discounts", null);
    p.getConfig().set("buy-tax-percent", 10);
    check(p.service.quote(player, stone, 1, true) == 220, "Buy tax");
    p.getConfig().set("buy-tax-percent", 0);
    player.removeAttachment(permissions);
    YamlConfiguration serviceConfig = new YamlConfiguration();
    serviceConfig.set("material", "DIAMOND");
    serviceConfig.set("buy", 10);
    serviceConfig.set("sell", -1);
    serviceConfig.set("commands", Arrays.asList("give {player} diamond 1"));
    final Product commandProduct = new Product("test_service", serviceConfig);
    p.products.put(commandProduct.id, commandProduct);
    int diamondsBefore =
        Inventories.count(Inventories.snapshot(player), new ItemStack(Material.DIAMOND));
    p.service.trade(player, commandProduct, 1, true, null);
    check(
        Inventories.count(Inventories.snapshot(player), new ItemStack(Material.DIAMOND))
            == diamondsBefore + 1,
        "Console service delivery");
    rejects(
        () -> p.service.trade(player, commandProduct, 2, true, null),
        "Service quantity restriction");
    p.products.remove(commandProduct.id);
    YamlConfiguration pending = p.store.copy();
    p.store.prepare(pending, "TEST ambiguous transaction", "test fixture only");
    rejects(() -> p.service.trade(player, stone, 1, true, null), "Pending transaction lock");
    p.store.reconcile(false);
    p.menus.browse(player, "", 0);
    check(player.getOpenInventory().getTopInventory().getSize() == 54, "Browse GUI");
    InventoryClickEvent shift =
        new InventoryClickEvent(
            player.getOpenInventory(),
            InventoryType.SlotType.CONTAINER,
            54,
            ClickType.SHIFT_LEFT,
            InventoryAction.MOVE_TO_OTHER_INVENTORY);
    p.menus.click(shift);
    check(shift.isCancelled(), "Bottom shift click blocked");
    InventoryClickEvent swap =
        new InventoryClickEvent(
            player.getOpenInventory(),
            InventoryType.SlotType.CONTAINER,
            0,
            ClickType.NUMBER_KEY,
            InventoryAction.HOTBAR_SWAP,
            0);
    p.menus.click(swap);
    check(swap.isCancelled(), "Hotbar swap blocked");
    p.menus.confirm(player, stone, 1, true);
    final long beforeGui = p.service.balance(player);
    final int itemsBefore = Inventories.count(Inventories.snapshot(player), stone.item);
    final long guiPrice = p.service.quote(player, stone, 1, true);
    InventoryClickEvent click =
        new InventoryClickEvent(
            player.getOpenInventory(),
            InventoryType.SlotType.CONTAINER,
            11,
            ClickType.LEFT,
            InventoryAction.PICKUP_ALL);
    p.menus.click(click);
    p.menus.click(click);
    check(click.isCancelled(), "Confirm click consumed");
    Bukkit.getScheduler()
        .runTaskLater(
            p,
            new Runnable() {
              public void run() {
                try {
                  check(
                      p.service.balance(player) == beforeGui - guiPrice,
                      "Double confirm debits once");
                  check(
                      Inventories.count(Inventories.snapshot(player), stone.item)
                          == itemsBefore + 1,
                      "Double confirm delivers once");
                  fixture(player);
                  p.getLogger().info("PLAYERTEST PASS checks=" + checks);
                } catch (Throwable e) {
                  p.getLogger().log(java.util.logging.Level.SEVERE, "TEST FAIL", e);
                }
              }
            },
            4);
  }

  private void fixture(Player player) throws Exception {
    player.getInventory().clear();
    player.getInventory().setItem(0, new ItemStack(Material.EMERALD, 7));
    p.service.setBalance(player.getUniqueId(), 34567);
    player.saveData();
    YamlConfiguration next = p.store.copy();
    next.set("test.restart", true);
    p.store.save(next, "TEST restart fixture");
  }

  private void restart(Player player) throws Exception {
    if (p.service.economy.external()) {
      long actual = p.service.balance(player);
      check(actual == 34567, "Vault balance survives restart, actual cents=" + actual);
      check(
          Inventories.count(Inventories.snapshot(player), new ItemStack(Material.EMERALD)) == 7,
          "Vault player inventory survives restart");
      check(!p.store.blocked(), "Vault restart unlocked");
      return;
    }
    check(p.store.state().getBoolean("test.restart"), "Ledger survives server restart");
    check(p.service.balance(player) == 34567, "Exact wallet survives restart");
    check(
        Inventories.count(Inventories.snapshot(player), new ItemStack(Material.EMERALD)) == 7,
        "Inventory survives restart");
    check(!p.store.blocked(), "Clean restart does not lock trading");
  }

  private void vault(Player player) throws Exception {
    p.getConfig().set("cooldown-milliseconds", 0);
    p.getConfig().set("permission-discounts", null);
    player.setOp(true);
    player.setGameMode(GameMode.SURVIVAL);
    player.getInventory().clear();
    long previous = p.service.balance(player);
    if (previous > 0)
      check(p.service.economy.change(player, previous, false), "Provider reset balance");
    check(p.service.economy.change(player, 100000, true), "Provider funds test player");
    Product stone = p.product("stone");
    long before = p.service.balance(player);
    p.service.trade(player, stone, 64, true, null);
    check(p.service.balance(player) == before - 12800, "Vault purchase debit");
    check(
        Inventories.count(Inventories.snapshot(player), stone.item) == 64,
        "Vault purchase delivery");
    p.service.trade(player, stone, 32, false, null);
    check(p.service.balance(player) == before - 12800 + 1600, "Vault sale deposit");
    check(Inventories.count(Inventories.snapshot(player), stone.item) == 32, "Vault sale removal");
    check(
        !p.service.economy.change(player, Money.MAX, false),
        "Provider declines insufficient funds");
    check(!p.store.blocked(), "Successful Vault trades clear journal");
    rejects(() -> p.service.list(player, 1, 100), "Internal-only market unavailable in Vault mode");
    long balance = p.service.balance(player);
    check(p.service.economy.change(player, balance, false), "Provider drain");
    rejects(
        () -> p.service.trade(player, stone, 1, true, null), "Vault insufficient funds preflight");
    check(p.service.economy.change(player, 34567, true), "Vault persistence fixture");
    player.getInventory().clear();
    player.getInventory().setItem(0, new ItemStack(Material.EMERALD, 7));
    player.saveData();
    p.getLogger().info("PLAYERTEST PASS checks=" + checks);
  }

  private void protocol() throws Exception {
    Class<?> provider = Class.forName("net.minecraft.network.ProtocolInfo$DetailsProvider"),
        details = Class.forName("net.minecraft.network.ProtocolInfo$Details"),
        visitor = Class.forName("net.minecraft.network.ProtocolInfo$Details$PacketVisitor"),
        packet = Class.forName("net.minecraft.network.protocol.PacketType");
    StringBuilder out = new StringBuilder("{");
    boolean first = true;
    for (String state : Arrays.asList("login", "configuration", "game"))
      for (String flow : Arrays.asList("CLIENTBOUND", "SERVERBOUND")) {
        String prefix =
            state.equals("game") ? "Game" : state.equals("login") ? "Login" : "Configuration";
        Class<?> registry =
            Class.forName("net.minecraft.network.protocol." + state + "." + prefix + "Protocols");
        Object info =
            provider.getMethod("details").invoke(registry.getField(flow + "_TEMPLATE").get(null));
        final Map<String, Integer> ids = new LinkedHashMap<String, Integer>();
        Object collector =
            Proxy.newProxyInstance(
                visitor.getClassLoader(),
                new Class<?>[] {visitor},
                (proxy, method, args) -> {
                  if (method.getName().equals("accept"))
                    ids.put(packet.getMethod("id").invoke(args[0]).toString(), (Integer) args[1]);
                  return null;
                });
        details.getMethod("listPackets", visitor).invoke(info, collector);
        if (!first) out.append(',');
        first = false;
        out.append('"')
            .append(state)
            .append('_')
            .append(flow.toLowerCase(Locale.ROOT))
            .append("\":{");
        boolean entry = true;
        for (Map.Entry<String, Integer> id : ids.entrySet()) {
          if (!entry) out.append(',');
          entry = false;
          out.append('"').append(id.getKey()).append("\":").append(id.getValue());
        }
        out.append('}');
      }
    out.append('}');
    AtomicFiles.write(p.getDataFolder().toPath().resolve("protocol.json"), out.toString());
    p.getLogger().info("PROTOCOL READY");
  }
}
