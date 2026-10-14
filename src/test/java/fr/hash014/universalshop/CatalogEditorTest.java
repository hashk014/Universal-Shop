package fr.hash014.universalshop;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CatalogEditorTest {
  @Test void PricesKeepExactCentsAndDisabledDirection() {
    assertEquals("1.01", CatalogEditor.parseField("buy", "1.01"));
    assertEquals("0.00", CatalogEditor.parseField("sell", "0"));
    assertEquals("-1", CatalogEditor.parseField("sell", "-1"));
    assertThrows(ArithmeticException.class, () -> CatalogEditor.parseField("buy", "1.001"));
    assertThrows(IllegalArgumentException.class, () -> CatalogEditor.parseField("buy", "-2"));
  }

  @Test void StockAndTimeBoundsCannotBeBypassed() {
    assertEquals(-1L, CatalogEditor.parseField("stock", "-1"));
    assertEquals(1000000000L, CatalogEditor.parseField("stock", "1000000000"));
    assertEquals(0L, CatalogEditor.parseField("dynamic.decay-hours", "0"));
    assertThrows(IllegalArgumentException.class, () -> CatalogEditor.parseField("stock", "1000000001"));
    assertThrows(IllegalArgumentException.class, () -> CatalogEditor.parseField("restock-hours", "876001"));
    assertThrows(IllegalArgumentException.class, () -> CatalogEditor.parseField("daily-buy-limit", "-1"));
    assertThrows(IllegalArgumentException.class, () -> CatalogEditor.parseField("dynamic.min", "0"));
    assertThrows(IllegalArgumentException.class, () -> CatalogEditor.parseField("dynamic.max", "Infinity"));
    assertThrows(IllegalArgumentException.class, () -> CatalogEditor.parseField("dynamic.step", "NaN"));
  }

  @Test void ConsoleCommandsRejectBlankAndMultilineDelivery() {
    assertEquals(Arrays.asList("xp 20 {player}", "say {uuid}"), CatalogEditor.parseField("commands", "/xp 20 {player} || say {uuid}"));
    assertEquals(Collections.emptyList(), CatalogEditor.parseField("commands", "-"));
    assertThrows(IllegalArgumentException.class, () -> CatalogEditor.parseField("commands", "say hello ||"));
    assertThrows(IllegalArgumentException.class, () -> CatalogEditor.parseField("commands", "say hello\nop player"));
  }

  @Test void OptionalFiltersCanBeClearedWithoutInvalidNodes() {
    assertEquals(Arrays.asList("world", "resource_world"), CatalogEditor.parseField("worlds", "world, resource_world,world"));
    assertEquals(Collections.emptyList(), CatalogEditor.parseField("worlds", "-"));
    assertEquals("", CatalogEditor.parseField("permission", "-"));
    assertThrows(IllegalArgumentException.class, () -> CatalogEditor.parseField("permission", "node with spaces"));
    assertThrows(IllegalArgumentException.class, () -> CatalogEditor.parseField("name", "\n"));
  }
}
