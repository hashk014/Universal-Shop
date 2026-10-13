package fr.hash014.universalshop;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ManagementPolicyTest {
  final YamlConfiguration config = new YamlConfiguration(), state = new YamlConfiguration();
  final UUID player = UUID.randomUUID();
  final Instant now = Instant.parse("2026-10-12T12:00:00Z");

  void set(String path, Object value) {
    config.set("management." + path, value);
  }

  ManagementPolicy policy() throws Exception {
    return new ManagementPolicy(config);
  }

  void record(ManagementPolicy p, int count, long total, boolean buy, Instant at) {
    p.record(state, player, "stone", "Building", count, total, buy, at);
  }

  @Test
  void defaultsHaveNoSideEffects() throws Exception {
    ManagementPolicy p = policy();
    record(p, 64, 12800, true, now);
    assertTrue(state.getKeys(true).isEmpty());
    assertTrue(p.open(now));
    assertEquals(0, p.discount(state, player, "stone", "Building", s -> true, now));
  }

  @Test
  void maintenancePreventsAnyCounterChange() throws Exception {
    set("access.enabled", true);
    set("access.maintenance", true);
    set("analytics.enabled", true);
    assertThrows(IllegalArgumentException.class, () -> record(policy(), 1, 100, true, now));
    assertTrue(state.getKeys(true).isEmpty());
  }

  @Test
  void normalWindowIsStartInclusiveEndExclusive() throws Exception {
    set("access.enabled", true);
    set("access.schedule-enabled", true);
    set("access.opens", "12:00");
    set("access.closes", "13:00");
    ManagementPolicy p = policy();
    assertTrue(p.open(now));
    assertFalse(p.open(now.minusSeconds(1)));
    assertFalse(p.open(now.plusSeconds(3600)));
  }

  @Test
  void overnightUsesOpeningDay() throws Exception {
    set("access.enabled", true);
    set("access.schedule-enabled", true);
    set("access.days", Arrays.asList("MONDAY"));
    set("access.opens", "22:00");
    set("access.closes", "02:00");
    ManagementPolicy p = policy();
    assertTrue(p.open(Instant.parse("2026-10-13T01:59:59Z")));
    assertFalse(p.open(Instant.parse("2026-10-13T02:00:00Z")));
    assertFalse(p.open(Instant.parse("2026-10-12T01:00:00Z")));
  }

  @Test
  void scheduleUsesConfiguredTimezone() throws Exception {
    set("access.enabled", true);
    set("access.schedule-enabled", true);
    set("access.timezone", "Europe/Paris");
    set("access.opens", "14:00");
    set("access.closes", "15:00");
    assertTrue(policy().open(now));
    assertFalse(policy().open(now.plusSeconds(3600)));
  }

  @Test
  void directionAndCategoryPausesAreIndependent() throws Exception {
    set("product-rules.enabled", true);
    set("product-rules.entries.stone.buy-enabled", false);
    assertFalse(policy().direction("stone", "Building", true));
    assertTrue(policy().direction("stone", "Building", false));
    set("product-rules.paused-categories", Arrays.asList("building"));
    assertFalse(policy().direction("stone", "Building", false));
  }

  @Test
  void orderBoundsRejectWithoutChangingLedger() throws Exception {
    set("product-rules.enabled", true);
    set("product-rules.entries.stone.min-order", 4);
    set("product-rules.entries.stone.max-order", 16);
    ManagementPolicy p = policy();
    assertThrows(IllegalArgumentException.class, () -> record(p, 3, 100, true, now));
    assertThrows(IllegalArgumentException.class, () -> record(p, 17, 100, true, now));
    record(p, 4, 100, true, now);
    assertTrue(state.getKeys(true).isEmpty());
  }

  @Test
  void globalQuotaIsSharedAndResetsAtUtcMidnight() throws Exception {
    set("product-rules.enabled", true);
    set("product-rules.entries.stone.global-daily-buy", 10);
    ManagementPolicy p = policy();
    record(p, 6, 1200, true, now);
    assertThrows(
        IllegalArgumentException.class,
        () -> p.record(state, UUID.randomUUID(), "stone", "Building", 5, 1000, true, now));
    assertEquals(6, state.getLong("management.global.stone.buy"));
    record(p, 10, 2000, true, now.plusSeconds(86400));
    assertEquals(10, state.getLong("management.global.stone.buy"));
  }

  @Test
  void cooldownSurvivesNewPolicyInstance() throws Exception {
    set("product-rules.enabled", true);
    set("product-rules.entries.stone.cooldown-seconds", 60);
    record(policy(), 1, 200, true, now);
    assertThrows(
        IllegalArgumentException.class, () -> record(policy(), 1, 200, true, now.plusSeconds(59)));
    record(policy(), 1, 200, true, now.plusSeconds(60));
  }

  @Test
  void budgetsCountCurrencyAndSeparateDirections() throws Exception {
    set("budgets.enabled", true);
    set("budgets.daily-spend", 5);
    set("budgets.daily-earn", 1);
    ManagementPolicy p = policy();
    record(p, 2, 400, true, now);
    assertThrows(IllegalArgumentException.class, () -> record(p, 1, 200, true, now));
    record(p, 2, 100, false, now);
    assertThrows(IllegalArgumentException.class, () -> record(p, 1, 50, false, now));
    record(p, 2, 400, true, now.plusSeconds(86400));
  }

  @Test
  void campaignTargetsPermissionAndTime() throws Exception {
    set("campaigns.enabled", true);
    String c = "campaigns.entries.weekend.";
    set(c + "products", Arrays.asList("stone"));
    set(c + "permission", "shop.special");
    set(c + "starts", now.toString());
    set(c + "ends", now.plusSeconds(60).toString());
    set(c + "discount-percent", 20);
    ManagementPolicy p = policy();
    assertEquals(.2, p.discount(state, player, "stone", "Building", s -> true, now));
    assertEquals(0, p.discount(state, player, "stone", "Building", s -> false, now));
    assertEquals(0, p.discount(state, player, "dirt", "Building", s -> true, now));
    assertEquals(0, p.discount(state, player, "stone", "Building", s -> true, now.plusSeconds(60)));
  }

  @Test
  void loyaltyUsesPreviouslyRecordedSpending() throws Exception {
    set("loyalty.enabled", true);
    Map<String, Object> tier = new HashMap<String, Object>();
    tier.put("spent", 5);
    tier.put("discount-percent", 10);
    set("loyalty.tiers", Arrays.asList(tier));
    ManagementPolicy p = policy();
    assertEquals(0, p.discount(state, player, "stone", "Building", s -> true, now));
    record(p, 3, 600, true, now);
    assertEquals(.1, p.discount(state, player, "stone", "Building", s -> true, now));
    record(p, 3, 150, false, now);
    assertEquals(600, state.getLong("management.stats.players." + player + ".spent"));
  }

  @Test
  void analyticsTracksUnitsMoneyAndTradeLines() throws Exception {
    set("analytics.enabled", true);
    ManagementPolicy p = policy();
    record(p, 64, 12800, true, now);
    record(p, 32, 1600, false, now);
    String path = "management.stats.products.stone";
    assertEquals(2, state.getLong(path + ".trades"));
    assertEquals(64, state.getLong(path + ".bought"));
    assertEquals(32, state.getLong(path + ".sold"));
    assertEquals(12800, state.getLong(path + ".spent"));
    assertEquals(1600, state.getLong(path + ".earned"));
  }

  @Test
  void recentHistoryIsBoundedAndDeduplicated() throws Exception {
    set("personal.enabled", true);
    ManagementPolicy p = policy();
    for (int i = 0; i < 25; i++) p.record(state, player, "item" + i, "Test", 1, 100, true, now);
    p.record(state, player, "item10", "Test", 1, 100, true, now);
    List<String> recent = state.getStringList("management.personal." + player + ".recent");
    assertEquals(20, recent.size());
    assertEquals("item10", recent.get(0));
    assertEquals(20, new HashSet<String>(recent).size());
  }

  @Test
  void invalidConfigurationFailsInsteadOfSilentlyEnabling() throws Exception {
    set("analytics.enabled", "maybe");
    assertThrows(IllegalArgumentException.class, () -> policy());
    set("analytics.enabled", true);
    set("product-rules.entries.stone.min-order", 10);
    set("product-rules.entries.stone.max-order", 2);
    assertThrows(IllegalArgumentException.class, () -> policy());
  }

  @Test
  void stateRoundTripPreservesQuotasAndStatistics() throws Exception {
    set("budgets.enabled", true);
    set("analytics.enabled", true);
    set("budgets.daily-spend", 2);
    record(policy(), 1, 200, true, now);
    YamlConfiguration restored = new YamlConfiguration();
    restored.loadFromString(state.saveToString());
    assertThrows(
        IllegalArgumentException.class,
        () -> policy().record(restored, player, "stone", "Building", 1, 200, true, now));
    assertEquals(200, restored.getLong("management.stats.products.stone.spent"));
  }

  @Test
  void upgradeRetainsResourceDefaultsWithoutOverwritingUserSettings() throws Exception {
    YamlConfiguration defaults = new YamlConfiguration();
    Map<String, Object> tier = new HashMap<String, Object>();
    tier.put("spent", 1);
    tier.put("discount-percent", 3);
    defaults.set("management.loyalty.tiers", Arrays.asList(tier));
    config.setDefaults(defaults);
    set("loyalty.enabled", true);
    ManagementPolicy p = policy();
    record(p, 1, 200, true, now);
    assertEquals(.03, p.discount(state, player, "stone", "Building", s -> true, now));
    assertFalse(config.saveToString().contains("discount-percent"));
  }
}
