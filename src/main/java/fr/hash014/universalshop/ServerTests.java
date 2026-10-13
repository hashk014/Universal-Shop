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
      if (args[1].equals("bedrock")) {
        Player player = Bukkit.getPlayerExact(".ShopBedrock");
        check(player != null, "Bedrock network client logged in");
        check(p.bedrock.available(player), "Real Geyser API detects Bedrock connection");
        Product stone = p.product("stone");
        String phase = args[2];
        if (phase.equals("start")) {
          p.getConfig().set("management.personal.enabled", true);
          p.getConfig().set("management.analytics.enabled", true);
          p.management = new ShopManagement(p);
          player.getInventory().clear();
          p.onCommand(
              Bukkit.getConsoleSender(),
              p.getCommand("shop"),
              "shop",
              new String[] {"admin", "balance", player.getName(), "100"});
          p.menus.browse(player, "stone", 0);
        } else if (phase.equals("bought")) {
          check(
              Inventories.count(Inventories.snapshot(player), stone.item) == 1,
              "Bedrock purchase delivered once");
          check(p.service.balance(player) == 9800, "Bedrock purchase charged once");
          check(p.management.catalog(player, "@favorites").size() == 1, "Bedrock favorite saved");
          check(
              p.management.catalog(player, "@recent").get(0).id.equals("stone"),
              "Bedrock recent purchase");
          check(
              p.store.state().getLong("management.stats.products.stone.bought") == 1,
              "Bedrock purchase analytics");
          p.menus.confirm(player, stone, 1, false);
        } else if (phase.equals("sold")) {
          check(
              Inventories.count(Inventories.snapshot(player), stone.item) == 0,
              "Bedrock sale removed item");
          check(p.service.balance(player) == 9850, "Bedrock sale credited once");
          p.onCommand(
              Bukkit.getConsoleSender(),
              p.getCommand("shop"),
              "shop",
              new String[] {"admin", "command", "disable", "buy"});
          rejects(() -> p.menus.confirm(player, stone, 1, true), "Disabled Bedrock buy");
          p.menus.browse(player, "stone", 0);
        } else if (phase.equals("finish")) {
          check(p.service.balance(player) == 9850, "Disabled action leaves balance unchanged");
          p.onCommand(
              Bukkit.getConsoleSender(),
              p.getCommand("shop"),
              "shop",
              new String[] {"admin", "command", "enable", "buy"});
          player.setOp(true);
          p.menus.adminPanel(player);
        } else if (phase.equals("panel")) {
          check(
              !p.management.enabled("analytics"),
              "Bedrock administrator toggled module through native form");
          player.setOp(false);
        } else throw new IllegalArgumentException("Unknown Bedrock phase");
        p.getLogger().info("BEDROCK PASS phase=" + phase + " checks=" + checks);
        return;
      }
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
    management(player, buyer);
    final Product stone = p.product("stone");
    p.onCommand(
        Bukkit.getConsoleSender(),
        p.getCommand("shop"),
        "shop",
        new String[] {"admin", "command", "disable", "buy"});
    rejects(
        () -> p.service.trade(player, stone, 1, true, null),
        "Disabled buy action blocks GUI/service");
    check(
        !p.onTabComplete(player, p.getCommand("shop"), "ushop", new String[] {"b"}).contains("buy"),
        "Disabled command hidden from alias completion");
    p.onCommand(player, p.getCommand("shop"), "universalshop:shop", new String[] {"buy", "stone"});
    check(
        !(player.getOpenInventory().getTopInventory().getHolder() instanceof ShopMenus.Menu),
        "Namespaced command cannot bypass policy");
    p.onCommand(
        Bukkit.getConsoleSender(),
        p.getCommand("shop"),
        "shop",
        new String[] {"admin", "command", "enable", "buy"});
    check(p.actionEnabled(player, "buy"), "Console re-enables command");
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
                  panel(player);
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

  private void panel(Player player) throws Exception {
    p.menus.adminPanel(player);
    check(player.getOpenInventory().getTopInventory().getSize() == 54, "Java management dashboard");
    InventoryClickEvent click =
        new InventoryClickEvent(
            player.getOpenInventory(),
            InventoryType.SlotType.CONTAINER,
            10,
            ClickType.LEFT,
            InventoryAction.PICKUP_ALL);
    p.menus.click(click);
    Bukkit.getScheduler()
        .runTaskLater(
            p,
            () -> {
              try {
                check(
                    p.management.enabled("access"),
                    "Java dashboard toggles module through a click");
                p.management.admin(
                    Bukkit.getConsoleSender(),
                    new String[] {"admin", "manage", "disable", "access"});
                fixture(player);
                p.getLogger().info("PLAYERTEST PASS checks=" + checks);
              } catch (Throwable error) {
                p.getLogger().log(java.util.logging.Level.SEVERE, "TEST FAIL", error);
              }
            },
            4);
  }

  private void management(Player player, Player buyer) throws Exception {
    String original = p.getConfig().saveToString();
    long wallet = p.service.balance(player), buyerWallet = p.service.balance(buyer);
    try {
      Product stone = p.product("stone");
      p.getConfig().set("management.analytics.enabled", true);
      p.getConfig().set("management.personal.enabled", true);
      p.getConfig().set("management.budgets.enabled", true);
      p.getConfig().set("management.budgets.daily-spend", 2);
      p.management = new ShopManagement(p);
      org.bukkit.permissions.PermissionAttachment permission = player.addAttachment(p);
      permission.setPermission("universalshop.admin", false);
      rejects(() -> p.menus.adminPanel(player), "Non-admin cannot open management dashboard");
      player.removeAttachment(permission);
      p.service.trade(player, stone, 1, true, null);
      String stats = "management.stats.products.stone";
      check(
          p.store.state().getLong(stats + ".spent") == 200, "Management records committed revenue");
      check(
          p.management.catalog(player, "@recent").get(0).id.equals("stone"),
          "Recent trades filter");
      p.management.personal(player, new String[] {"favorite", "stone"});
      check(
          p.management.catalog(player, "@favorites").size() == 1,
          "Favorites use persistent ledger");
      long balance = p.service.balance(player), lines = p.store.state().getLong(stats + ".trades");
      rejects(() -> p.service.trade(player, stone, 1, true, null), "Daily spend cap");
      check(
          p.service.balance(player) == balance
              && p.store.state().getLong(stats + ".trades") == lines,
          "Rejected management trade changes no money or statistics");
      p.getConfig().set("management.budgets.daily-earn", .55);
      p.management = new ShopManagement(p);
      player.getInventory().clear();
      player.getInventory().setItem(0, new ItemStack(Material.STONE, 1));
      player.getInventory().setItem(1, new ItemStack(Material.DIRT, 1));
      Map<String, Integer> basket = p.service.sellBasket(player);
      long total = p.service.basketQuote(player, basket);
      rejects(
          () -> p.service.sellAll(player, basket, total),
          "Bulk sale aggregates budget across products");
      check(
          p.service.balance(player) == balance
              && Inventories.count(Inventories.snapshot(player), stone.item) == 1,
          "Rejected bulk sale is atomic");
      p.getConfig().set("management.budgets.enabled", false);
      p.getConfig().set("management.access.enabled", true);
      p.getConfig().set("management.access.maintenance", true);
      p.management = new ShopManagement(p);
      rejects(
          () -> p.service.trade(player, stone, 1, true, null),
          "Maintenance prevents stale confirmation");
      p.getConfig().set("management.access.maintenance", false);
      p.getConfig().set("management.product-rules.enabled", true);
      p.getConfig().set("management.product-rules.entries.stone.global-daily-buy", 1);
      p.management = new ShopManagement(p);
      player.getInventory().clear();
      p.service.trade(player, stone, 1, true, null);
      rejects(
          () -> p.service.trade(buyer, stone, 1, true, null),
          "Server quota shared by two connected players");
      p.getConfig().set("management.product-rules.entries.stone.global-daily-buy", 0);
      p.getConfig().set("management.product-rules.entries.stone.cooldown-seconds", 60);
      p.management = new ShopManagement(p);
      p.service.trade(player, stone, 1, true, null);
      rejects(() -> p.service.trade(player, stone, 1, true, null), "Product cooldown");
      p.getConfig().set("management.product-rules.enabled", false);
      p.getConfig().set("management.sale-protection.enabled", true);
      p.getConfig().set("management.sale-protection.materials", Arrays.asList("STONE"));
      p.management = new ShopManagement(p);
      rejects(() -> p.service.trade(player, stone, 1, false, null), "Protected direct sale");
      check(
          !p.service.sellBasket(player).containsKey("stone"),
          "Protected items omitted from bulk sale");
      p.getConfig().set("management.sale-protection.enabled", false);
      p.getConfig().set("management.campaigns.enabled", true);
      String campaign = "management.campaigns.entries.test.";
      p.getConfig().set(campaign + "products", Arrays.asList("stone"));
      p.getConfig().set(campaign + "starts", "2020-01-01T00:00:00Z");
      p.getConfig().set(campaign + "ends", "2099-01-01T00:00:00Z");
      p.getConfig().set(campaign + "discount-percent", 50);
      p.management = new ShopManagement(p);
      check(p.service.quote(player, stone, 1, true) == 100, "Targeted campaign quote");
      rejects(
          () -> p.service.trade(player, stone, 1, true, 200L),
          "Changed campaign invalidates previous price");
      p.getConfig().set(campaign + "discount-percent", 100);
      p.management = new ShopManagement(p);
      check(
          p.service.quote(player, stone, 1, true) >= p.service.quote(player, stone, 1, false),
          "Campaign cannot bypass resale floor");
      p.management.admin(Bukkit.getConsoleSender(), new String[] {"admin", "manage", "report"});
      try (java.util.stream.Stream<Path> files =
          Files.list(p.getDataFolder().toPath().resolve("exports"))) {
        check(
            files.anyMatch(f -> f.getFileName().toString().endsWith(".csv")), "Sales CSV exported");
      }
      p.onCommand(
          Bukkit.getConsoleSender(),
          p.getCommand("shop"),
          "shop",
          new String[] {"admin", "duplicate", "diamond", "test_copy"});
      check(
          p.product("test_copy").step == p.product("diamond").step
              && p.product("test_copy").stock == p.product("diamond").stock,
          "Duplicate retains nested pricing and stock configuration");
      p.onCommand(
          Bukkit.getConsoleSender(),
          p.getCommand("shop"),
          "shop",
          new String[] {"admin", "rename", "test_copy", "Test", "Copy"});
      check(p.product("test_copy").name.equals("Test Copy"), "Rename product");
      p.onCommand(
          Bukkit.getConsoleSender(),
          p.getCommand("shop"),
          "shop",
          new String[] {"admin", "category", "test_copy", "Test", "Category"});
      check(p.product("test_copy").category.equals("Test Category"), "Move product category");
      p.onCommand(
          Bukkit.getConsoleSender(),
          p.getCommand("shop"),
          "shop",
          new String[] {"admin", "remove", "test_copy"});
      // Command saves are tested too; finally restores the fixture's config file.
      p.management.admin(Bukkit.getConsoleSender(), new String[] {"admin", "manage", "close"});
      check(
          !p.management.policy.open(java.time.Instant.now()),
          "Maintenance persisted by management command");
      p.management.admin(Bukkit.getConsoleSender(), new String[] {"admin", "manage", "open"});
      p.management.admin(
          Bukkit.getConsoleSender(), new String[] {"admin", "manage", "pause", "stone"});
      rejects(
          () -> p.service.trade(player, p.product("stone"), 1, true, null),
          "Pause command enforced");
      p.management.admin(
          Bukkit.getConsoleSender(), new String[] {"admin", "manage", "resume", "stone"});
      Product diamond = p.product("diamond");
      YamlConfiguration stock = p.store.copy();
      stock.set("products.diamond.stock", 0L);
      stock.set(
          "products.diamond.restock",
          System.currentTimeMillis() / (diamond.restockHours * 3600000L));
      stock.set("products.diamond.demand", 123L);
      p.store.save(stock, "TEST management stock");
      p.management.admin(
          Bukkit.getConsoleSender(), new String[] {"admin", "manage", "restock", "diamond"});
      check(p.service.stock(diamond) == diamond.stock, "Manual restock restores capacity");
      p.management.admin(
          Bukkit.getConsoleSender(), new String[] {"admin", "manage", "reset-demand", "diamond"});
      check(
          p.store.state().getLong("products.diamond.demand") == 0,
          "Demand reset preserves configured prices");
    } finally {
      p.getConfig().loadFromString(original);
      AtomicFiles.write(p.getDataFolder().toPath().resolve("config.yml"), original);
      p.management = new ShopManagement(p);
      p.service.setBalance(player.getUniqueId(), wallet);
      p.service.setBalance(buyer.getUniqueId(), buyerWallet);
      player.getInventory().clear();
      buyer.getInventory().clear();
    }
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
    check(
        p.store.state().getLong("management.stats.products.stone.bought") == 3,
        "Optional analytics survive restart");
    check(
        p.store
            .state()
            .getStringList("management.personal." + player.getUniqueId() + ".favorites")
            .contains("stone"),
        "Favorites survive restart");
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
    p.getConfig().set("management.budgets.enabled", true);
    p.getConfig().set("management.budgets.daily-spend", 1);
    p.getConfig().set("management.analytics.enabled", true);
    p.management = new ShopManagement(p);
    long managedBalance = p.service.balance(player);
    rejects(
        () -> p.service.trade(player, stone, 1, true, null), "Vault management budget rejection");
    check(p.service.balance(player) == managedBalance, "Rejected budget does not call Vault debit");
    p.getConfig().set("management.budgets.daily-spend", 3);
    p.management = new ShopManagement(p);
    p.service.trade(player, stone, 1, true, null);
    check(p.service.balance(player) == managedBalance - 200, "Vault managed purchase debit");
    check(
        p.store.state().getLong("management.stats.products.stone.spent") == 200,
        "Vault managed purchase statistics");
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
