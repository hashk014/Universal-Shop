package fr.hash014.universalshop;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreTest {
  @TempDir Path temp;
  private static final String PLAYER = "e265a00d-5cc3-466e-b0db-8b65ca415284";

  private Store store() throws Exception { return new Store(temp); }

  private void save(Store store, long amount, String description) throws Exception {
    YamlConfiguration next = store.copy();
    next.set("wallets." + PLAYER, amount);
    store.save(next, description);
  }

  private List<Path> records() throws IOException {
    try (Stream<Path> paths = Files.walk(temp.resolve("audit"))) {
      List<Path> result = new ArrayList<Path>();
      paths.filter(p -> p.toString().endsWith(".yml")).forEach(result::add);
      Collections.sort(result);
      return result;
    }
  }

  @Test void restartReplaysCommittedDeltaAndIndependentCopies() throws Exception {
    Store first = store();
    save(first, 105, "BUY " + PLAYER + " stone cents=105");
    save(first, 87, "SELL " + PLAYER + " stone cents=18");
    Store restarted = store();
    assertEquals(87, restarted.state().getLong("wallets." + PLAYER));
    assertEquals(2, restarted.auditHistory("", 10).size());
    assertEquals(2, restarted.state().getStringList("history").size());
    YamlConfiguration independent = restarted.copy();
    independent.set("wallets." + PLAYER, 0);
    independent.getStringList("history").clear();
    assertEquals(87, restarted.state().getLong("wallets." + PLAYER));
    assertEquals(2, restarted.state().getStringList("history").size());
  }

  @Test void externalPreparationRemainsQuarantinedAcrossRestarts() throws Exception {
    Store first = store();
    YamlConfiguration next = first.copy();
    next.set("wallets." + PLAYER, 77);
    first.prepare(next, "BUY " + PLAYER, "Ledger-only change.");
    Store restarted = store();
    assertTrue(restarted.blocked()); // Text evidence cannot masquerade as the explicit save() flag.
    assertFalse(restarted.state().contains("wallets." + PLAYER));
    assertThrows(IllegalStateException.class, () -> restarted.save(restarted.copy(), "another"));
    restarted.reconcile(true);
    assertEquals(77, store().state().getLong("wallets." + PLAYER));
    assertTrue(store().auditHistory("", 10).get(0).contains("RECOVERED_COMMIT"));
    assertEquals(1, records().size());
    try (Stream<Path> paths = Files.list(temp.resolve("recovery"))) { assertEquals(1, paths.count()); }
  }

  @Test void recoveryAbortRecordsDecisionWithoutChangingWallet() throws Exception {
    Store first = store();
    save(first, 99, "seed");
    YamlConfiguration next = first.copy();
    next.set("wallets." + PLAYER, 1);
    first.prepare(next, "uncertain external debit", "before/after inventory fixture");
    Store restarted = store();
    restarted.reconcile(false);
    assertEquals(99, store().state().getLong("wallets." + PLAYER));
    assertTrue(store().auditHistory("", 10).get(0).contains("RECOVERED_ABORT"));
    assertFalse(store().blocked());
  }

  @Test void crashAfterPublicationBeforePendingDeletionCannotDuplicateCommit() throws Exception {
    Store first = store();
    YamlConfiguration next = first.copy();
    next.set("wallets." + PLAYER, 45);
    first.prepare(next, "BUY " + PLAYER + " product=stone", "inventory effects complete");
    byte[] prepared = Files.readAllBytes(temp.resolve("pending.yml"));
    first.commit();
    Files.write(temp.resolve("pending.yml"), prepared); // Storage image at the cleanup crash boundary.
    Store restarted = store();
    assertFalse(restarted.blocked());
    assertEquals(45, restarted.state().getLong("wallets." + PLAYER));
    assertEquals(1, store().auditHistory("", 10).size());
    assertEquals(1, store().state().getStringList("history").size());
  }

  @Test void crashAfterAbortPublicationCannotReapplyPreparedWallet() throws Exception {
    Store first = store();
    YamlConfiguration next = first.copy();
    next.set("wallets." + PLAYER, 45);
    first.prepare(next, "provider declined", "no inventory effect");
    byte[] prepared = Files.readAllBytes(temp.resolve("pending.yml"));
    first.abort();
    Files.write(temp.resolve("pending.yml"), prepared);
    assertFalse(store().blocked());
    assertFalse(store().state().contains("wallets." + PLAYER));
    assertEquals(1, store().auditHistory("status:ABORTED", 10).size());
  }

  @Test void auditFailureLocksTradingAndSafeLedgerOnlySaveRecovers() throws Exception {
    Store first = store();
    Path segment = temp.resolve("audit/00000000000000000000");
    Files.write(segment, new byte[] {1}); // A deterministic filesystem fault at audit publication.
    assertThrows(Exception.class, () -> save(first, 83, "ADMIN BALANCE " + PLAYER));
    assertTrue(first.blocked());
    assertFalse(first.state().contains("wallets." + PLAYER));
    Files.delete(segment);
    Store restarted = store();
    assertFalse(restarted.blocked());
    assertEquals(83, restarted.state().getLong("wallets." + PLAYER));
    assertEquals(1, restarted.auditHistory("status:RECOVERED_COMMIT", 10).size());
  }

  @Test void externalAuditFailureNeverAutoCommitsOnRestart() throws Exception {
    Store first = store();
    Path segment = temp.resolve("audit/00000000000000000000");
    Files.write(segment, new byte[] {1});
    YamlConfiguration next = first.copy();
    next.set("wallets." + PLAYER, 83);
    first.prepare(next, "BUY " + PLAYER, "effects uncertain");
    assertThrows(Exception.class, first::commit);
    Files.delete(segment);
    assertTrue(store().blocked());
    assertFalse(store().state().contains("wallets." + PLAYER));
  }

  @Test void checkpointFailureAfterDurableCommitRestartsWithoutDuplicate() throws Exception {
    Store first = store();
    byte[] checkpoint = Files.readAllBytes(temp.resolve("state.yml"));
    for (int i = 1; i < 100; i++) save(first, i, "counter " + i);
    Files.delete(temp.resolve("state.yml"));
    Files.createDirectory(temp.resolve("state.yml"));
    assertThrows(Exception.class, () -> save(first, 100, "counter 100"));
    assertTrue(first.blocked());
    Files.delete(temp.resolve("state.yml"));
    Files.write(temp.resolve("state.yml"), checkpoint);
    Store restarted = store();
    assertFalse(restarted.blocked());
    assertEquals(100, restarted.state().getLong("wallets." + PLAYER));
    assertEquals(100, restarted.auditHistory("counter", 150).size());
  }

  @Test void completeHistorySurvivesCheckpointAndExceedsLegacyLimit() throws Exception {
    Store first = store();
    for (int i = 1; i <= 205; i++) save(first, i, "BUY " + PLAYER + " stone step=" + i);
    Store restarted = store();
    assertEquals(205, restarted.auditHistory("player:" + PLAYER + " product:stone", 300).size());
    assertEquals(200, restarted.state().getStringList("history").size());
    assertEquals(205, restarted.state().getLong("wallets." + PLAYER));
    assertTrue(restarted.auditHistory("", 1).get(0).contains("step=205"));
  }

  @Test void checksumCorruptionFailsClosed() throws Exception {
    Store first = store();
    save(first, 44, "BUY " + PLAYER);
    Path record = records().get(0);
    String text = new String(Files.readAllBytes(record), StandardCharsets.UTF_8);
    Files.write(record, text.replace("BUY", "BAD").getBytes(StandardCharsets.UTF_8));
    assertThrows(IOException.class, this::store);
    assertThrows(IOException.class, () -> first.auditHistory("", 10));
  }

  @Test void missingRecordAndConflictingCheckpointFailClosed() throws Exception {
    Store first = store();
    save(first, 1, "one");
    save(first, 2, "two");
    Files.delete(records().get(0));
    assertThrows(IOException.class, this::store);
  }

  @Test void checkpointValueMismatchIsNotSilentlyOverwritten() throws Exception {
    Store first = store();
    save(first, 15, "one");
    first.checkpoint();
    save(first, 20, "two");
    YamlConfiguration checkpoint = new YamlConfiguration();
    checkpoint.load(temp.resolve("state.yml").toFile());
    checkpoint.set("wallets." + PLAYER, 900);
    checkpoint.save(temp.resolve("state.yml").toFile());
    assertThrows(IOException.class, this::store);
  }

  @Test void missingCheckpointAuditBoundaryFailsClosed() throws Exception {
    Store first = store();
    save(first, 15, "one");
    first.close();
    Files.delete(records().get(0));
    assertThrows(IOException.class, this::store);
  }

  @Test void untouchedCheckpointWalletCorruptionFailsClosedWithoutLaterDeltas() throws Exception {
    Store first = store();
    save(first, 15, "wallet checkpoint");
    first.close();
    Path checkpoint = temp.resolve("state.yml");
    String source = new String(Files.readAllBytes(checkpoint), StandardCharsets.UTF_8);
    String damaged = source.replace(PLAYER + ": 15", PLAYER + ": 900");
    assertNotEquals(source, damaged);
    Files.write(checkpoint, damaged.getBytes(StandardCharsets.UTF_8));
    IOException failure = assertThrows(IOException.class, this::store);
    assertTrue(failure.getMessage().contains("checksum mismatch"));
  }

  @Test void removedOrTruncatedCheckpointFooterFailsClosed() throws Exception {
    Store first = store();
    save(first, 15, "checkpoint");
    first.close();
    Path checkpoint = temp.resolve("state.yml");
    String source = new String(Files.readAllBytes(checkpoint), StandardCharsets.UTF_8);
    int footer = source.lastIndexOf("# UniversalShop-checkpoint-sha256:");
    assertTrue(footer > 0);
    Files.write(checkpoint, source.substring(0, footer).getBytes(StandardCharsets.UTF_8));
    assertThrows(IOException.class, this::store);
    Files.write(checkpoint, source.substring(0, source.length() - 17).getBytes(StandardCharsets.UTF_8));
    assertThrows(IOException.class, this::store);
  }

  @Test void integrityFooterKeepsYamlReadableAndCoversUtf8Exactly() throws Exception {
    Store first = store();
    YamlConfiguration next = first.copy();
    next.set("note", "Économie 日本語");
    first.save(next, "unicode checkpoint");
    first.close();
    YamlConfiguration ordinaryYaml = new YamlConfiguration();
    ordinaryYaml.load(temp.resolve("state.yml").toFile());
    assertEquals("Économie 日本語", ordinaryYaml.getString("note"));
    assertEquals(1, ordinaryYaml.getInt("storage.checkpoint-format"));
    assertEquals("Économie 日本語", store().state().getString("note"));
  }

  @Test void plainLegacyCheckpointUpgradesImmediatelyEvenIfHistoryWasImported() throws Exception {
    YamlConfiguration legacy = new YamlConfiguration();
    legacy.set("schema", 1);
    legacy.set("wallets." + PLAYER, 123);
    legacy.set("storage.legacy-imported", true);
    AtomicFiles.write(temp.resolve("state.yml"), legacy.saveToString());
    assertEquals(123, store().state().getLong("wallets." + PLAYER));
    String source = new String(Files.readAllBytes(temp.resolve("state.yml")), StandardCharsets.UTF_8);
    assertTrue(source.contains("checkpoint-format: 1"));
    assertTrue(source.contains("# UniversalShop-checkpoint-sha256:"));
  }

  @Test void replayCrossesSegmentBoundaryAndRejectsDuplicateSequences() throws Exception {
    String id = UUID.randomUUID().toString();
    YamlConfiguration checkpoint = new YamlConfiguration();
    checkpoint.set("schema", 1);
    checkpoint.set("storage.sequence", 999L);
    checkpoint.set("storage.last-audit-id", id);
    checkpoint.set("storage.legacy-imported", true);
    checkpoint.set("last-transaction", id);
    checkpoint.set("wallets." + PLAYER, 8);
    AtomicFiles.write(temp.resolve("state.yml"), checkpoint.saveToString());
    YamlConfiguration boundary = new YamlConfiguration();
    boundary.set("sequence", 999L);
    boundary.set("parent", 998L);
    boundary.set("id", id);
    boundary.set("created", Instant.now().toString());
    boundary.set("outcome", "COMMITTED");
    boundary.set("changes", Collections.emptyList());
    AuditLog log = new AuditLog(temp.resolve("audit"));
    log.publish(boundary);
    Store first = store();
    save(first, 10, "boundary 1000");
    save(first, 11, "boundary 1001");
    assertEquals(11, store().state().getLong("wallets." + PLAYER));
    YamlConfiguration duplicate = AuditLog.read(records().get(1));
    duplicate.set("id", UUID.randomUUID().toString());
    log.publish(duplicate);
    assertThrows(IOException.class, this::store);
  }

  @Test void corruptYamlAndIncompleteJournalFailClosed() throws Exception {
    store();
    Files.write(temp.resolve("pending.yml"), "id: nonsense\n".getBytes(StandardCharsets.UTF_8));
    assertThrows(IOException.class, this::store);
    Files.delete(temp.resolve("pending.yml"));
    Files.write(temp.resolve("state.yml"), "schema: [unclosed".getBytes(StandardCharsets.UTF_8));
    assertThrows(Exception.class, this::store);
  }

  @Test void uncommittedTemporaryAuditFileIsIgnored() throws Exception {
    Store first = store();
    save(first, 67, "one");
    Files.write(records().get(0).resolveSibling("partial.tmp"), new byte[] {1, 2, 3});
    assertEquals(67, store().state().getLong("wallets." + PLAYER));
  }

  @Test void staleCopyAndCallerMutationCannotEscapeDurableJournal() throws Exception {
    Store first = store();
    YamlConfiguration stale = first.copy();
    save(first, 1, "one");
    assertThrows(IllegalStateException.class, () -> first.save(stale, "stale"));
    YamlConfiguration next = first.copy();
    next.set("wallets." + PLAYER, 2);
    first.prepare(next, "two", "fixture");
    next.set("wallets." + PLAYER, 9999);
    next.set("unrecorded", "changed after prepare");
    first.commit();
    assertEquals(2, first.state().getLong("wallets." + PLAYER));
    assertFalse(first.state().contains("unrecorded"));
    assertEquals(2, store().state().getLong("wallets." + PLAYER));
  }

  @Test void nestedListsSectionsAndDeletionsReplayExactly() throws Exception {
    Store first = store();
    YamlConfiguration next = first.copy();
    next.set("market.abcd.owner", PLAYER);
    next.set("market.abcd.price", 15L);
    next.set("preferences." + PLAYER + ".favorites", Arrays.asList("stone", "diamond"));
    next.createSection("empty");
    first.save(next, "new subtrees");
    assertEquals(Arrays.asList("stone", "diamond"), store().state().getStringList("preferences." + PLAYER + ".favorites"));
    next = first.copy();
    next.set("market.abcd", null);
    next.set("empty", "replaced section with scalar");
    first.save(next, "delete subtree");
    Store restarted = store();
    assertFalse(restarted.state().contains("market.abcd"));
    assertEquals("replaced section with scalar", restarted.state().getString("empty"));
  }

  @Test void importsLegacyHistoryOncePreservingExistingLedger() throws Exception {
    YamlConfiguration legacy = new YamlConfiguration();
    legacy.set("schema", 1);
    legacy.set("wallets." + PLAYER, 321);
    legacy.set("economy-mode", "INTERNAL");
    legacy.set("history", Arrays.asList("2026-10-01T12:00:00Z " + UUID.randomUUID() + " BUY " + PLAYER + " stone"));
    AtomicFiles.write(temp.resolve("state.yml"), legacy.saveToString());
    assertEquals(321, store().state().getLong("wallets." + PLAYER));
    assertEquals("INTERNAL", store().state().getString("economy-mode"));
    assertEquals(1, store().auditHistory("LEGACY_COMMITTED", 10).size());
    assertEquals(1, records().size());
  }

  private String legacyPending(boolean alreadyCommitted) throws Exception {
    String id = UUID.randomUUID().toString();
    YamlConfiguration before = new YamlConfiguration();
    before.set("schema", 1);
    before.set("wallets." + PLAYER, 25);
    YamlConfiguration after = new YamlConfiguration();
    after.loadFromString(before.saveToString());
    after.set("wallets." + PLAYER, 75);
    after.set("last-transaction", id);
    YamlConfiguration journal = new YamlConfiguration();
    journal.set("id", id);
    journal.set("description", "legacy external trade");
    journal.set("evidence", "player/provider effects");
    journal.set("before", before.saveToString());
    journal.set("after", after.saveToString());
    AtomicFiles.write(temp.resolve("state.yml"), (alreadyCommitted ? after : before).saveToString());
    AtomicFiles.write(temp.resolve("pending.yml"), journal.saveToString());
    return id;
  }

  @Test void legacyExternalJournalCanBeReconciledWithoutLosingData() throws Exception {
    String id = legacyPending(false);
    Store migrated = store();
    assertTrue(migrated.blocked());
    assertEquals(25, migrated.state().getLong("wallets." + PLAYER));
    migrated.reconcile(true);
    assertEquals(75, store().state().getLong("wallets." + PLAYER));
    assertEquals(1, store().auditHistory(id, 10).size());
  }

  @Test void completedLegacyJournalCleanupIsIdempotent() throws Exception {
    String id = legacyPending(true);
    byte[] journal = Files.readAllBytes(temp.resolve("pending.yml"));
    assertFalse(store().blocked());
    Files.write(temp.resolve("pending.yml"), journal);
    assertFalse(store().blocked());
    assertEquals(75, store().state().getLong("wallets." + PLAYER));
    assertEquals(1, store().auditHistory(id, 10).size());
  }

  @Test void normalShutdownFlushesReadableCheckpoint() throws Exception {
    Store first = store();
    save(first, 999, "before shutdown");
    first.close();
    YamlConfiguration checkpoint = new YamlConfiguration();
    checkpoint.load(temp.resolve("state.yml").toFile());
    assertEquals(999, checkpoint.getLong("wallets." + PLAYER));
    assertEquals(1, checkpoint.getLong("storage.sequence"));
    assertEquals(1, store().auditHistory("", 10).size());
  }

  @Test void pluginBackupRestoresCatalogAuditAndPendingQuarantine() throws Exception {
    Store first = store();
    save(first, 8, "completed");
    Files.write(temp.resolve("shops.yml"), "catalog: fixture".getBytes(StandardCharsets.UTF_8));
    Files.write(temp.resolve("config.yml"), "economy: INTERNAL".getBytes(StandardCharsets.UTF_8));
    Files.write(temp.resolve("ledger.lock"), "process lease".getBytes(StandardCharsets.UTF_8));
    YamlConfiguration next = first.copy();
    next.set("wallets." + PLAYER, 9);
    first.prepare(next, "uncertain", "player inventory fixture");
    Path backup = first.backup(), restored = temp.resolve("restored");
    Files.createDirectories(restored);
    try (ZipFile zip = new ZipFile(backup.toFile())) {
      assertNotNull(zip.getEntry("shops.yml"));
      assertNotNull(zip.getEntry("config.yml"));
      assertNotNull(zip.getEntry("pending.yml"));
      assertNull(zip.getEntry("ledger.lock"));
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        assertFalse(entry.getName().startsWith("backups/"));
        Path path = restored.resolve(entry.getName()).normalize();
        assertTrue(path.startsWith(restored));
        Files.createDirectories(path.getParent());
        try (InputStream input = zip.getInputStream(entry)) { Files.copy(input, path); }
      }
    }
    Store recovered = new Store(restored);
    assertTrue(recovered.blocked());
    assertEquals(8, recovered.state().getLong("wallets." + PLAYER));
    assertEquals(1, recovered.auditHistory("completed", 10).size());
    assertTrue(recovered.diagnostics().stream().anyMatch(s -> s.contains("Ledger changes:")));
  }

  @Test void historyDateTextAndStatusFiltersAndSafeCsv() throws Exception {
    Store first = store();
    save(first, 1, "BUY " + PLAYER + " stone");
    YamlConfiguration next = first.copy();
    first.prepare(next, "=1+1,\"line\nother", "external fixture");
    first.abort();
    String today = LocalDate.now(ZoneOffset.UTC).toString();
    assertEquals(1, first.auditHistory("from:" + today + " to:" + today + " player:" + PLAYER + " product:stone status:COMMITTED", 10).size());
    assertTrue(first.auditHistory("from:2000-01-01 to:2001-01-01", 10).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> first.auditHistory("from:bad", 10));
    assertThrows(IllegalArgumentException.class, () -> first.auditHistory("", 0));
    String csv = first.auditCsv("status:ABORTED");
    assertTrue(csv.contains("\"'=1+1,\"\"line\nother\""));
    assertTrue(csv.contains("external fixture"));
    assertTrue(csv.startsWith("sequence,transaction_id,"));
  }
}
