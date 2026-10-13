package fr.hash014.universalshop;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

public final class ShopMenus implements Listener {
  private final UniversalShop plugin;

  public ShopMenus(UniversalShop plugin) {
    this.plugin = plugin;
  }

  static final class Menu implements InventoryHolder {
    Inventory inventory;
    final UUID owner;
    final Map<Integer, String> actions = new HashMap<Integer, String>();
    String filter = "";
    int page;
    Product product;
    int quantity;
    boolean buying;
    long price, expires;

    Menu(Player p) {
      owner = p.getUniqueId();
    }

    public Inventory getInventory() {
      return inventory;
    }
  }

  static ItemStack icon(Material type, String name, String... lines) {
    ItemStack item = new ItemStack(type);
    ItemMeta meta = item.getItemMeta();
    meta.setDisplayName(UniversalShop.color(name));
    List<String> lore = new ArrayList<String>();
    for (String line : lines) lore.add(UniversalShop.color(line));
    meta.setLore(lore);
    item.setItemMeta(meta);
    return item;
  }

  private void button(
      Menu menu, int slot, Material material, String label, String action, String... lore) {
    Player owner = Bukkit.getPlayer(menu.owner);
    String policy =
        action.equals("categories")
            ? "category"
            : action.startsWith("buy-listing:")
                ? "market.buy"
                : action.startsWith("cancel-listing:")
                    ? "market.cancel"
                    : action.equals("switch") ? (menu.buying ? "sell" : "buy") : action;
    if (owner != null && !plugin.actionEnabled(owner, policy)) return;
    menu.inventory.setItem(slot, icon(material, label, lore));
    menu.actions.put(slot, action);
  }

  public void browse(Player p, String filter, int page) throws Exception {
    plugin.service.allowed(p);
    if (filter.equalsIgnoreCase("deals")) plugin.requireAction(p, "deals");
    if (plugin.bedrock.browse(p, filter, page)) return;
    Menu menu = new Menu(p);
    menu.filter = filter;
    List<Product> matches = new ArrayList<Product>();
    Set<String> deals = plugin.service.deals();
    for (Product product : plugin.products.values()) {
      String term = filter.toLowerCase(Locale.ROOT);
      if (plugin.service.visible(p, product)
          && (term.isEmpty()
              || (term.equals("deals")
                  ? deals.contains(product.id)
                  : (product.name + " " + product.id + " " + product.category)
                      .toLowerCase(Locale.ROOT)
                      .contains(term)))) matches.add(product);
    }
    menu.page = Math.max(0, Math.min(page, Math.max(0, (matches.size() - 1) / 45)));
    menu.inventory =
        Bukkit.createInventory(
            menu, 54, UniversalShop.color("&8UniversalShop &6| &8" + (menu.page + 1)));
    for (int i = menu.page * 45; i < Math.min(matches.size(), (menu.page + 1) * 45); i++) {
      Product product = matches.get(i);
      int slot = i % 45;
      ItemStack item = product.item.clone();
      ItemMeta meta = item.getItemMeta();
      List<String> lore =
          meta.hasLore() ? new ArrayList<String>(meta.getLore()) : new ArrayList<String>();
      lore.add(UniversalShop.color("&8" + product.category + " / " + product.id));
      if (product.buy >= 0)
        lore.add(
            UniversalShop.color(
                "&aBuy: "
                    + plugin.money(plugin.service.quote(p, product, 1, true))
                    + " &7Left click"));
      if (product.sell >= 0)
        lore.add(
            UniversalShop.color(
                "&eSell: "
                    + plugin.money(plugin.service.quote(p, product, 1, false))
                    + " &7Right click"));
      lore.add(UniversalShop.color("&7Shift click: stack | Middle: 1"));
      lore.add(
          UniversalShop.color(
              "&7Stock: "
                  + (plugin.service.stock(product) < 0
                      ? "Unlimited"
                      : plugin.service.stock(product))));
      if (deals.contains(product.id)) lore.add(UniversalShop.color("&6Featured deal"));
      if (product.dailyBuy > 0 || product.dailySell > 0)
        lore.add(
            UniversalShop.color(
                "&7Daily limits: buy "
                    + product.dailyBuy
                    + " / sell "
                    + product.dailySell
                    + " (0 = none)"));
      meta.setDisplayName(UniversalShop.color("&f" + product.name));
      meta.setLore(lore);
      item.setItemMeta(meta);
      menu.inventory.setItem(slot, item);
      menu.actions.put(slot, "product:" + product.id);
    }
    button(menu, 45, Material.ARROW, "&ePrevious page", "prev");
    button(menu, 46, Material.CHEST, "&6Categories", "categories");
    button(
        menu,
        47,
        Material.GOLD_INGOT,
        "&6Featured deals",
        "deals",
        "&7Next rotation (UTC):",
        plugin.service.nextRotation().toString());
    button(menu, 48, Material.PAPER, "&eSearch", "search", "&7Use /shop search <text>");
    button(
        menu,
        49,
        Material.EMERALD,
        "&aBalance: " + plugin.money(plugin.service.balance(p)),
        "balance");
    button(
        menu,
        50,
        Material.BOOK,
        "&bPlayer market",
        "market",
        "&7Use /shop market list <amount> <total price>");
    button(
        menu,
        51,
        Material.HOPPER,
        "&eSell inventory",
        "sellall",
        "&7Preview matching items before selling.");
    button(menu, 52, Material.REDSTONE, "&cClose", "close");
    button(menu, 53, Material.ARROW, "&eNext page", "next");
    p.openInventory(menu.inventory);
  }

