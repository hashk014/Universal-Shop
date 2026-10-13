package fr.hash014.universalshop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FormSessionsTest {
  @Test
  void onlyOneResponse() {
    FormSessions s = new FormSessions();
    UUID p = UUID.randomUUID(), t = s.open(p);
    assertTrue(s.consume(p, t, 200, 100));
    assertFalse(s.consume(p, t, 200, 100));
  }

  @Test
  void replacedAndExpiredResponses() {
    FormSessions s = new FormSessions();
    UUID p = UUID.randomUUID(), old = s.open(p), fresh = s.open(p);
    assertFalse(s.consume(p, old, 200, 100));
    assertFalse(s.consume(p, fresh, 100, 101));
    assertFalse(s.consume(p, fresh, 200, 101));
  }

  @Test
  void reloadAndOtherPlayerCannotUseToken() {
    FormSessions s = new FormSessions();
    UUID p = UUID.randomUUID(), t = s.open(p);
    assertFalse(s.consume(UUID.randomUUID(), t, 200, 100));
    s.clear();
    assertFalse(s.consume(p, t, 200, 100));
  }
}
