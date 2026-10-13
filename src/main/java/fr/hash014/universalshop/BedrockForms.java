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
                        plugin.service.allowed(player);
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

  boolean browse(Player p, String filter, int requested) throws Exception {
    if (!available(p)) return false;
    List<Product> products = new ArrayList<Product>();
    Set<String> deals = plugin.service.deals();
    String term = filter.toLowerCase(Locale.ROOT);
    for (Product item : plugin.products.values())
      if (plugin.service.visible(p, item)
          && (term.isEmpty()
              || (term.equals("deals")
                  ? deals.contains(item.id)
                  : (item.name + " " + item.id + " " + item.category)
                      .toLowerCase(Locale.ROOT)
                      .contains(term)))) products.add(item);
    int page = Math.max(0, Math.min(requested, Math.max(0, (products.size() - 1) / 12)));
    List<String> labels = new ArrayList<String>();
    List<Action> actions = new ArrayList<Action>();
    for (int i = page * 12; i < Math.min(products.size(), page * 12 + 12); i++) {
      Product item = products.get(i);
      labels.add(item.name + "\n" + item.category);
      actions.add(() -> select(p, item));
    }
    if (page > 0) {
      labels.add("Previous page");
      actions.add(() -> plugin.menus.browse(p, filter, page - 1));
    }
    if ((page + 1) * 12 < products.size()) {
      labels.add("Next page");
      actions.add(() -> plugin.menus.browse(p, filter, page + 1));
    }
    add(p, labels, actions, "category", "Categories", () -> plugin.menus.categories(p, 0));
    add(p, labels, actions, "deals", "Featured deals", () -> plugin.menus.browse(p, "deals", 0));
    add(p, labels, actions, "market", "Player market", () -> plugin.menus.market(p, 0));
    add(p, labels, actions, "sellall", "Sell inventory", () -> plugin.previewSellAll(p));
    add(
        p,
        labels,
        actions,
        "search",
        "Search (use /shop search <text>)",
        () -> plugin.tell(p, "&eUse /shop search <text>."));
    labels.add("Close");
    actions.add(() -> {});
    String balance =
        plugin.actionEnabled(p, "balance")
            ? "Balance: " + plugin.money(plugin.service.balance(p)) + "\n"
            : "";
    return send(
        p,
        "UniversalShop | " + (page + 1),
        balance + "Choose a product. Buy and Sell are separate buttons.",
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
          "Buy - " + plugin.money(plugin.service.quote(p, item, 1, true)),
          () -> plugin.menus.confirm(p, item, 1, true));
    if (item.sell >= 0)
      add(
          p,
          labels,
          actions,
          "sell",
          "Sell - " + plugin.money(plugin.service.quote(p, item, 1, false)),
          () -> plugin.menus.confirm(p, item, 1, false));
    labels.add("Back");
    actions.add(() -> plugin.menus.browse(p, "", 0));
    send(
        p,
        item.name,
        "Stock: " + (plugin.service.stock(item) < 0 ? "Unlimited" : plugin.service.stock(item)),
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
        "Confirm " + (buying ? "purchase" : "sale"),
        () -> {
          if (System.currentTimeMillis() > expiry || plugin.products.get(item.id) != item)
            throw new IllegalArgumentException("This quote expired. Open a fresh confirmation.");
          plugin.service.trade(p, item, count, buying, quote);
        });
    if (item.commands.isEmpty())
      for (int size : new int[] {1, 16, 64, 256})
        if (size <= plugin.getConfig().getInt("max-transaction-items", 2304)) {
          labels.add("Quantity: " + size);
          actions.add(() -> plugin.menus.confirm(p, item, size, buying));
        }
    if ((buying ? item.sell : item.buy) >= 0)
      add(
          p,
          labels,
          actions,
          buying ? "sell" : "buy",
          buying ? "Switch to Sell" : "Switch to Buy",
          () -> plugin.menus.confirm(p, item, count, !buying));
    labels.add("Cancel");
    actions.add(() -> plugin.menus.browse(p, "", 0));
    return send(
        p,
        "UniversalShop | Confirm",
        (buying ? "Buy " : "Sell ")
            + count
            + " x "
            + item.name
            + "\nTotal: "
            + plugin.money(quote)
            + "\nQuote valid for 30 seconds.",
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
      labels.add("Previous page");
      actions.add(() -> plugin.menus.categories(p, page - 1));
    }
    if ((page + 1) * 12 < names.size()) {
      labels.add("Next page");
      actions.add(() -> plugin.menus.categories(p, page + 1));
    }
    labels.add("All products");
    actions.add(() -> plugin.menus.browse(p, "", 0));
    return send(p, "UniversalShop | Categories", "Choose a category.", labels, actions);
  }

  boolean sellAll(Player p, Map<String, Integer> basket, long quote) {
    if (!available(p)) return false;
    long expiry = System.currentTimeMillis() + 30000, revision = plugin.catalogRevision();
    return send(
        p,
        "UniversalShop | Sell inventory",
        basket + "\nTotal: " + plugin.money(quote),
        Arrays.asList("Confirm sale", "Cancel"),
        Arrays.<Action>asList(
            () -> {
              if (System.currentTimeMillis() > expiry || revision != plugin.catalogRevision())
                throw new IllegalArgumentException("This preview expired. Preview again.");
              plugin.service.sellAll(p, basket, quote);
            },
            () -> {}));
  }

  boolean market(Player p, int requested) throws Exception {
    if (!available(p)) return false;
    org.bukkit.configuration.ConfigurationSection section =
        plugin.store.state().getConfigurationSection("market");
    List<String> ids =
        section == null ? new ArrayList<String>() : new ArrayList<String>(section.getKeys(false));
    Collections.sort(ids);
    int page = Math.max(0, Math.min(requested, Math.max(0, (ids.size() - 1) / 12)));
    List<String> labels = new ArrayList<String>();
    List<Action> actions = new ArrayList<Action>();
    for (int i = page * 12; i < Math.min(ids.size(), page * 12 + 12); i++) {
      String id = ids.get(i);
      labels.add(
          id
              + " | "
              + section.getItemStack(id + ".item").getType()
              + " x"
              + section.getInt(id + ".quantity")
              + "\n"
              + plugin.money(section.getLong(id + ".price")));
      actions.add(() -> listing(p, id));
    }
    if (page > 0) {
      labels.add("Previous page");
      actions.add(() -> plugin.menus.market(p, page - 1));
    }
    if ((page + 1) * 12 < ids.size()) {
      labels.add("Next page");
      actions.add(() -> plugin.menus.market(p, page + 1));
    }
    labels.add("Server shop");
    actions.add(() -> plugin.menus.browse(p, "", 0));
    return send(
        p,
        "UniversalShop | Player market",
        "List held items with /shop market list <amount> <total price>.",
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
    add(
        p,
        labels,
        actions,
        own ? "market.cancel" : "market.buy",
        own ? "Reclaim items" : "Confirm purchase",
        () -> {
          plugin.service.marketTake(p, id, own);
          plugin.tell(p, "&aMarket transaction complete.");
        });
    labels.add("Cancel");
    actions.add(() -> plugin.menus.market(p, 0));
    send(
        p,
        "UniversalShop | Listing " + id,
        "Seller: "
            + state.getString(path + ".seller")
            + "\nQuantity: "
            + state.getInt(path + ".quantity")
            + "\nTotal: "
            + plugin.money(state.getLong(path + ".price"))
            + "\nExpires: "
            + java.time.Instant.ofEpochMilli(state.getLong(path + ".expires")),
        labels,
        actions);
  }
}
