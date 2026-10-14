package fr.hash014.universalshop;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MenuLayoutsTest {
  private MenuLayouts parse(String yaml) throws Exception {
    YamlConfiguration config = new YamlConfiguration(); config.loadFromString(yaml); return new MenuLayouts(config);
  }
  @Test void absentSettingsKeepExistingChestGeometry() throws Exception {
    MenuLayouts defaults = parse("");
    assertEquals(36, defaults.get("browse").capacity());
    assertEquals(54, defaults.get("browse").size);
    assertEquals(11, defaults.get("confirm").target(11));
    assertEquals(45, defaults.get("editor-list").capacity());
  }
  @Test void paginationAndControlsCanMoveIntoASmallerChest() throws Exception {
    MenuLayouts menus = parse("categories:\n  rows: 3\n  content-slots: [10, 11, 12, 13]\n  slots:\n    '45': 18\n    '49': 22\n    '53': 26\n");
    assertEquals(27, menus.get("categories").size);
    assertEquals(4, menus.get("categories").capacity());
    assertEquals(10, menus.get("categories").target(0));
    assertEquals(22, menus.get("categories").target(49));
  }
  @Test void controlSwapsAreResolvedTogether() throws Exception {
    MenuLayouts menus = parse("confirm:\n  slots:\n    '11': 15\n    '15': 11\n");
    assertEquals(15, menus.get("confirm").target(11));
    assertEquals(11, menus.get("confirm").target(15));
  }
  @Test void rejectsCollisionsAndInvalidGeometryRatherThanOverwritingButtons() {
    for (String yaml : Arrays.asList(
        "confirm:\n  slots:\n    '11': 13\n", "browse:\n  content-slots: [0, 0]\n",
        "browse:\n  content-slots: [49]\n", "browse:\n  rows: 3\n",
        "browse:\n  rows: 7\n", "browse:\n  rows: 3.5\n",
        "browse:\n  content-slots: []\n", "sort:\n  content-slots: [0]\n",
        "sort:\n  slots:\n    '999': 1\n", "broswe: {}\n",
        "sort:\n  slots:\n    '10': -1\n", "sort:\n  decorations:\n    - material: STONE\n      slots: [10]\n",
        "sort:\n  decorations:\n    - material: STONE\n      slots: [0, 0]\n",
        "sort:\n  slots: []\n", "sort:\n  row: 3\n")) {
      assertThrows(IllegalArgumentException.class, () -> parse(yaml), yaml);
    }
  }
  @Test void shippedConfigurationMatchesAllDefaults() throws Exception {
    java.io.InputStream resource = MenuLayoutsTest.class.getResourceAsStream("/menus.yml");
    assertNotNull(resource);
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.load(new java.io.InputStreamReader(resource, java.nio.charset.StandardCharsets.UTF_8));
    MenuLayouts menus = new MenuLayouts(yaml);
    assertEquals(36, menus.get("market").capacity());
    assertEquals(18, menus.get("admin").target(18));
  }
}
