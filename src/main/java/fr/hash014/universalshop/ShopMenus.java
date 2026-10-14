package fr.hash014.universalshop;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

/** Java inventory presentation. Economic actions are rechecked by ShopService. */
public final class ShopMenus implements Listener {
  private final UniversalShop plugin;
  private final Map<UUID, BrowseContext> browsing = new HashMap<UUID, BrowseContext>();
  private final Map<UUID, MarketContext> markets = new HashMap<UUID, MarketContext>();
  private static final class BrowseContext {
    final String filter;
    final int page;
    BrowseContext(String filter, int page) { this.filter = filter; this.page = page; }
  }
  static final class MarketContext {
    final String filter, sort;
    final boolean mine;
    final int page;
    MarketContext(String filter, String sort, boolean mine, int page) {
      this.filter = filter; this.sort = sort; this.mine = mine; this.page = page;
    }
  }
  public ShopMenus(UniversalShop plugin) { this.plugin = plugin; }
  void forget(UUID id) { browsing.remove(id); markets.remove(id); }
  void clear() { browsing.clear(); markets.clear(); }
  String text(Player p, String key, String fallback, String... replacements) {
    return plugin.texts.format(p, key, fallback, replacements);
  }
  private String title(Player p, String key, String fallback, String... replacements) {
    return menuTitle(text(p, key, fallback, replacements));
  }
  static String menuTitle(String value) {
    String title = UniversalShop.color(value);
    if (title.length() <= 32) return title;
    title = title.substring(0, 32);
    char last = title.charAt(title.length() - 1);
    if (last == '\u00a7' || Character.isHighSurrogate(last)) title = title.substring(0, title.length() - 1);
    return title;
  }

  public void adminPanel(Player player) throws Exception {
    plugin.inputs.cancel(player.getUniqueId());
    plugin.requireAction(player, "admin.panel");
    plugin.requireAdmin(player, "panel");
    if (plugin.bedrock.adminPanel(player)) return;
    Menu menu = new Menu(player);
    menu.inventory = Bukkit.createInventory(menu, 54, title(player, "menu.admin.title", "UniversalShop | Management"));
    int slot = 10;
    for (String module : ManagementPolicy.MODULES) {
      boolean enabled = plugin.management.enabled(module);
      if (plugin.hasAdmin(player, "manage")) button(menu, slot++, enabled ? Material.EMERALD : Material.REDSTONE,
          (enabled ? "&a" : "&c") + module + ": " + (enabled ? "ON" : "OFF"),
          "admin:manage:" + (enabled ? "disable:" : "enable:") + module,
          text(player, "menu.admin.toggle", "&7Click to toggle this optional module."));
    }
    if (plugin.hasAdmin(player, "catalog")) button(menu, 4, Product.material("CRAFTING_TABLE|WORKBENCH"),
        text(player, "menu.admin.editor", "&6Catalog editor"), "editor",
        text(player, "menu.admin.editor-help", "&7Create and edit products in game."));
    if (plugin.hasAdmin(player, "stock")) button(menu, 45, Material.CHEST,
        text(player, "menu.admin.stock", "&eStock overview"), "admin:manage:stock");
    if (plugin.hasAdmin(player, "history")) {
      button(menu, 46, Material.BOOK, text(player, "menu.admin.stats", "&eSales statistics"), "admin:manage:stats");
      button(menu, 47, Material.PAPER, text(player, "menu.admin.report", "&eExport sales CSV"), "admin:manage:report");
    }
    if (plugin.hasAdmin(player, "manage")) {
      button(menu, 49, Material.EMERALD, text(player, "menu.admin.open", "&aOpen shop"), "admin:manage:open");
      button(menu, 51, Material.REDSTONE, text(player, "menu.admin.close", "&cClose shop"), "admin:manage:close");
    }
    button(menu, 53, Material.ARROW, text(player, "menu.close", "&cClose"), "close");
    open(player, menu);
  }

  static final class Menu implements InventoryHolder {
    Inventory inventory;
    final UUID owner;
    final Map<Integer, String> actions = new HashMap<Integer, String>();
    String filter = "";
    int page;
    Product product;
    Map<String, Integer> basket;
    long revision;
    ItemStack sample;
    int quantity;
    boolean buying;
    long price, expires;
    MarketContext market;
    Menu(Player player) { owner = player.getUniqueId(); }
    public Inventory getInventory() { return inventory; }
  }

