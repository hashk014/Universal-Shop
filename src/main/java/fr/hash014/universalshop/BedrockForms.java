package fr.hash014.universalshop;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/** Optional Cumulus integration. No bridge classes are linked on Java-only/legacy servers. */
final class BedrockForms {
  interface Action {
    void run() throws Exception;
  }

  private final UniversalShop plugin;
  final FormSessions sessions = new FormSessions();
  private long lastWarning;

  BedrockForms(UniversalShop plugin) {
    this.plugin = plugin;
  }

  private Object[] bridge(Player player) {
    if (!plugin.getConfig().getBoolean("bedrock.enabled", true)) return null;
    String[][] candidates = {
      {"org.geysermc.floodgate.api.FloodgateApi", "getInstance", "isFloodgatePlayer"},
      {"org.geysermc.geyser.api.GeyserApi", "api", "isBedrockPlayer"}
    };
    for (String[] candidate : candidates)
      try {
        Class<?> api = Class.forName(candidate[0]);
        Object instance = api.getMethod(candidate[1]).invoke(null);
        if (instance != null
            && Boolean.TRUE.equals(
                api.getMethod(candidate[2], UUID.class).invoke(instance, player.getUniqueId())))
          return new Object[] {api, instance};
      } catch (ClassNotFoundException absent) {
      } catch (ReflectiveOperationException | LinkageError error) {
        warn(error);
      }
    return null;
  }

  boolean available(Player player) {
    return bridge(player) != null;
  }

  private void warn(Throwable error) {
    if (System.currentTimeMillis() - lastWarning > 60000) {
      lastWarning = System.currentTimeMillis();
      plugin.getLogger().warning("Bedrock forms unavailable; using inventory menus: " + error);
    }
  }

  private boolean show(
      Player player, String title, String content, List<String> labels, List<Action> actions)
      throws Exception {
    Object[] connection = bridge(player);
    if (connection == null) return false;
    Class<?> formClass = Class.forName("org.geysermc.cumulus.form.SimpleForm");
    Class<?> builderClass = Class.forName("org.geysermc.cumulus.form.SimpleForm$Builder");
    Class<?> baseBuilder = Class.forName("org.geysermc.cumulus.form.util.FormBuilder");
    Class<?> baseForm = Class.forName("org.geysermc.cumulus.form.Form");
    Class<?> responseClass = Class.forName("org.geysermc.cumulus.response.SimpleFormResponse");
    Object builder = formClass.getMethod("builder").invoke(null);
    baseBuilder.getMethod("title", String.class).invoke(builder, plain(title));
    builderClass.getMethod("content", String.class).invoke(builder, plain(content));
    for (String label : labels)
      builderClass.getMethod("button", String.class).invoke(builder, plain(label));
    UUID id = player.getUniqueId(), token = sessions.open(id);
    long expires = System.currentTimeMillis() + 120000;
    Consumer<Object> result =
        response -> {
          try {
            final int index = (Integer) responseClass.getMethod("clickedButtonId").invoke(response);
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler()
                .runTask(
                    plugin,
                    () -> {
                      if (!sessions.consume(id, token, expires, System.currentTimeMillis())
                          || !player.isOnline()) return;
                      if (index < 0 || index >= actions.size()) return;
                      try {
                        actions.get(index).run();
                      } catch (Exception error) {
                        plugin.failure(player, error);
                      }
                    });
          } catch (ReflectiveOperationException error) {
            warn(error);
          }
        };
    baseBuilder.getMethod("validResultHandler", Consumer.class).invoke(builder, result);
    Runnable closed =
        () -> {
          if (plugin.isEnabled())
            Bukkit.getScheduler().runTask(plugin, () -> sessions.consume(id, token, 0, 1));
        };
    baseBuilder.getMethod("closedOrInvalidResultHandler", Runnable.class).invoke(builder, closed);
    Object form = baseBuilder.getMethod("build").invoke(builder);
    player.closeInventory();
    boolean sent =
        Boolean.TRUE.equals(
            ((Class<?>) connection[0])
                .getMethod("sendForm", UUID.class, baseForm)
                .invoke(connection[1], id, form));
    if (!sent) sessions.close(id);
    return sent;
  }

  private static String plain(String text) {
    return ChatColor.stripColor(UniversalShop.color(text));
  }

