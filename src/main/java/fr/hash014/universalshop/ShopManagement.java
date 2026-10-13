package fr.hash014.universalshop;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Administration and presentation for opt-in management modules. */
final class ShopManagement {
  private final UniversalShop plugin;
  ManagementPolicy policy;
  private final Map<String, Long> alerts = new HashMap<String, Long>();

  ShopManagement(UniversalShop plugin) throws Exception {
    this.plugin = plugin;
    policy = new ManagementPolicy(plugin.getConfig());
  }

  boolean enabled(String module) {
    return policy.enabled(module);
  }

  boolean sellable(Product product) {
    if (!policy.direction(product.id, product.category, false)) return false;
    if (!enabled("sale-protection")) return true;
    ItemStack item = product.item;
    if (policy
        .config
        .getStringList("management.sale-protection.materials")
        .contains(item.getType().name())) return false;
    return !(policy.config.getBoolean("management.sale-protection.named", true)
            && item.hasItemMeta()
            && item.getItemMeta().hasDisplayName())
        && !(policy.config.getBoolean("management.sale-protection.lore", true)
            && item.hasItemMeta()
            && item.getItemMeta().hasLore())
        && !(policy.config.getBoolean("management.sale-protection.enchanted", true)
            && !item.getEnchantments().isEmpty())
        && !(policy.config.getBoolean("management.sale-protection.damaged", true)
            && item.getType().getMaxDurability() > 0
            && item.getDurability() > 0);
  }

  void record(
      YamlConfiguration next,
      Player player,
      Product product,
      int quantity,
      long total,
      boolean buy) {
    if (!buy && !sellable(product))
      throw new IllegalArgumentException("This item is protected from shop sales.");
    policy.record(
        next,
        player.getUniqueId(),
        product.id,
        product.category,
        quantity,
        total,
        buy,
        Instant.now());
  }

  double discount(Player player, Product product) {
    return policy.discount(
        plugin.store.state(),
        player.getUniqueId(),
        product.id,
        product.category,
        player::hasPermission,
        Instant.now());
  }

  void afterTrade(Product product) {
    if (!enabled("stock-alerts") || product.stock < 1) return;
    long stock = plugin.service.stock(product);
    if (stock * 100 > product.stock * policy.number("stock-alerts.threshold-percent", 20, 0, 100)) {
      alerts.remove(product.id);
      return;
    }
    long now = System.currentTimeMillis(),
        last = alerts.containsKey(product.id) ? alerts.get(product.id) : 0;
    if (now - last < policy.number("stock-alerts.cooldown-seconds", 300, 1, 86400) * 1000) return;
    alerts.put(product.id, now);
    plugin
        .getLogger()
        .warning("Low shop stock: " + product.id + " = " + stock + " / " + product.stock);
  }

  private void require(String module) {
    if (!enabled(module))
      throw new IllegalArgumentException("The " + module + " module is disabled.");
  }

