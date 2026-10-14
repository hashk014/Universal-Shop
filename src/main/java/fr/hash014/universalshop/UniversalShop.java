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
  BedrockForms bedrock;
  ShopManagement management;
  PlayerInputs inputs;
  CatalogEditor editor;
  Texts texts;
  private LedgerLease lease;
  private CommandPolicy commandPolicy = new CommandPolicy(Collections.<String>emptyList());
  long startingBalance;
  private long catalogRevision;
  private long catalogEditRevision;
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
      lease = new LedgerLease(getDataFolder().toPath());
      saveDefaultConfig();
      saveResourceIfMissing("shops.yml");
      validateConfig();
      texts = new Texts(this);
      loadCatalog();
      store = new Store(getDataFolder().toPath());
      String mode = getConfig().getString("economy", "INTERNAL").toUpperCase(Locale.ROOT);
      if (store.state().contains("economy-mode")
          && !mode.equals(store.state().getString("economy-mode")))
        throw new IllegalStateException(
            "Economy mode differs from the saved ledger. Follow the documented offline migration"
                + " procedure.");
      Economy economy = new Economy(mode);
      if (economy.external()) {
        String provider = economy.providerName();
        String previousProvider = store.state().getString("economy-provider", provider);
        ConfigurationSection credits = store.state().getConfigurationSection("market-credits");
        boolean owesCredits = false;
        if (credits != null) for (String id : credits.getKeys(false))
          if (credits.getLong(id) > 0) owesCredits = true;
        if (!provider.equals(previousProvider) && owesCredits)
          throw new IllegalStateException("The Vault provider changed while seller credits remain. Collect or migrate liabilities with the original provider first.");
        if (!provider.equals(store.state().getString("economy-provider", "")) && !store.blocked()) {
          YamlConfiguration binding = store.copy();
          binding.set("economy-provider", provider);
          store.save(binding, "BIND VAULT PROVIDER " + provider);
        }
      }
      if (!store.state().contains("economy-mode")) {
        YamlConfiguration initial = store.copy();
        initial.set("economy-mode", mode);
        store.save(initial, "INITIALIZE ECONOMY " + mode);
      }
      service = new ShopService(this, store, economy);
      menus = new ShopMenus(this);
      bedrock = new BedrockForms(this);
      inputs = new PlayerInputs(this);
      editor = new CatalogEditor(this);
      getCommand("shop").setExecutor(this);
      getCommand("shop").setTabCompleter(this);
      getServer().getPluginManager().registerEvents(this, this);
      getServer().getPluginManager().registerEvents(menus, this);
      getServer().getPluginManager().registerEvents(editor, this);
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
      if (p.getOpenInventory().getTopInventory().getHolder() instanceof ShopMenus.Menu
          || p.getOpenInventory().getTopInventory().getHolder() instanceof CatalogEditor.Menu)
        p.closeInventory();
    baskets.clear();
    if (bedrock != null) bedrock.sessions.clear();
    if (inputs != null) inputs.clear();
    if (editor != null) editor.clear();
    if (menus != null) menus.clear();
    if (store != null) {
      try { store.close(); }
      catch (Exception e) { getLogger().log(java.util.logging.Level.SEVERE, "Could not checkpoint the ledger during shutdown; durable audit records remain available.", e); }
    }
    if (lease != null) {
      try { lease.close(); }
      catch (Exception e) { getLogger().log(java.util.logging.Level.SEVERE, "Could not release ledger lease.", e); }
      lease = null;
    }
  }

  private void validateConfig() throws Exception {
    Texts.language(getConfig().getString("language", "en"));
    commandPolicy = new CommandPolicy(getConfig().getStringList("disabled-commands"));
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
    int listings = getConfig().getInt("market.max-total-listings", 10000);
    if (listings < 1 || listings > 100000)
      throw new IllegalArgumentException("market.max-total-listings must be between 1 and 100000.");
    if (Money.parse(String.valueOf(getConfig().get("market.min-price", "0.01"))) < 1)
      throw new IllegalArgumentException("market.min-price must be at least 0.01.");
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
    management = new ShopManagement(this);
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
    catalogEditRevision++;
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
        color(getConfig().getString("messages.prefix", "&6UniversalShop &8> &r")
            + (texts == null ? text : texts.translate(sender, text))));
  }

  public void message(CommandSender sender, String key, String... replacements) {
    String text = getConfig().getString("messages." + key, key);
    String defaultText = getConfig().getDefaults() == null ? text : getConfig().getDefaults().getString("messages." + key, text);
    if (texts != null && (!texts.locale(sender).equals("en") || text.equals(defaultText)))
      text = texts.format(sender, "messages." + key, text);
    for (int i = 0; i < replacements.length; i += 2)
      text = text.replace("{" + replacements[i] + "}", replacements[i + 1]);
    tell(sender, text);
  }

  public void failure(CommandSender sender, Exception e) {
    if (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof ArithmeticException)
      message(
          sender, "error", "reason", texts == null ? (e instanceof ArithmeticException ? "Amounts must have at most two decimal places and stay within the supported range." : e.getMessage() == null ? "Invalid input." : e.getMessage())
              : texts.translate(sender, e instanceof ArithmeticException ? "Amounts must have at most two decimal places and stay within the supported range." : e.getMessage() == null ? "Invalid input." : e.getMessage()));
    else {
      message(
          sender,
          "error",
          "reason",
          texts == null ? "The operation could not finish safely. Contact an administrator."
              : texts.format(sender, "errors.safe-failure", "The operation could not finish safely. Contact an administrator."));
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

  public boolean hasAdmin(CommandSender sender, String action) {
    return AdminPermissions.allowed(sender::hasPermission, action);
  }

  public void requireAdmin(CommandSender sender, String action) {
    if (!hasAdmin(sender, action))
      throw new IllegalArgumentException("Administrator permission required: universalshop.admin." + AdminPermissions.scope(action));
  }

  private void usage(boolean valid, String syntax) {
    if (!valid) throw new IllegalArgumentException("Usage: " + syntax);
  }

  public void previewSellAll(Player p) throws Exception {
    requireAction(p, "sellall");
    requireAction(p, "sell");
    service.allowed(p);
    Map<String, Integer> items = service.sellBasket(p);
    if (items.isEmpty())
      throw new IllegalArgumentException("No sellable items were found in your storage slots.");
    long total = service.basketQuote(p, items);
    baskets.put(p.getUniqueId(), new Basket(items, total, catalogRevision));
    if (menus.sellAll(p, items, total)) return;
    tell(p, "&eInventory sale: " + items + ". Total: " + money(total));
    tell(
        p,
        "&eType /shop sellall confirm within 30 seconds. Named or modified items require an exact"
            + " matching product.");
  }

  @Override
  public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
    try {
      requireAction(sender, CommandPolicy.path(args));
      if (args.length == 0) {
        menus.browse(player(sender), "", 0);
        return true;
      }
      String sub = args[0].toLowerCase(Locale.ROOT);
      if (sub.equals("test") && Boolean.getBoolean("universalshop.test")) {
        requireAdmin(sender, "manage");
        new ServerTests(this).run(sender, args);
        return true;
      }
      if (sub.equals("help")) {
        showHelp(sender);
        return true;

      } else if (sub.equals("admin")) {
        adminCommand(sender, args);
      } else if (sub.equals("language")) {
        usage(args.length == 2, "/shop language <en|fr|auto>");
        Player p = player(sender);
        if (!p.hasPermission("universalshop.language")) throw new IllegalArgumentException("Language permission required.");
        if (!getConfig().getBoolean("player-language", true)) throw new IllegalArgumentException("Player language preferences are disabled.");
        texts.setLocale(p.getUniqueId(), args[1]);
        invalidateView(p);
        tell(p, texts.format(p, "language.changed", "&aLanguage: {language}", "language", texts.locale(p)));
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
      } else if (Arrays.asList("favorite", "favorites", "recent", "sort", "stats").contains(sub)) {
        management.personal(player(sender), args);
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
    if (args.length == 2 && args[1].equalsIgnoreCase("claim")) {
      int count = service.claimExpired(p);
      tell(p, texts.format(p, "market.claimed", "&aClaimed {count} item stacks and available seller earnings.", "count", String.valueOf(count)));
      return;
    }
    if (args.length == 2 && args[1].equalsIgnoreCase("notices")) {
      List<String> notices = service.consumeNotifications(p);
      if (notices.isEmpty()) tell(p, texts.format(p, "market.no-notices", "&7No new market notifications."));
      for (String notice : notices) tell(p, "&e" + notice);
      return;
    }
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
    if (args.length == 1) {
      requireAdmin(sender, "panel");
      menus.adminPanel(player(sender));
      return;
    }
    usage(
        args.length >= 2,
        "/shop admin <add|remove|price|stock|balance|history|reload|recovery|export>");
    String action = args[1].toLowerCase(Locale.ROOT);
    requireAdmin(sender, action.equals("manage")
        ? "manage." + (args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "status") : action);
    if (action.equals("editor")) {
      editor.open(player(sender));
      return;
    }
    if (action.equals("diagnose")) {
      for (String issue : CatalogDiagnostics.inspect(this)) tell(sender, "&e" + issue);
      for (String line : store.diagnostics()) tell(sender, "&7" + line);
      return;
    }
    if (action.equals("backup")) {
      tell(sender, "&aBackup saved: " + store.backup().getFileName());
      return;
    }
    if (action.equals("panel")) {
      menus.adminPanel(player(sender));
      return;
    }
    if (action.equals("manage")) {
      management.admin(sender, args);
      return;
    }
    if (action.equals("command")) {
      if (args.length == 2) {
        tell(sender, "&eDisabled commands: " + getConfig().getStringList("disabled-commands"));
        return;
      }
      usage(
          args.length == 4
              && (args[2].equalsIgnoreCase("enable") || args[2].equalsIgnoreCase("disable")),
          "/shop admin command enable|disable <path>");
      String path = CommandPolicy.normalize(args[3]);
      new CommandPolicy(Collections.singletonList(path));
      List<String> disabled = new ArrayList<String>();
      for (String value : getConfig().getStringList("disabled-commands"))
        disabled.add(CommandPolicy.normalize(value));
      if (args[2].equalsIgnoreCase("enable")) disabled.removeAll(Collections.singleton(path));
      else if (!disabled.contains(path)) disabled.add(path);
      YamlConfiguration next = new YamlConfiguration();
      next.loadFromString(SafeYaml.write(getConfig()));
      next.set("disabled-commands", disabled);
      AtomicFiles.write(getDataFolder().toPath().resolve("config.yml"), SafeYaml.write(next));
      getConfig().set("disabled-commands", disabled);
      commandPolicy = new CommandPolicy(disabled);
      invalidateViews();
      tell(sender, "&aDisabled commands: " + disabled);
      return;
    }
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
      if (args.length >= 3 && args[2].equalsIgnoreCase("csv")) {
        String filter = args.length > 3 ? join(args, 3) : "";
        Path path = getDataFolder().toPath().resolve("exports/audit-" + System.currentTimeMillis() + ".csv");
        AtomicFiles.write(path, store.auditCsv(filter));
        tell(sender, "&aExported " + path.getFileName());
        return;
      }
      String filter = "";
      int count = 10;
      int end = args.length;
      if (end > 2 && args[end - 1].matches("[0-9]+")) {
        count = Integer.parseInt(args[end - 1]);
        end--;
      }
      if (end > 2) filter = join(Arrays.copyOf(args, end), 2);
      List<String> history = store.auditHistory(filter, Math.max(1, Math.min(1000, count)));
      if (history.isEmpty()) tell(sender, "&7No matching transactions.");
      for (String line : history)
        tell(sender, "&7" + line);
      return;
    }
    if (action.equals("export")) {
      Path path =
          getDataFolder().toPath().resolve("exports/state-" + System.currentTimeMillis() + ".yml");
      AtomicFiles.write(path, SafeYaml.write(store.state()));
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
      String previous = SafeYaml.write(getConfig());
      long previousBalance = startingBalance;
      try {
        getConfig().loadFromString(SafeYaml.write(candidate));
        validateConfig();
        texts.reload();
        loadCatalog();
      } catch (Exception e) {
        getConfig().loadFromString(previous);
        startingBalance = previousBalance;
        commandPolicy = new CommandPolicy(getConfig().getStringList("disabled-commands"));
        management = new ShopManagement(this);
        throw e;
      }
      for (Player p : onlinePlayers())
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof ShopMenus.Menu)
          p.closeInventory();
      tell(sender, "&aConfiguration and catalog reloaded.");
      bedrock.sessions.clear();
      editor.clear();
      inputs.clear();
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
      invalidateViews();
      tell(sender, "&aStock updated.");
      return;
    }
    YamlConfiguration catalog = catalogCopy();
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
    } else if (action.equals("duplicate")) {
      usage(args.length == 4, "/shop admin duplicate <source> <new-id>");
      Product source = product(args[2]);
      ManagementPolicy.id(args[3]);
      if (catalog.contains("products." + args[3]))
        throw new IllegalArgumentException("This ID already exists.");
      ConfigurationSection original = catalog.getConfigurationSection("products." + source.id);
      ConfigurationSection copy = catalog.createSection("products." + args[3]);
      for (String key : original.getKeys(true))
        if (!original.isConfigurationSection(key)) copy.set(key, original.get(key));
    } else if (action.equals("rename") || action.equals("category")) {
      usage(args.length >= 4, "/shop admin " + action + " <id> <text>");
      Product source = product(args[2]);
      catalog.set(
          "products." + source.id + (action.equals("rename") ? ".name" : ".category"),
          join(args, 3));
    } else throw new IllegalArgumentException("Unknown admin command. Use /shop help.");
    saveCatalog(catalog);
    tell(sender, "&aCatalog saved. Previous file: shops.yml.bak");
  }

  public YamlConfiguration catalogCopy() throws Exception {
    YamlConfiguration catalog = new YamlConfiguration();
    catalog.load(new File(getDataFolder(), "shops.yml"));
    return catalog;
  }

  public void saveCatalog(YamlConfiguration catalog) throws Exception {
    Map<String, Product> checked = parseCatalog(catalog);
    Path file = getDataFolder().toPath().resolve("shops.yml");
    String previous = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
    AtomicFiles.write(file.resolveSibling("shops.yml.bak"), previous);
    AtomicFiles.write(file, SafeYaml.write(catalog));
    products = checked;
    catalogEditRevision++;
    invalidateViews();
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
              "language",
              "market"));
    if (args.length == 1) {
      if (management.enabled("personal"))
        values.addAll(Arrays.asList("favorite", "favorites", "recent", "sort"));
      if (management.enabled("analytics")) values.add("stats");
    }
    if (args.length == 1 && hasAdmin(sender, "panel")) values.add("admin");
    if (args.length == 2 && (args[0].equalsIgnoreCase("buy") || args[0].equalsIgnoreCase("sell")))
      values.addAll(products.keySet());
    if (args.length == 2
        && args[0].equalsIgnoreCase("admin")
        && hasAdmin(sender, "panel"))
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
              "export",
              "command",
              "manage",
              "panel",
              "duplicate",
              "rename",
              "category"));
    if (args.length == 2 && args[0].equalsIgnoreCase("admin") && hasAdmin(sender, "panel"))
      values.addAll(Arrays.asList("editor", "diagnose", "backup"));
    if (args.length == 3
        && args[0].equalsIgnoreCase("admin")
        && args[1].equalsIgnoreCase("manage")
        && hasAdmin(sender, "panel"))
      values.addAll(
          Arrays.asList(
              "status",
              "enable",
              "disable",
              "open",
              "close",
              "pause",
              "resume",
              "category-pause",
              "category-resume",
              "stock",
              "restock",
              "reset-demand",
              "stats",
              "report"));
    if (args.length == 4
        && args[0].equalsIgnoreCase("admin")
        && args[1].equalsIgnoreCase("manage")
        && hasAdmin(sender, "panel")) {
      if (args[2].equalsIgnoreCase("enable") || args[2].equalsIgnoreCase("disable"))
        values.addAll(ManagementPolicy.MODULES);
      if (Arrays.asList("pause", "resume", "restock", "reset-demand", "stats")
          .contains(args[2].toLowerCase(Locale.ROOT))) values.addAll(products.keySet());
    }
    if (args.length == 2 && args[0].equalsIgnoreCase("favorite") && management.enabled("personal"))
      values.addAll(products.keySet());
    if (args.length == 2 && args[0].equalsIgnoreCase("sort") && management.enabled("personal"))
      values.addAll(Arrays.asList("catalog", "name", "price"));
    if (args.length == 2 && args[0].equalsIgnoreCase("market"))
      values.addAll(Arrays.asList("list", "buy", "cancel", "claim", "notices"));
    if (args.length == 2 && args[0].equalsIgnoreCase("language"))
      values.addAll(Arrays.asList("en", "fr", "auto"));
    String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
    Iterator<String> it = values.iterator();
    while (it.hasNext()) {
      String value = it.next();
      String[] completed = args.clone();
      completed[completed.length - 1] = value;
      if (!value.toLowerCase(Locale.ROOT).startsWith(prefix)
          || !actionEnabled(sender, CommandPolicy.path(completed))
          || (completed.length >= 2 && completed[0].equalsIgnoreCase("admin")
              && !(completed[1].equalsIgnoreCase("manage") && completed.length == 2
                  ? hasAdmin(sender, "manage") || hasAdmin(sender, "stock") || hasAdmin(sender, "history")
                  : hasAdmin(sender, completed[1].equalsIgnoreCase("manage") && completed.length >= 3
                      ? "manage." + completed[2] : completed[1])))) it.remove();
    }
    return values;
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    bedrock.sessions.close(e.getPlayer().getUniqueId());
    baskets.remove(e.getPlayer().getUniqueId());
    service.forget(e.getPlayer().getUniqueId());
    inputs.cancel(e.getPlayer().getUniqueId());
    editor.forget(e.getPlayer().getUniqueId());
    menus.forget(e.getPlayer().getUniqueId());
  }

  @EventHandler
  public void join(final PlayerJoinEvent e) {
    getServer().getScheduler().runTaskLater(this, new Runnable() {
      public void run() {
        Player p = e.getPlayer();
        if (!p.isOnline()) return;
        int count = service.notificationCount(p);
        if (count > 0) tell(p, texts.format(p, "market.notifications", "&eYou have {count} market notifications. /shop market notices", "count", String.valueOf(count)));
      }
    }, 40L);
  }

  boolean actionEnabled(CommandSender sender, String action) {
    return sender instanceof ConsoleCommandSender || commandPolicy.enabled(action);
  }

  void requireAction(CommandSender sender, String action) {
    if (!actionEnabled(sender, action))
      throw new IllegalArgumentException(
          "This command/action is disabled by the server administrator: " + action);
  }

  long catalogRevision() {
    return catalogRevision;
  }

  long catalogEditRevision() { return catalogEditRevision; }

  public void invalidateViews() {
    catalogRevision++;
    baskets.clear();
    if (bedrock != null) bedrock.sessions.clear();
    if (inputs != null) inputs.clear();
    for (Player player : onlinePlayers())
      if (player.getOpenInventory().getTopInventory().getHolder() instanceof ShopMenus.Menu
          || player.getOpenInventory().getTopInventory().getHolder() instanceof CatalogEditor.Menu)
        player.closeInventory();
  }

  private void invalidateView(Player player) {
    baskets.remove(player.getUniqueId());
    if (inputs != null) inputs.cancel(player.getUniqueId());
    if (bedrock != null) bedrock.sessions.close(player.getUniqueId());
    if (player.getOpenInventory().getTopInventory().getHolder() instanceof ShopMenus.Menu
        || player.getOpenInventory().getTopInventory().getHolder() instanceof CatalogEditor.Menu)
      player.closeInventory();
  }

  private void showHelp(CommandSender sender) {
    String[][] entries = {
      {"shop", "/shop - browse"},
      {"search", "/shop search <text>"},
      {"category", "/shop category <name>"},
      {"deals", "/shop deals"},
      {"buy", "/shop buy <id> [amount]"},
      {"sell", "/shop sell <id> [amount]"},
      {"sellhand", "/shop sellhand [amount]"},
      {"sellall", "/shop sellall [confirm]"},
      {"balance", "/shop balance"},
      {"pay", "/shop pay <online player> <amount>"},
      {"market", "/shop market [page]"},
      {"market.list", "/shop market list <amount> <total price>"},
      {"market.buy", "/shop market buy <id> confirm"},
      {"market.cancel", "/shop market cancel <id> confirm"},
      {"market.claim", "/shop market claim - reclaim expired listings and seller earnings"},
      {"market.notices", "/shop market notices"},
      {"language", "/shop language <en|fr|auto>"},
      {"admin.command", "/shop admin command [enable|disable <path>]"},
      {"admin.manage", "/shop admin manage [action] - optional management modules"},
      {"admin.manage.stock", "/shop admin manage stock|restock|reset-demand [product]"},
      {"admin.manage.stats", "/shop admin manage stats|report [product]"},
      {"admin.panel", "/shop admin panel - management dashboard"},
      {"admin.editor", "/shop admin editor - edit the catalog in game"},
      {"admin.diagnose", "/shop admin diagnose - catalog and ledger diagnostics"},
      {"admin.backup", "/shop admin backup - ledger backup"},
      {"admin.duplicate", "/shop admin duplicate <source> <new-id>"},
      {"admin.rename", "/shop admin rename <id> <name>"},
      {"admin.category", "/shop admin category <id> <category>"},
      {"admin.reload", "/shop admin reload"},
      {"admin.add", "/shop admin add <id> <buy> <sell> [category]"},
      {"admin.remove", "/shop admin remove <id>"},
      {"admin.price", "/shop admin price <id> <buy> <sell>"},
      {"admin.stock", "/shop admin stock <id> <quantity>"},
      {"admin.balance", "/shop admin balance <online player|uuid> <amount>"},
      {"admin.history", "/shop admin history [filter] [lines] or csv [filter]"},
      {"admin.export", "/shop admin export"},
      {"admin.recovery", "/shop admin recovery [commit|abort] confirm"}
    };
    for (String[] entry : entries)
      if (actionEnabled(sender, entry[0])
          && (!entry[0].startsWith("admin.") || hasAdmin(sender, entry[0])))
        tell(sender, "&e" + entry[1]);
    if (management.enabled("personal"))
      for (String action : Arrays.asList("favorite", "favorites", "recent", "sort"))
        if (actionEnabled(sender, action))
          tell(
              sender,
              "&e/shop "
                  + action
                  + (action.equals("favorite")
                      ? " <product>"
                      : action.equals("sort") ? " <catalog|name|price>" : ""));
    if (management.enabled("analytics") && actionEnabled(sender, "stats"))
      tell(sender, "&e/shop stats");
  }

  @EventHandler(ignoreCancelled = true)
  public void signCreate(SignChangeEvent e) {
    if (!e.getLine(0).equalsIgnoreCase("[ushop]")) return;
    if (!hasAdmin(e.getPlayer(), "sign")) {
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