  public void categories(Player p, int page) throws Exception {
    plugin.requireAction(p, "category");
    plugin.service.allowed(p);
    if (plugin.bedrock.categories(p, page)) return;
    Menu menu = new Menu(p);
    menu.page = Math.max(0, page);
    menu.inventory = Bukkit.createInventory(menu, 54, "UniversalShop | Categories");
    Set<String> set = new TreeSet<String>();
    for (Product product : plugin.products.values())
      if (plugin.service.visible(p, product)) set.add(product.category);
    List<String> categories = new ArrayList<String>(set);
    page = Math.max(0, Math.min(page, Math.max(0, (categories.size() - 1) / 45)));
    menu.page = page;
    for (int i = page * 45; i < Math.min(categories.size(), (page + 1) * 45); i++)
      button(
          menu, i % 45, Material.CHEST, "&6" + categories.get(i), "category:" + categories.get(i));
    button(menu, 45, Material.ARROW, "&ePrevious", "catprev");
    button(menu, 49, Material.CHEST, "&eAll products", "home");
    button(menu, 53, Material.ARROW, "&eNext", "catnext");
    p.openInventory(menu.inventory);
  }

  public void confirm(Player p, Product product, int quantity, boolean buying) throws Exception {
    plugin.requireAction(p, buying ? "buy" : "sell");
    plugin.service.allowed(p);
    if (!plugin.service.visible(p, product))
      throw new IllegalArgumentException("This product is unavailable.");
    if (plugin.bedrock.confirm(p, product, quantity, buying)) return;
    Menu menu = new Menu(p);
    menu.product = product;
    menu.quantity = quantity;
    menu.buying = buying;
    menu.price = plugin.service.quote(p, product, quantity, buying);
    menu.expires = System.currentTimeMillis() + 30000;
    menu.inventory = Bukkit.createInventory(menu, 27, "UniversalShop | Confirm");
    menu.inventory.setItem(
        13,
        icon(
            product.item.getType(),
            "&f" + product.name,
            "&7" + (buying ? "Buy" : "Sell") + " " + quantity,
            "&6Total: " + plugin.money(menu.price),
            "&7Quote expires in 30 seconds."));
    button(menu, 11, Material.EMERALD, "&aConfirm", "confirm");
    button(menu, 15, Material.REDSTONE, "&cCancel", "home");
    if (product.commands.isEmpty()) {
      button(menu, 18, Material.PAPER, "&e1 item", "qty:1");
      button(menu, 20, Material.PAPER, "&e16 items", "qty:16");
      button(menu, 22, Material.PAPER, "&e64 items", "qty:64");
      button(menu, 24, Material.PAPER, "&e256 items", "qty:256");
      button(menu, 26, Material.PAPER, "&eSwitch buy / sell", "switch");
    }
    p.openInventory(menu.inventory);
  }

