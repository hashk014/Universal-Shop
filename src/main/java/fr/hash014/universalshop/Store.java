package fr.hash014.universalshop;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** Durable delta journal. The immutable audit record is also the ledger commit point. */
public final class Store implements AutoCloseable {
  private static final int CHECKPOINT_INTERVAL = 100;
  private final Path directory;
  private final AuditLog audit;
  private YamlConfiguration state, pending;
  private long sequence, checkpointSequence;

  public Store(Path directory) throws Exception {
    this.directory = directory.toAbsolutePath().normalize();
    Files.createDirectories(this.directory);
    state = readCheckpoint(this.directory.resolve("state.yml"));
    boolean checkpointUpgrade = !state.contains("storage.checkpoint-format");
    if (state.getInt("schema", 1) != 1) throw new IOException("Unsupported state schema.");
    audit = new AuditLog(this.directory.resolve("audit"));
    sequence = checkpointSequence = state.getLong("storage.sequence", 0);
    if (sequence < 0) throw new IOException("Invalid storage sequence.");
    if (sequence > 0) {
      String headId = state.getString("storage.last-audit-id", "");
      if (headId.isEmpty()) {
        // Accept checkpoints from the first delta format while validating their final record.
        for (Path path : audit.pathsAfter(sequence - 1)) {
          YamlConfiguration record = AuditLog.read(path);
          if (record.getLong("sequence") == sequence) { headId = record.getString("id"); break; }
        }
      }
      if (headId.isEmpty() || !Files.exists(audit.path(sequence, headId)))
        throw new IOException("Checkpoint's final audit record is missing; restore a coordinated backup.");
      YamlConfiguration head = AuditLog.read(audit.path(sequence, headId));
      if (head.getLong("sequence") != sequence || !headId.equals(head.getString("id")))
        throw new IOException("Checkpoint's audit identity is inconsistent.");
      state.set("storage.last-audit-id", headId);
    }
    replay();
    Path journalPath = this.directory.resolve("pending.yml");
    if (Files.exists(journalPath)) {
      pending = read(journalPath);
      validatePending();
      // A published record proves the ledger decision; it never causes inventory/provider replay.
      if (pending.getString("id").equals(state.getString("last-transaction", "none"))) {
        if (!pending.contains("sequence") && (sequence < 1
            || !Files.exists(audit.path(sequence, pending.getString("id"))))) archiveLegacyFinal("COMMITTED");
        clearPending();
      } else if (pending.contains("sequence") && pending.getLong("sequence") <= sequence) {
        Path recordPath = audit.path(pending.getLong("sequence"), pending.getString("id"));
        if (!Files.exists(recordPath)) throw new IOException("Pending decision is missing from audit.");
        YamlConfiguration record = AuditLog.read(recordPath);
        if (!pending.getString("id").equals(record.getString("id")))
          throw new IOException("Pending transaction conflicts with the durable audit record.");
        clearPending();
      } else if (pending.getBoolean("ledger-only", false)) {
        // This explicit flag is set only by save(), which never changes inventory or providers.
        finish(true, "RECOVERED_COMMIT");
      }
    }
    if (!state.getBoolean("storage.legacy-imported", false)) {
      audit.importLegacy(state.getStringList("history"));
      state.set("storage.legacy-imported", true);
      checkpoint();
    } else if (checkpointUpgrade) checkpoint();
  }

