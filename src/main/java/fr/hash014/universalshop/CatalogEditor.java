package fr.hash014.universalshop;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Transactional catalog drafts: nothing is published until an administrator reviews and saves. */
public final class CatalogEditor implements Listener {
  private final UniversalShop plugin;
  private final Map<UUID, Draft> drafts = new HashMap<UUID, Draft>();

  static final class Menu implements InventoryHolder {
    Inventory inventory;
    String layout, title;
    final UUID owner;
    final Map<Integer, String> actions = new HashMap<Integer, String>();
    String filter = "";
    int page;
    Menu(Player player) { owner = player.getUniqueId(); }
    public Inventory getInventory() { return inventory; }
  }

  private static final class Draft {
    final YamlConfiguration catalog;
    final String baseline;
    final long revision;
    final String id;
    final boolean created;
    final Set<String> changes = new LinkedHashSet<String>();
    Draft(YamlConfiguration catalog, long revision, String id, boolean created) {
      this.catalog = catalog;
      baseline = SafeYaml.write(catalog);
      this.revision = revision;
      this.id = id;
      this.created = created;
    }
    String path() { return "products." + id; }
    ConfigurationSection section() { return catalog.getConfigurationSection(path()); }
  }

  CatalogEditor(UniversalShop plugin) { this.plugin = plugin; }
  public void clear() { drafts.clear(); }
  public void forget(UUID player) { drafts.remove(player); }
  public boolean hasDraft(UUID player) { return drafts.containsKey(player); }
  public void cancel(Player player) {
    drafts.remove(player.getUniqueId());
    plugin.inputs.cancel(player.getUniqueId());
    if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) player.closeInventory();
  }

  private String text(Player player, String key, String fallback, String... replacements) {
    return plugin.texts.format(player, "editor." + key, fallback, replacements);
  }

  private String title(Player player, String key, String fallback, String... replacements) {
    String value = UniversalShop.color(text(player, key, fallback, replacements));
    // Legacy Bukkit rejects inventory titles longer than 32 characters including color codes.
    if (value.length() <= 32) return value;
    value = value.substring(0, 32);
    return value.charAt(value.length() - 1) == '\u00a7' ? value.substring(0, value.length() - 1) : value;
  }

  public void open(Player player) throws Exception {
    plugin.requireAction(player, "admin.editor");
    plugin.requireAdmin(player, "catalog");
    Draft draft = drafts.get(player.getUniqueId());
    if (draft != null) edit(player, draft);
    else list(player, "", 0);
  }

  private void list(Player player, String filter, int page) throws Exception {
    plugin.requireAdmin(player, "catalog");
    Menu menu = new Menu(player);
    menu.filter = filter;
    List<Product> products = new ArrayList<Product>();
    String term = filter.toLowerCase(Locale.ROOT);
    for (Product product : plugin.products.values())
      if ((product.name + " " + product.category + " " + product.id).toLowerCase(Locale.ROOT).contains(term)) products.add(product);
    int capacity = plugin.layouts.get("editor-list").capacity();
    menu.page = Math.max(0, Math.min(page, Math.max(0, (products.size() - 1) / capacity)));
    menu.layout = "editor-list";
    menu.title = title(player, "list-title", "&8Catalog editor | {page}", "page", String.valueOf(menu.page + 1));
    menu.inventory = Bukkit.createInventory(menu, 54, menu.title);
    for (int i = menu.page * capacity; i < Math.min(products.size(), (menu.page + 1) * capacity); i++) {
      Product product = products.get(i);
      ItemStack item = product.item.clone();
      ItemMeta meta = item.getItemMeta();
      meta.setDisplayName(UniversalShop.color(product.name));
      List<String> lore = meta.hasLore() ? new ArrayList<String>(meta.getLore()) : new ArrayList<String>();
      lore.add(UniversalShop.color("&8" + product.id + " / " + product.category));
      lore.add(UniversalShop.color(text(player, "list-prices", "&7Buy: {buy} / Sell: {sell}", "buy", price(player, product.buy), "sell", price(player, product.sell))));
      lore.add(UniversalShop.color(text(player, "list-edit", "&eClick to edit this product.")));
      meta.setLore(lore);
      item.setItemMeta(meta);
      menu.inventory.setItem(i % capacity, item);
      menu.actions.put(i % capacity, "edit:" + product.id);
    }
    button(player, menu, 45, Material.ARROW, "previous", "&ePrevious page", "previous");
    button(player, menu, 46, Material.PAPER, "search", "&eSearch products", "search");
    button(player, menu, 48, Material.EMERALD, "create", "&aCreate from held item", "create", text(player, "create-lore", "&7Hold the finished item before opening this menu."));
    button(player, menu, 50, Material.BOOK, "diagnose", "&eCatalog diagnostics", "diagnose");
    button(player, menu, 52, Material.CHEST, "dashboard", "&eAdministration dashboard", "dashboard");
    button(player, menu, 53, Material.ARROW, "next", "&eNext page", "next");
    menu.inventory = plugin.layouts.apply(menu.layout, menu.inventory, menu, menu.actions, menu.page, menu.title, plugin.getConfig().getString("menu-background", ""));
    player.openInventory(menu.inventory);
  }

  private String price(Player player, long cents) {
    return cents < 0 ? text(player, "disabled", "disabled") : plugin.money(cents);
  }

  private void button(Player player, Menu menu, int slot, Material material, String key, String fallback, String action, String... lore) {
    if (!plugin.actionEnabled(player, actionPolicy(action))) return;
    menu.inventory.setItem(slot, ShopMenus.icon(material, text(player, key, fallback), lore));
    menu.actions.put(slot, action);
  }

  private String actionPolicy(String action) {
    if (action.equals("create")) return "admin.add";
    if (action.equals("duplicate")) return "admin.duplicate";
    if (action.equals("delete") || action.startsWith("delete-")) return "admin.remove";
    if (action.equals("stock-now")) return "admin.stock";
    if (action.equals("diagnose")) return "admin.diagnose";
    if (action.equals("dashboard")) return "admin.panel";
    if (action.equals("field:buy") || action.equals("field:sell")) return "admin.price";
    if (action.equals("field:name")) return "admin.rename";
    if (action.equals("field:category")) return "admin.category";
    return "admin.editor";
  }

  private void field(Player player, Menu menu, Draft draft, int slot, String field, Material material, String title, String help) {
    Object value = draft.section().get(field);
    String shown = value == null ? text(player, "default", "default") : String.valueOf(value);
    button(player, menu, slot, material, "field." + field, title, "field:" + field,
        text(player, "value", "&fCurrent: {value}", "value", shown), text(player, "hint." + field, help));
  }

  private void edit(Player player, Draft draft) throws Exception {
    check(player, draft);
    Menu menu = new Menu(player);
    menu.layout = "editor-product";
    menu.title = title(player, "edit-title", "&8Edit: {id}", "id", shortId(draft.id));
    menu.inventory = Bukkit.createInventory(menu, 54, menu.title);
    ItemStack item = draft.section().getItemStack("item");
    if (item == null) {
      Material material = Product.material(draft.section().getString("material", "STONE"));
      item = new ItemStack(material == null ? Material.STONE : material, 1, (short) draft.section().getInt("data"));
    }
    menu.inventory.setItem(4, item.clone());
    field(player, menu, draft, 10, "name", Material.NAME_TAG, "&eDisplay name", "&7Color codes with & are supported.");
    field(player, menu, draft, 11, "category", Material.CHEST, "&eCategory", "&7Choose a category name.");
    field(player, menu, draft, 12, "buy", Material.EMERALD, "&aBuy price", "&7Price per item; -1 disables buying.");
    field(player, menu, draft, 13, "sell", Material.GOLD_INGOT, "&aSell price", "&7Price per item; -1 disables selling.");
    field(player, menu, draft, 14, "stock", Material.CHEST, "&eStock capacity", "&7-1 unlimited, otherwise 0 to 1000000000.");
    field(player, menu, draft, 15, "restock-hours", Product.material("CLOCK|WATCH"), "&eRestock interval", "&7Hours; 0 disables automatic restocking.");
    field(player, menu, draft, 16, "daily-buy-limit", Material.PAPER, "&eDaily buy limit", "&7Units per player per UTC day; 0 unlimited.");
    field(player, menu, draft, 19, "daily-sell-limit", Material.PAPER, "&eDaily sell limit", "&7Units per player per UTC day; 0 unlimited.");
    field(player, menu, draft, 20, "permission", Material.BOOK, "&eRequired permission", "&7Enter - to clear the permission.");
    field(player, menu, draft, 21, "worlds", Material.COMPASS, "&eAllowed worlds", "&7Comma-separated world names; - for all worlds.");
    field(player, menu, draft, 22, "dynamic.enabled", Material.REDSTONE, "&eDynamic pricing", "&7Click to toggle demand-based pricing.");
    field(player, menu, draft, 23, "dynamic.step", Material.REDSTONE, "&eDynamic step", "&7Multiplier change for each net purchased item.");
    field(player, menu, draft, 24, "dynamic.min", Material.REDSTONE, "&eMinimum multiplier", "&7Positive multiplier, at most the maximum.");
    field(player, menu, draft, 25, "dynamic.max", Material.REDSTONE, "&eMaximum multiplier", "&7Multiplier between the minimum and 1000.");
    field(player, menu, draft, 28, "dynamic.decay-hours", Product.material("CLOCK|WATCH"), "&eIdle demand half-life", "&7Demand halves after each full idle interval; 0 preserves it.");
    field(player, menu, draft, 29, "rotating", Material.NETHER_STAR, "&eEligible for deals", "&7Click to toggle rotating deal eligibility.");
    field(player, menu, draft, 30, "rotation-only", Material.NETHER_STAR, "&eOnly during deals", "&7Requires rotating deals enabled for this product.");
    if (plugin.hasAdmin(player, "commands")) field(player, menu, draft, 31, "commands", Material.BOOK, "&cConsole delivery commands", "&7Separate commands with ||; - clears commands.");
    button(player, menu, 32, Material.ANVIL, "replace", "&eReplace template with held item", "replace", text(player, "replace-lore", "&7Preserves the held item's complete metadata."));
    button(player, menu, 33, Material.GLASS, "preview", "&ePreview product", "preview");
    button(player, menu, 34, Material.PAPER, "duplicate", "&eDuplicate this draft", "duplicate");
    if (!draft.created && plugin.hasAdmin(player, "stock")) button(player, menu, 35, Material.CHEST, "stock-now", "&eSet current stock", "stock-now");
    button(player, menu, 45, Material.ARROW, "back", "&eReturn to catalog", "back");
    if (!draft.created) button(player, menu, 47, Material.REDSTONE, "delete", "&cDelete product", "delete");
    button(player, menu, 49, Material.EMERALD, "save", "&aReview and save", "review", text(player, "changes", "&7Unsaved fields: {fields}", "fields", draft.changes.toString()));
    button(player, menu, 53, Material.BOOK, "close", "&eClose (keep draft)", "close", text(player, "resume", "&7Reopen with /shop admin editor."));
    menu.inventory = plugin.layouts.apply(menu.layout, menu.inventory, menu, menu.actions, menu.page, menu.title, plugin.getConfig().getString("menu-background", ""));
    player.openInventory(menu.inventory);
  }

  private String shortId(String id) { return id.length() > 18 ? id.substring(0, 18) + "..." : id; }

  private void check(Player player, Draft draft) {
    plugin.requireAction(player, "admin.editor");
    plugin.requireAdmin(player, "catalog");
    if (drafts.get(player.getUniqueId()) != draft) throw new IllegalArgumentException("This editor draft is no longer active.");
  }

  private void current(Player player, Draft draft) throws Exception {
    check(player, draft);
    if (draft.revision != plugin.catalogEditRevision() || !SafeYaml.write(plugin.catalogCopy()).equals(draft.baseline))
      throw new IllegalArgumentException("The catalog changed while you were editing. Discard this draft and reopen the product.");
  }

  private ItemStack held(Player player) {
    ItemStack hand = player.getItemInHand();
    if (hand == null || hand.getType() == Material.AIR) throw new IllegalArgumentException("Hold the exact item to add.");
    ItemStack item = hand.clone();
    item.setAmount(1);
    return item;
  }

  private void create(final Player player) throws Exception {
    plugin.requireAction(player, "admin.add");
    final ItemStack item = held(player);
    plugin.inputs.request(player, text(player, "prompt-id", "&eEnter a unique product ID (lowercase letters, digits, _ or -)."), value -> {
      plugin.requireAdmin(player, "catalog");
      plugin.requireAction(player, "admin.add");
      ManagementPolicy.id(value);
      YamlConfiguration catalog = plugin.catalogCopy();
      if (catalog.contains("products." + value)) throw new IllegalArgumentException("This ID already exists.");
      final Draft draft = new Draft(catalog, plugin.catalogEditRevision(), value, true);
      String path = draft.path();
      catalog.set(path + ".item", item);
      catalog.set(path + ".name", item.hasItemMeta() && item.getItemMeta().hasDisplayName() ? item.getItemMeta().getDisplayName() : value);
      catalog.set(path + ".category", "General");
      catalog.set(path + ".buy", -1);
      catalog.set(path + ".sell", -1);
      draft.changes.add("new product");
      drafts.put(player.getUniqueId(), draft);
      guided(player, draft, 0);
    });
  }

  private void guided(final Player player, final Draft draft, final int index) throws Exception {
    check(player, draft);
    final String[] fields = {"buy", "sell", "name", "category"};
    if (index == fields.length) { edit(player, draft); return; }
    String field = fields[index];
    plugin.inputs.request(player, text(player, "prompt." + field,
        "&eEnter {field} (-1 disables a price; - keeps the current value).", "field", field), value -> {
      check(player, draft);
      if (!value.equals("-")) setField(draft, field, value);
      guided(player, draft, index + 1);
    });
  }

  /** Parses administrator input without silently rounding money or accepting invalid bounds. */
  static Object parseField(String field, String value) {
    if (value.length() > 4096) throw new IllegalArgumentException("The value is too long.");
    if (field.equals("buy") || field.equals("sell")) {
      if (value.equals("-1")) return "-1";
      return Money.format(Money.parse(value));
    }
    if (Arrays.asList("stock", "restock-hours", "daily-buy-limit", "daily-sell-limit", "dynamic.decay-hours").contains(field)) {
      long number = Long.parseLong(value);
      long min = field.equals("stock") ? -1 : 0;
      long max = field.equals("restock-hours") || field.equals("dynamic.decay-hours") ? 876000 : field.equals("stock") ? 1000000000L : Long.MAX_VALUE;
      if (number < min || number > max) throw new IllegalArgumentException("Invalid stock/limit value.");
      return number;
    }
    if (field.equals("dynamic.step") || field.equals("dynamic.min") || field.equals("dynamic.max")) {
      double number = Double.parseDouble(value);
      if (!Double.isFinite(number) || number < 0 || (!field.equals("dynamic.step") && number == 0)
          || (field.equals("dynamic.max") && number > 1000)) throw new IllegalArgumentException("Invalid dynamic pricing value.");
      return number;
    }
    if (field.equals("worlds")) {
      if (value.equals("-")) return Collections.emptyList();
      List<String> worlds = new ArrayList<String>();
      for (String world : value.split(",")) {
        String name = world.trim();
        if (name.isEmpty() || name.length() > 128) throw new IllegalArgumentException("Invalid world name.");
        if (!worlds.contains(name)) worlds.add(name);
      }
      return worlds;
    }
    if (field.equals("commands")) {
      if (value.equals("-")) return Collections.emptyList();
      List<String> commands = new ArrayList<String>();
      for (String command : value.split("\\|\\|", -1)) {
        String line = command.trim();
        if (line.startsWith("/")) line = line.substring(1);
        if (line.isEmpty() || line.contains("\n") || line.contains("\r")) throw new IllegalArgumentException("Invalid delivery command.");
        commands.add(line);
      }
      return commands;
    }
    if (field.equals("permission")) {
      if (value.equals("-")) return "";
      if (!value.matches("[A-Za-z0-9_.*-]{1,200}")) throw new IllegalArgumentException("Invalid permission node.");
      return value;
    }
    if (field.equals("name") || field.equals("category")) {
      if (value.trim().isEmpty() || value.length() > 128 || value.contains("\n") || value.contains("\r")) throw new IllegalArgumentException("Invalid name or category.");
      return value;
    }
    throw new IllegalArgumentException("Unknown editable product field.");
  }

  private void setField(Draft draft, String field, String value) {
    draft.catalog.set(draft.path() + "." + field, parseField(field, value));
    draft.changes.add(field);
  }

  private void prompt(final Player player, final Draft draft, final String field) throws Exception {
    check(player, draft);
    plugin.requireAction(player, actionPolicy("field:" + field));
    if (Arrays.asList("dynamic.enabled", "rotating", "rotation-only").contains(field)) {
      boolean enabled = !draft.section().getBoolean(field, false);
      draft.catalog.set(draft.path() + "." + field, enabled);
      if (field.equals("rotation-only") && enabled) draft.catalog.set(draft.path() + ".rotating", true);
      if (field.equals("rotating") && !enabled) draft.catalog.set(draft.path() + ".rotation-only", false);
      draft.changes.add(field);
      edit(player, draft);
      return;
    }
    if (field.equals("commands")) plugin.requireAdmin(player, "commands");
    int inputLimit = field.equals("commands") ? 4096 : field.equals("worlds") ? 1024 : field.equals("permission") ? 200 : 128;
    plugin.inputs.request(player, text(player, "prompt." + field, "&eEnter {field}. Use - to clear optional fields.", "field", field), inputLimit, value -> {
      check(player, draft);
      plugin.requireAction(player, actionPolicy("field:" + field));
      final Object parsed = parseField(field, value);
      if (field.equals("commands")) {
        plugin.requireAdmin(player, "commands");
        plugin.tell(player, text(player, "commands-warning", "&cConsole commands execute with full server privileges: {commands}", "commands", parsed.toString()));
        plugin.inputs.request(player, text(player, "commands-confirm", "&cType CONFIRM COMMANDS to stage this command change."), confirm -> {
          check(player, draft);
          plugin.requireAdmin(player, "commands");
          if (!confirm.equals("CONFIRM COMMANDS")) { edit(player, draft); return; }
          draft.catalog.set(draft.path() + ".commands", parsed);
          if (!((List<?>) parsed).isEmpty()) {
            draft.catalog.set(draft.path() + ".sell", -1);
            draft.changes.add("sell");
          }
          draft.changes.add(field);
          edit(player, draft);
        });
      } else {
        draft.catalog.set(draft.path() + "." + field, parsed);
        draft.changes.add(field);
        edit(player, draft);
      }
    });
  }

  private void confirm(Player player, String titleKey, String title, String action, int step, String... details) {
    Menu menu = new Menu(player);
    menu.layout = "editor-confirm";
    menu.title = title(player, titleKey, title);
    menu.inventory = Bukkit.createInventory(menu, 27, menu.title);
    button(player, menu, step == 1 ? 11 : 13, step == 1 ? Material.REDSTONE : Material.EMERALD,
        step == 1 ? "confirm-first" : "confirm-final", step == 1 ? "&cConfirm this change" : "&aConfirm and publish", action, details);
    button(player, menu, 15, Material.ARROW, "cancel", "&eCancel", "cancel");
    menu.inventory = plugin.layouts.apply(menu.layout, menu.inventory, menu, menu.actions, menu.page, menu.title, plugin.getConfig().getString("menu-background", ""));
    player.openInventory(menu.inventory);
  }

  private void preview(Player player, Draft draft) throws Exception {
    Product product = new Product(draft.id, draft.section());
    Menu menu = new Menu(player);
    menu.layout = "editor-preview";
    menu.title = title(player, "preview-title", "&8Product preview");
    menu.inventory = Bukkit.createInventory(menu, 27, menu.title);
    ItemStack item = product.item.clone();
    ItemMeta meta = item.getItemMeta();
    meta.setDisplayName(UniversalShop.color(product.name));
    List<String> lore = meta.hasLore() ? new ArrayList<String>(meta.getLore()) : new ArrayList<String>();
    lore.add(UniversalShop.color("&8" + product.category + " / " + product.id));
    lore.add(UniversalShop.color(text(player, "list-prices", "&7Buy: {buy} / Sell: {sell}", "buy", price(player, product.buy), "sell", price(player, product.sell))));
    lore.add(UniversalShop.color(text(player, "preview-note", "&7Preview only; no purchase is performed.")));
    meta.setLore(lore);
    item.setItemMeta(meta);
    menu.inventory.setItem(13, item);
    button(player, menu, 22, Material.ARROW, "return", "&eReturn to editor", "cancel");
    menu.inventory = plugin.layouts.apply(menu.layout, menu.inventory, menu, menu.actions, menu.page, menu.title, plugin.getConfig().getString("menu-background", ""));
    player.openInventory(menu.inventory);
  }

  private void publish(Player player, Draft draft) throws Exception {
    current(player, draft);
    boolean duplicate = false;
    for (String change : draft.changes) if (change.startsWith("duplicate of ")) duplicate = true;
    if (draft.created) plugin.requireAction(player, duplicate ? "admin.duplicate" : "admin.add");
    else {
      if (draft.changes.contains("buy") || draft.changes.contains("sell")) plugin.requireAction(player, "admin.price");
      if (draft.changes.contains("name")) plugin.requireAction(player, "admin.rename");
      if (draft.changes.contains("category")) plugin.requireAction(player, "admin.category");
    }
    if (draft.changes.contains("commands")) plugin.requireAdmin(player, "commands");
    plugin.saveCatalog(draft.catalog);
    drafts.remove(player.getUniqueId());
    plugin.tell(player, text(player, "saved", "&aCatalog saved. Previous file: shops.yml.bak"));
    list(player, "", 0);
  }

  @EventHandler(ignoreCancelled = true)
  public void click(InventoryClickEvent event) {
    Inventory top = event.getView().getTopInventory();
    if (!(top.getHolder() instanceof Menu)) return;
    event.setCancelled(true);
    if (!(event.getWhoClicked() instanceof Player)) return;
    Player player = (Player) event.getWhoClicked();
    Menu menu = (Menu) top.getHolder();
    if (!menu.owner.equals(player.getUniqueId()) || event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) return;
    ClickType click = event.getClick();
    if (click != ClickType.LEFT && click != ClickType.RIGHT && click != ClickType.SHIFT_LEFT
        && click != ClickType.SHIFT_RIGHT && click != ClickType.MIDDLE) return;
    final String action = menu.actions.remove(event.getRawSlot());
    if (action == null) return;
    final Player target = player;
    final Menu original = menu;
    Bukkit.getScheduler().runTask(plugin, new Runnable() {
      public void run() {
        if (!target.isOnline() || target.getOpenInventory().getTopInventory().getHolder() != original) return;
        handle(target, original, action);
      }
    });
  }

  private void handle(final Player player, final Menu menu, final String action) {
    try {
      plugin.requireAdmin(player, "catalog");
      plugin.requireAction(player, "admin.editor");
      plugin.requireAction(player, actionPolicy(action));
      final Draft draft = drafts.get(player.getUniqueId());
      if (action.startsWith("edit:")) {
        String id = action.substring(5);
        plugin.product(id);
        Draft next = new Draft(plugin.catalogCopy(), plugin.catalogEditRevision(), id, false);
        drafts.put(player.getUniqueId(), next);
        edit(player, next);
      } else if (action.equals("create")) create(player);
      else if (action.equals("previous") || action.equals("next")) list(player, menu.filter, menu.page + (action.equals("next") ? 1 : -1));
      else if (action.equals("search")) plugin.inputs.request(player, text(player, "prompt-search", "&eEnter a product name, ID or category; - clears the search."), value -> list(player, value.equals("-") ? "" : value, 0));
      else if (action.equals("diagnose")) plugin.onCommand(player, plugin.getCommand("shop"), "shop", new String[] {"admin", "diagnose"});
      else if (action.equals("dashboard")) { player.closeInventory(); plugin.menus.adminPanel(player); }
      else if (action.equals("close")) player.closeInventory();
      else if (draft != null) {
        check(player, draft);
        if (action.startsWith("field:")) prompt(player, draft, action.substring(6));
        else if (action.equals("preview")) preview(player, draft);
        else if (action.equals("cancel")) edit(player, draft);
        else if (action.equals("replace")) {
          final ItemStack replacement = held(player);
          plugin.inputs.request(player, text(player, "replace-confirm", "&eType CONFIRM ITEM to replace the entire item template."), value -> {
            check(player, draft);
            if (value.equals("CONFIRM ITEM")) {
              draft.catalog.set(draft.path() + ".item", replacement);
              draft.catalog.set(draft.path() + ".material", null);
              draft.catalog.set(draft.path() + ".data", null);
              draft.changes.add("item");
            }
            edit(player, draft);
          });
        } else if (action.equals("duplicate")) {
          plugin.inputs.request(player, text(player, "prompt-duplicate", "&eEnter a new unique ID for the duplicate."), value -> {
            current(player, draft);
            ManagementPolicy.id(value);
            if (draft.catalog.contains("products." + value)) throw new IllegalArgumentException("This ID already exists.");
            YamlConfiguration copy = plugin.catalogCopy();
            Draft duplicate = new Draft(copy, plugin.catalogEditRevision(), value, true);
            ConfigurationSection destination = copy.createSection(duplicate.path());
            for (String key : draft.section().getKeys(true))
              if (!draft.section().isConfigurationSection(key)) destination.set(key, draft.section().get(key));
            duplicate.changes.add("duplicate of " + draft.id);
            if (!draft.section().getStringList("commands").isEmpty()) duplicate.changes.add("commands");
            drafts.put(player.getUniqueId(), duplicate);
            edit(player, duplicate);
          });
        } else if (action.equals("stock-now")) {
          plugin.requireAdmin(player, "stock");
          plugin.inputs.request(player, text(player, "prompt-stock-now", "&eEnter current stock (within the saved product capacity)."), value -> {
            check(player, draft);
            plugin.onCommand(player, plugin.getCommand("shop"), "shop", new String[] {"admin", "stock", draft.id, value});
            edit(player, draft);
          });
        } else if (action.equals("back")) {
          if (draft.created || !draft.changes.isEmpty()) confirm(player, "discard-title", "&8Discard unsaved changes?", "discard", 1);
          else { drafts.remove(player.getUniqueId()); list(player, "", 0); }
        } else if (action.equals("discard")) { drafts.remove(player.getUniqueId()); list(player, "", 0); }
        else if (action.equals("review")) {
          current(player, draft);
          new Product(draft.id, draft.section());
          confirm(player, "review-title", "&8Review catalog changes", "publish", 2,
              text(player, "review-product", "&fProduct: {id}", "id", draft.id), text(player, "changes", "&7Unsaved fields: {fields}", "fields", draft.changes.toString()),
              text(player, "save-backup", "&7A previous catalog backup will be kept."));
        } else if (action.equals("publish")) publish(player, draft);
        else if (action.equals("delete")) confirm(player, "delete-title", "&8Delete product?", "delete-final", 1,
            text(player, "delete-warning", "&cProduct {id} will be removed from the catalog.", "id", draft.id));
        else if (action.equals("delete-final")) confirm(player, "delete-final-title", "&8Confirm permanent deletion", "delete-publish", 2,
            text(player, "delete-history", "&7Historical transactions remain in the ledger."));
        else if (action.equals("delete-publish")) {
          current(player, draft);
          YamlConfiguration candidate = plugin.catalogCopy();
          candidate.set(draft.path(), null);
          plugin.saveCatalog(candidate);
          drafts.remove(player.getUniqueId());
          plugin.tell(player, text(player, "deleted", "&aProduct deleted; previous catalog saved to shops.yml.bak."));
          list(player, "", 0);
        }
      }
    } catch (Exception exception) { plugin.failure(player, exception); }
  }

  @EventHandler(ignoreCancelled = true)
  public void drag(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
  }
}
