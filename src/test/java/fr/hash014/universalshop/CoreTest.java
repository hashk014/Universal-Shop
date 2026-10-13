package fr.hash014.universalshop;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreTest {
  @TempDir Path temp;

  @Test
  void providerFloatingPointBoundary() {
    assertEquals(34567, Money.fromProvider(345.66999999999996));
    assertEquals(Money.MAX, Money.fromProvider(1e100));
  }

  @Test
  void invalidProviderAmount() {
    assertThrows(IllegalArgumentException.class, () -> Money.fromProvider(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> Money.fromProvider(-1));
  }

  @Test
  void exactCents() {
    assertEquals(101, Money.parse("1.01"));
    assertEquals("1.01", Money.format(101));
  }

  @Test
  void rejectSubCent() {
    assertThrows(ArithmeticException.class, () -> Money.parse("1.001"));
  }

  @Test
  void rejectNegative() {
    assertThrows(IllegalArgumentException.class, () -> Money.parse("-1"));
  }

  @Test
  void rejectNonFinite() {
    for (String s : Arrays.asList("NaN", "Infinity", "-Infinity"))
      assertThrows(NumberFormatException.class, () -> Money.parse(s));
  }

  @Test
  void overflow() {
    assertThrows(IllegalArgumentException.class, () -> Money.add(Money.MAX, 1));
    assertThrows(IllegalArgumentException.class, () -> Money.add(0, -1));
  }

  @Test
  void fixedPrices() {
    assertEquals(6400, Pricing.quote(100, 50, 0, 64, true, 0, 1, 1, 0, 0));
    assertEquals(3200, Pricing.quote(100, 50, 0, 64, false, 0, 1, 1, 0, 0));
  }

  @Test
  void noSplitAdvantage() {
    long total = Pricing.quote(200, 50, 0, 64, true, .01, .5, 2, 0, 0), split = 0;
    for (int i = 0; i < 64; i++) split += Pricing.quote(200, 50, i, 1, true, .01, .5, 2, 0, 0);
    assertEquals(total, split);
  }

  @Test
  void inverseTradeCannotPrintMoney() {
    for (int demand = -100; demand <= 100; demand += 10)
      for (int n : new int[] {1, 16, 64, 2304}) {
        long paid = Pricing.quote(200, 180, demand, n, true, .01, .5, 2, .99, 0);
        long received = Pricing.quote(200, 180, demand + n, n, false, .01, .5, 2, 0, 0);
        assertTrue(paid >= received);
      }
  }

  @Test
  void buyOnlyAndSellOnly() {
    assertThrows(
        IllegalArgumentException.class, () -> Pricing.quote(-1, 100, 0, 1, true, 0, 1, 1, 0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> Pricing.quote(100, -1, 0, 1, false, 0, 1, 1, 0, 0));
  }

  @Test
  void quantityValidation() {
    for (int count : new int[] {-1, 0, 2305})
      assertThrows(
          IllegalArgumentException.class,
          () -> Pricing.quote(100, 50, 0, count, true, 0, 1, 1, 0, 0));
  }

  @Test
  void taxRounding() {
    assertEquals(102, Pricing.quote(101, 101, 0, 1, true, 0, 1, 1, 0, .005));
    assertEquals(100, Pricing.quote(101, 101, 0, 1, false, 0, 1, 1, 0, .005));
  }

  @Test
  void priceBounds() {
    assertEquals(200, Pricing.quote(100, 50, Long.MAX_VALUE, 1, true, .1, .5, 2, 0, 0));
    assertEquals(50, Pricing.quote(100, 50, Long.MIN_VALUE, 1, true, .1, .5, 2, 0, 0));
  }

  @Test
  void stableRotation() {
    List<String> ids = Arrays.asList("a", "b", "c", "d", "e");
    Set<String> picked = Rotation.pick(ids, "seed", 12, 3);
    assertEquals(3, picked.size());
    assertEquals(picked, Rotation.pick(Arrays.asList("e", "d", "c", "b", "a"), "seed", 12, 3));
  }

  @Test
  void emptyAndOversizeRotation() {
    assertTrue(Rotation.pick(Collections.<String>emptyList(), "seed", 1, 9).isEmpty());
    assertEquals(2, Rotation.pick(Arrays.asList("a", "b"), "seed", 1, 9).size());
  }

  @Test
  void rotationChangesAcrossPeriods() {
    Set<Set<String>> periods = new HashSet<Set<String>>();
    for (int i = 0; i < 20; i++)
      periods.add(Rotation.pick(Arrays.asList("a", "b", "c", "d", "e"), "seed", i, 3));
    assertTrue(periods.size() > 1);
  }

  @Test
  void atomicOverwrite() throws Exception {
    Path file = temp.resolve("state.yml");
    AtomicFiles.write(file, "before");
    AtomicFiles.write(file, "after");
    assertEquals(
        "after", new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8));
    assertFalse(Files.exists(temp.resolve("state.yml.tmp")));
  }
}
