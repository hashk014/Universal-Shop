package fr.hash014.universalshop;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LedgerLeaseTest {
  @TempDir Path directory;
  @Test void twoWritersCannotOwnTheSameDirectory() throws Exception {
    try (LedgerLease first = new LedgerLease(directory)) {
      assertThrows(java.io.IOException.class, () -> new LedgerLease(directory));
      try (LedgerLease independent = new LedgerLease(directory.resolve("independent"))) { assertNotNull(independent); }
    }
    try (LedgerLease reopened = new LedgerLease(directory)) { assertNotNull(reopened); }
  }
}
