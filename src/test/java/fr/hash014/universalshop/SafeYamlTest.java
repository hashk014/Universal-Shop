package fr.hash014.universalshop;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.configuration.serialization.SerializableAs;
import org.junit.jupiter.api.Test;

public class SafeYamlTest {
  @SerializableAs("UniversalShopSafeYamlFixture")
  public static final class Fixture implements ConfigurationSerializable {
    final Map<String, Object> values;
    public Fixture(Map<String, Object> values) { this.values = values; }
    public Map<String, Object> serialize() { return values; }
    public static Fixture deserialize(Map<String, Object> values) { return new Fixture(values); }
  }

  @Test
  void supplementaryStringsNestedKeysAndGenuineBinaryRoundTripWithoutMutation() throws Exception {
    String emoji = "\ud83d\udecd";
    byte[] binary = ("name " + emoji).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    YamlConfiguration source = new YamlConfiguration();
    Map<String, Object> map = new LinkedHashMap<String, Object>();
    map.put("key " + emoji, Arrays.asList("line \"quoted\" \\ " + emoji, binary));
    source.set("nested", map);
    source.set("root " + emoji, "\u0000" + emoji);
    source.set("looks-like-token", "USHOP_YAML_00000000000000000000000000000000_0_END");
    String saved = SafeYaml.write(source);
    assertTrue(saved.contains("\\U0001f6cd"));
    assertTrue(saved.contains("!!binary"));
    YamlConfiguration loaded = new YamlConfiguration(); loaded.loadFromString(saved);
    List<?> list = loaded.getList("nested.key " + emoji);
    assertEquals("line \"quoted\" \\ " + emoji, list.get(0));
    assertArrayEquals(binary, (byte[]) list.get(1));
    assertEquals("\u0000" + emoji, loaded.getString("root " + emoji));
    assertEquals(source.getString("looks-like-token"), loaded.getString("looks-like-token"));
    assertEquals("\u0000" + emoji, source.getString("root " + emoji));
    assertSame(binary, ((List<?>) map.get("key " + emoji)).get(1));
  }

  @Test
  void serializationAliasesRemainTypedAndBinaryIsNeverRewrittenAsText() throws Exception {
    ConfigurationSerialization.registerClass(Fixture.class);
    try {
      Map<String, Object> values = new LinkedHashMap<String, Object>();
      values.put("name", "Blade \ud83d\udecd");
      values.put("data", new byte[] {0, 1, 2, -1});
      YamlConfiguration source = new YamlConfiguration(); source.set("item", new Fixture(values));
      YamlConfiguration loaded = new YamlConfiguration(); loaded.loadFromString(SafeYaml.write(source));
      assertTrue(loaded.get("item") instanceof Fixture);
      Fixture item = (Fixture) loaded.get("item");
      assertEquals(values.get("name"), item.values.get("name"));
      assertArrayEquals((byte[]) values.get("data"), (byte[]) item.values.get("data"));
      assertEquals("Blade \ud83d\udecd", values.get("name"));
    } finally { ConfigurationSerialization.unregisterClass(Fixture.class); }
  }

  @Test
  void ordinaryDataKeepsTheExistingFastPathAndInvalidGraphsFailBeforeSaving() {
    YamlConfiguration plain = new YamlConfiguration(); plain.set("wallets.player", 1234L);
    assertEquals(plain.saveToString(), SafeYaml.write(plain));
    List<Object> cycle = new ArrayList<Object>(); cycle.add(cycle);
    plain.set("cycle", cycle);
    assertThrows(IllegalArgumentException.class, () -> SafeYaml.write(plain));
    plain.set("cycle", null); plain.set("unsupported", new Object());
    assertThrows(IllegalArgumentException.class, () -> SafeYaml.write(plain));
  }
}
