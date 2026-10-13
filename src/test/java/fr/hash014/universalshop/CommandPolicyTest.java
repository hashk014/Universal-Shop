package fr.hash014.universalshop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CommandPolicyTest {
  @Test
  void parentDisablesChildren() {
    CommandPolicy p = new CommandPolicy(Arrays.asList("market"));
    assertFalse(p.enabled("market.buy"));
    assertFalse(p.enabled("market.cancel"));
    assertTrue(p.enabled("buy"));
  }

  @Test
  void childDoesNotDisableSiblings() {
    CommandPolicy p = new CommandPolicy(Arrays.asList("market.list"));
    assertFalse(p.enabled("market.list"));
    assertTrue(p.enabled("market.cancel"));
    assertTrue(p.enabled("market"));
  }

  @Test
  void normalizeAndRejectTypos() {
    assertFalse(new CommandPolicy(Arrays.asList(" Market List ")).enabled("market.list"));
    assertThrows(IllegalArgumentException.class, () -> new CommandPolicy(Arrays.asList("slel")));
  }

  @Test
  void rootDisablesEverything() {
    CommandPolicy p = new CommandPolicy(Arrays.asList("shop"));
    assertFalse(p.enabled("admin.reload"));
    assertFalse(p.enabled("buy"));
  }

  @Test
  void canonicalPaths() {
    assertEquals("shop", CommandPolicy.path(new String[0]));
    assertEquals("market", CommandPolicy.path(new String[] {"market", "2"}));
    assertEquals("market.buy", CommandPolicy.path(new String[] {"MARKET", "BUY", "id"}));
    assertEquals("search", CommandPolicy.path(new String[] {"diamonds"}));
  }
}