  public void market(Player p, int page) throws Exception {
    plugin.requireAction(p, "market");
    plugin.service.allowed(p);
    if (plugin.bedrock.market(p, page)) return;
    Menu menu = new Menu(p);
    menu.page = Math.max(0, page);
    menu.inventory = Bukkit.createInventory(menu, 54, "UniversalShop | Player Market");
    org.bukkit.configuration.ConfigurationSection market =
        plugin.store.state().getConfigurationSection("market");
    if (market != null) {
      List<String> ids = new ArrayList<String>(market.getKeys(false));
      Collections.sort(ids);
      page = Math.max(0, Math.min(page, Math.max(0, (ids.size() - 1) / 45)));
      menu.page = page;
      for (int i = page * 45; i < Math.min(ids.size(), (page + 1) * 45); i++) {
        String id = ids.get(i);
        ItemStack sample = market.getItemStack(id + ".item");
        boolean own = p.getUniqueId().toString().equals(market.getString(id + ".owner"));
        button(
            menu,
            i % 45,
            sample.getType(),
            "&f" + id + " | " + sample.getType(),
            "listing:" + id,
            "&7Seller: " + market.getString(id + ".seller"),
            "&7Quantity: " + market.getInt(id + ".quantity"),
            "&6Total: " + plugin.money(market.getLong(id + ".price")),
            "&7Expires: " + InstantText(market.getLong(id + ".expires")),
            own ? "&eClick to reclaim" : "&eClick to review purchase");
      }
    }
    button(menu, 45, Material.ARROW, "&ePrevious", "marketprev");
    button(menu, 49, Material.CHEST, "&eServer shop", "home");
    button(menu, 53, Material.ARROW, "&eNext", "marketnext");
    p.openInventory(menu.inventory);
  }

  private String InstantText(long time) {
    return java.time.Instant.ofEpochMilli(time).toString();
  }

