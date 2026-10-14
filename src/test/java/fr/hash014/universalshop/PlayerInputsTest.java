package fr.hash014.universalshop;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PlayerInputsTest {
  @Test
  void boundedPrivateRepliesRejectControlAndFormattingPayloads() {
    for (String value : new String[] {"", "   ", "line\ncommand", "tab\tvalue", "\u0000", "\u00a7cprivate", new String(new char[129]).replace('\0', 'x')})
      assertThrows(IllegalArgumentException.class, () -> PlayerInputs.validate(value));
  }

  @Test
  void humanNamesAndEditableColorCodesRemainSupported() {
    assertEquals("Épée &bOcéan", PlayerInputs.validate("  Épée &bOcéan  "));
    assertEquals("*", PlayerInputs.validate("*"));
    assertEquals("2304", PlayerInputs.validate("2304"));
    String commands = new String(new char[400]).replace('\0', 'a');
    assertThrows(IllegalArgumentException.class, () -> PlayerInputs.validate(commands));
    assertEquals(commands, PlayerInputs.validate(commands, 4096));
  }
}
