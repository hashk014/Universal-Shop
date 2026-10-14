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
    return Math.max(0, Math.min(p.stock, state.getLong(path(p) + ".stock", p.stock)));
  }

  public long stock(Product p) {
    return stock(p, store.state());
  }

  long demand(Product product) {
    return Demand.decayed(store.state().getLong(path(product) + ".demand", 0),
        store.state().getLong(path(product) + ".demand-updated", System.currentTimeMillis()),
        System.currentTimeMillis(), product.decayHours);
  }

  /** Remaining product/global unit quota. -1 denotes no unit cap. */
  public int remainingLimit(Player player, Product product, boolean buying) {
    String day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
    String base = "limits." + player.getUniqueId();
    long cap = buying ? product.dailyBuy : product.dailySell;
    long left = cap > 0 ? Math.max(0, cap - (day.equals(store.state().getString(base + ".day"))
        ? store.state().getLong(base + "." + product.id + (buying ? ".buy" : ".sell")) : 0)) : Long.MAX_VALUE;
    if (plugin.management.enabled("product-rules")) {
      String rule = "management.product-rules.entries." + product.id;
      long globalCap = plugin.getConfig().getLong(rule + (buying ? ".global-daily-buy" : ".global-daily-sell"), 0);
      String global = "management.global." + product.id;
      if (globalCap > 0) left = Math.min(left, Math.max(0, globalCap -
          (day.equals(store.state().getString(global + ".date"))
              ? store.state().getLong(global + (buying ? ".buy" : ".sell")) : 0)));
    }
    return left == Long.MAX_VALUE ? -1 : (int) Math.min(Integer.MAX_VALUE, left);
  }

  /** Largest currently feasible order, with no ledger or inventory mutation. */
  public int maxQuantity(Player player, Product product, boolean buying) throws Exception {
    allowed(player);
    if (!player.hasPermission(buying ? "universalshop.buy" : "universalshop.sell")
        || !plugin.actionEnabled(player, buying ? "buy" : "sell") || !visible(player, product)
        || (buying ? product.buy : product.sell) < 0) return 0;
    if (!plugin.management.policy.open(Instant.now())
        || !plugin.management.policy.direction(product.id, product.category, buying)
        || (!buying && !plugin.management.sellable(product))) return 0;
    int maximum = product.commands.isEmpty() ? plugin.getConfig().getInt("max-transaction-items", 2304) : 1;
    int minimum = 1;
    if (plugin.management.enabled("product-rules")) {
      String base = "management.product-rules.entries." + product.id;
      minimum = plugin.getConfig().getInt(base + ".min-order", 1);
      maximum = Math.min(maximum, plugin.getConfig().getInt(base + ".max-order", 2304));
      long wait = plugin.getConfig().getLong(base + ".cooldown-seconds", 0) * 1000;
      if (wait > 0 && store.state().contains("management.cooldowns." + player.getUniqueId() + "." + product.id)
          && System.currentTimeMillis() - store.state().getLong("management.cooldowns." + player.getUniqueId() + "." + product.id) < wait)
        return 0;
    }
    int remaining = remainingLimit(player, product, buying);
    if (remaining >= 0) maximum = Math.min(maximum, remaining);
    ItemStack[] inventory = Inventories.snapshot(player);
    if (buying && stock(product) >= 0) maximum = (int) Math.min(maximum, stock(product));
    if (!product.commands.isEmpty()) maximum = Math.min(maximum, 1);
    else if (buying) {
      long stock = stock(product);
      if (stock >= 0) maximum = (int) Math.min(maximum, stock);
      maximum = Math.min(maximum, Inventories.capacity(inventory, product.item));
    } else maximum = Math.min(maximum, Inventories.count(inventory, product.item));
    long money = buying ? balance(player) : economy.external() ? Money.MAX : Money.MAX - balance(player);
    if (plugin.management.enabled("budgets")) {
      String base = "management.daily." + player.getUniqueId();
      long limit = Money.parse(String.valueOf(plugin.getConfig().get(buying ? "management.budgets.daily-spend" : "management.budgets.daily-earn", 0)));
      if (limit > 0) {
        String day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        long used = day.equals(store.state().getString(base + ".date"))
            ? store.state().getLong(base + (buying ? ".spent" : ".earned")) : 0;
        money = Math.min(money, Math.max(0, limit - used));
      }
    }
    int lo = 0, hi = Math.max(0, maximum);
    while (lo < hi) {
      int mid = lo + (hi - lo + 1) / 2;
      try {
        if (quote(player, product, mid, buying) <= money) lo = mid;
        else hi = mid - 1;
      } catch (ArithmeticException | IllegalArgumentException overflow) { hi = mid - 1; }
    }
    return lo < minimum ? 0 : lo;
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
    return Math.max(percent / 100.0, plugin.management.discount(p, product));
  }

  public long quote(Player p, Product product, int quantity, boolean buying) {
    if (quantity < 1 || quantity > plugin.getConfig().getInt("max-transaction-items", 2304))
      throw new IllegalArgumentException("Invalid quantity.");
    if (!product.commands.isEmpty() && quantity != 1)
      throw new IllegalArgumentException("Services can only be purchased one at a time.");
    return Pricing.quote(
        product.buy,
        product.sell,
        demand(product),
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
    plugin.management.record(next, p, product, quantity, total, buying);
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
        Math.addExact(demand(product), buying ? quantity : -quantity));
    next.set(path(product) + ".demand-updated", System.currentTimeMillis());
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
    plugin.management.afterTrade(product);
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
    if (amount > 0 && economy.external() && !economy.change(p, amount, !buying)) {
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
    committed(p, description, amount, !buying);
  }

  private void committed(Player player, String description, long amount, boolean credit) {
    try {
      Bukkit.getPluginManager().callEvent(new ShopTransactionEvent(player.getUniqueId(),
          store.state().getString("last-transaction"), description, amount, credit));
    } catch (RuntimeException listenerFailure) {
      plugin.getLogger().log(java.util.logging.Level.WARNING,
          "A transaction observer failed after durable commit; the transaction must not be replayed.", listenerFailure);
    }
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
    busy = true;
    try {
      YamlConfiguration next = store.copy();
      wallet(next, sender.getUniqueId(), -amount);
      wallet(next, recipient.getUniqueId(), amount);
      String description = "PAY " + sender.getUniqueId() + " -> " + recipient.getUniqueId() + " cents=" + amount;
      store.save(next, description);
      lastTrade.put(sender.getUniqueId(), System.nanoTime());
      committed(sender, description, amount, false);
    } finally { busy = false; }
  }

  public void setBalance(UUID player, long amount) throws Exception {
    if (busy) throw new IllegalStateException("Another transaction is in progress.");
    if (economy.external())
      throw new IllegalArgumentException("Use the Vault provider's balance management commands.");
    if (amount < 0 || amount > Money.MAX) throw new IllegalArgumentException("Invalid amount.");
    YamlConfiguration next = store.copy();
    next.set("wallets." + player, amount);
    store.save(next, "ADMIN BALANCE " + player + " cents=" + amount);
  }

  private void marketAllowed(Player p) { marketAllowed(p, false); }

  private void marketAllowed(Player p, boolean reclaim) {
    allowed(p);
    cooldown(p);
    if (!reclaim && (!plugin.getConfig().getBoolean("market.enabled", true)
        || (economy.external() && !plugin.getConfig().getBoolean("market.allow-vault", true))))
      throw new IllegalArgumentException(
          "The player market is disabled for this economy.");
    if (!p.hasPermission("universalshop.market"))
      throw new IllegalArgumentException("You do not have permission to use the player market.");
  }

  public String list(Player p, int quantity, long total) throws Exception {
    plugin.requireAction(p, "market.list");
    marketAllowed(p);
    busy = true;
    try { return listPrepared(p, quantity, total); }
    finally { busy = false; }
  }

  private String listPrepared(Player p, int quantity, long total) throws Exception {
    if (quantity < 1 || quantity > 2304 || total < 1 || total > Money.MAX)
      throw new IllegalArgumentException("Invalid quantity or listing price.");
    ItemStack held = p.getItemInHand();
    if (held == null || held.getType() == org.bukkit.Material.AIR)
      throw new IllegalArgumentException("Hold the item to list.");
    YamlConfiguration next = store.copy();
    org.bukkit.configuration.ConfigurationSection listings = next.getConfigurationSection("market");
    if (listings != null && listings.getKeys(false).size() >= plugin.getConfig().getInt("market.max-total-listings", 10000))
      throw new IllegalArgumentException("The player market is full. Reclaim expired listings first.");
    if (total < Money.parse(String.valueOf(plugin.getConfig().get("market.min-price", "0.01"))))
      throw new IllegalArgumentException("The listing price is below the configured minimum.");
    int active = 0;
    if (listings != null)
      for (String id : listings.getKeys(false))
        if (p.getUniqueId().toString().equals(listings.getString(id + ".owner"))
            && listings.getLong(id + ".expires") > System.currentTimeMillis()) active++;
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
    next.set(path + ".created", System.currentTimeMillis());
    next.set(
        path + ".expires",
        System.currentTimeMillis()
            + plugin.getConfig().getLong("market.listing-hours", 168) * 3600000L);
    ItemStack[] before = Inventories.snapshot(p),
        after = Inventories.change(before, item, -quantity);
    marketEvent(p, id, p.getUniqueId(), "LIST", quantity, total);
    if (!Arrays.equals(before, Inventories.snapshot(p)))
      throw new IllegalArgumentException("Inventory changed during validation.");
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
    marketAllowed(p, cancel);
    busy = true;
    try { marketTakePrepared(p, id, cancel); }
    finally { busy = false; }
  }

  private void marketTakePrepared(Player p, String id, boolean cancel) throws Exception {
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
      if (balance(p) < price) throw new IllegalArgumentException("You do not have enough money.");
      long proceeds = Money.scaled(price, 1 - plugin.percent("market.tax-percent"), false);
      if (economy.external()) {
        String credit = "market-credits." + owner;
        next.set(credit, Money.add(next.getLong(credit), proceeds));
      } else {
        wallet(next, p.getUniqueId(), -price);
        wallet(next, owner, proceeds);
      }
      notice(next, owner, "Sold listing " + id + " to " + p.getName() + " for " + plugin.money(proceeds)
          + (economy.external() ? ". Use /shop market claim to collect your Vault credit." : "."));
    }
    ItemStack[] before = Inventories.snapshot(p),
        after =
            Inventories.change(
                before, next.getItemStack(path + ".item"), next.getInt(path + ".quantity"));
    next.set(path, null);
    marketEvent(p, id, owner, cancel ? "CANCEL" : "BUY", store.state().getInt(path + ".quantity"), price);
    if (!Arrays.equals(before, Inventories.snapshot(p)))
      throw new IllegalArgumentException("Inventory changed during validation.");
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
        cancel ? 0 : price,
        true,
        Collections.<String>emptyList());
    lastTrade.put(p.getUniqueId(), System.nanoTime());
  }

  private void marketEvent(Player player, String id, UUID seller, String action, int quantity, long price) {
    ShopMarketEvent event = new ShopMarketEvent(player, id, seller, action, quantity, price);
    Bukkit.getPluginManager().callEvent(event);
    if (event.isCancelled()) throw new IllegalArgumentException("This market transaction was cancelled by a server rule.");
  }

  public List<String> marketEntries(Player player, String filter, String sort, boolean mine) {
    final YamlConfiguration state = store.state();
    org.bukkit.configuration.ConfigurationSection section = state.getConfigurationSection("market");
    List<String> ids = new ArrayList<String>();
    if (section == null) return ids;
    String term = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
    boolean expiredOnly = term.equals("@expired");
    long now = System.currentTimeMillis();
    for (String id : section.getKeys(false)) {
      String base = "market." + id;
      boolean own = player.getUniqueId().toString().equals(state.getString(base + ".owner"));
      boolean expired = state.getLong(base + ".expires") <= now;
      ItemStack item = state.getItemStack(base + ".item");
      if (item == null || (mine && !own) || (expiredOnly ? (!own || !expired) : (!mine && expired))) continue;
      String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName() ? item.getItemMeta().getDisplayName() : item.getType().name();
      String haystack = id + " " + item.getType().name() + " " + name + " " + state.getString(base + ".seller", "");
      if (!term.isEmpty() && !expiredOnly && !haystack.toLowerCase(Locale.ROOT).contains(term)) continue;
      ids.add(id);
    }
    final String ordering = sort == null ? "newest" : sort.toLowerCase(Locale.ROOT);
    Collections.sort(ids, (a, b) -> {
      String x = "market." + a, y = "market." + b;
      int cmp;
      if (ordering.equals("price")) cmp = Long.compare(state.getLong(x + ".price"), state.getLong(y + ".price"));
      else if (ordering.equals("unit-price")) cmp = java.math.BigDecimal.valueOf(state.getLong(x + ".price"))
          .multiply(java.math.BigDecimal.valueOf(Math.max(1, state.getInt(y + ".quantity"))))
          .compareTo(java.math.BigDecimal.valueOf(state.getLong(y + ".price"))
              .multiply(java.math.BigDecimal.valueOf(Math.max(1, state.getInt(x + ".quantity")))));
      else if (ordering.equals("name")) cmp = marketName(state, x).compareToIgnoreCase(marketName(state, y));
      else if (ordering.equals("seller")) cmp = state.getString(x + ".seller", "").compareToIgnoreCase(state.getString(y + ".seller", ""));
      else cmp = Long.compare(state.getLong(y + ".created", state.getLong(y + ".expires")), state.getLong(x + ".created", state.getLong(x + ".expires")));
      return cmp == 0 ? a.compareTo(b) : cmp;
    });
    return ids;
  }

  private String marketName(YamlConfiguration state, String base) {
    ItemStack item = state.getItemStack(base + ".item");
    return item.hasItemMeta() && item.getItemMeta().hasDisplayName() ? item.getItemMeta().getDisplayName() : item.getType().name();
  }

  public long pendingMarketCredit(Player player) {
    return store.state().getLong("market-credits." + player.getUniqueId(), 0);
  }

  /** Vault liabilities are paid in their own prepared transaction; never replay an uncertain deposit. */
  public int claimExpired(Player player) throws Exception {
    plugin.requireAction(player, "market.claim");
    marketAllowed(player, true);
    busy = true;
    try {
      long credit = pendingMarketCredit(player);
      if (credit > 0) {
        if (!economy.external()) throw new IllegalStateException("Pending Vault credit requires the original economy provider.");
        YamlConfiguration paid = store.copy();
        paid.set("market-credits." + player.getUniqueId(), null);
        ItemStack[] unchanged = Inventories.snapshot(player);
        execute(player, paid, unchanged, unchanged, "MARKET CREDIT " + player.getUniqueId() + " cents=" + credit,
            credit, false, Collections.<String>emptyList());
        plugin.tell(player, "&aCollected marketplace credit: " + plugin.money(credit));
      }
      YamlConfiguration next = store.copy();
      ItemStack[] before = Inventories.snapshot(player), after = before;
      List<String> expired = marketEntries(player, "@expired", "newest", true);
      int count = 0;
      for (String id : expired) {
        String base = "market." + id;
        ItemStack[] candidate;
        try { candidate = Inventories.change(after, next.getItemStack(base + ".item"), next.getInt(base + ".quantity")); }
        catch (IllegalArgumentException noRoom) { continue; }
        marketEvent(player, id, player.getUniqueId(), "CANCEL", next.getInt(base + ".quantity"), next.getLong(base + ".price"));
        after = candidate;
        next.set(base, null);
        count++;
      }
      if (count > 0) {
        if (!Arrays.equals(before, Inventories.snapshot(player))) throw new IllegalArgumentException("Inventory changed during validation.");
        execute(player, next, before, after, "MARKET CLAIM " + player.getUniqueId() + " listings=" + count,
            0, true, Collections.<String>emptyList());
      }
      if (count > 0 || credit > 0) lastTrade.put(player.getUniqueId(), System.nanoTime());
      if (!expired.isEmpty() && count == 0) plugin.tell(player, "&eMake room in your inventory to reclaim expired listings.");
      return count;
    } finally { busy = false; }
  }

  private void notice(YamlConfiguration state, UUID recipient, String text) {
    String base = "market-notices." + recipient;
    List<String> notices = new ArrayList<String>(state.getStringList(base));
    notices.add(java.time.Instant.now() + " " + text);
    while (notices.size() > 100) notices.remove(0);
    state.set(base, notices);
  }

  public int notificationCount(Player player) { return store.state().getStringList("market-notices." + player.getUniqueId()).size(); }

  public List<String> consumeNotifications(Player player) throws Exception {
    plugin.requireAction(player, "market.notices");
    allowed(player);
    List<String> notices = new ArrayList<String>(store.state().getStringList("market-notices." + player.getUniqueId()));
    if (!notices.isEmpty()) {
      YamlConfiguration next = store.copy();
      next.set("market-notices." + player.getUniqueId(), null);
      store.save(next, "MARKET NOTICES " + player.getUniqueId() + " read=" + notices.size());
    }
    return notices;
  }

  public Map<String, Integer> sellBasket(Player p) {
    ItemStack[] remaining = Inventories.snapshot(p);
    Map<String, Integer> basket = new LinkedHashMap<String, Integer>();
    List<Product> candidates = new ArrayList<Product>();
    for (Product product : plugin.products.values())
      if (product.sell >= 0 && visible(p, product) && product.commands.isEmpty() && plugin.management.sellable(product))
        candidates.add(product);
    Collections.sort(candidates, (a, b) -> {
      int comparison = Long.compare(quote(p, b, 1, false), quote(p, a, 1, false));
      return comparison == 0 ? a.id.compareTo(b.id) : comparison;
    });
    for (Product product : candidates)
      if (product.sell >= 0
          && visible(p, product)
          && product.commands.isEmpty()
          && plugin.management.sellable(product)) {
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
        plugin.management.record(next, p, product, count, quote(p, product, count, false), false);
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
            Math.subtractExact(demand(product), count));
        next.set(path(product) + ".demand-updated", System.currentTimeMillis());
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
    for (String id : basket.keySet()) plugin.management.afterTrade(plugin.product(id));
  }
}