  private void listing(Player p, String id) throws Exception {
    String path = "market." + id;
    org.bukkit.configuration.file.YamlConfiguration state = plugin.store.state();
    if (!state.contains(path))
      throw new IllegalArgumentException("This listing is no longer available.");
    boolean own = p.getUniqueId().toString().equals(state.getString(path + ".owner"));
    Menu menu = new Menu(p);
    menu.inventory = Bukkit.createInventory(menu, 27, "UniversalShop | Market Confirm");
    menu.inventory.setItem(13, state.getItemStack(path + ".item"));
    button(
        menu,
        11,
        Material.EMERALD,
        own ? "&aReclaim items" : "&aBuy for " + plugin.money(state.getLong(path + ".price")),
        (own ? "cancel-listing:" : "buy-listing:") + id,
        "&7Quantity: " + state.getInt(path + ".quantity"));
    button(menu, 15, Material.REDSTONE, "&cCancel", "market");
    p.openInventory(menu.inventory);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void click(InventoryClickEvent e) {
    Inventory top = e.getView().getTopInventory();
    if (!(top.getHolder() instanceof Menu)) return;
    e.setCancelled(true);
    if (!(e.getWhoClicked() instanceof Player)) return;
    final Player p = (Player) e.getWhoClicked();
    final Menu menu = (Menu) top.getHolder();
    if (!menu.owner.equals(p.getUniqueId())
        || e.getRawSlot() < 0
        || e.getRawSlot() >= top.getSize()) return;
    final String action = menu.actions.get(e.getRawSlot());
    if (action == null) return;
    // Block hotbar swaps, double clicks, drops and unusual click types entirely.
    ClickType type = e.getClick();
    if (type != ClickType.LEFT
        && type != ClickType.RIGHT
        && type != ClickType.SHIFT_LEFT
        && type != ClickType.SHIFT_RIGHT
        && type != ClickType.MIDDLE) return;
    final boolean right = e.isRightClick(), shift = e.isShiftClick();
    menu.actions
        .clear(); // Consume this view immediately, before a second packet can confirm again.
    Bukkit.getScheduler()
        .runTask(
            plugin,
            new Runnable() {
              public void run() {
                if (!p.isOnline() || p.getOpenInventory().getTopInventory().getHolder() != menu)
                  return;
                try {
                  plugin.service.allowed(p);
                  if (action.startsWith("category:")) plugin.requireAction(p, "category");
                  if (action.equals("search") || action.equals("balance"))
                    plugin.requireAction(p, action);
                  if (action.startsWith("listing:")) plugin.requireAction(p, "market");
                  if (action.startsWith("product:")) {
                    Product product = plugin.product(action.substring(8));
                    confirm(
                        p,
                        product,
                        shift && product.commands.isEmpty() ? product.item.getMaxStackSize() : 1,
                        !right);
                  } else if (action.equals("confirm")) {
                    p.closeInventory();
                    if (System.currentTimeMillis() > menu.expires)
                      throw new IllegalArgumentException("This quote has expired.");
                    if (plugin.products.get(menu.product.id) != menu.product)
                      throw new IllegalArgumentException(
                          "The catalog changed. Open a fresh quote.");
                    plugin.service.trade(p, menu.product, menu.quantity, menu.buying, menu.price);
                  } else if (action.startsWith("qty:"))
                    confirm(p, menu.product, Integer.parseInt(action.substring(4)), menu.buying);
                  else if (action.equals("switch"))
                    confirm(p, menu.product, menu.quantity, !menu.buying);
                  else if (action.equals("prev") || action.equals("next"))
                    browse(p, menu.filter, menu.page + (action.equals("next") ? 1 : -1));
                  else if (action.equals("categories")) categories(p, 0);
                  else if (action.equals("catprev") || action.equals("catnext"))
                    categories(p, Math.max(0, menu.page + (action.equals("catnext") ? 1 : -1)));
                  else if (action.startsWith("category:")) browse(p, action.substring(9), 0);
                  else if (action.equals("deals")) browse(p, "deals", 0);
                  else if (action.equals("market")) market(p, 0);
                  else if (action.equals("marketnext") || action.equals("marketprev"))
                    market(p, Math.max(0, menu.page + (action.equals("marketnext") ? 1 : -1)));
                  else if (action.startsWith("listing:")) listing(p, action.substring(8));
                  else if (action.startsWith("buy-listing:")
                      || action.startsWith("cancel-listing:")) {
                    p.closeInventory();
                    plugin.service.marketTake(
                        p, action.substring(action.indexOf(':') + 1), action.startsWith("cancel"));
                    plugin.tell(p, "&aMarket transaction complete.");
                  } else if (action.equals("sellall")) {
                    p.closeInventory();
                    plugin.previewSellAll(p);
                  } else if (action.equals("search")) {
                    p.closeInventory();
                    plugin.tell(p, "&eUse /shop search <text>.");
                  } else if (action.equals("balance")) {
                    plugin.message(
                        p, "balance", "balance", plugin.money(plugin.service.balance(p)));
                    browse(p, menu.filter, menu.page);
                  } else if (action.equals("close")) p.closeInventory();
                  else browse(p, "", 0);
                } catch (Exception ex) {
                  p.closeInventory();
                  plugin.failure(p, ex);
                }
              }
            });
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
  public void drag(InventoryDragEvent e) {
    if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
  }
}