  List<Product> catalog(Player player, String filter) {
    String term = filter.toLowerCase(Locale.ROOT);
    boolean favorites = term.equals("@favorites"), recent = term.equals("@recent");
    if (favorites || recent) {
      require("personal");
      plugin.requireAction(player, favorites ? "favorites" : "recent");
    }
    String base = "management.personal." + player.getUniqueId();
    List<String> ids =
        plugin.store.state().getStringList(base + (favorites ? ".favorites" : ".recent"));
    List<Product> result = new ArrayList<Product>();
    Set<String> deals = plugin.service.deals();
    for (Product product : plugin.products.values())
      if (plugin.service.visible(player, product)
          && (favorites || recent
              ? ids.contains(product.id)
              : term.isEmpty()
                  || (term.equals("deals")
                      ? deals.contains(product.id)
                      : (product.name + " " + product.id + " " + product.category)
                          .toLowerCase(Locale.ROOT)
                          .contains(term)))) result.add(product);
    if (recent) Collections.sort(result, Comparator.comparingInt(p -> ids.indexOf(p.id)));
    else if (enabled("personal")) {
      String sort = plugin.store.state().getString(base + ".sort", "catalog");
      if (sort.equals("name"))
        Collections.sort(result, Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT)));
      if (sort.equals("price"))
        Collections.sort(result, Comparator.comparingLong(p -> p.buy < 0 ? Long.MAX_VALUE : p.buy));
    }
    return result;
  }

  void personal(Player player, String[] args) throws Exception {
    plugin.service.allowed(player);
    String action = args[0].toLowerCase(Locale.ROOT);
    plugin.requireAction(player, action);
    if (action.equals("stats")) {
      require("analytics");
      stats(player, "management.stats.players." + player.getUniqueId(), "Your shop activity");
      return;
    }
    require("personal");
    if (action.equals("favorites") || action.equals("recent")) {
      plugin.menus.browse(player, "@" + action, 0);
      return;
    }
    if (args.length != 2)
      throw new IllegalArgumentException(
          "Usage: /shop favorite <product> or /shop sort <catalog|name|price>");
    YamlConfiguration next = plugin.store.copy();
    String base = "management.personal." + player.getUniqueId();
    if (action.equals("favorite")) {
      Product product = plugin.product(args[1]);
      if (!plugin.service.visible(player, product))
        throw new IllegalArgumentException("This product is unavailable.");
      List<String> ids = new ArrayList<String>(next.getStringList(base + ".favorites"));
      // Deleted products must not consume favorite slots forever.
      ids.retainAll(plugin.products.keySet());
      if (!ids.remove(product.id)) {
        if (ids.size() >= policy.number("personal.max-favorites", 24, 1, 100))
          throw new IllegalArgumentException("Your favorites list is full.");
        ids.add(product.id);
      }
      next.set(base + ".favorites", ids);
      plugin.store.save(next, "FAVORITES " + player.getUniqueId());
      plugin.tell(player, "&aFavorites updated: " + ids);
    } else {
      String sort = args[1].toLowerCase(Locale.ROOT);
      if (!Arrays.asList("catalog", "name", "price").contains(sort))
        throw new IllegalArgumentException("Sort must be catalog, name or price.");
      next.set(base + ".sort", sort);
      plugin.store.save(next, "SORT " + player.getUniqueId());
      plugin.menus.browse(player, "", 0);
    }
  }

  private void stats(CommandSender sender, String path, String title) {
    YamlConfiguration state = plugin.store.state();
    plugin.tell(
        sender,
        "&6"
            + title
            + " &7| Lines: "
            + state.getLong(path + ".trades")
            + " | Bought: "
            + state.getLong(path + ".bought")
            + " | Sold: "
            + state.getLong(path + ".sold")
            + " | Spent: "
            + plugin.money(state.getLong(path + ".spent"))
            + " | Earned: "
            + plugin.money(state.getLong(path + ".earned")));
  }

  void admin(CommandSender sender, String[] args) throws Exception {
    String action = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "status";
    if (action.equals("status")) {
      plugin.tell(sender, "&6Server shop: " + (policy.open(Instant.now()) ? "OPEN" : "CLOSED"));
      for (String module : ManagementPolicy.MODULES)
        plugin.tell(sender, "&e" + module + ": " + (enabled(module) ? "ON" : "OFF"));
      plugin.tell(sender, "&7/shop admin manage <enable|disable> <module>");
      plugin.tell(
          sender,
          "&7Actions: open, close, pause, resume, category-pause, category-resume, stock, restock,"
              + " reset-demand, stats, report");
      return;
    }
    if (action.equals("stats")) {
      require("analytics");
      if (args.length == 4) {
        Product product = plugin.product(args[3]);
        stats(sender, "management.stats.products." + product.id, product.name);
        return;
      }
      ConfigurationSection section =
          plugin.store.state().getConfigurationSection("management.stats.products");
      if (section == null) {
        plugin.tell(sender, "&eNo recorded shop activity.");
        return;
      }
      List<String> ids = new ArrayList<String>(section.getKeys(false));
      Collections.sort(
          ids,
          (a, b) -> Long.compare(section.getLong(b + ".spent"), section.getLong(a + ".spent")));
      for (String id : ids.subList(0, Math.min(10, ids.size())))
        stats(sender, "management.stats.products." + id, id);
      return;
    }
    if (action.equals("report")) {
      require("analytics");
      StringBuilder csv =
          new StringBuilder("product,buy_units,sell_units,charged_cents,paid_cents,trade_lines\n");
      ConfigurationSection section =
          plugin.store.state().getConfigurationSection("management.stats.products");
      if (section != null)
        for (String id : new TreeSet<String>(section.getKeys(false))) {
          ManagementPolicy.id(id);
          csv.append(id);
          for (String key : Arrays.asList("bought", "sold", "spent", "earned", "trades"))
            csv.append(',').append(section.getLong(id + "." + key));
          csv.append('\n');
        }
      Path file =
          plugin
              .getDataFolder()
              .toPath()
              .resolve(
                  "exports/sales-"
                      + System.currentTimeMillis()
                      + "-"
                      + UUID.randomUUID().toString().substring(0, 8)
                      + ".csv");
      AtomicFiles.write(file, csv.toString());
      plugin.tell(sender, "&aExported " + file.getFileName());
      return;
    }
    if (action.equals("stock")) {
      List<Product> products = new ArrayList<Product>();
      for (Product product : plugin.products.values())
        if (product.stock >= 0) products.add(product);
      Collections.sort(products, Comparator.comparingLong(p -> plugin.service.stock(p)));
      if (products.isEmpty()) plugin.tell(sender, "&eAll products have unlimited stock.");
      for (Product product : products.subList(0, Math.min(20, products.size())))
        plugin.tell(
            sender,
            "&e" + product.id + ": " + plugin.service.stock(product) + " / " + product.stock);
      return;
    }
    if (action.equals("restock") || action.equals("reset-demand")) {
      if (args.length != 4)
        throw new IllegalArgumentException(
            "Usage: /shop admin manage " + action + " <product|all>");
      Collection<Product> products =
          args[3].equalsIgnoreCase("all")
              ? plugin.products.values()
              : Collections.singleton(plugin.product(args[3]));
      YamlConfiguration next = plugin.store.copy();
      for (Product product : products) {
        String path = "products." + product.id;
        if (action.equals("reset-demand")) next.set(path + ".demand", 0);
        else if (product.stock >= 0) {
          next.set(path + ".stock", product.stock);
          if (product.restockHours > 0)
            next.set(
                path + ".restock", System.currentTimeMillis() / (product.restockHours * 3600000L));
        }
      }
      plugin.store.save(next, "ADMIN " + action + " " + args[3]);
      alerts.clear();
      plugin.invalidateViews();
      plugin.tell(sender, "&a" + action + " completed.");
      return;
    }
    YamlConfiguration next = new YamlConfiguration();
    next.loadFromString(plugin.getConfig().saveToString());
    if (plugin.getConfig().getDefaults() != null)
      next.setDefaults(plugin.getConfig().getDefaults());
    if (action.equals("enable") || action.equals("disable")) {
      if (args.length != 4 || !ManagementPolicy.MODULES.contains(args[3]))
        throw new IllegalArgumentException("Choose a module: " + ManagementPolicy.MODULES);
      next.set("management." + args[3] + ".enabled", action.equals("enable"));
    } else if (action.equals("open") || action.equals("close")) {
      next.set("management.access.enabled", true);
      next.set("management.access.maintenance", action.equals("close"));
    } else if (action.equals("pause") || action.equals("resume")) {
      if (args.length != 4) throw new IllegalArgumentException("Specify a product ID.");
      Product product = plugin.product(args[3]);
      next.set("management.product-rules.enabled", true);
      next.set(
          "management.product-rules.entries." + product.id + ".paused", action.equals("pause"));
    } else if (action.equals("category-pause") || action.equals("category-resume")) {
      if (args.length < 4) throw new IllegalArgumentException("Specify a category name.");
      String name = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
      boolean exists = false;
      for (Product product : plugin.products.values())
        if (product.category.equalsIgnoreCase(name)) exists = true;
      if (!exists) throw new IllegalArgumentException("Unknown category.");
      List<String> categories =
          new ArrayList<String>(next.getStringList("management.product-rules.paused-categories"));
      categories.removeIf(c -> c.equalsIgnoreCase(name));
      if (action.equals("category-pause")) categories.add(name);
      next.set("management.product-rules.enabled", true);
      next.set("management.product-rules.paused-categories", categories);
    } else throw new IllegalArgumentException("Unknown management action. Use /shop admin manage.");
    ManagementPolicy candidate = new ManagementPolicy(next);
    AtomicFiles.write(plugin.getDataFolder().toPath().resolve("config.yml"), next.saveToString());
    plugin.getConfig().loadFromString(next.saveToString());
    policy = candidate;
    plugin.invalidateViews();
    plugin.tell(
        sender,
        "&aManagement settings saved. Server shop: "
            + (policy.open(Instant.now()) ? "OPEN" : "CLOSED"));
  }
}