  boolean input(Player player, String prompt, int maxLength, PlayerInputs.InputHandler handler) {
    try {
      Object[] connection = bridge(player);
      if (connection == null) return false;
      Class<?> formClass = Class.forName("org.geysermc.cumulus.form.CustomForm");
      Class<?> builderClass = Class.forName("org.geysermc.cumulus.form.CustomForm$Builder");
      Class<?> baseBuilder = Class.forName("org.geysermc.cumulus.form.util.FormBuilder");
      Class<?> baseForm = Class.forName("org.geysermc.cumulus.form.Form");
      Class<?> responseClass = Class.forName("org.geysermc.cumulus.response.CustomFormResponse");
      Object builder = formClass.getMethod("builder").invoke(null);
      baseBuilder.getMethod("title", String.class).invoke(builder, "UniversalShop");
      builderClass.getMethod("input", String.class, String.class, String.class)
          .invoke(builder, plain(prompt), "", "");
      UUID id = player.getUniqueId(), token = sessions.open(id);
      long expires = System.currentTimeMillis() + 120000;
      Consumer<Object> response = value -> {
        try {
          final String text = (String) responseClass.getMethod("asInput", int.class).invoke(value, 0);
          if (!plugin.isEnabled()) return;
          Bukkit.getScheduler().runTask(plugin, () -> {
            if (!sessions.consume(id, token, expires, System.currentTimeMillis()) || !player.isOnline()) return;
            try {
              if (text == null || text.trim().equalsIgnoreCase("cancel")) return;
              handler.accept(PlayerInputs.validate(text, maxLength));
            } catch (Exception error) {
              plugin.failure(player, error);
            }
          });
        } catch (ReflectiveOperationException error) {
          warn(error);
        }
      };
      baseBuilder.getMethod("validResultHandler", Consumer.class).invoke(builder, response);
      Runnable closed = () -> {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> sessions.consume(id, token, 0, 1));
      };
      baseBuilder.getMethod("closedOrInvalidResultHandler", Runnable.class).invoke(builder, closed);
      Object form = baseBuilder.getMethod("build").invoke(builder);
      player.closeInventory();
      boolean sent = Boolean.TRUE.equals(((Class<?>) connection[0])
          .getMethod("sendForm", UUID.class, baseForm).invoke(connection[1], id, form));
      if (!sent) sessions.close(id);
      return sent;
    } catch (ReflectiveOperationException | LinkageError error) {
      warn(error);
      sessions.close(player.getUniqueId());
      return false;
    }
  }

  private boolean send(
      Player p, String title, String content, List<String> labels, List<Action> actions) {
    try {
      return show(p, title, content, labels, actions);
    } catch (ReflectiveOperationException | LinkageError error) {
      warn(error);
      sessions.close(p.getUniqueId());
      return false;
    } catch (Exception error) {
      plugin.failure(p, error);
      return true;
    }
  }

  private void add(
      Player p,
      List<String> labels,
      List<Action> actions,
      String policy,
      String text,
      Action action) {
    if (plugin.actionEnabled(p, policy)) {
      labels.add(text);
      actions.add(action);
    }
  }

  private String text(Player p, String key, String fallback, String... replacements) {
    return plugin.texts.format(p, key, fallback, replacements);
  }

  boolean browse(Player p, String filter, int requested) throws Exception {
    if (!available(p)) return false;
    List<Product> products = plugin.management.catalog(p, filter);
    int page = Math.max(0, Math.min(requested, Math.max(0, (products.size() - 1) / 12)));
    List<String> labels = new ArrayList<String>();
    List<Action> actions = new ArrayList<Action>();
    for (int i = page * 12; i < Math.min(products.size(), page * 12 + 12); i++) {
      Product item = products.get(i);
      labels.add(item.name + "\n" + item.category);
      actions.add(() -> select(p, item));
    }
    if (page > 0) {
      labels.add(text(p, "menu.previous", "Previous page"));
      actions.add(() -> plugin.menus.browse(p, filter, page - 1));
    }
    if ((page + 1) * 12 < products.size()) {
      labels.add(text(p, "menu.next", "Next page"));
      actions.add(() -> plugin.menus.browse(p, filter, page + 1));
    }
    add(p, labels, actions, "category", text(p, "menu.categories", "Categories"), () -> plugin.menus.categories(p, 0));
    add(p, labels, actions, "deals", text(p, "menu.deals", "Featured deals"), () -> plugin.menus.browse(p, "deals", 0));
    add(p, labels, actions, "market", text(p, "menu.market", "Player market"), () -> plugin.menus.market(p, "", "newest", false, 0));
    add(p, labels, actions, "sellall", text(p, "menu.sellall", "Sell inventory"), () -> plugin.previewSellAll(p));
    add(
        p,
        labels,
        actions,
        "search",
        text(p, "menu.search", "Search"),
        () -> plugin.menus.search(p));
    if (plugin.management.enabled("personal")) {
      add(
          p,
          labels,
          actions,
          "favorites",
          text(p, "menu.favorites", "Favorites"),
          () -> plugin.menus.browse(p, "@favorites", 0));
      add(
          p,
          labels,
          actions,
          "recent",
          text(p, "menu.recent", "Recently traded"),
          () -> plugin.menus.browse(p, "@recent", 0));
      add(p, labels, actions, "sort", text(p, "menu.sort", "Sort products"), () -> plugin.menus.sorting(p));
    }
    if (plugin.hasAdmin(p, "catalog"))
      add(p, labels, actions, "admin.editor", text(p, "menu.admin.editor", "Catalog editor"), () -> plugin.editor.open(p));
    labels.add(text(p, "menu.close", "Close"));
    actions.add(() -> {});
    String balance =
        plugin.actionEnabled(p, "balance")
            ? text(p, "menu.balance", "Balance: {balance}", "balance", plugin.money(plugin.service.balance(p))) + "\n"
            : "";
    return send(
        p,
        text(p, "menu.browse.title", "UniversalShop | {page}", "page", String.valueOf(page + 1)),
        balance + text(p, "bedrock.browse-help", "Choose a product. Buy and Sell are separate buttons."),
        labels,
        actions);
  }

  private void select(Player p, Product item) throws Exception {
    if (plugin.products.get(item.id) != item || !plugin.service.visible(p, item))
      throw new IllegalArgumentException("The catalog changed. Open the shop again.");
    List<String> labels = new ArrayList<String>();
    List<Action> actions = new ArrayList<Action>();
    if (item.buy >= 0)
      add(
          p,
          labels,
          actions,
          "buy",
          text(p, "bedrock.product-buy", "Buy - {price}", "price", plugin.money(plugin.service.quote(p, item, 1, true))),
          () -> plugin.menus.confirm(p, item, 1, true));
    if (item.sell >= 0)
      add(
          p,
          labels,
          actions,
          "sell",
          text(p, "bedrock.product-sell", "Sell - {price}", "price", plugin.money(plugin.service.quote(p, item, 1, false))),
          () -> plugin.menus.confirm(p, item, 1, false));
    if (plugin.management.enabled("personal"))
      add(
          p,
          labels,
          actions,
          "favorite",
          text(p, "bedrock.favorite", "Toggle favorite"),
          () -> {
            plugin.management.personal(p, new String[] {"favorite", item.id});
            select(p, item);
          });
    labels.add(text(p, "menu.back", "Back"));
    actions.add(() -> plugin.menus.back(p));
    send(
        p,
        item.name,
        text(p, "menu.product.stock", "Stock: {stock}", "stock", plugin.menus.stockText(p, item))
            + "\n" + plugin.menus.limitText(p, item, true) + "\n" + plugin.menus.limitText(p, item, false),
        labels,
        actions);
  }

  boolean adminPanel(Player p) {
    if (!available(p)) return false;
    List<String> labels = new ArrayList<String>();
    List<Action> actions = new ArrayList<Action>();
    for (String module : ManagementPolicy.MODULES) {
      boolean enabled = plugin.management.enabled(module);
      if (plugin.hasAdmin(p, "manage")) add(
          p,
          labels,
          actions,
          "admin.manage",
          module + ": " + (enabled ? "ON" : "OFF"),
          () -> {
            plugin.onCommand(
                p,
                plugin.getCommand("shop"),
                "shop",
                new String[] {"admin", "manage", enabled ? "disable" : "enable", module});
            plugin.menus.adminPanel(p);
          });
    }
    for (String action : Arrays.asList("open", "close", "stock", "stats", "report")) {
      String permission = action.equals("stock") ? "stock" : action.equals("stats") || action.equals("report") ? "history" : "manage";
      if (!plugin.hasAdmin(p, permission)) continue;
      add(
          p,
          labels,
          actions,
          "admin.manage",
          text(p, "menu.admin." + action, "Shop: " + action),
          () -> {
            plugin.onCommand(
                p, plugin.getCommand("shop"), "shop", new String[] {"admin", "manage", action});
            plugin.menus.adminPanel(p);
          });
    }
    if (plugin.hasAdmin(p, "catalog"))
      add(p, labels, actions, "admin.editor", text(p, "menu.admin.editor", "Catalog editor"), () -> plugin.editor.open(p));
    return send(
        p,
        text(p, "menu.admin.title", "UniversalShop | Management"),
        text(p, "bedrock.admin-help", "Enable only the modules you need. Stock, statistics and reports appear in chat."),
        labels,
        actions);
  }

  boolean confirm(Player p, Product item, int count, boolean buying) throws Exception {
    if (!available(p)) return false;
    long quote = plugin.service.quote(p, item, count, buying),
        expiry = System.currentTimeMillis() + 30000;
    List<String> labels = new ArrayList<String>();
    List<Action> actions = new ArrayList<Action>();
    add(
        p,
        labels,
        actions,
        buying ? "buy" : "sell",
        text(p, buying ? "bedrock.confirm-buy" : "bedrock.confirm-sell", buying ? "Confirm purchase" : "Confirm sale"),
        () -> {
          if (System.currentTimeMillis() > expiry || plugin.products.get(item.id) != item)
            throw new IllegalArgumentException("This quote expired. Open a fresh confirmation.");
          plugin.service.trade(p, item, count, buying, quote);
          plugin.menus.back(p);
        });
    if (item.commands.isEmpty())
      for (int size : new int[] {1, 16, 64, 256})
        if (size <= plugin.getConfig().getInt("max-transaction-items", 2304)) {
          labels.add(text(p, "menu.quantity", "{quantity} items", "quantity", String.valueOf(size)));
          actions.add(() -> plugin.menus.confirm(p, item, size, buying));
        }
    if (item.commands.isEmpty()) {
      labels.add(text(p, "menu.custom-quantity", "Custom quantity"));
      actions.add(() -> plugin.menus.customQuantity(p, item, buying));
      int possible = plugin.service.maxQuantity(p, item, buying);
      labels.add(text(p, "menu.max-quantity", "Maximum: {quantity}", "quantity", String.valueOf(possible)));
      actions.add(() -> {
        int max = plugin.service.maxQuantity(p, item, buying);
        if (max < 1) throw new IllegalArgumentException("No quantity can be traded with your current money, items, stock and quotas.");
        plugin.menus.confirm(p, item, max, buying);
      });
    }
    if ((buying ? item.sell : item.buy) >= 0)
      add(
          p,
          labels,
          actions,
          buying ? "sell" : "buy",
          text(p, buying ? "menu.switch-sell" : "menu.switch-buy", buying ? "Switch to Sell" : "Switch to Buy"),
          () -> plugin.menus.confirm(p, item, count, !buying));
    labels.add(text(p, "menu.cancel", "Cancel"));
    actions.add(() -> plugin.menus.back(p));
    return send(
        p,
        text(p, "menu.confirm-title", "UniversalShop | Confirm"),
        text(p, buying ? "bedrock.order-buy" : "bedrock.order-sell", buying ? "Buy {quantity} x {product}" : "Sell {quantity} x {product}",
            "quantity", String.valueOf(count), "product", item.name)
            + "\n" + text(p, "menu.total", "Total: {price}", "price", plugin.money(quote))
            + "\n" + plugin.menus.limitText(p, item, buying)
            + "\n" + text(p, "menu.quote-expiry", "Quote expires in 30 seconds."),
        labels,
        actions);
  }

  boolean categories(Player p, int requested) throws Exception {
    if (!available(p)) return false;
    Set<String> unique = new TreeSet<String>();
    for (Product item : plugin.products.values())
      if (plugin.service.visible(p, item)) unique.add(item.category);
    List<String> names = new ArrayList<String>(unique), labels = new ArrayList<String>();
    List<Action> actions = new ArrayList<Action>();
    int page = Math.max(0, Math.min(requested, Math.max(0, (names.size() - 1) / 12)));
    for (int i = page * 12; i < Math.min(names.size(), page * 12 + 12); i++) {
      String name = names.get(i);
      labels.add(name);
      actions.add(() -> plugin.menus.browse(p, name, 0));
    }
    if (page > 0) {
      labels.add(text(p, "menu.previous", "Previous page"));
      actions.add(() -> plugin.menus.categories(p, page - 1));
    }
    if ((page + 1) * 12 < names.size()) {
      labels.add(text(p, "menu.next", "Next page"));
      actions.add(() -> plugin.menus.categories(p, page + 1));
    }
    labels.add(text(p, "menu.all", "All products"));
    actions.add(() -> plugin.menus.browse(p, "", 0));
    return send(p, text(p, "menu.categories-title", "UniversalShop | Categories"), text(p, "bedrock.categories-help", "Choose a category."), labels, actions);
  }

  boolean sellAll(Player p, Map<String, Integer> basket, long quote) {
    if (!available(p)) return false;
    long expiry = System.currentTimeMillis() + 30000, revision = plugin.catalogRevision();
    return send(
        p,
        text(p, "bedrock.sellall-title", "UniversalShop | Sell inventory"),
        basket + "\n" + text(p, "menu.total", "Total: {price}", "price", plugin.money(quote)),
        Arrays.asList(text(p, "bedrock.confirm-sell", "Confirm sale"), text(p, "menu.cancel", "Cancel")),
        Arrays.<Action>asList(
            () -> {
              if (System.currentTimeMillis() > expiry || revision != plugin.catalogRevision())
                throw new IllegalArgumentException("This preview expired. Preview again.");
              plugin.service.sellAll(p, basket, quote);
              plugin.menus.back(p);
            },
            () -> plugin.menus.back(p)));
  }

  boolean sorting(Player p) {
    if (!available(p)) return false;
    List<String> labels = Arrays.asList(text(p, "menu.sort-catalog", "Catalog order"),
        text(p, "menu.sort-name", "Name"), text(p, "menu.sort-price", "Buy price"), text(p, "menu.back", "Back"));
    return send(p, text(p, "menu.sort-title", "UniversalShop | Sort"), "", labels,
        Arrays.<Action>asList(() -> plugin.menus.setSort(p, "catalog"), () -> plugin.menus.setSort(p, "name"),
            () -> plugin.menus.setSort(p, "price"), () -> plugin.menus.back(p)));
  }

  boolean market(Player p, int requested) throws Exception {
    return market(p, "", "newest", false, requested);
  }

  boolean market(Player p, String filter, String sort, boolean mine, int requested) throws Exception {
    if (!available(p)) return false;
    org.bukkit.configuration.ConfigurationSection section =
        plugin.store.state().getConfigurationSection("market");
    List<String> ids = plugin.service.marketEntries(p, filter, sort, mine);
    int page = Math.max(0, Math.min(requested, Math.max(0, (ids.size() - 1) / 12)));
    List<String> labels = new ArrayList<String>();
    List<Action> actions = new ArrayList<Action>();
    for (int i = page * 12; i < Math.min(ids.size(), page * 12 + 12); i++) {
      String id = ids.get(i);
      org.bukkit.inventory.ItemStack item = section.getItemStack(id + ".item");
      String name = item.hasItemMeta() && item.getItemMeta().hasDisplayName() ? item.getItemMeta().getDisplayName() : item.getType().name();
      labels.add(
          name
              + " x"
              + section.getInt(id + ".quantity")
              + "\n"
              + plugin.money(section.getLong(id + ".price"))
              + " | " + section.getString(id + ".seller")
              + (section.getLong(id + ".expires") <= System.currentTimeMillis() ? " | " + text(p, "bedrock.expired", "Expired") : ""));
      actions.add(() -> listing(p, id));
    }
    if (page > 0) {
      labels.add(text(p, "menu.previous", "Previous page"));
      actions.add(() -> plugin.menus.market(p, page - 1));
    }
    if ((page + 1) * 12 < ids.size()) {
      labels.add(text(p, "menu.next", "Next page"));
      actions.add(() -> plugin.menus.market(p, page + 1));
    }
    add(p, labels, actions, "market", text(p, "menu.market-mine", "My listings"), () -> plugin.menus.market(p, "", "newest", true, 0));
    add(p, labels, actions, "market", text(p, "menu.market-expired-button", "Expired items"), () -> plugin.menus.market(p, "@expired", "newest", true, 0));
    add(p, labels, actions, "market", text(p, "menu.search", "Search"), () -> plugin.menus.marketSearch(p));
    add(p, labels, actions, "market", text(p, "menu.market-sort", "Sort: {sort}", "sort", plugin.menus.sortName(p, sort)), () -> plugin.menus.marketSort(p));
    add(p, labels, actions, "market", text(p, "menu.market-all", "All active listings"), () -> plugin.menus.market(p, "", "newest", false, 0));
    add(p, labels, actions, "market.list", text(p, "menu.market-list", "List held item"), () -> plugin.menus.createListing(p));
    add(p, labels, actions, "market.claim", text(p, "menu.market-claim", "Recover items and earnings"), () -> plugin.menus.claim(p));
    add(p, labels, actions, "market.notices", text(p, "menu.market-notices", "Sale notifications: {count}", "count", String.valueOf(plugin.service.notificationCount(p))), () -> plugin.menus.notifications(p));
    labels.add(text(p, "menu.server-shop", "Server shop"));
    actions.add(() -> plugin.menus.back(p));
    return send(
        p,
        text(p, "menu.market-title", "UniversalShop | Player Market"),
        text(p, "bedrock.market-help", "Review listings or list your held item. Expired items remain safely stored.")
            + "\n" + text(p, "menu.market-credit", "Unclaimed earnings: {price}", "price", plugin.money(plugin.service.pendingMarketCredit(p))),
        labels,
        actions);
  }

  private void listing(Player p, String id) throws Exception {
    String path = "market." + id;
    org.bukkit.configuration.file.YamlConfiguration state = plugin.store.state();
    if (!state.contains(path))
      throw new IllegalArgumentException("This listing is no longer available.");
    boolean own = p.getUniqueId().toString().equals(state.getString(path + ".owner"));
    List<String> labels = new ArrayList<String>();
    List<Action> actions = new ArrayList<Action>();
    if (own || state.getLong(path + ".expires") > System.currentTimeMillis()) add(
        p,
        labels,
        actions,
        own ? "market.cancel" : "market.buy",
        text(p, own ? "menu.market-reclaim" : "bedrock.confirm-buy", own ? "Reclaim items" : "Confirm purchase"),
        () -> {
          plugin.service.marketTake(p, id, own);
          plugin.tell(p, text(p, "menu.market-complete", "&aMarket transaction complete."));
          plugin.menus.marketBack(p);
        });
    labels.add(text(p, "menu.cancel", "Cancel"));
    actions.add(() -> plugin.menus.marketBack(p));
    org.bukkit.inventory.ItemStack sample = state.getItemStack(path + ".item");
    String name = sample.hasItemMeta() && sample.getItemMeta().hasDisplayName() ? sample.getItemMeta().getDisplayName() : sample.getType().name();
    List<String> detail = sample.hasItemMeta() && sample.getItemMeta().hasLore() ? sample.getItemMeta().getLore() : Collections.<String>emptyList();
    send(
        p,
        text(p, "menu.listing-title", "UniversalShop | Market Confirm"),
        name + " #" + id + (detail.isEmpty() ? "" : "\n" + String.join("\n", detail))
            + "\n" + text(p, "menu.market-seller", "Seller: {seller}", "seller", state.getString(path + ".seller"))
            + "\n" + text(p, "menu.quantity", "{quantity} items", "quantity", String.valueOf(state.getInt(path + ".quantity")))
            + "\n" + text(p, "menu.total", "Total: {price}", "price", plugin.money(state.getLong(path + ".price")))
            + "\n" + text(p, "menu.market-expiry", "Expires: {time}", "time", java.time.Instant.ofEpochMilli(state.getLong(path + ".expires")).toString()),
        labels,
        actions);
  }

  boolean listConfirm(Player p, org.bukkit.inventory.ItemStack sample, int count, long price) {
    if (!available(p)) return false;
    long expires = System.currentTimeMillis() + 30000;
    String name = sample.hasItemMeta() && sample.getItemMeta().hasDisplayName() ? sample.getItemMeta().getDisplayName() : sample.getType().name();
    return send(p, text(p, "menu.list-title", "UniversalShop | Create Listing"),
        name + "\n" + text(p, "menu.quantity", "{quantity} items", "quantity", String.valueOf(count))
            + "\n" + text(p, "menu.total", "Total: {price}", "price", plugin.money(price)),
        Arrays.asList(text(p, "menu.market-publish", "Publish listing"), text(p, "menu.cancel", "Cancel")),
        Arrays.<Action>asList(() -> {
          if (System.currentTimeMillis() > expires) throw new IllegalArgumentException("This preview expired. Create a fresh listing.");
          plugin.menus.publishListing(p, sample, count, price);
        }, () -> plugin.menus.marketBack(p)));
  }
}
