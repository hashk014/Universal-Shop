package fr.hash014.universalshop;

import java.time.Instant;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class ShopService {
  final UniversalShop plugin;
  final Store store;
  final Economy economy;
  private boolean busy;
  private final Map<UUID, Long> lastTrade = new HashMap<UUID, Long>();

  public ShopService(UniversalShop p, Store store, Economy economy) {
    this.plugin = p;
    this.store = store;
    this.economy = economy;
  }

  public long balance(Player p) throws Exception {
    return economy.external() ? economy.balance(p) : balance(store.state(), p.getUniqueId());
  }

  long balance(YamlConfiguration state, UUID id) {
    return state.getLong("wallets." + id, plugin.startingBalance);
  }

  void wallet(YamlConfiguration state, UUID id, long change) {
    state.set("wallets." + id, Money.add(balance(state, id), change));
  }

  public void forget(UUID id) {
    lastTrade.remove(id);
  }

  public void allowed(Player p) {
    plugin.requireAction(p, "shop");
    if (!Bukkit.isPrimaryThread())
      throw new IllegalStateException("Shop operations must run on the main server thread.");
    if (busy) throw new IllegalStateException("Another transaction is in progress.");
    if (store.blocked())
      throw new IllegalStateException("Trading is locked pending administrator recovery.");
    if (!p.hasPermission("universalshop.use"))
      throw new IllegalArgumentException("You do not have permission to use the shop.");
    if (plugin.getConfig().getStringList("disabled-worlds").contains(p.getWorld().getName()))
      throw new IllegalArgumentException("Shops are disabled in this world.");
    if (p.getGameMode() == org.bukkit.GameMode.CREATIVE)
      throw new IllegalArgumentException("Switch out of Creative mode before trading.");
  }

  private void cooldown(Player p) {
    Long last = lastTrade.get(p.getUniqueId());
    if (last != null
        && System.nanoTime() - last
            < plugin.getConfig().getLong("cooldown-milliseconds", 350) * 1000000L)
      throw new IllegalArgumentException("Please wait before making another transaction.");
  }

  public Set<String> deals() {
    if (!plugin.getConfig().getBoolean("rotation.enabled", true)) return Collections.emptySet();
    List<String> ids = new ArrayList<String>();
    for (Product product : plugin.products.values()) if (product.rotating) ids.add(product.id);
    return Rotation.pick(
        ids,
        plugin.getConfig().getString("rotation.seed", "survival-market"),
        rotationEpoch(),
        plugin.getConfig().getInt("rotation.count", 3));
  }

  long rotationEpoch() {
    return System.currentTimeMillis()
        / (plugin.getConfig().getLong("rotation.period-hours", 168) * 3600000L);
  }

  public Instant nextRotation() {
    return Instant.ofEpochMilli(
        (rotationEpoch() + 1)
            * plugin.getConfig().getLong("rotation.period-hours", 168)
            * 3600000L);
  }

  public boolean visible(Player p, Product product) {
    return (product.permission.isEmpty() || p.hasPermission(product.permission))
        && (product.worlds.isEmpty() || product.worlds.contains(p.getWorld().getName()))
        && (!product.rotationOnly || deals().contains(product.id));
  }

  private String path(Product p) {
    return "products." + p.id;
  }

  long stock(Product p, YamlConfiguration state) {
    if (p.stock < 0) return -1;
    if (p.restockHours > 0
        && state.getLong(path(p) + ".restock", -1)
            != System.currentTimeMillis() / (p.restockHours * 3600000L)) return p.stock;
    return state.getLong(path(p) + ".stock", p.stock);
  }

  public long stock(Product p) {
    return stock(p, store.state());
  }

  private double discount(Player p, Product product) {
    double percent = 0;
    org.bukkit.configuration.ConfigurationSection discounts =
        plugin.getConfig().getConfigurationSection("permission-discounts");
    if (discounts != null)
      for (Map.Entry<String, Object> entry : discounts.getValues(true).entrySet())
        if (entry.getValue() instanceof Number && p.hasPermission(entry.getKey()))
          percent = Math.max(percent, ((Number) entry.getValue()).doubleValue());
    if (deals().contains(product.id))
      percent = Math.max(percent, plugin.percent("rotation.discount-percent") * 100);
    String start = plugin.getConfig().getString("promotion.starts", "");
    String end = plugin.getConfig().getString("promotion.ends", "");
    if (!start.isEmpty() && !end.isEmpty()) {
      Instant now = Instant.now();
      if (!now.isBefore(Instant.parse(start)) && now.isBefore(Instant.parse(end)))
        percent = Math.max(percent, plugin.percent("promotion.discount-percent") * 100);
    }
    return percent / 100.0;
  }

  public long quote(Player p, Product product, int quantity, boolean buying) {
    if (quantity < 1 || quantity > plugin.getConfig().getInt("max-transaction-items", 2304))
      throw new IllegalArgumentException("Invalid quantity.");
    if (!product.commands.isEmpty() && quantity != 1)
      throw new IllegalArgumentException("Services can only be purchased one at a time.");
    return Pricing.quote(
        product.buy,
        product.sell,
        store.state().getLong(path(product) + ".demand", 0),
        quantity,
        buying,
        product.step,
        product.min,
        product.max,
        discount(p, product),
        plugin.percent(buying ? "buy-tax-percent" : "sell-tax-percent"));
  }

  public void trade(Player p, Product product, int quantity, boolean buying, Long expected)
      throws Exception {
    plugin.requireAction(p, buying ? "buy" : "sell");
    allowed(p);
    cooldown(p);
    if (!p.hasPermission(buying ? "universalshop.buy" : "universalshop.sell"))
      throw new IllegalArgumentException("You do not have permission for this transaction.");
    if (!visible(p, product))
      throw new IllegalArgumentException("This product is not available to you here.");
    long total = quote(p, product, quantity, buying);
    if (expected != null && total != expected.longValue())
      throw new IllegalArgumentException(
          "The price changed. Review a fresh quote before confirming.");
    if (buying && balance(p) < total)
      throw new IllegalArgumentException("You do not have enough money.");
    long available = stock(product);
    if (buying && available >= 0 && available < quantity)
      throw new IllegalArgumentException("This product is out of stock.");
    YamlConfiguration next = store.copy();
    String day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
    String limitPath = "limits." + p.getUniqueId();
    if (!day.equals(next.getString(limitPath + ".day"))) {
      next.set(limitPath, null);
      next.set(limitPath + ".day", day);
    }
    String counter = limitPath + "." + product.id + (buying ? ".buy" : ".sell");
    long used = next.getLong(counter, 0), limit = buying ? product.dailyBuy : product.dailySell;
    if (limit > 0 && used + quantity > limit)
      throw new IllegalArgumentException("Your daily limit for this product has been reached.");
    next.set(counter, Math.addExact(used, quantity));
    if (available >= 0) {
      next.set(
          path(product) + ".stock",
          buying ? available - quantity : Math.min(product.stock, available + quantity));
      if (product.restockHours > 0)
        next.set(
            path(product) + ".restock",
            System.currentTimeMillis() / (product.restockHours * 3600000L));
    }
    next.set(
        path(product) + ".demand",
        Math.addExact(next.getLong(path(product) + ".demand", 0), buying ? quantity : -quantity));
    if (!economy.external()) wallet(next, p.getUniqueId(), buying ? -total : total);
    busy = true;
    try {
      ShopTradeEvent event = new ShopTradeEvent(p, product.id, quantity, buying, total);
      Bukkit.getPluginManager().callEvent(event);
      if (event.isCancelled())
        throw new IllegalArgumentException("This transaction was cancelled by a server rule.");
      ItemStack[] before = Inventories.snapshot(p);
      ItemStack[] after =
          product.commands.isEmpty()
              ? Inventories.change(before, product.item, buying ? quantity : -quantity)
              : before;
      execute(
          p,
          next,
          before,
          after,
          (buying ? "BUY " : "SELL ")
              + p.getUniqueId()
              + " "
              + product.id
              + " x"
              + quantity
              + " cents="
              + total,
          total,
          buying,
          product.commands);
      lastTrade.put(p.getUniqueId(), System.nanoTime());
    } finally {
      busy = false;
    }
    plugin.message(
        p,
        "success",
        "quantity",
        String.valueOf(quantity),
        "product",
        product.name,
        "price",
        plugin.money(total));
  }

  private void execute(
      Player p,
      YamlConfiguration next,
      ItemStack[] before,
      ItemStack[] after,
      String description,
      long amount,
      boolean buying,
      List<String> commands)
      throws Exception {
    store.prepare(next, description, Inventories.evidence(p, before, after));
    // Exceptions after prepare remain quarantined: third-party effects may already have happened.
    if (economy.external() && !economy.change(p, amount, !buying)) {
      store.abort();
      throw new IllegalArgumentException("The economy provider declined this transaction.");
    }
    Inventories.apply(p, after);
    for (String command : commands) {
      String expanded =
          command.replace("{player}", p.getName()).replace("{uuid}", p.getUniqueId().toString());
      if (expanded.startsWith("/")) expanded = expanded.substring(1);
      if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), expanded))
        throw new IllegalStateException(
            "A delivery command failed; trading is locked for administrator recovery.");
    }
    p.saveData();
    store.commit();
  }

  public void pay(Player sender, Player recipient, long amount) throws Exception {
    plugin.requireAction(sender, "pay");
    allowed(sender);
    cooldown(sender);
    if (economy.external())
      throw new IllegalArgumentException(
          "Use your economy provider's payment command in Vault mode.");
    if (!sender.hasPermission("universalshop.pay"))
      throw new IllegalArgumentException("You do not have permission to send money.");
    if (sender.equals(recipient) || amount <= 0)
      throw new IllegalArgumentException("Choose another player and a positive amount.");
    YamlConfiguration next = store.copy();
    wallet(next, sender.getUniqueId(), -amount);
    wallet(next, recipient.getUniqueId(), amount);
    store.save(
        next,
        "PAY " + sender.getUniqueId() + " -> " + recipient.getUniqueId() + " cents=" + amount);
    lastTrade.put(sender.getUniqueId(), System.nanoTime());
  }

  public void setBalance(UUID player, long amount) throws Exception {
    if (economy.external())
      throw new IllegalArgumentException("Use the Vault provider's balance management commands.");
    if (amount < 0 || amount > Money.MAX) throw new IllegalArgumentException("Invalid amount.");
    YamlConfiguration next = store.copy();
    next.set("wallets." + player, amount);
    store.save(next, "ADMIN BALANCE " + player + " cents=" + amount);
  }

  private void marketAllowed(Player p) {
    allowed(p);
    cooldown(p);
    if (!plugin.getConfig().getBoolean("market.enabled", true) || economy.external())
      throw new IllegalArgumentException(
          "Player market requires the internal economy and market.enabled: true.");
    if (!p.hasPermission("universalshop.market"))
      throw new IllegalArgumentException("You do not have permission to use the player market.");
  }

  public String list(Player p, int quantity, long total) throws Exception {
    plugin.requireAction(p, "market.list");
    marketAllowed(p);
    if (quantity < 1 || quantity > 2304 || total < 1 || total > Money.MAX)
      throw new IllegalArgumentException("Invalid quantity or listing price.");
    ItemStack held = p.getItemInHand();
    if (held == null || held.getType() == org.bukkit.Material.AIR)
      throw new IllegalArgumentException("Hold the item to list.");
    YamlConfiguration next = store.copy();
    org.bukkit.configuration.ConfigurationSection listings = next.getConfigurationSection("market");
    int active = 0;
    if (listings != null)
      for (String id : listings.getKeys(false))
        if (p.getUniqueId().toString().equals(listings.getString(id + ".owner"))) active++;
    if (active >= plugin.getConfig().getInt("market.max-listings-per-player", 10))
      throw new IllegalArgumentException("Cancel an existing listing before creating another.");
    String id = UUID.randomUUID().toString().substring(0, 8);
    while (next.contains("market." + id)) id = UUID.randomUUID().toString().substring(0, 8);
    String path = "market." + id;
    ItemStack item = held.clone();
    item.setAmount(1);
    next.set(path + ".owner", p.getUniqueId().toString());
    next.set(path + ".seller", p.getName());
    next.set(path + ".item", item);
    next.set(path + ".quantity", quantity);
    next.set(path + ".price", total);
    next.set(
        path + ".expires",
        System.currentTimeMillis()
            + plugin.getConfig().getLong("market.listing-hours", 168) * 3600000L);
    ItemStack[] before = Inventories.snapshot(p),
        after = Inventories.change(before, item, -quantity);
    execute(
        p,
        next,
        before,
        after,
        "MARKET LIST " + p.getUniqueId() + " id=" + id,
        0,
        true,
        Collections.<String>emptyList());
    lastTrade.put(p.getUniqueId(), System.nanoTime());
    return id;
  }

  public void marketTake(Player p, String id, boolean cancel) throws Exception {
    plugin.requireAction(p, cancel ? "market.cancel" : "market.buy");
    marketAllowed(p);
    if (!id.matches("[a-f0-9]{8}")) throw new IllegalArgumentException("Invalid listing ID.");
    YamlConfiguration next = store.copy();
    String path = "market." + id;
    if (!next.contains(path)) throw new IllegalArgumentException("That listing no longer exists.");
    UUID owner = UUID.fromString(next.getString(path + ".owner"));
    if (cancel && !p.getUniqueId().equals(owner))
      throw new IllegalArgumentException("Only the seller can cancel this listing.");
    if (!cancel && p.getUniqueId().equals(owner))
      throw new IllegalArgumentException("Use /shop market cancel to reclaim your own listing.");
    if (!cancel && next.getLong(path + ".expires") <= System.currentTimeMillis())
      throw new IllegalArgumentException("This listing has expired.");
    long price = next.getLong(path + ".price");
    if (!cancel) {
      wallet(next, p.getUniqueId(), -price);
      wallet(next, owner, Money.scaled(price, 1 - plugin.percent("market.tax-percent"), false));
    }
    ItemStack[] before = Inventories.snapshot(p),
        after =
            Inventories.change(
                before, next.getItemStack(path + ".item"), next.getInt(path + ".quantity"));
    next.set(path, null);
    execute(
        p,
        next,
        before,
        after,
        "MARKET "
            + (cancel ? "CANCEL " : "BUY ")
            + p.getUniqueId()
            + " id="
            + id
            + " cents="
            + price,
        0,
        true,
        Collections.<String>emptyList());
    lastTrade.put(p.getUniqueId(), System.nanoTime());
  }

  public Map<String, Integer> sellBasket(Player p) {
    ItemStack[] remaining = Inventories.snapshot(p);
    Map<String, Integer> basket = new LinkedHashMap<String, Integer>();
    for (Product product : plugin.products.values())
      if (product.sell >= 0 && visible(p, product) && product.commands.isEmpty()) {
        int count =
            Math.min(
                Inventories.count(remaining, product.item),
                plugin.getConfig().getInt("max-transaction-items", 2304));
        String path = "limits." + p.getUniqueId();
        long used =
            java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                    .toString()
                    .equals(store.state().getString(path + ".day"))
                ? store.state().getLong(path + "." + product.id + ".sell", 0)
                : 0;
        if (product.dailySell > 0)
          count = (int) Math.min(count, Math.max(0, product.dailySell - used));
        if (count > 0) {
          basket.put(product.id, count);
          remaining = Inventories.change(remaining, product.item, -count);
        }
      }
    return basket;
  }

  public long basketQuote(Player p, Map<String, Integer> basket) {
    long total = 0;
    for (Map.Entry<String, Integer> entry : basket.entrySet())
      total = Money.add(total, quote(p, plugin.product(entry.getKey()), entry.getValue(), false));
    return total;
  }

  public void sellAll(Player p, Map<String, Integer> basket, long expected) throws Exception {
    plugin.requireAction(p, "sellall");
    plugin.requireAction(p, "sell");
    allowed(p);
    cooldown(p);
    if (!p.hasPermission("universalshop.sell"))
      throw new IllegalArgumentException("You do not have permission to sell.");
    if (basket.isEmpty() || !sellBasket(p).equals(basket))
      throw new IllegalArgumentException(
          "Your sellable inventory or limits changed. Preview again.");
    long total = basketQuote(p, basket);
    if (total != expected) throw new IllegalArgumentException("Prices changed. Preview again.");
    YamlConfiguration next = store.copy();
    ItemStack[] before = Inventories.snapshot(p), after = before;
    String limitPath = "limits." + p.getUniqueId(),
        day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
    if (!day.equals(next.getString(limitPath + ".day"))) {
      next.set(limitPath, null);
      next.set(limitPath + ".day", day);
    }
    busy = true;
    try {
      for (Map.Entry<String, Integer> entry : basket.entrySet()) {
        Product product = plugin.product(entry.getKey());
        int count = entry.getValue();
        ShopTradeEvent event =
            new ShopTradeEvent(p, product.id, count, false, quote(p, product, count, false));
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled())
          throw new IllegalArgumentException("A server rule cancelled the inventory sale.");
        after = Inventories.change(after, product.item, -count);
        String counter = limitPath + "." + product.id + ".sell";
        next.set(counter, Math.addExact(next.getLong(counter, 0), count));
        long available = stock(product);
        if (available >= 0) {
          next.set(path(product) + ".stock", Math.min(product.stock, available + count));
          if (product.restockHours > 0)
            next.set(
                path(product) + ".restock",
                System.currentTimeMillis() / (product.restockHours * 3600000L));
        }
        next.set(
            path(product) + ".demand",
            Math.subtractExact(next.getLong(path(product) + ".demand", 0), count));
      }
      if (!economy.external()) wallet(next, p.getUniqueId(), total);
      // A listener that edited the inventory invalidates the whole prepared basket.
      if (!Arrays.equals(before, Inventories.snapshot(p)))
        throw new IllegalArgumentException("Inventory changed during validation.");
      execute(
          p,
          next,
          before,
          after,
          "SELL ALL " + p.getUniqueId() + " " + basket + " cents=" + total,
          total,
          false,
          Collections.<String>emptyList());
      lastTrade.put(p.getUniqueId(), System.nanoTime());
    } finally {
      busy = false;
    }
    plugin.tell(p, "&aInventory sale complete: " + plugin.money(total));
  }
}