  static ItemStack icon(Material type, String name, String... lines) {
    return preview(new ItemStack(type), name, lines);
  }
  static ItemStack preview(ItemStack sample, String name, String... lines) {
    ItemStack item = sample.clone();
    ItemMeta meta = item.getItemMeta();
    if (name != null) meta.setDisplayName(UniversalShop.color(name));
    List<String> lore = meta.hasLore() ? new ArrayList<String>(meta.getLore()) : new ArrayList<String>();
    for (String line : lines) lore.add(UniversalShop.color(line));
    meta.setLore(lore);
    item.setItemMeta(meta);
    return item;
  }
  private String policy(Menu menu, String action) {
    if (action.equals("categories") || action.startsWith("category:")) return "category";
    if (action.startsWith("buy-listing:")) return "market.buy";
    if (action.startsWith("cancel-listing:")) return "market.cancel";
    if (action.equals("list") || action.equals("create-listing")) return "market.list";
    if (action.equals("claim")) return "market.claim";
    if (action.equals("notifications")) return "market.notices";
    if (action.startsWith("market") || action.startsWith("listing:")) return "market";
    if (action.equals("switch")) return menu.buying ? "sell" : "buy";
    if (action.equals("confirm")) return menu.buying ? "buy" : "sell";
    if (action.equals("confirm-basket")) return "sellall";
    if (action.startsWith("qty:") || action.equals("custom") || action.equals("maximum")) return menu.buying ? "buy" : "sell";
    if (action.equals("editor")) return "admin.editor";
    if (action.startsWith("admin:")) return "admin.manage";
    if (action.startsWith("sort:")) return "sort";
    return action;
  }
  private void button(Menu menu, int slot, Material material, String label, String action, String... lore) {
    Player owner = Bukkit.getPlayer(menu.owner);
    if (owner != null && !plugin.actionEnabled(owner, policy(menu, action))) return;
    Material configured = Product.material(plugin.getConfig().getString("menu-icons." + policy(menu, action), ""));
    menu.inventory.setItem(slot, icon(configured == null || configured == Material.AIR ? material : configured, label, lore));
    menu.actions.put(slot, action);
  }
  private void open(Player player, Menu menu) {
    Material background = Product.material(plugin.getConfig().getString("menu-background", ""));
    if (background != null && background != Material.AIR)
      for (int slot = 0; slot < menu.inventory.getSize(); slot++)
        if (menu.inventory.getItem(slot) == null) menu.inventory.setItem(slot, icon(background, " "));
    player.openInventory(menu.inventory);
  }
  private static int page(int requested, int count, int size) {
    return Math.max(0, Math.min(requested, Math.max(0, (count - 1) / size)));
  }

