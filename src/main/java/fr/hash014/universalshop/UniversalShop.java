package fr.hash014.universalshop;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Sign;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class UniversalShop extends JavaPlugin
    implements CommandExecutor, TabCompleter, Listener {
  Map<String, Product> products = new LinkedHashMap<String, Product>();
  Store store;
  ShopService service;
  ShopMenus menus;
  long startingBalance;
  private long catalogRevision;
  private final Map<UUID, Basket> baskets = new HashMap<UUID, Basket>();

  private static final class Basket {
    Map<String, Integer> items;
    long total, expiry, revision;

    Basket(Map<String, Integer> items, long total, long revision) {
      this.items = items;
      this.total = total;
      this.revision = revision;
      expiry = System.currentTimeMillis() + 30000;
    }
  }

  @Override
  public void onEnable() {
    try {
      saveDefaultConfig();
      saveResourceIfMissing("shops.yml");
      validateConfig();
      loadCatalog();
      store = new Store(getDataFolder().toPath());
      String mode = getConfig().getString("economy", "INTERNAL").toUpperCase(Locale.ROOT);
      if (store.state().contains("economy-mode")
          && !mode.equals(store.state().getString("economy-mode")))
        throw new IllegalStateException(
            "Economy mode differs from the saved ledger. Follow the documented offline migration"
                + " procedure.");
      Economy economy = new Economy(mode);
      if (!store.state().contains("economy-mode")) {
        YamlConfiguration initial = store.copy();
        initial.set("economy-mode", mode);
        store.save(initial, "INITIALIZE ECONOMY " + mode);
      }
      service = new ShopService(this, store, economy);
      menus = new ShopMenus(this);
      getCommand("shop").setExecutor(this);
      getCommand("shop").setTabCompleter(this);
      getServer().getPluginManager().registerEvents(this, this);
      getServer().getPluginManager().registerEvents(menus, this);
      getLogger()
          .info(
              "Loaded "
                  + products.size()
                  + " products. Economy: "
                  + getConfig().getString("economy")
                  + ". All prices use integer cents.");
      if (store.blocked()) getLogger().severe("RECOVERY REQUIRED: " + store.pendingInfo());
    } catch (Exception e) {
      getLogger().log(java.util.logging.Level.SEVERE, "UniversalShop could not safely start.", e);
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  private void saveResourceIfMissing(String name) {
    if (!new File(getDataFolder(), name).exists()) saveResource(name, false);
  }

  @Override
  public void onDisable() {
    for (Player p : onlinePlayers())
      if (p.getOpenInventory().getTopInventory().getHolder() instanceof ShopMenus.Menu)
        p.closeInventory();
    baskets.clear();
  }

  private void validateConfig() {
    startingBalance = Money.parse(String.valueOf(getConfig().get("starting-balance", 100)));
    for (String field :
        Arrays.asList(
            "buy-tax-percent",
            "sell-tax-percent",
            "rotation.discount-percent",
            "promotion.discount-percent",
            "market.tax-percent")) percent(field);
    int max = getConfig().getInt("max-transaction-items", 2304);
    if (max < 1 || max > 2304)
      throw new IllegalArgumentException("max-transaction-items must be between 1 and 2304.");
    long cooldown = getConfig().getLong("cooldown-milliseconds", 350);
    if (cooldown < 0 || cooldown > 60000) throw new IllegalArgumentException("Invalid cooldown.");
    for (String field : Arrays.asList("rotation.period-hours", "market.listing-hours")) {
      long hours = getConfig().getLong(field, 168);
      if (hours < 1 || hours > 876000)
        throw new IllegalArgumentException("Invalid hours: " + field);
    }
    if (getConfig().getInt("rotation.count", 3) < 0
        || getConfig().getInt("market.max-listings-per-player", 10) < 1)
      throw new IllegalArgumentException("Invalid rotation or market count.");
    String start = getConfig().getString("promotion.starts", ""),
        end = getConfig().getString("promotion.ends", "");
    if (!start.isEmpty() || !end.isEmpty())
      if (!java.time.Instant.parse(end).isAfter(java.time.Instant.parse(start)))
        throw new IllegalArgumentException("Promotion end must follow its start.");
    ConfigurationSection discounts = getConfig().getConfigurationSection("permission-discounts");
    if (discounts != null)
      for (Map.Entry<String, Object> entry : discounts.getValues(true).entrySet())
        if (entry.getValue() instanceof Number) {
          double d = ((Number) entry.getValue()).doubleValue();
          if (!Double.isFinite(d) || d < 0 || d > 100)
            throw new IllegalArgumentException("Invalid permission discount.");
        }
  }

  double percent(String path) {
    double value = getConfig().getDouble(path, 0);
    if (!Double.isFinite(value) || value < 0 || value > 100)
      throw new IllegalArgumentException("Invalid percentage: " + path);
    return value / 100;
  }

  void loadCatalog() throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.load(new File(getDataFolder(), "shops.yml"));
    products = parseCatalog(yaml);
    catalogRevision++;
    baskets.clear();
  }

  private Map<String, Product> parseCatalog(YamlConfiguration yaml) {
    ConfigurationSection root = yaml.getConfigurationSection("products");
    if (root == null) throw new IllegalArgumentException("shops.yml requires a products section.");
    Map<String, Product> result = new LinkedHashMap<String, Product>();
    for (String id : root.getKeys(false))
      try {
        result.put(id, new Product(id, root.getConfigurationSection(id)));
      } catch (UnsupportedOperationException e) {
        getLogger().warning("Skipping " + id + " on this Minecraft version: " + e.getMessage());
      }
    if (result.isEmpty()) throw new IllegalArgumentException("No supported products were found.");
    return result;
  }

  public Product product(String id) {
    Product p = products.get(id.toLowerCase(Locale.ROOT));
    if (p == null) throw new IllegalArgumentException("Unknown product: " + id);
    return p;
  }

  public static String color(String text) {
    return ChatColor.translateAlternateColorCodes('&', text);
  }

  public String money(long cents) {
    return getConfig().getString("currency", "$") + Money.format(cents);
  }

  public void tell(CommandSender sender, String text) {
    sender.sendMessage(
        color(getConfig().getString("messages.prefix", "&6UniversalShop &8> &r") + text));
  }

  public void message(CommandSender sender, String key, String... replacements) {
    String text = getConfig().getString("messages." + key, key);
    for (int i = 0; i < replacements.length; i += 2)
      text = text.replace("{" + replacements[i] + "}", replacements[i + 1]);
    tell(sender, text);
  }

  public void failure(CommandSender sender, Exception e) {
    if (e instanceof IllegalArgumentException || e instanceof IllegalStateException)
      message(
          sender, "error", "reason", e.getMessage() == null ? "Invalid input." : e.getMessage());
    else {
      message(
          sender,
          "error",
          "reason",
          "The operation could not finish safely. Contact an administrator.");
      getLogger()
          .log(
              java.util.logging.Level.SEVERE,
              "Shop operation failed; inspect pending.yml before recovery.",
              e);
    }
  }

  private Player player(CommandSender sender) {
    if (!(sender instanceof Player))
      throw new IllegalArgumentException("This command requires a player.");
    return (Player) sender;
  }

  private void admin(CommandSender sender) {
    if (!sender.hasPermission("universalshop.admin"))
      throw new IllegalArgumentException("Administrator permission required.");
  }

  private void usage(boolean valid, String syntax) {
    if (!valid) throw new IllegalArgumentException("Usage: " + syntax);
  }

  public void previewSellAll(Player p) throws Exception {
    service.allowed(p);
    Map<String, Integer> items = service.sellBasket(p);
    if (items.isEmpty())
      throw new IllegalArgumentException("No sellable items were found in your storage slots.");
    long total = service.basketQuote(p, items);
    baskets.put(p.getUniqueId(), new Basket(items, total, catalogRevision));
    tell(p, "&eInventory sale: " + items + ". Total: " + money(total));
    tell(
        p,
        "&eType /shop sellall confirm within 30 seconds. Named or modified items require an exact"
            + " matching product.");
  }

  @Override
  public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
    try {
      if (args.length == 0) {
        menus.browse(player(sender), "", 0);
        return true;
      }
      String sub = args[0].toLowerCase(Locale.ROOT);
      if (sub.equals("test") && Boolean.getBoolean("universalshop.test")) {
        admin(sender);
        new ServerTests(this).run(sender, args);
        return true;
      }
      if (sub.equals("help")) {
        tell(
            sender,
            "&6/shop &7- browse | /shop search <text> | /shop category <name> | /shop deals");
        tell(sender, "&e/shop buy|sell <id> [amount] &7- review and confirm");
        tell(sender, "&e/shop sellhand [amount] | /shop sellall [confirm] | /shop balance");
        tell(sender, "&e/shop pay <online player> <amount> | /shop market [page]");
        tell(sender, "&e/shop market list <amount> <total price> | buy|cancel <id> confirm");
        if (sender.hasPermission("universalshop.admin")) {
          tell(sender, "&c/shop admin add <id> <buy|-1> <sell|-1> [category] (held item)");
          tell(
              sender,
              "&c/shop admin remove <id> | price <id> <buy> <sell> | stock <id> <quantity>");
          tell(
              sender,
              "&c/shop admin balance <online player|uuid> <amount> | history [lines] | reload");
          tell(sender, "&c/shop admin recovery [commit|abort] confirm | export");
        }
      } else if (sub.equals("admin")) {
        admin(sender);
        adminCommand(sender, args);
      } else if (sub.equals("balance")) {
        Player p = player(sender);
        service.allowed(p);
        message(p, "balance", "balance", money(service.balance(p)));
      } else if (sub.equals("buy") || sub.equals("sell")) {
        usage(args.length >= 2 && args.length <= 3, "/shop " + sub + " <id> [amount]");
        menus.confirm(
            player(sender),
            product(args[1]),
            args.length == 3 ? Integer.parseInt(args[2]) : 1,
            sub.equals("buy"));
      } else if (sub.equals("search") || sub.equals("category")) {
        usage(args.length >= 2, "/shop " + sub + " <text>");
        menus.browse(player(sender), join(args, 1), 0);
      } else if (sub.equals("deals")) {
        menus.browse(player(sender), "deals", 0);
      } else if (sub.equals("sellall")) {
        Player p = player(sender);
        if (args.length == 1) previewSellAll(p);
        else {
          usage(args.length == 2 && args[1].equalsIgnoreCase("confirm"), "/shop sellall [confirm]");
          Basket basket = baskets.remove(p.getUniqueId());
          if (basket == null
              || basket.expiry < System.currentTimeMillis()
              || basket.revision != catalogRevision)
            throw new IllegalArgumentException("Preview your inventory sale again first.");
          service.sellAll(p, basket.items, basket.total);
        }
      } else if (sub.equals("sellhand")) {
        usage(args.length <= 2, "/shop sellhand [amount]");
        Player p = player(sender);
        ItemStack hand = p.getItemInHand();
        Product match = null;
        for (Product item : products.values())
          if (item.sell >= 0 && item.item.isSimilar(hand) && service.visible(p, item)) {
            match = item;
            break;
          }
        if (match == null)
          throw new IllegalArgumentException("The held item has no matching sell product.");
        menus.confirm(
            p, match, args.length == 2 ? Integer.parseInt(args[1]) : hand.getAmount(), false);
      } else if (sub.equals("pay")) {
        usage(args.length == 3, "/shop pay <online player> <amount>");
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) throw new IllegalArgumentException("That player is not online.");
        long amount = Money.parse(args[2]);
        service.pay(player(sender), target, amount);
        tell(sender, "&aSent " + money(amount) + " to " + target.getName());
        tell(target, "&aReceived " + money(amount) + " from " + sender.getName());
      } else if (sub.equals("market")) {
        marketCommand(player(sender), args);
      } else {
        menus.browse(player(sender), join(args, 0), 0);
      }
    } catch (Exception e) {
      failure(sender, e);
    }
    return true;
  }

  private void marketCommand(Player p, String[] args) throws Exception {
    if (args.length <= 2 && (args.length == 1 || args[1].matches("[0-9]+"))) {
      menus.market(p, args.length == 2 ? Math.max(0, Integer.parseInt(args[1]) - 1) : 0);
      return;
    }
    usage(
        args.length == 4,
        "/shop market list <amount> <total price> OR /shop market buy|cancel <id> confirm");
    if (args[1].equalsIgnoreCase("list")) {
      String id = service.list(p, Integer.parseInt(args[2]), Money.parse(args[3]));
      tell(p, "&aListing created: " + id);
    } else {
      usage(
          (args[1].equalsIgnoreCase("buy") || args[1].equalsIgnoreCase("cancel"))
              && args[3].equalsIgnoreCase("confirm"),
          "/shop market buy|cancel <id> confirm");
      service.marketTake(p, args[2], args[1].equalsIgnoreCase("cancel"));
      tell(p, "&aMarket transaction complete.");
    }
  }

  private void adminCommand(CommandSender sender, String[] args) throws Exception {
    usage(
        args.length >= 2,
        "/shop admin <add|remove|price|stock|balance|history|reload|recovery|export>");
    String action = args[1].toLowerCase(Locale.ROOT);
    if (action.equals("recovery")) {
      if (args.length == 2) {
        tell(sender, "&e" + store.pendingInfo());
        tell(
            sender,
            "&eInspect pending.yml and reconcile inventory/Vault/commands first. Then /shop admin"
                + " recovery commit|abort confirm");
        return;
      }
      usage(
          args.length == 4
              && args[3].equalsIgnoreCase("confirm")
              && (args[2].equalsIgnoreCase("commit") || args[2].equalsIgnoreCase("abort")),
          "/shop admin recovery commit|abort confirm");
      store.reconcile(args[2].equalsIgnoreCase("commit"));
      tell(sender, "&aRecovery decision recorded. Trading unlocked.");
      return;
    }
    if (action.equals("history")) {
      usage(args.length <= 3, "/shop admin history [lines]");
      List<String> history = store.state().getStringList("history");
      int count = args.length == 3 ? Math.max(1, Math.min(200, Integer.parseInt(args[2]))) : 10;
      for (String line : history.subList(Math.max(0, history.size() - count), history.size()))
        tell(sender, "&7" + line);
      return;
    }
    if (action.equals("export")) {
      Path path =
          getDataFolder().toPath().resolve("exports/state-" + System.currentTimeMillis() + ".yml");
      AtomicFiles.write(path, store.state().saveToString());
      tell(sender, "&aExported " + path.getFileName());
      return;
    }
    if (action.equals("reload")) {
      // Runtime economy switching is deliberately not supported: restart avoids mixed wallets.
      YamlConfiguration candidate = new YamlConfiguration();
      candidate.load(new File(getDataFolder(), "config.yml"));
      if (!candidate
          .getString("economy", "INTERNAL")
          .equalsIgnoreCase(getConfig().getString("economy", "INTERNAL")))
        throw new IllegalArgumentException(
            "Changing economy requires a server restart and a planned balance migration.");
      String previous = getConfig().saveToString();
      long previousBalance = startingBalance;
      try {
        getConfig().loadFromString(candidate.saveToString());
        validateConfig();
        loadCatalog();
      } catch (Exception e) {
        getConfig().loadFromString(previous);
        startingBalance = previousBalance;
        throw e;
      }
      for (Player p : onlinePlayers())
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof ShopMenus.Menu)
          p.closeInventory();
      tell(sender, "&aConfiguration and catalog reloaded.");
      return;
    }
    if (action.equals("balance")) {
      usage(args.length == 4, "/shop admin balance <online player|uuid> <amount>");
      Player target = Bukkit.getPlayerExact(args[2]);
      UUID id = target == null ? UUID.fromString(args[2]) : target.getUniqueId();
      service.setBalance(id, Money.parse(args[3]));
      tell(sender, "&aBalance updated.");
      return;
    }
    if (action.equals("stock")) {
      usage(args.length == 4, "/shop admin stock <id> <quantity>");
      Product product = product(args[2]);
      long stock = Long.parseLong(args[3]);
      if (product.stock < 0 || stock < 0 || stock > product.stock)
        throw new IllegalArgumentException(
            "Stock must be between 0 and the product's configured capacity.");
      YamlConfiguration next = store.copy();
      next.set("products." + product.id + ".stock", stock);
      if (product.restockHours > 0)
        next.set(
            "products." + product.id + ".restock",
            System.currentTimeMillis() / (product.restockHours * 3600000L));
      store.save(next, "ADMIN STOCK " + product.id + "=" + stock);
      tell(sender, "&aStock updated.");
      return;
    }
    YamlConfiguration catalog = new YamlConfiguration();
    File file = new File(getDataFolder(), "shops.yml");
    catalog.load(file);
    if (action.equals("add")) {
      usage(args.length >= 5, "/shop admin add <id> <buy|-1> <sell|-1> [category]");
      if (!args[2].matches("[a-z0-9_-]{1,48}"))
        throw new IllegalArgumentException("Invalid product ID.");
      String path = "products." + args[2];
      if (catalog.contains(path)) throw new IllegalArgumentException("This ID already exists.");
      ItemStack hand = player(sender).getItemInHand();
      if (hand == null || hand.getType() == Material.AIR)
        throw new IllegalArgumentException("Hold the exact item to add.");
      ItemStack item = hand.clone();
      item.setAmount(1);
      catalog.set(path + ".item", item);
      catalog.set(
          path + ".name",
          hand.hasItemMeta() && hand.getItemMeta().hasDisplayName()
              ? hand.getItemMeta().getDisplayName()
              : args[2]);
      catalog.set(path + ".buy", args[3]);
      catalog.set(path + ".sell", args[4]);
      catalog.set(path + ".category", args.length > 5 ? join(args, 5) : "General");
      new Product(args[2], catalog.getConfigurationSection(path));
    } else if (action.equals("remove")) {
      usage(args.length == 3, "/shop admin remove <id>");
      product(args[2]);
      catalog.set("products." + args[2], null);
      if (catalog.getConfigurationSection("products").getKeys(false).isEmpty())
        throw new IllegalArgumentException("Keep at least one product.");
    } else if (action.equals("price")) {
      usage(args.length == 5, "/shop admin price <id> <buy|-1> <sell|-1>");
      product(args[2]);
      catalog.set("products." + args[2] + ".buy", args[3]);
      catalog.set("products." + args[2] + ".sell", args[4]);
      new Product(args[2], catalog.getConfigurationSection("products." + args[2]));
    } else throw new IllegalArgumentException("Unknown admin command. Use /shop help.");
    parseCatalog(catalog);
    AtomicFiles.write(
        file.toPath().resolveSibling("shops.yml.bak"),
        new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8));
    AtomicFiles.write(file.toPath(), catalog.saveToString());
    loadCatalog();
    tell(sender, "&aCatalog saved. Previous file: shops.yml.bak");
  }

  private String join(String[] args, int from) {
    StringBuilder b = new StringBuilder();
    for (int i = from; i < args.length; i++) {
      if (i > from) b.append(' ');
      b.append(args[i]);
    }
    return b.toString();
  }

  @SuppressWarnings("unchecked")
  private Collection<? extends Player> onlinePlayers() {
    try {
      Object result = Bukkit.class.getMethod("getOnlinePlayers").invoke(null);
      return result instanceof Player[]
          ? Arrays.asList((Player[]) result)
          : (Collection<? extends Player>) result;
    } catch (Exception e) {
      return Collections.emptyList();
    }
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    List<String> values = new ArrayList<String>();
    if (args.length == 1)
      values.addAll(
          Arrays.asList(
              "help",
              "buy",
              "sell",
              "sellhand",
              "sellall",
              "balance",
              "pay",
              "search",
              "category",
              "deals",
              "market"));
    if (args.length == 1 && sender.hasPermission("universalshop.admin")) values.add("admin");
    if (args.length == 2 && (args[0].equalsIgnoreCase("buy") || args[0].equalsIgnoreCase("sell")))
      values.addAll(products.keySet());
    if (args.length == 2
        && args[0].equalsIgnoreCase("admin")
        && sender.hasPermission("universalshop.admin"))
      values.addAll(
          Arrays.asList(
              "add",
              "remove",
              "price",
              "stock",
              "balance",
              "history",
              "reload",
              "recovery",
              "export"));
    if (args.length == 2 && args[0].equalsIgnoreCase("market"))
      values.addAll(Arrays.asList("list", "buy", "cancel"));
    String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
    Iterator<String> it = values.iterator();
    while (it.hasNext()) if (!it.next().toLowerCase(Locale.ROOT).startsWith(prefix)) it.remove();
    return values;
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    baskets.remove(e.getPlayer().getUniqueId());
    service.forget(e.getPlayer().getUniqueId());
  }

  @EventHandler(ignoreCancelled = true)
  public void signCreate(SignChangeEvent e) {
    if (!e.getLine(0).equalsIgnoreCase("[ushop]")) return;
    if (!e.getPlayer().hasPermission("universalshop.admin")) {
      e.setCancelled(true);
      tell(e.getPlayer(), "&cOnly administrators can create shop signs.");
      return;
    }
    try {
      if (!e.getLine(1).isEmpty()) product(e.getLine(1));
      e.setLine(0, color("&6[UShop]"));
      e.setLine(2, "Right-click to shop");
    } catch (Exception ex) {
      e.setCancelled(true);
      failure(e.getPlayer(), ex);
    }
  }

  @EventHandler(ignoreCancelled = true)
  public void signUse(PlayerInteractEvent e) {
    if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
        || e.getClickedBlock() == null
        || !(e.getClickedBlock().getState() instanceof Sign)) return;
    Sign sign = (Sign) e.getClickedBlock().getState();
    if (!sign.getLine(0).equals(color("&6[UShop]"))) return;
    e.setCancelled(true);
    try {
      if (sign.getLine(1).isEmpty()) menus.browse(e.getPlayer(), "", 0);
      else menus.confirm(e.getPlayer(), product(sign.getLine(1)), 1, true);
    } catch (Exception ex) {
      failure(e.getPlayer(), ex);
    }
  }
}
