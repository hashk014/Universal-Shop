package fr.hash014.universalshop;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DemandTest {
  @Test void demandRecoversSymmetricallyWithElapsedTime() {
    assertEquals(50, Demand.decayed(100, 0, 3600000, 1));
    assertEquals(-50, Demand.decayed(-100, 0, 3600000, 1));
    assertEquals(25, Demand.decayed(100, 0, 7200000, 1));
    assertEquals(0, Demand.decayed(1, 0, 3600000, 1));
  }
  @Test void disabledAndBackwardClocksKeepExactDemand() {
    assertEquals(Long.MAX_VALUE, Demand.decayed(Long.MAX_VALUE, 1, 0, 1));
    assertEquals(123, Demand.decayed(123, 0, Long.MAX_VALUE, 0));
    assertEquals(0, Demand.decayed(Long.MIN_VALUE, 0, Long.MAX_VALUE, 1));
  }
  @Test void activeSmallOrdersDoNotLoseOneDemandUnitAtEveryMillisecond() {
    assertEquals(1, Demand.decayed(1, 0, 1, 1));
    assertEquals(-1, Demand.decayed(-1, 0, 1, 1));
    long demand = 0;
    for (int i = 0; i < 100; i++) demand = Demand.decayed(demand, i, i + 1, 1) + 1;
    assertEquals(100, demand);
    assertEquals(1, Demand.decayed(1, 0, 3599999, 1));
    assertEquals(0, Demand.decayed(1, 0, 3600000, 1));
  }
  @Test void disjointWorldCatalogsAreExcludedFromDuplicateWarnings() {
    assertFalse(CatalogDiagnostics.worldsOverlap(java.util.Arrays.asList("world"), java.util.Arrays.asList("creative")));
    assertTrue(CatalogDiagnostics.worldsOverlap(java.util.Collections.emptyList(), java.util.Arrays.asList("world")));
  }
}
