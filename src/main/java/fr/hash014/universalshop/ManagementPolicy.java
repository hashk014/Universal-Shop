package fr.hash014.universalshop;

import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Optional server-shop rules. Callers stage counters in the same journal as the trade. */
public final class ManagementPolicy {
  public static final List<String> MODULES =
      Collections.unmodifiableList(
          Arrays.asList(
              "access",
              "product-rules",
              "budgets",
              "campaigns",
              "loyalty",
              "sale-protection",
              "analytics",
              "personal",
              "stock-alerts"));
  final YamlConfiguration config = new YamlConfiguration();
  final ZoneId zone;

  public ManagementPolicy(org.bukkit.configuration.file.FileConfiguration source) throws Exception {
    config.loadFromString(source.saveToString());
    if (source.getDefaults() != null) config.setDefaults(source.getDefaults());
    for (String module : MODULES) bool(module + ".enabled");
    for (String flag :
        Arrays.asList(
            "access.maintenance",
            "access.schedule-enabled",
            "sale-protection.named",
            "sale-protection.lore",
            "sale-protection.enchanted",
            "sale-protection.damaged")) bool(flag);
    for (String path : Arrays.asList("product-rules.entries", "campaigns.entries"))
      if (config.contains("management." + path) && section(path) == null)
        throw new IllegalArgumentException("Expected a section: management." + path);
    zone = ZoneId.of(config.getString("management.access.timezone", "UTC"));
    LocalTime.parse(config.getString("management.access.opens", "00:00"));
    LocalTime.parse(config.getString("management.access.closes", "00:00"));
    for (String day : config.getStringList("management.access.days"))
      DayOfWeek.valueOf(day.toUpperCase(Locale.ROOT));
    money("budgets.daily-spend", 0);
    money("budgets.daily-earn", 0);
    number("personal.max-favorites", 24, 1, 100);
    number("stock-alerts.threshold-percent", 20, 0, 100);
    number("stock-alerts.cooldown-seconds", 300, 1, 86400);
    ConfigurationSection rules = section("product-rules.entries");
    if (rules != null)
      for (String id : rules.getKeys(false)) {
        id(id);
        String key = "product-rules.entries." + id;
        if (rules.getConfigurationSection(id) == null)
          throw new IllegalArgumentException("Expected product rule section: " + id);
        for (String flag : Arrays.asList("paused", "buy-enabled", "sell-enabled"))
          bool(key + "." + flag);
        long min = number(key + ".min-order", 1, 1, 2304),
            max = number(key + ".max-order", 2304, 1, 2304);
        if (max < min)
          throw new IllegalArgumentException("Maximum order is below minimum for " + id);
        number(key + ".cooldown-seconds", 0, 0, 31536000);
        number(key + ".global-daily-buy", 0, 0, 1000000000);
        number(key + ".global-daily-sell", 0, 0, 1000000000);
      }
    ConfigurationSection campaigns = section("campaigns.entries");
    if (campaigns != null)
      for (String id : campaigns.getKeys(false)) {
        id(id);
        String key = "campaigns.entries." + id;
        if (campaigns.getConfigurationSection(id) == null)
          throw new IllegalArgumentException("Expected campaign section: " + id);
        bool(key + ".enabled");
        percent(key + ".discount-percent");
        if (!Instant.parse(text(key + ".ends", ""))
            .isAfter(Instant.parse(text(key + ".starts", ""))))
          throw new IllegalArgumentException("Campaign end must follow its start: " + id);
      }
    for (Map<?, ?> tier : config.getMapList("management.loyalty.tiers")) {
      Money.parse(String.valueOf(tier.get("spent")));
      double value = Double.parseDouble(String.valueOf(tier.get("discount-percent")));
      if (!Double.isFinite(value) || value < 0 || value > 100)
        throw new IllegalArgumentException("Invalid loyalty discount.");
    }
  }

  static void id(String id) {
    if (!id.matches("[a-z0-9_-]{1,48}")) throw new IllegalArgumentException("Invalid ID: " + id);
  }

  private void bool(String path) {
    Object value = config.get("management." + path);
    if (value != null && !(value instanceof Boolean))
      throw new IllegalArgumentException("Expected true or false: management." + path);
  }

  boolean enabled(String module) {
    return config.getBoolean("management." + module + ".enabled", false);
  }

  String text(String path, String fallback) {
    return config.getString("management." + path, fallback);
  }

  ConfigurationSection section(String path) {
    return config.getConfigurationSection("management." + path);
  }

  long number(String path, long fallback, long min, long max) {
    Object raw = config.get("management." + path, fallback);
    long value;
    try {
      value = Long.parseLong(String.valueOf(raw));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Expected an integer: management." + path);
    }
    if (value < min || value > max)
      throw new IllegalArgumentException("Out of range: management." + path);
    return value;
  }

  long money(String path, long fallback) {
    return Money.parse(String.valueOf(config.get("management." + path, fallback)));
  }

  double percent(String path) {
    double value = Double.parseDouble(String.valueOf(config.get("management." + path, 0)));
    if (!Double.isFinite(value) || value < 0 || value > 100)
      throw new IllegalArgumentException("Invalid percentage: " + path);
    return value;
  }

