package fr.hash014.universalshop;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Synthetic persistence measurement, not a Minecraft load/TPS assertion. */
class StoreBenchmarkTest {
  @TempDir Path temp;

  @Test @EnabledIfSystemProperty(named = "universalshop.storage-benchmark", matches = "true")
  void measureLargeLedgerAndSparseJournalCosts() throws Exception {
    final int wallets = 20000, measured = 12;
    YamlConfiguration fixture = new YamlConfiguration();
    fixture.set("schema", 1);
    for (int i = 0; i < wallets; i++)
      fixture.set("wallets." + String.format(Locale.ROOT, "%032x", i), 10000L + i);
    for (int i = 0; i < 500; i++) {
      fixture.set("products.product" + i + ".stock", 1000L);
      fixture.set("products.product" + i + ".demand", 50L);
    }
    String initial = fixture.saveToString();
    Path modernPath = temp.resolve("delta"), legacyPath = temp.resolve("legacy");
    AtomicFiles.write(modernPath.resolve("state.yml"), initial);
    AtomicFiles.write(legacyPath.resolve("state.yml"), initial);
    Store modern = new Store(modernPath);
    YamlConfiguration legacy = fixture;
    String wallet = "wallets." + String.format(Locale.ROOT, "%032x", 19);
    long[] legacyTimes = new long[measured], deltaTimes = new long[measured];
    long legacyBytes = 0, deltaBytes = 0;
    for (int i = -3; i < measured; i++) {
      long start = System.nanoTime();
      YamlConfiguration next = new YamlConfiguration();
      next.loadFromString(legacy.saveToString()); // The previous Store.copy implementation.
      next.set(wallet, 90000L + i);
      String id = UUID.randomUUID().toString();
      next.set("last-transaction", id);
      List<String> history = new ArrayList<String>(next.getStringList("history"));
      history.add(Instant.now() + " " + id + " BENCHMARK BUY stone");
      next.set("history", history);
      YamlConfiguration oldJournal = new YamlConfiguration();
      oldJournal.set("id", id);
      oldJournal.set("description", "BENCHMARK BUY stone");
      oldJournal.set("evidence", "Synthetic ledger-only fixture; no player effects.");
      oldJournal.set("before", legacy.saveToString());
      oldJournal.set("after", next.saveToString());
      String serializedJournal = oldJournal.saveToString();
      AtomicFiles.write(legacyPath.resolve("pending.yml"), serializedJournal);
      YamlConfiguration committed = new YamlConfiguration();
      committed.loadFromString(oldJournal.getString("after"));
      String serializedState = committed.saveToString();
      AtomicFiles.write(legacyPath.resolve("state.yml"), serializedState);
      Files.delete(legacyPath.resolve("pending.yml"));
      legacy = committed;
      long elapsed = System.nanoTime() - start;
      if (i >= 0) {
        legacyTimes[i] = elapsed;
        legacyBytes += serializedJournal.getBytes(StandardCharsets.UTF_8).length
            + serializedState.getBytes(StandardCharsets.UTF_8).length;
      }

      start = System.nanoTime();
      next = modern.copy();
      next.set(wallet, 90000L + i);
      modern.prepare(next, "BENCHMARK BUY stone", "Synthetic ledger-only fixture; no player effects.");
      long journalBytes = Files.size(modernPath.resolve("pending.yml"));
      String transactionId = next.getString("last-transaction");
      long transactionSequence = next.getLong("storage.sequence");
      modern.commit();
      elapsed = System.nanoTime() - start;
      if (i >= 0) {
        deltaTimes[i] = elapsed;
        deltaBytes += journalBytes + Files.size(new AuditLog(modernPath.resolve("audit")).path(transactionSequence, transactionId));
      }
    }
    long checkpointStart = System.nanoTime();
    modern.checkpoint();
    long checkpointNanos = System.nanoTime() - checkpointStart;
    long restartStart = System.nanoTime();
    Store restarted = new Store(modernPath);
    long restartNanos = System.nanoTime() - restartStart;
    assertEquals(90000L + measured - 1, restarted.state().getLong(wallet));
    assertEquals(measured + 3, restarted.auditHistory("BENCHMARK", 100).size());
    assertTrue(deltaBytes * 20 < legacyBytes, "Sparse persistence must avoid full-ledger I/O per trade.");
    String json = "{\n  \"fixture\": \"synthetic, single-process, no player effects\",\n"
        + "  \"measuredAt\": \"" + Instant.now() + "\",\n"
        + "  \"operatingSystem\": \"" + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\",\n"
        + "  \"jvm\": \"" + System.getProperty("java.vm.name") + " " + System.getProperty("java.version") + "\",\n"
        + "  \"scope\": \"copy + prepare + durable commit; legacy full snapshots versus delta audit; sequential local filesystem; not a Minecraft TPS/load test\",\n"
        + "  \"wallets\": " + wallets + ",\n  \"products\": 500,\n  \"measuredTrades\": " + measured + ",\n"
        + "  \"ledgerBytes\": " + initial.getBytes(StandardCharsets.UTF_8).length + ",\n"
        + "  \"legacyMedianMs\": " + millis(percentile(legacyTimes, .5)) + ",\n"
        + "  \"legacyP95Ms\": " + millis(percentile(legacyTimes, .95)) + ",\n"
        + "  \"deltaMedianMs\": " + millis(percentile(deltaTimes, .5)) + ",\n"
        + "  \"deltaP95Ms\": " + millis(percentile(deltaTimes, .95)) + ",\n"
        + "  \"legacyMeanBytesWritten\": " + legacyBytes / measured + ",\n"
        + "  \"deltaMeanBytesWritten\": " + deltaBytes / measured + ",\n"
        + "  \"checkpointMs\": " + millis(checkpointNanos) + ",\n"
        + "  \"checkpointRestartMs\": " + millis(restartNanos) + "\n}\n";
    Path report = Paths.get(".tools", "storage-benchmark.json");
    AtomicFiles.write(report, json);
    System.out.println("Storage benchmark: " + json);
  }

  private static long percentile(long[] values, double percentile) {
    long[] sorted = values.clone();
    Arrays.sort(sorted);
    return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * percentile) - 1)];
  }
  private static String millis(long nanos) { return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000d); }
}