  private static YamlConfiguration read(Path path) throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    if (Files.exists(path)) yaml.load(path.toFile());
    return yaml;
  }

  private static final String CHECKPOINT_FOOTER = "# UniversalShop-checkpoint-sha256: ";

  private static YamlConfiguration readCheckpoint(Path path) throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    if (!Files.exists(path)) return yaml;
    // Decode strictly so malformed UTF-8 cannot be silently normalized before verification.
    String source = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(path))).toString();
    int footer = source.lastIndexOf("\n" + CHECKPOINT_FOOTER);
    if (footer >= 0) {
      footer++; // Keep the payload's final newline in the exact byte sequence being checked.
      String line = source.substring(footer);
      if (!line.matches(java.util.regex.Pattern.quote(CHECKPOINT_FOOTER) + "[a-f0-9]{64}\\n"))
        throw new IOException("Malformed or truncated checkpoint checksum footer.");
      String payload = source.substring(0, footer);
      String expected = line.substring(CHECKPOINT_FOOTER.length(), CHECKPOINT_FOOTER.length() + 64);
      if (!checkpointChecksum(payload).equals(expected))
        throw new IOException("Checkpoint checksum mismatch; restore a coordinated backup.");
      yaml.loadFromString(payload);
      if (yaml.getInt("storage.checkpoint-format", -1) != 1)
        throw new IOException("Unsupported checkpoint integrity format.");
    } else {
      yaml.loadFromString(source);
      if (yaml.contains("storage.checkpoint-format"))
        throw new IOException("Checkpoint checksum footer is missing or truncated; restore a coordinated backup.");
      // Plain 1.x YAML is accepted once, then immediately upgraded by this constructor.
    }
    return yaml;
  }

  private static String checkpointChecksum(String payload) throws Exception {
    byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
        .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    StringBuilder result = new StringBuilder(64);
    for (byte b : hash) result.append(String.format(Locale.ROOT, "%02x", b & 255));
    return result.toString();
  }

  private void replay() throws Exception {
    for (Path path : audit.pathsAfter(sequence)) {
      YamlConfiguration record = AuditLog.read(path);
      long next = record.getLong("sequence", -1);
      if (next != sequence + 1 || record.getLong("parent", -1) != sequence)
        throw new IOException("Ledger audit gap or duplicate sequence at " + path.getFileName());
      String outcome = record.getString("outcome");
      if (!Arrays.asList("COMMITTED", "ABORTED", "RECOVERED_COMMIT", "RECOVERED_ABORT").contains(outcome))
        throw new IOException("Unknown ledger outcome: " + outcome);
      if (outcome.equals("COMMITTED") || outcome.equals("RECOVERED_COMMIT")) {
        applyChanges(state, record, true, true);
        state.set("last-transaction", record.getString("id"));
        if (!record.getBoolean("legacy-finalized", false)) appendHistory(state, record);
      }
      sequence = next;
      state.set("storage.sequence", sequence);
      state.set("storage.last-audit-id", record.getString("id"));
    }
  }

  /** Independent object copy, without a full YAML serialize/parse round trip on every trade. */
  public synchronized YamlConfiguration copy() {
    YamlConfiguration copy = new YamlConfiguration();
    copySection(state, copy);
    return copy;
  }

  private static void copySection(ConfigurationSection source, ConfigurationSection target) {
    for (String key : source.getKeys(false)) {
      Object value = source.get(key);
      if (value instanceof ConfigurationSection)
        copySection((ConfigurationSection) value, target.createSection(key));
      else target.set(key, cloneValue(value));
    }
  }

  private static Object cloneValue(Object value) {
    if (value instanceof ItemStack) return ((ItemStack) value).clone();
    if (value instanceof ConfigurationSection) {
      Map<String, Object> map = new LinkedHashMap<String, Object>();
      for (String key : ((ConfigurationSection) value).getKeys(false))
        map.put(key, cloneValue(((ConfigurationSection) value).get(key)));
      return map;
    }
    if (value instanceof Map) {
      Map<Object, Object> map = new LinkedHashMap<Object, Object>();
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
        map.put(entry.getKey(), cloneValue(entry.getValue()));
      return map;
    }
    if (value instanceof List) {
      List<Object> list = new ArrayList<Object>();
      for (Object item : (List<?>) value) list.add(cloneValue(item));
      return list;
    }
    if (value instanceof byte[]) return ((byte[]) value).clone();
    return value;
  }

  public synchronized YamlConfiguration state() { return state; }
  public synchronized boolean blocked() { return pending != null; }

  public synchronized String pendingInfo() {
    return pending == null ? "No pending transaction."
        : pending.getString("description") + " [" + pending.getString("id") + "]";
  }

  public synchronized void prepare(YamlConfiguration next, String description, String evidence)
      throws Exception {
    prepare(next, description, evidence, false);
  }

  private void prepare(YamlConfiguration next, String description, String evidence, boolean ledgerOnly)
      throws Exception {
    if (blocked()) throw new IllegalStateException(
        "Trading is locked: an administrator must reconcile the pending transaction.");
    if (next == state) throw new IllegalArgumentException("Prepare requires an independent ledger copy.");
    if (next.getLong("storage.sequence", 0) != sequence)
      throw new IllegalStateException("Ledger copy is stale; retry against the current state.");
    if (sequence == Long.MAX_VALUE) throw new IOException("Storage sequence exhausted.");
    String id = UUID.randomUUID().toString();
    String created = Instant.now().toString();
    next.set("schema", 1);
    next.set("last-transaction", id);
    next.set("storage.sequence", sequence + 1);
    next.set("storage.last-audit-id", id);
    YamlConfiguration journal = new YamlConfiguration();
    journal.set("journal-format", 2);
    journal.set("id", id);
    journal.set("created", created);
    journal.set("sequence", sequence + 1);
    journal.set("parent", sequence);
    journal.set("description", description);
    journal.set("evidence", evidence);
    journal.set("ledger-only", ledgerOnly);
    appendHistory(next, journal); // Compatibility cache; deliberately excluded from sparse patches.
    List<Map<String, Object>> changes = new ArrayList<Map<String, Object>>();
    diff(state, next, "", changes);
    journal.set("changes", changes);
    pending = journal;
    AtomicFiles.write(directory.resolve("pending.yml"), SafeYaml.write(journal));
  }

  private static void diff(ConfigurationSection before, ConfigurationSection after, String base,
      List<Map<String, Object>> changes) {
    Set<String> keys = new LinkedHashSet<String>();
    if (before != null) keys.addAll(before.getKeys(false));
    if (after != null) keys.addAll(after.getKeys(false));
    for (String key : keys) {
      if (base.isEmpty() && key.equals("history")) continue;
      Object old = before == null ? null : before.get(key), value = after == null ? null : after.get(key);
      String path = base.isEmpty() ? key : base + "." + key;
      if (old instanceof ConfigurationSection && value instanceof ConfigurationSection)
        diff((ConfigurationSection) old, (ConfigurationSection) value, path, changes);
      else if (value instanceof ConfigurationSection)
        change(path, cloneValue(old), cloneValue(value), changes);
      else if (old instanceof ConfigurationSection || !equalValue(old, value))
        change(path, cloneValue(old), cloneValue(value), changes);
    }
  }

  private static void change(String path, Object before, Object after, List<Map<String, Object>> changes) {
    Map<String, Object> change = new LinkedHashMap<String, Object>();
    change.put("path", path);
    change.put("before-present", before != null);
    if (before != null) change.put("before", before);
    change.put("after-present", after != null);
    if (after != null) change.put("after", after);
    changes.add(change);
  }

  private static void applyChanges(YamlConfiguration target, YamlConfiguration journal,
      boolean after, boolean verify) throws IOException {
    List<?> changes = journal.getList("changes");
    if (changes == null) throw new IOException("Transaction has no change list.");
    for (Object item : changes) {
      if (!(item instanceof Map)) throw new IOException("Invalid transaction change.");
      Map<?, ?> change = (Map<?, ?>) item;
      Object rawPath = change.get("path");
      if (!(rawPath instanceof String) || ((String) rawPath).isEmpty())
        throw new IOException("Invalid transaction change path.");
      String path = (String) rawPath;
      if (verify) {
        Object expected = change.get("before");
        Object current = cloneValue(target.get(path));
        if (!equalValue(expected, current))
          throw new IOException("Ledger/audit state mismatch at " + path + "; restore a coordinated backup.");
      }
      Object value = Boolean.TRUE.equals(change.get(after ? "after-present" : "before-present"))
          ? cloneValue(change.get(after ? "after" : "before")) : null;
      target.set(path, null);
      if (value instanceof Map) target.createSection(path, (Map<?, ?>) value);
      else target.set(path, value);
    }
  }

  private static boolean equalValue(Object a, Object b) {
    if (a instanceof Number && b instanceof Number)
      return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString())) == 0;
    if (a instanceof Map && b instanceof Map) {
      Map<?, ?> left = (Map<?, ?>) a, right = (Map<?, ?>) b;
      if (!left.keySet().equals(right.keySet())) return false;
      for (Object key : left.keySet()) if (!equalValue(left.get(key), right.get(key))) return false;
      return true;
    }
    if (a instanceof List && b instanceof List) {
      List<?> left = (List<?>) a, right = (List<?>) b;
      if (left.size() != right.size()) return false;
      for (int i = 0; i < left.size(); i++) if (!equalValue(left.get(i), right.get(i))) return false;
      return true;
    }
    return Objects.deepEquals(a, b);
  }

  private static void appendHistory(YamlConfiguration target, YamlConfiguration journal) {
    List<String> history = new ArrayList<String>(target.getStringList("history"));
    history.add(journal.getString("created", Instant.now().toString()) + " "
        + journal.getString("id") + " " + journal.getString("description"));
    while (history.size() > 200) history.remove(0);
    target.set("history", history);
  }

  private void validatePending() throws Exception {
    String id = pending.getString("id", "");
    if (!id.matches("[a-fA-F0-9-]{36}")) throw new IOException("Invalid pending transaction ID.");
    if (pending.contains("sequence")) {
      if (pending.getInt("journal-format") != 2 || pending.getLong("sequence") < 1
          || pending.getLong("parent", -1) != pending.getLong("sequence") - 1
          || pending.getList("changes") == null)
        throw new IOException("Invalid pending delta journal.");
    } else {
      if (pending.getString("after") == null || pending.getString("before") == null)
        throw new IOException("Incomplete legacy pending transaction.");
      YamlConfiguration fixture = new YamlConfiguration();
      fixture.loadFromString(pending.getString("after"));
      if (!id.equals(fixture.getString("last-transaction")))
        throw new IOException("Legacy pending identity mismatch.");
    }
  }

  public synchronized void commit() throws Exception { finish(true, "COMMITTED"); }
  public synchronized void abort() throws Exception { if (pending != null) finish(false, "ABORTED"); }

  private void finish(boolean commit, String outcome) throws Exception {
    if (pending == null) throw new IllegalStateException("No prepared transaction.");
    if (!pending.contains("sequence")) {
      convertLegacyPending();
      AtomicFiles.write(directory.resolve("pending.yml"), SafeYaml.write(pending));
    }
    long nextSequence = pending.getLong("sequence");
    Path finalPath = audit.path(nextSequence, pending.getString("id"));
    if (Files.exists(finalPath)) {
      YamlConfiguration existing = AuditLog.read(finalPath);
      // A retry must not replace an irrevocably published commit with a requested abort.
      if (!existing.getString("outcome").equals(outcome))
        throw new IOException("Transaction already has a durable " + existing.getString("outcome") + " decision; restart to complete cleanup.");
      if (nextSequence > sequence) replay();
      clearPending();
      return;
    }
    if (nextSequence != sequence + 1 || pending.getLong("parent") != sequence)
      throw new IOException("Pending journal is not based on the current ledger.");
    YamlConfiguration next = null;
    if (commit) {
      next = copy();
      applyChanges(next, pending, true, true);
      appendHistory(next, pending);
    }
    YamlConfiguration record = new YamlConfiguration();
    copySection(pending, record);
    record.set("finished", Instant.now().toString());
    record.set("outcome", outcome);
    audit.publish(record); // State delta and audit become durable together before unlock.
    if (commit) state = next;
    sequence = nextSequence;
    state.set("storage.sequence", sequence);
    state.set("storage.last-audit-id", pending.getString("id"));
    if (sequence - checkpointSequence >= CHECKPOINT_INTERVAL) checkpoint();
    clearPending();
  }

  private void convertLegacyPending() throws Exception {
    YamlConfiguration before = new YamlConfiguration(), after = new YamlConfiguration();
    before.loadFromString(pending.getString("before"));
    after.loadFromString(pending.getString("after"));
    // Import newly introduced bookkeeping independently from the legacy external effect.
    Object bookkeeping = cloneValue(state.get("storage"));
    before.set("storage", null);
    after.set("storage", null);
    if (bookkeeping instanceof Map) {
      before.createSection("storage", (Map<?, ?>) bookkeeping);
      after.createSection("storage", (Map<?, ?>) cloneValue(bookkeeping));
    }
    before.set("storage.sequence", sequence);
    after.set("storage.sequence", sequence + 1);
    List<Map<String, Object>> changes = new ArrayList<Map<String, Object>>();
    diff(before, after, "", changes);
    pending.set("changes", changes);
    pending.set("journal-format", 2);
    pending.set("created", Instant.now().toString());
    pending.set("sequence", sequence + 1);
    pending.set("parent", sequence);
    pending.set("before", null);
    pending.set("after", null);
  }

  private void archiveLegacyFinal(String outcome) throws Exception {
    YamlConfiguration record = new YamlConfiguration();
    record.set("id", pending.getString("id"));
    record.set("sequence", sequence + 1);
    record.set("parent", sequence);
    record.set("created", Instant.now().toString());
    record.set("finished", record.getString("created"));
    record.set("description", pending.getString("description", "Legacy completed transaction"));
    record.set("evidence", pending.getString("evidence", ""));
    record.set("outcome", outcome);
    record.set("legacy-finalized", true);
    record.set("changes", Collections.emptyList());
    audit.publish(record);
    sequence++;
    state.set("storage.sequence", sequence);
    state.set("storage.last-audit-id", pending.getString("id"));
  }

  private void clearPending() throws IOException {
    AtomicFiles.delete(directory.resolve("pending.yml"));
    pending = null;
  }

  public synchronized void save(YamlConfiguration next, String description) throws Exception {
    prepare(next, description, "Ledger-only change.", true);
    commit();
  }

  public synchronized void reconcile(boolean commit) throws Exception {
    if (pending == null) throw new IllegalStateException("No pending transaction.");
    YamlConfiguration evidence = new YamlConfiguration();
    copySection(pending, evidence);
    evidence.set("operator-decision", commit ? "COMMIT" : "ABORT");
    evidence.set("decision-time", Instant.now().toString());
    AtomicFiles.write(directory.resolve("recovery/" + pending.getString("id") + ".yml"), SafeYaml.write(evidence));
    finish(commit, commit ? "RECOVERED_COMMIT" : "RECOVERED_ABORT");
  }

  public synchronized List<String> auditHistory(String filter, int limit) throws Exception {
    return audit.history(filter, limit);
  }

  public synchronized String auditCsv(String filter) throws Exception { return audit.csv(filter); }

  /** Flush a checkpoint; committed audit records remain available for unrestricted history. */
  public synchronized void checkpoint() throws Exception {
    state.set("schema", 1);
    state.set("storage.sequence", sequence);
    state.set("storage.checkpoint-format", 1);
    String payload = SafeYaml.write(state);
    AtomicFiles.write(directory.resolve("state.yml"), payload + CHECKPOINT_FOOTER + checkpointChecksum(payload) + "\n");
    checkpointSequence = sequence;
  }

  public synchronized Path backup() throws Exception {
    checkpoint();
    Path backups = directory.resolve("backups");
    Files.createDirectories(backups);
    String name = "UniversalShop-" + Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID().toString().substring(0, 8) + ".zip";
    Path target = backups.resolve(name), temp = Files.createTempFile(backups, "backup-", ".tmp");
    boolean complete = false;
    try {
      try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp))) {
        final byte[] buffer = new byte[8192];
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
          @Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
            return dir.equals(backups) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
          }
          @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
            if (!attrs.isRegularFile() || file.getFileName().toString().endsWith(".tmp")
                || file.getFileName().toString().equals("ledger.lock")) return FileVisitResult.CONTINUE;
            ZipEntry entry = new ZipEntry(directory.relativize(file).toString().replace('\\', '/'));
            entry.setTime(attrs.lastModifiedTime().toMillis());
            zip.putNextEntry(entry);
            try (InputStream input = Files.newInputStream(file)) {
              int read;
              while ((read = input.read(buffer)) != -1) zip.write(buffer, 0, read);
            }
            zip.closeEntry();
            return FileVisitResult.CONTINUE;
          }
        });
      }
      AtomicFiles.replace(temp, target);
      complete = true;
      return target;
    } finally {
      if (!complete) Files.deleteIfExists(temp);
    }
  }

  public synchronized List<String> diagnostics() throws Exception {
    List<String> result = new ArrayList<String>();
    result.add("Storage: delta write-ahead ledger; sequence=" + sequence + ", checkpoint=" + checkpointSequence + ", replay-window=" + (sequence - checkpointSequence));
    result.add("Audit: " + audit.count() + " durable records; checkpoints every " + CHECKPOINT_INTERVAL + " final operations.");
    result.add("Directory: " + directory);
    result.add(pendingInfo());
    if (pending != null) {
      result.add("Prepared: " + pending.getString("created", "legacy/unknown") + "; ledger-only=" + pending.getBoolean("ledger-only", false));
      String evidence = pending.getString("evidence", "No inventory/provider evidence recorded.").replace('\n', ' ').replace('\r', ' ');
      result.add("Evidence: " + (evidence.length() > 400 ? evidence.substring(0, 400) + "... (see pending.yml)" : evidence));
      result.add("Ledger changes: " + (pending.contains("changes") ? pending.getList("changes").size() : "legacy before/after snapshots"));
      result.add("Back up, reconcile actual inventories/provider effects, then choose recovery commit or abort with confirm.");
    }
    result.add("Backup covers plugin data/configuration/audit; playerdata and external economy require separate backups.");
    return result;
  }

  @Override public synchronized void close() throws Exception { checkpoint(); }
}
