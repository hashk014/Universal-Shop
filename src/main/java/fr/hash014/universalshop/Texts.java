package fr.hash014.universalshop;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Editable language packs with persistent, optional player preferences. */
public final class Texts {
  private final UniversalShop plugin;
  private final Map<String, YamlConfiguration> packs = new HashMap<String, YamlConfiguration>();
  private final Map<String, List<Phrase>> phrases = new HashMap<String, List<Phrase>>();
  private YamlConfiguration preferences;

  private static final class Phrase {
    final Pattern pattern;
    final String translated;
    final List<String> names = new ArrayList<String>();

    Phrase(String source, String translated) {
      this.translated = translated;
      StringBuilder expression = new StringBuilder("^");
      Matcher tokens = Pattern.compile("\\{([^}]+)\\}").matcher(source);
      int start = 0;
      while (tokens.find()) {
        expression.append(Pattern.quote(source.substring(start, tokens.start()))).append("(.*?)");
        names.add(tokens.group(1));
        start = tokens.end();
      }
      pattern = Pattern.compile(expression.append(Pattern.quote(source.substring(start))).append('$').toString());
    }

    String apply(String value) {
      Matcher match = pattern.matcher(value);
      if (!match.matches()) return null;
      String result = translated;
      for (int i = 0; i < names.size(); i++) result = result.replace("{" + names.get(i) + "}", match.group(i + 1));
      return result;
    }
  }

  Texts(UniversalShop plugin) throws Exception {
    this.plugin = plugin;
    reload();
  }

  public void reload() throws Exception {
    Map<String, YamlConfiguration> next = new HashMap<String, YamlConfiguration>();
    Map<String, List<Phrase>> nextPhrases = new HashMap<String, List<Phrase>>();
    for (String language : Arrays.asList("en", "fr")) {
      String resource = "languages/" + language + ".yml";
      YamlConfiguration defaults;
      try (InputStream input = plugin.getResource(resource)) {
        if (input == null) throw new IllegalStateException("Missing language resource: " + resource);
        defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
      }
      File file = new File(plugin.getDataFolder(), resource);
      if (!file.exists()) plugin.saveResource(resource, false);
      YamlConfiguration pack = new YamlConfiguration();
      pack.load(file);
      pack.setDefaults(defaults);
      next.put(language, pack);
      List<Phrase> rules = new ArrayList<Phrase>();
      for (Map<?, ?> rule : pack.getMapList("phrases")) {
        Object source = rule.get("source"), target = rule.get("target");
        if (source instanceof String && target instanceof String)
          rules.add(new Phrase((String) source, (String) target));
      }
      nextPhrases.put(language, rules);
    }
    YamlConfiguration nextPreferences = new YamlConfiguration();
    File file = new File(plugin.getDataFolder(), "player-languages.yml");
    if (file.exists()) nextPreferences.load(file);
    packs.clear();
    packs.putAll(next);
    phrases.clear();
    phrases.putAll(nextPhrases);
    preferences = nextPreferences;
    language(plugin.getConfig().getString("language", "en"));
  }

  public static String language(String value) {
    String result = value.toLowerCase(Locale.ROOT).replace('_', '-').split("-")[0];
    if (!result.equals("en") && !result.equals("fr"))
      throw new IllegalArgumentException("Language must be en or fr.");
    return result;
  }

  public String locale(CommandSender sender) {
    String configured = language(plugin.getConfig().getString("language", "en"));
    if (!(sender instanceof Player) || !plugin.getConfig().getBoolean("player-language", true)) return configured;
    String preferred = preferences.getString(((Player) sender).getUniqueId().toString(), "");
    return preferred.isEmpty() ? configured : language(preferred);
  }

  public void setLocale(UUID player, String value) throws Exception {
    YamlConfiguration next = new YamlConfiguration();
    next.loadFromString(SafeYaml.write(preferences));
    next.set(player.toString(), value.equalsIgnoreCase("auto") ? null : language(value));
    AtomicFiles.write(plugin.getDataFolder().toPath().resolve("player-languages.yml"), SafeYaml.write(next));
    preferences = next;
  }

  public String format(String key, String fallback, String... replacements) {
    return format(null, key, fallback, replacements);
  }

  public String format(CommandSender sender, String key, String fallback, String... replacements) {
    String value = packs.get(locale(sender)).getString(key, fallback);
    return replace(value, replacements);
  }

  public static String replace(String value, String... replacements) {
    if (replacements.length % 2 != 0) throw new IllegalArgumentException("Text replacements require key/value pairs.");
    for (int i = 0; i < replacements.length; i += 2)
      value = value.replace("{" + replacements[i] + "}", replacements[i + 1]);
    return value;
  }

  /** Compatibility translations for messages emitted by existing command/service code. */
  public String translate(CommandSender sender, String value) {
    String locale = locale(sender);
    if (locale.equals("en")) return value;
    int colors = 0;
    while (value.length() > colors + 1 && (value.charAt(colors) == '&' || value.charAt(colors) == '\u00a7')
        && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(value.charAt(colors + 1)) >= 0) colors += 2;
    String prefix = value.substring(0, colors), plain = value.substring(colors);
    for (Phrase phrase : phrases.get(locale)) {
      String translated = phrase.apply(plain);
      if (translated != null) return prefix + translated;
    }
    return value;
  }
}
