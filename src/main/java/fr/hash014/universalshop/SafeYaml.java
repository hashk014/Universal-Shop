package fr.hash014.universalshop;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;

/** Prevent legacy SnakeYAML from misrepresenting Unicode String values as binary data. */
public final class SafeYaml {
  private SafeYaml() {}

  public static String write(org.bukkit.configuration.file.FileConfiguration source) {
    if (!(source instanceof YamlConfiguration)) throw new IllegalArgumentException("Expected a YAML configuration.");
    return write((YamlConfiguration) source);
  }

  public static String write(YamlConfiguration source) {
    Walker walker = new Walker();
    walker.inspect(source, 0);
    if (!walker.escape) return source.saveToString();
    walker.prefix();
    YamlConfiguration output = new YamlConfiguration();
    output.options().header(source.options().header());
    output.options().copyHeader(source.options().copyHeader());
    output.options().indent(source.options().indent());
    Map<?, ?> root = (Map<?, ?>) walker.convert(source, 0);
    for (Map.Entry<?, ?> entry : root.entrySet()) output.set((String) entry.getKey(), entry.getValue());
    String encoded = output.saveToString();
    Matcher matcher = Pattern.compile(Pattern.quote(walker.tokenPrefix) + "[0-9]+_END").matcher(encoded);
    StringBuffer result = new StringBuffer(encoded.length());
    Set<String> used = new HashSet<String>();
    while (matcher.find()) {
      String token = matcher.group();
      String original = walker.tokens.get(token);
      if (original == null) throw new IllegalArgumentException("Unrecognized Unicode YAML token.");
      used.add(token);
      matcher.appendReplacement(result, Matcher.quoteReplacement(quote(original)));
    }
    matcher.appendTail(result);
    if (used.size() != walker.tokens.size()) throw new IllegalArgumentException("A Unicode YAML value could not be represented safely.");
    return result.toString();
  }

  /** ASCII YAML escapes are understood by both the legacy and current loaders. */
  private static String quote(String value) {
    StringBuilder result = new StringBuilder("\"");
    for (int index = 0; index < value.length();) {
      int cp = value.codePointAt(index);
      index += Character.charCount(cp);
      if (cp == '"' || cp == '\\') result.append('\\').append((char) cp);
      else if (cp >= 0x20 && cp <= 0x7e) result.append((char) cp);
      else if (cp <= 0xffff) result.append(String.format(Locale.ROOT, "\\u%04x", cp));
      else result.append(String.format(Locale.ROOT, "\\U%08x", cp));
    }
    return result.append('"').toString();
  }

  private static boolean unsafe(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      // The old reader examines UTF-16 units and incorrectly rejects surrogate pairs.
      if (Character.isSurrogate(c) || (c < 0x20 && c != '\t' && c != '\n' && c != '\r')
          || c >= 0x7f && c < 0xa0 || c == 0xfffe || c == 0xffff) return true;
    }
    return false;
  }

  private static final class Walker {
    private final List<String> strings = new ArrayList<String>();
    private final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<Object, Boolean>();
    private final IdentityHashMap<ConfigurationSerializable, Map<String, Object>> serialized =
        new IdentityHashMap<ConfigurationSerializable, Map<String, Object>>();
    private final Map<String, String> tokens = new LinkedHashMap<String, String>();
    private String tokenPrefix;
    private boolean escape;

    void prefix() {
      boolean collision;
      do {
        tokenPrefix = "USHOP_YAML_" + UUID.randomUUID().toString().replace("-", "") + "_";
        collision = false;
        for (String value : strings) if (value.contains(tokenPrefix)) { collision = true; break; }
      } while (collision);
    }

    private Map<String, Object> serialized(ConfigurationSerializable value) {
      Map<String, Object> map = serialized.get(value);
      if (map == null) {
        String alias = ConfigurationSerialization.getAlias(value.getClass());
        if (alias == null || ConfigurationSerialization.getClassByAlias(alias) == null)
          throw new IllegalArgumentException("Unregistered YAML serialization alias: " + alias);
        map = new LinkedHashMap<String, Object>();
        map.put(ConfigurationSerialization.SERIALIZED_TYPE_KEY, alias);
        map.putAll(value.serialize());
        serialized.put(value, map);
      }
      return map;
    }

    private void enter(Object value, int depth) {
      if (depth > 128 || active.put(value, Boolean.TRUE) != null)
        throw new IllegalArgumentException("YAML value graph is cyclic or too deeply nested.");
    }

    void inspect(Object value, int depth) {
      if (value == null) return;
      if (value instanceof String) {
        strings.add((String) value);
        escape |= unsafe((String) value);
        return;
      }
      if (value instanceof ConfigurationSection) {
        enter(value, depth);
        inspect(((ConfigurationSection) value).getValues(false), depth + 1);
        active.remove(value);
      } else if (value instanceof ConfigurationSerializable) {
        enter(value, depth); inspect(serialized((ConfigurationSerializable) value), depth + 1); active.remove(value);
      } else if (value instanceof Map) {
        enter(value, depth);
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
          inspect(entry.getKey(), depth + 1); inspect(entry.getValue(), depth + 1);
        }
        active.remove(value);
      } else if (value instanceof Iterable) {
        enter(value, depth);
        for (Object entry : (Iterable<?>) value) inspect(entry, depth + 1);
        active.remove(value);
      } else if (value instanceof Object[]) {
        enter(value, depth);
        for (Object entry : (Object[]) value) inspect(entry, depth + 1);
        active.remove(value);
      } else if (!(value instanceof Number || value instanceof Boolean || value instanceof Character
          || value instanceof Date || value instanceof Enum || value instanceof byte[]
          || value instanceof int[] || value instanceof long[] || value instanceof short[]
          || value instanceof float[] || value instanceof double[] || value instanceof boolean[]
          || value instanceof char[])) {
        throw new IllegalArgumentException("Unsupported YAML value type: " + value.getClass().getName());
      }
    }

    Object convert(Object value, int depth) {
      if (value instanceof String && unsafe((String) value)) {
        String token = tokenPrefix + tokens.size() + "_END";
        tokens.put(token, (String) value);
        return token;
      }
      if (value instanceof ConfigurationSection) return convert(((ConfigurationSection) value).getValues(false), depth + 1);
      if (value instanceof ConfigurationSerializable) return convert(serialized((ConfigurationSerializable) value), depth + 1);
      if (value instanceof Map) {
        Map<Object, Object> result = new LinkedHashMap<Object, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
          result.put(convert(entry.getKey(), depth + 1), convert(entry.getValue(), depth + 1));
        return result;
      }
      if (value instanceof Set) {
        Set<Object> result = new LinkedHashSet<Object>();
        for (Object entry : (Set<?>) value) result.add(convert(entry, depth + 1));
        return result;
      }
      if (value instanceof Iterable) {
        List<Object> result = new ArrayList<Object>();
        for (Object entry : (Iterable<?>) value) result.add(convert(entry, depth + 1));
        return result;
      }
      if (value instanceof Object[]) {
        List<Object> result = new ArrayList<Object>();
        for (Object entry : (Object[]) value) result.add(convert(entry, depth + 1));
        return result;
      }
      return value; // Genuine byte[] retains its binary tag, independently of String values.
    }
  }
}