  public boolean open(Instant now) {
    if (!enabled("access")) return true;
    if (config.getBoolean("management.access.maintenance", false)) return false;
    if (!config.getBoolean("management.access.schedule-enabled", false)) return true;
    ZonedDateTime local = now.atZone(zone);
    LocalTime at = local.toLocalTime();
    LocalTime from = LocalTime.parse(text("access.opens", "00:00")),
        to = LocalTime.parse(text("access.closes", "00:00"));
    DayOfWeek day = local.getDayOfWeek();
    boolean within;
    if (from.equals(to)) within = true;
    else if (from.isBefore(to)) within = !at.isBefore(from) && at.isBefore(to);
    else {
      within = !at.isBefore(from) || at.isBefore(to);
      if (at.isBefore(to)) day = day.minus(1);
    }
    List<String> days = config.getStringList("management.access.days");
    boolean allowedDay = days.isEmpty();
    for (String value : days) if (value.equalsIgnoreCase(day.name())) allowedDay = true;
    return within && allowedDay;
  }

  boolean direction(String id, String category, boolean buy) {
    if (!enabled("product-rules")) return true;
    for (String value : config.getStringList("management.product-rules.paused-categories"))
      if (value.equalsIgnoreCase(category)) return false;
    String path = "management.product-rules.entries." + id;
    return !config.getBoolean(path + ".paused", false)
        && config.getBoolean(path + (buy ? ".buy-enabled" : ".sell-enabled"), true);
  }

  public double discount(
      YamlConfiguration state,
      UUID player,
      String product,
      String category,
      Predicate<String> permission,
      Instant now) {
    double best = 0;
    if (enabled("campaigns")) {
      ConfigurationSection campaigns = section("campaigns.entries");
      if (campaigns != null)
        for (String id : campaigns.getKeys(false)) {
          ConfigurationSection c = campaigns.getConfigurationSection(id);
          if (!c.getBoolean("enabled", true)) continue;
          List<String> products = c.getStringList("products"),
              categories = c.getStringList("categories");
          boolean target = products.isEmpty() && categories.isEmpty() || products.contains(product);
          for (String name : categories) if (name.equalsIgnoreCase(category)) target = true;
          String perm = c.getString("permission", "");
          if (target
              && (perm.isEmpty() || permission.test(perm))
              && !now.isBefore(Instant.parse(c.getString("starts")))
              && now.isBefore(Instant.parse(c.getString("ends"))))
            best = Math.max(best, percent("campaigns.entries." + id + ".discount-percent"));
        }
    }
    if (enabled("loyalty"))
      for (Map<?, ?> tier : config.getMapList("management.loyalty.tiers"))
        if (state.getLong("management.stats.players." + player + ".spent", 0)
            >= Money.parse(String.valueOf(tier.get("spent"))))
          best = Math.max(best, Double.parseDouble(String.valueOf(tier.get("discount-percent"))));
    return best / 100.0;
  }

  private void day(YamlConfiguration state, String path, String date) {
    if (!date.equals(state.getString(path + ".date"))) {
      state.set(path, null);
      state.set(path + ".date", date);
    }
  }

  private void add(YamlConfiguration state, String path, long value) {
    state.set(path, Math.addExact(state.getLong(path, 0), value));
  }

  private void capped(YamlConfiguration state, String path, long value, long cap, String message) {
    long next = Math.addExact(state.getLong(path, 0), value);
    if (cap > 0 && next > cap) throw new IllegalArgumentException(message);
    state.set(path, next);
  }

  public void record(
      YamlConfiguration next,
      UUID player,
      String product,
      String category,
      int quantity,
      long total,
      boolean buy,
      Instant now) {
    if (quantity < 1 || total < 0)
      throw new IllegalArgumentException("Invalid management transaction.");
    if (!open(now))
      throw new IllegalArgumentException(
          text("access.message", "The server shop is currently closed."));
    if (!direction(product, category, buy))
      throw new IllegalArgumentException("Trading is paused for this product or direction.");
    String date = now.atZone(ZoneOffset.UTC).toLocalDate().toString();
    if (enabled("product-rules")) {
      String rule = "product-rules.entries." + product;
      if (quantity < number(rule + ".min-order", 1, 1, 2304)
          || quantity > number(rule + ".max-order", 2304, 1, 2304))
        throw new IllegalArgumentException("Quantity is outside this product's order limits.");
      long cooldown = number(rule + ".cooldown-seconds", 0, 0, 31536000) * 1000;
      String last = "management.cooldowns." + player + "." + product;
      if (cooldown > 0) {
        if (next.contains(last) && now.toEpochMilli() - next.getLong(last) < cooldown)
          throw new IllegalArgumentException("This product is on cooldown for you.");
        next.set(last, now.toEpochMilli());
      }
      long cap =
          number(rule + (buy ? ".global-daily-buy" : ".global-daily-sell"), 0, 0, 1000000000);
      if (cap > 0) {
        String path = "management.global." + product;
        day(next, path, date);
        capped(
            next,
            path + (buy ? ".buy" : ".sell"),
            quantity,
            cap,
            "The server-wide daily product quota has been reached.");
      }
    }
    if (enabled("budgets")) {
      String path = "management.daily." + player;
      day(next, path, date);
      capped(
          next,
          path + (buy ? ".spent" : ".earned"),
          total,
          money(buy ? "budgets.daily-spend" : "budgets.daily-earn", 0),
          "Your daily shop budget has been reached.");
    }
    if (enabled("analytics") || enabled("loyalty")) {
      for (String path :
          Arrays.asList(
              "management.stats.players." + player, "management.stats.products." + product)) {
        add(next, path + ".trades", 1);
        add(next, path + (buy ? ".bought" : ".sold"), quantity);
        add(next, path + (buy ? ".spent" : ".earned"), total);
      }
    }
    if (enabled("personal")) {
      String path = "management.personal." + player + ".recent";
      List<String> recent = new ArrayList<String>(next.getStringList(path));
      recent.remove(product);
      recent.add(0, product);
      while (recent.size() > 20) recent.remove(recent.size() - 1);
      next.set(path, recent);
    }
  }
}