  public void browse(Player p, String filter, int requested) throws Exception {
    plugin.inputs.cancel(p.getUniqueId());
    plugin.service.allowed(p);
    if (filter.equalsIgnoreCase("deals")) plugin.requireAction(p, "deals");
    List<Product> matches = plugin.management.catalog(p, filter);
    int selected = page(requested, matches.size(), plugin.bedrock.available(p) ? 12 : 36);
    browsing.put(p.getUniqueId(), new BrowseContext(filter, selected));
    if (plugin.bedrock.browse(p, filter, selected)) return;
    selected = page(requested, matches.size(), 36);
    browsing.put(p.getUniqueId(), new BrowseContext(filter, selected));
    Menu menu = new Menu(p); menu.filter = filter; menu.page = selected;
    menu.inventory = Bukkit.createInventory(menu, 54,
        title(p, "menu.browse.title", "&8UniversalShop &6| &8{page}", "page", String.valueOf(selected + 1)));
    Set<String> deals = plugin.service.deals();
    for (int i = selected * 36; i < Math.min(matches.size(), (selected + 1) * 36); i++) {
      Product product = matches.get(i); List<String> lore = new ArrayList<String>();
      lore.add("&8" + product.category + " / " + product.id);
      if (product.buy >= 0 && plugin.actionEnabled(p, "buy")) lore.add(text(p, "menu.product.buy",
          "&aBuy: {price} &7Left click", "price", plugin.money(plugin.service.quote(p, product, 1, true))));
      if (product.sell >= 0 && plugin.actionEnabled(p, "sell")) lore.add(text(p, "menu.product.sell",
          "&eSell: {price} &7Right click", "price", plugin.money(plugin.service.quote(p, product, 1, false))));
      lore.add(text(p, "menu.product.stack", "&7Shift click: stack"));
      if (plugin.management.enabled("personal") && plugin.actionEnabled(p, "favorite"))
        lore.add(text(p, "menu.product.favorite", "&bMiddle click: toggle favorite"));
      lore.add(text(p, "menu.product.stock", "&7Stock: {stock}", "stock", stockText(p, product)));
      if (deals.contains(product.id)) lore.add(text(p, "menu.product.deal", "&6Featured deal"));
      if (plugin.service.remainingLimit(p, product, true) >= 0) lore.add(limitText(p, product, true));
      if (plugin.service.remainingLimit(p, product, false) >= 0) lore.add(limitText(p, product, false));
      if (!plugin.management.policy.direction(product.id, product.category, true))
        lore.add(text(p, "menu.product.buy-paused", "&cPurchases are paused for this product."));
      if (!plugin.management.policy.direction(product.id, product.category, false))
        lore.add(text(p, "menu.product.sell-paused", "&cSales are paused for this product."));
      int slot = i % 36;
      menu.inventory.setItem(slot, preview(product.item, "&f" + product.name, lore.toArray(new String[0])));
      menu.actions.put(slot, "product:" + product.id);
    }
    if (matches.isEmpty()) menu.inventory.setItem(22, icon(Material.PAPER,
        text(p, "menu.empty", "&eNo matching products"), text(p, "menu.empty-help", "&7Try another search or category.")));
    if (plugin.management.enabled("personal")) {
      button(menu, 37, Material.GOLD_INGOT, text(p, "menu.favorites", "&eFavorites"), "favorites");
      button(menu, 38, Product.material("CLOCK|WATCH"), text(p, "menu.recent", "&eRecent trades"), "recent");
      button(menu, 39, Material.PAPER, text(p, "menu.sort", "&eSort products"), "sort");
    }
    button(menu, 41, Material.CHEST, text(p, "menu.all", "&eAll products"), "home");
    if (plugin.hasAdmin(p, "catalog")) button(menu, 43, Product.material("CRAFTING_TABLE|WORKBENCH"),
        text(p, "menu.admin.editor", "&6Catalog editor"), "editor");
    if (selected > 0) button(menu, 45, Material.ARROW, text(p, "menu.previous", "&ePrevious page"), "prev");
    button(menu, 46, Material.CHEST, text(p, "menu.categories", "&6Categories"), "categories");
    button(menu, 47, Material.GOLD_INGOT, text(p, "menu.deals", "&6Featured deals"), "deals",
        text(p, "menu.deals-reset", "&7Next rotation (UTC): {time}", "time", plugin.service.nextRotation().toString()));
    button(menu, 48, Material.PAPER, text(p, "menu.search", "&eSearch"), "search",
        text(p, "menu.search-help", "&7Search names, IDs and categories."), "&7" + filter);
    button(menu, 49, Material.EMERALD, text(p, "menu.balance", "&aBalance: {balance}",
        "balance", plugin.money(plugin.service.balance(p))), "balance");
    button(menu, 50, Material.BOOK, text(p, "menu.market", "&bPlayer market"), "market");
    button(menu, 51, Material.HOPPER, text(p, "menu.sellall", "&eSell inventory"), "sellall",
        text(p, "menu.sellall-help", "&7Review matching items before selling."));
    button(menu, 52, Material.REDSTONE, text(p, "menu.close", "&cClose"), "close");
    if ((selected + 1) * 36 < matches.size()) button(menu, 53, Material.ARROW, text(p, "menu.next", "&eNext page"), "next");
    open(p, menu);
  }
  String stockText(Player p, Product product) {
    long stock = plugin.service.stock(product);
    return stock < 0 ? text(p, "menu.unlimited", "Unlimited") : String.valueOf(stock);
  }
  String limitText(Player p, Product product, boolean buying) {
    int remaining = plugin.service.remainingLimit(p, product, buying);
    return text(p, buying ? "menu.remaining-buy" : "menu.remaining-sell",
        buying ? "&7Buy quota remaining today: {remaining}" : "&7Sell quota remaining today: {remaining}",
        "remaining", remaining < 0 ? text(p, "menu.unlimited", "Unlimited") : String.valueOf(remaining));
  }
  void back(Player p) throws Exception {
    BrowseContext context = browsing.get(p.getUniqueId());
    browse(p, context == null ? "" : context.filter, context == null ? 0 : context.page);
  }
  void search(final Player p) {
    plugin.requireAction(p, "search");
    plugin.inputs.request(p, text(p, "input.search", "&eEnter a product name, category or ID (type * for all):"), value -> {
      plugin.requireAction(p, "search"); browse(p, value.equals("*") ? "" : value, 0);
    });
  }
  void customQuantity(final Player p, final Product product, final boolean buying) {
    plugin.requireAction(p, buying ? "buy" : "sell");
    plugin.inputs.request(p, text(p, "input.quantity", "&eEnter the quantity for {product}:", "product", product.name), value -> {
      if (!value.matches("[0-9]{1,4}")) throw new IllegalArgumentException("Enter a whole number from 1 to 2304.");
      if (plugin.products.get(product.id) != product) throw new IllegalArgumentException("The catalog changed. Open a fresh quote.");
      confirm(p, product, Integer.parseInt(value), buying);
    });
  }
  void sorting(Player p) throws Exception {
    plugin.requireAction(p, "sort");
    if (plugin.bedrock.sorting(p)) return;
    Menu menu = new Menu(p);
    menu.inventory = Bukkit.createInventory(menu, 27, title(p, "menu.sort-title", "UniversalShop | Sort"));
    button(menu, 10, Material.CHEST, text(p, "menu.sort-catalog", "&eCatalog order"), "sort:catalog");
    button(menu, 13, Material.PAPER, text(p, "menu.sort-name", "&eName"), "sort:name");
    button(menu, 16, Material.GOLD_INGOT, text(p, "menu.sort-price", "&eBuy price"), "sort:price");
    button(menu, 22, Material.ARROW, text(p, "menu.back", "&eBack"), "back");
    open(p, menu);
  }
  void setSort(Player p, String sort) throws Exception {
    BrowseContext context = browsing.get(p.getUniqueId());
    plugin.requireAction(p, "sort"); plugin.service.allowed(p);
    if (!plugin.management.enabled("personal")) throw new IllegalArgumentException("The personal module is disabled.");
    if (!Arrays.asList("catalog", "name", "price").contains(sort)) throw new IllegalArgumentException("Sort must be catalog, name or price.");
    org.bukkit.configuration.file.YamlConfiguration next = plugin.store.copy();
    next.set("management.personal." + p.getUniqueId() + ".sort", sort);
    plugin.store.save(next, "SORT " + p.getUniqueId());
    browse(p, context == null ? "" : context.filter, context == null ? 0 : context.page);
  }
  public void categories(Player p, int requested) throws Exception {
    plugin.inputs.cancel(p.getUniqueId());
    plugin.requireAction(p, "category"); plugin.service.allowed(p);
    if (plugin.bedrock.categories(p, requested)) return;
    Set<String> set = new TreeSet<String>();
    for (Product product : plugin.products.values()) if (plugin.service.visible(p, product)) set.add(product.category);
    List<String> names = new ArrayList<String>(set);
    Menu menu = new Menu(p); menu.page = page(requested, names.size(), 45);
    menu.inventory = Bukkit.createInventory(menu, 54, title(p, "menu.categories-title", "UniversalShop | Categories"));
    for (int i = menu.page * 45; i < Math.min(names.size(), (menu.page + 1) * 45); i++)
      button(menu, i % 45, Material.CHEST, "&6" + names.get(i), "category:" + names.get(i));
    if (menu.page > 0) button(menu, 45, Material.ARROW, text(p, "menu.previous", "&ePrevious page"), "catprev");
    button(menu, 49, Material.CHEST, text(p, "menu.all", "&eAll products"), "home");
    if ((menu.page + 1) * 45 < names.size()) button(menu, 53, Material.ARROW, text(p, "menu.next", "&eNext page"), "catnext");
    open(p, menu);
  }
  public void confirm(Player p, Product product, int quantity, boolean buying) throws Exception {
    plugin.inputs.cancel(p.getUniqueId());
    plugin.requireAction(p, buying ? "buy" : "sell"); plugin.service.allowed(p);
    if (plugin.products.get(product.id) != product || !plugin.service.visible(p, product))
      throw new IllegalArgumentException("This product is unavailable. Open the catalog again.");
    if (plugin.bedrock.confirm(p, product, quantity, buying)) return;
    Menu menu = new Menu(p); menu.product = product; menu.quantity = quantity; menu.buying = buying;
    menu.price = plugin.service.quote(p, product, quantity, buying); menu.expires = System.currentTimeMillis() + 30000;
    menu.inventory = Bukkit.createInventory(menu, 36, title(p, "menu.confirm-title", "UniversalShop | Confirm"));
    menu.inventory.setItem(13, preview(product.item, "&f" + product.name,
        text(p, buying ? "menu.confirm-buy" : "menu.confirm-sell", buying ? "&7Buy {quantity}" : "&7Sell {quantity}", "quantity", String.valueOf(quantity)),
        text(p, "menu.total", "&6Total: {price}", "price", plugin.money(menu.price)), limitText(p, product, buying),
        text(p, "menu.quote-expiry", "&7Quote expires in 30 seconds.")));
    button(menu, 11, Material.EMERALD, text(p, "menu.confirm", "&aConfirm"), "confirm");
    button(menu, 15, Material.REDSTONE, text(p, "menu.cancel", "&cCancel"), "back");
    if (product.commands.isEmpty()) {
      int max = plugin.getConfig().getInt("max-transaction-items", 2304), slot = 18;
      for (int size : new int[] {1, 16, 64, 256}) if (size <= max)
        button(menu, slot++, Material.PAPER, text(p, "menu.quantity", "&e{quantity} items", "quantity", String.valueOf(size)), "qty:" + size);
      button(menu, 23, Material.BOOK, text(p, "menu.custom-quantity", "&eCustom quantity"), "custom");
      int possible = plugin.service.maxQuantity(p, product, buying);
      button(menu, 24, Material.HOPPER, text(p, "menu.max-quantity", "&aMaximum: {quantity}", "quantity", String.valueOf(possible)), "maximum",
          text(p, "menu.max-help", "&7Considers money, inventory, stock and quotas."));
    }
    if ((buying ? product.sell : product.buy) >= 0) button(menu, 26, Material.PAPER,
        text(p, buying ? "menu.switch-sell" : "menu.switch-buy", buying ? "&eSwitch to Sell" : "&eSwitch to Buy"), "switch");
    open(p, menu);
  }
  boolean sellAll(Player p, Map<String, Integer> basket, long quote) throws Exception {
    plugin.requireAction(p, "sellall"); plugin.requireAction(p, "sell"); plugin.service.allowed(p);
    plugin.inputs.cancel(p.getUniqueId());
    if (plugin.bedrock.sellAll(p, basket, quote)) return true;
    Menu menu = new Menu(p);
    menu.basket = new LinkedHashMap<String, Integer>(basket);
    menu.price = quote; menu.expires = System.currentTimeMillis() + 30000; menu.revision = plugin.catalogRevision();
    menu.inventory = Bukkit.createInventory(menu, 54, title(p, "bedrock.sellall-title", "UniversalShop | Sell inventory"));
    int slot = 18;
    for (Map.Entry<String, Integer> entry : basket.entrySet()) {
      Product product = plugin.product(entry.getKey());
      if (slot < 45) menu.inventory.setItem(slot++, preview(product.item, "&f" + product.name,
          text(p, "menu.quantity", "&e{quantity} items", "quantity", String.valueOf(entry.getValue())),
          text(p, "menu.total", "&6Total: {price}", "price", plugin.money(plugin.service.quote(p, product, entry.getValue(), false)))));
    }
    menu.inventory.setItem(13, icon(Material.BOOK,
        text(p, "menu.basket-summary", "&e{count} product types", "count", String.valueOf(basket.size())),
        text(p, "menu.total", "&6Total: {price}", "price", plugin.money(quote)), text(p, "menu.quote-expiry", "&7Quote expires in 30 seconds.")));
    button(menu, 11, Material.EMERALD, text(p, "bedrock.confirm-sell", "&aConfirm sale"), "confirm-basket");
    button(menu, 15, Material.REDSTONE, text(p, "menu.cancel", "&cCancel"), "back");
    open(p, menu);
    return true;
  }
  public void market(Player p, int requested) throws Exception {
    MarketContext c = markets.get(p.getUniqueId());
    market(p, c == null ? "" : c.filter, c == null ? "newest" : c.sort, c != null && c.mine, requested);
  }
  void market(Player p, String filter, String sort, boolean mine, int requested) throws Exception {
    plugin.inputs.cancel(p.getUniqueId());
    plugin.requireAction(p, "market"); plugin.service.allowed(p);
    List<String> ids = plugin.service.marketEntries(p, filter, sort, mine);
    int selected = page(requested, ids.size(), plugin.bedrock.available(p) ? 12 : 36);
    markets.put(p.getUniqueId(), new MarketContext(filter, sort, mine, selected));
    if (plugin.bedrock.market(p, filter, sort, mine, selected)) return;
    Menu menu = new Menu(p); menu.page = page(requested, ids.size(), 36);
    menu.market = new MarketContext(filter, sort, mine, menu.page); markets.put(p.getUniqueId(), menu.market);
    menu.inventory = Bukkit.createInventory(menu, 54, title(p, "menu.market-title", "UniversalShop | Player Market"));
    org.bukkit.configuration.file.YamlConfiguration state = plugin.store.state();
    for (int i = menu.page * 36; i < Math.min(ids.size(), (menu.page + 1) * 36); i++) {
      String id = ids.get(i), path = "market." + id; ItemStack sample = state.getItemStack(path + ".item");
      if (sample == null) continue;
      boolean own = p.getUniqueId().toString().equals(state.getString(path + ".owner"));
      boolean expired = state.getLong(path + ".expires") <= System.currentTimeMillis();
      menu.inventory.setItem(i % 36, preview(sample, null, "&8#" + id,
          text(p, "menu.market-seller", "&7Seller: {seller}", "seller", state.getString(path + ".seller")),
          text(p, "menu.quantity", "&e{quantity} items", "quantity", String.valueOf(state.getInt(path + ".quantity"))),
          text(p, "menu.total", "&6Total: {price}", "price", plugin.money(state.getLong(path + ".price"))),
          expired ? text(p, "menu.market-expired", "&cExpired - safely stored for recovery") :
              text(p, "menu.market-expiry", "&7Expires: {time}", "time", java.time.Instant.ofEpochMilli(state.getLong(path + ".expires")).toString()),
          own ? text(p, "menu.market-reclaim-help", "&eClick to reclaim") : text(p, "menu.market-buy-help", "&eClick to review purchase")));
      menu.actions.put(i % 36, "listing:" + id);
    }
    if (ids.isEmpty()) menu.inventory.setItem(22, icon(Material.PAPER, text(p, "menu.market-empty", "&eNo matching listings")));
    button(menu, 36, Material.CHEST, text(p, "menu.market-mine", "&eMy listings"), "marketmine");
    button(menu, 37, Product.material("CLOCK|WATCH"), text(p, "menu.market-expired-button", "&eExpired items"), "marketexpired");
    button(menu, 38, Material.PAPER, text(p, "menu.search", "&eSearch"), "marketsearch");
    button(menu, 39, Material.BOOK, text(p, "menu.market-sort", "&eSort: {sort}", "sort", sortName(p, sort)), "marketsort");
    button(menu, 40, Material.CHEST, text(p, "menu.market-all", "&eAll active listings"), "marketall");
    button(menu, 41, Material.EMERALD, text(p, "menu.market-list", "&aList held item"), "list");
    button(menu, 42, Material.HOPPER, text(p, "menu.market-claim", "&aRecover items and earnings"), "claim",
        text(p, "menu.market-credit", "&7Unclaimed earnings: {price}", "price", plugin.money(plugin.service.pendingMarketCredit(p))));
    button(menu, 43, Material.BOOK, text(p, "menu.market-notices", "&eSale notifications: {count}",
        "count", String.valueOf(plugin.service.notificationCount(p))), "notifications");
    if (menu.page > 0) button(menu, 45, Material.ARROW, text(p, "menu.previous", "&ePrevious page"), "marketprev");
    button(menu, 49, Material.CHEST, text(p, "menu.server-shop", "&eServer shop"), "back");
    button(menu, 52, Material.REDSTONE, text(p, "menu.close", "&cClose"), "close");
    if ((menu.page + 1) * 36 < ids.size()) button(menu, 53, Material.ARROW, text(p, "menu.next", "&eNext page"), "marketnext");
    open(p, menu);
  }
  void marketBack(Player p) throws Exception {
    MarketContext c = markets.get(p.getUniqueId()); market(p, c == null ? 0 : c.page);
  }
  void marketSearch(Player p) {
    plugin.requireAction(p, "market"); final MarketContext c = markets.get(p.getUniqueId());
    plugin.inputs.request(p, text(p, "input.market-search", "&eSearch item names, materials or sellers (type * for all):"), value ->
        market(p, value.equals("*") ? "" : value, c == null ? "newest" : c.sort, c != null && c.mine, 0));
  }
  void marketSort(Player p) throws Exception {
    MarketContext c = markets.get(p.getUniqueId()); String[] sorts = {"newest", "price", "name", "seller"};
    int at = c == null ? -1 : Arrays.asList(sorts).indexOf(c.sort);
    market(p, c == null ? "" : c.filter, sorts[(at + 1) % sorts.length], c != null && c.mine, 0);
  }
  String sortName(Player p, String sort) {
    return text(p, "menu.market-sort-" + sort, sort);
  }
  void notifications(Player p) throws Exception {
    plugin.requireAction(p, "market.notices");
    List<String> notices = plugin.service.consumeNotifications(p);
    if (notices.isEmpty()) plugin.tell(p, text(p, "market.no-notices", "&eNo unread sale notifications."));
    for (String notice : notices) plugin.tell(p, "&a" + notice);
    marketBack(p);
  }
  void claim(Player p) throws Exception {
    plugin.requireAction(p, "market.claim"); int count = plugin.service.claimExpired(p);
    plugin.tell(p, text(p, "menu.market-claimed", "&aRecovered {count} expired listings. Items that do not fit remain safely stored.", "count", String.valueOf(count)));
    marketBack(p);
  }
  void createListing(final Player p) throws Exception {
    plugin.requireAction(p, "market.list"); plugin.service.allowed(p);
    ItemStack held = p.getItemInHand();
    if (held == null || held.getType() == Material.AIR) throw new IllegalArgumentException("Hold an item to list.");
    final ItemStack sample = held.clone(); sample.setAmount(1);
    plugin.inputs.request(p, text(p, "input.market-quantity", "&eHow many held items do you want to list?"), value -> {
      if (!value.matches("[0-9]{1,4}")) throw new IllegalArgumentException("Enter a whole number from 1 to 2304.");
      final int count = Integer.parseInt(value);
      if (count < 1 || count > 2304) throw new IllegalArgumentException("Quantity must be between 1 and 2304.");
      plugin.inputs.request(p, text(p, "input.market-price", "&eEnter the total price for all {quantity} items:", "quantity", String.valueOf(count)), price ->
          listConfirm(p, sample, count, Money.parse(price)));
    });
  }
  private void listConfirm(Player p, ItemStack sample, int count, long price) throws Exception {
    plugin.requireAction(p, "market.list"); plugin.service.allowed(p);
    if (price < 1) throw new IllegalArgumentException("The listing price must be positive.");
    if (plugin.bedrock.listConfirm(p, sample, count, price)) return;
    Menu menu = new Menu(p); menu.sample = sample; menu.quantity = count; menu.price = price; menu.expires = System.currentTimeMillis() + 30000;
    menu.inventory = Bukkit.createInventory(menu, 27, title(p, "menu.list-title", "UniversalShop | Create Listing"));
    menu.inventory.setItem(13, preview(sample, null, text(p, "menu.quantity", "&e{quantity} items", "quantity", String.valueOf(count)),
        text(p, "menu.total", "&6Total: {price}", "price", plugin.money(price))));
    button(menu, 11, Material.EMERALD, text(p, "menu.market-publish", "&aPublish listing"), "create-listing");
    button(menu, 15, Material.REDSTONE, text(p, "menu.cancel", "&cCancel"), "marketback");
    open(p, menu);
  }
  void publishListing(Player p, ItemStack sample, int count, long price) throws Exception {
    ItemStack current = p.getItemInHand();
    if (current == null || !current.isSimilar(sample)) throw new IllegalArgumentException("The held item changed. Create a fresh listing.");
    String id = plugin.service.list(p, count, price);
    plugin.tell(p, text(p, "menu.market-listed", "&aListing #{id} published.", "id", id)); market(p, "", "newest", true, 0);
  }
  private void listing(Player p, String id) throws Exception {
    org.bukkit.configuration.file.YamlConfiguration state = plugin.store.state(); String path = "market." + id;
    if (!state.contains(path)) throw new IllegalArgumentException("This listing is no longer available.");
    boolean own = p.getUniqueId().toString().equals(state.getString(path + ".owner"));
    Menu menu = new Menu(p);
    menu.inventory = Bukkit.createInventory(menu, 27, title(p, "menu.listing-title", "UniversalShop | Market Confirm"));
    menu.inventory.setItem(13, preview(state.getItemStack(path + ".item"), null,
        text(p, "menu.quantity", "&e{quantity} items", "quantity", String.valueOf(state.getInt(path + ".quantity"))),
        text(p, "menu.total", "&6Total: {price}", "price", plugin.money(state.getLong(path + ".price")))));
    if (own || state.getLong(path + ".expires") > System.currentTimeMillis())
      button(menu, 11, Material.EMERALD, own ? text(p, "menu.market-reclaim", "&aReclaim items") :
          text(p, "menu.market-buy", "&aBuy for {price}", "price", plugin.money(state.getLong(path + ".price"))),
          (own ? "cancel-listing:" : "buy-listing:") + id);
    button(menu, 15, Material.REDSTONE, text(p, "menu.cancel", "&cCancel"), "marketback"); open(p, menu);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void click(InventoryClickEvent e) {
    Inventory top = e.getView().getTopInventory(); if (!(top.getHolder() instanceof Menu)) return;
    e.setCancelled(true); if (!(e.getWhoClicked() instanceof Player)) return;
    final Player p = (Player) e.getWhoClicked(); final Menu menu = (Menu) top.getHolder();
    if (!menu.owner.equals(p.getUniqueId()) || e.getRawSlot() < 0 || e.getRawSlot() >= top.getSize()) return;
    final String action = menu.actions.get(e.getRawSlot()); if (action == null) return;
    ClickType type = e.getClick();
    if (type != ClickType.LEFT && type != ClickType.RIGHT && type != ClickType.SHIFT_LEFT && type != ClickType.SHIFT_RIGHT && type != ClickType.MIDDLE) return;
    final boolean right = e.isRightClick(), shift = e.isShiftClick(), middle = type == ClickType.MIDDLE;
    menu.actions.clear();
    Bukkit.getScheduler().runTask(plugin, new Runnable() {
      public void run() {
        if (!p.isOnline() || p.getOpenInventory().getTopInventory().getHolder() != menu) return;
        try {
          if (action.equals("close")) { p.closeInventory(); return; }
          if (!action.startsWith("admin:") && !action.equals("editor")) plugin.service.allowed(p);
          plugin.requireAction(p, policy(menu, action));
          if (action.startsWith("admin:")) {
            plugin.onCommand(p, plugin.getCommand("shop"), "shop", action.split(":")); adminPanel(p);
          } else if (action.equals("editor")) plugin.editor.open(p);
          else if (action.startsWith("product:")) {
            Product product = plugin.product(action.substring(8));
            if (middle && plugin.management.enabled("personal")) {
              plugin.management.personal(p, new String[] {"favorite", product.id}); browse(p, menu.filter, menu.page);
            } else confirm(p, product, shift && product.commands.isEmpty() ? Math.min(product.item.getMaxStackSize(), plugin.getConfig().getInt("max-transaction-items", 2304)) : 1, !right);
          } else if (action.equals("confirm-basket")) {
            if (System.currentTimeMillis() > menu.expires || menu.revision != plugin.catalogRevision())
              throw new IllegalArgumentException("This preview expired. Preview again.");
            plugin.service.sellAll(p, menu.basket, menu.price); back(p);
          } else if (action.equals("confirm")) {
            if (System.currentTimeMillis() > menu.expires) throw new IllegalArgumentException("This quote expired. Open a fresh confirmation.");
            if (plugin.products.get(menu.product.id) != menu.product) throw new IllegalArgumentException("The catalog changed. Open a fresh quote.");
            plugin.service.trade(p, menu.product, menu.quantity, menu.buying, menu.price); back(p);
          } else if (action.startsWith("qty:")) confirm(p, menu.product, Integer.parseInt(action.substring(4)), menu.buying);
          else if (action.equals("custom")) customQuantity(p, menu.product, menu.buying);
          else if (action.equals("maximum")) {
            int max = plugin.service.maxQuantity(p, menu.product, menu.buying);
            if (max < 1) throw new IllegalArgumentException("No quantity can be traded with your current money, items, stock and quotas.");
            confirm(p, menu.product, max, menu.buying);
          } else if (action.equals("switch")) confirm(p, menu.product, menu.quantity, !menu.buying);
          else if (action.equals("prev") || action.equals("next")) browse(p, menu.filter, menu.page + (action.equals("next") ? 1 : -1));
          else if (action.equals("categories")) categories(p, 0);
          else if (action.equals("catprev") || action.equals("catnext")) categories(p, menu.page + (action.equals("catnext") ? 1 : -1));
          else if (action.startsWith("category:")) browse(p, action.substring(9), 0);
          else if (action.equals("deals")) browse(p, "deals", 0);
          else if (action.equals("favorites") || action.equals("recent")) browse(p, "@" + action, 0);
          else if (action.equals("sort")) sorting(p);
          else if (action.startsWith("sort:")) setSort(p, action.substring(5));
          else if (action.equals("market")) market(p, "", "newest", false, 0);
          else if (action.equals("marketback")) marketBack(p);
          else if (action.equals("marketnext") || action.equals("marketprev")) market(p, menu.page + (action.equals("marketnext") ? 1 : -1));
          else if (action.equals("marketmine")) market(p, "", "newest", true, 0);
          else if (action.equals("marketexpired")) market(p, "@expired", "newest", true, 0);
          else if (action.equals("marketall")) market(p, "", "newest", false, 0);
          else if (action.equals("marketsearch")) marketSearch(p);
          else if (action.equals("marketsort")) marketSort(p);
          else if (action.equals("notifications")) notifications(p);
          else if (action.equals("claim")) claim(p);
          else if (action.equals("list")) createListing(p);
          else if (action.equals("create-listing")) {
            if (System.currentTimeMillis() > menu.expires) throw new IllegalArgumentException("This preview expired. Create a fresh listing.");
            publishListing(p, menu.sample, menu.quantity, menu.price);
          } else if (action.startsWith("listing:")) listing(p, action.substring(8));
          else if (action.startsWith("buy-listing:") || action.startsWith("cancel-listing:")) {
            plugin.service.marketTake(p, action.substring(action.indexOf(':') + 1), action.startsWith("cancel"));
            plugin.tell(p, text(p, "menu.market-complete", "&aMarket transaction complete.")); marketBack(p);
          } else if (action.equals("sellall")) { p.closeInventory(); plugin.previewSellAll(p); }
          else if (action.equals("search")) search(p);
          else if (action.equals("balance")) { plugin.message(p, "balance", "balance", plugin.money(plugin.service.balance(p))); back(p); }
          else if (action.equals("close")) p.closeInventory();
          else if (action.equals("back")) back(p);
          else browse(p, "", 0);
        } catch (Exception error) { p.closeInventory(); plugin.failure(p, error); }
      }
    });
  }
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void drag(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
  }
}
