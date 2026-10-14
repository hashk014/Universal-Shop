package fr.hash014.universalshop;

import static org.junit.jupiter.api.Assertions.*;
import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;

class MenuTitlesTest {
  @Test
  void translatedColorsAndAccentsRenderAsInventoryTitles() {
    String title = ShopMenus.menuTitle("&8Boutique &6| Épées");
    assertEquals("Boutique | Épées", ChatColor.stripColor(title));
    assertTrue(title.startsWith("\u00a78"));
    assertFalse(title.contains("&6"));
  }

  @Test
  void legacyBoundsCannotLeaveAnOrphanColorOrHalfEmoji() {
    String prefix = new String(new char[31]).replace('\0', 'x');
    assertEquals(prefix, ShopMenus.menuTitle(prefix + "&6suffix"));
    assertEquals(prefix, ShopMenus.menuTitle(prefix + "\ud83d\udecd suffix"));
    assertEquals(32, ShopMenus.menuTitle(prefix + "Éextra").length());
  }
}
