package fr.hash014.universalshop;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** A durable prepare/commit journal. Ambiguous external effects require operator reconciliation. */
public final class Store {
  private final Path directory;
  private YamlConfiguration state;
  private YamlConfiguration pending;

  public Store(Path directory) throws Exception {
    this.directory = directory;
    Files.createDirectories(directory);
    state = read(directory.resolve("state.yml"));
    if (state.getInt("schema", 1) != 1) throw new IOException("Unsupported state schema.");
    if (Files.exists(directory.resolve("pending.yml"))) {
      pending = read(directory.resolve("pending.yml"));
      if (pending.getString("id", "").equals(state.getString("last-transaction", "none")))
        clearPending();
    }
  }

  private static YamlConfiguration read(Path path) throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    if (Files.exists(path))
      yaml.load(path.toFile()); // loadConfiguration would swallow corrupt-file errors.
    return yaml;
  }

  public YamlConfiguration copy() throws Exception {
    YamlConfiguration copy = new YamlConfiguration();
    copy.loadFromString(state.saveToString());
    return copy;
  }

  public YamlConfiguration state() {
    return state;
  }

  public boolean blocked() {
    return pending != null;
  }

  public String pendingInfo() {
    return pending == null
        ? "No pending transaction."
        : pending.getString("description") + " [" + pending.getString("id") + "]";
  }

  public void prepare(YamlConfiguration next, String description, String evidence)
      throws Exception {
    if (blocked())
      throw new IllegalStateException(
          "Trading is locked: an administrator must reconcile the pending transaction.");
    String id = UUID.randomUUID().toString();
    next.set("schema", 1);
    next.set("last-transaction", id);
    List<String> history = new ArrayList<String>(next.getStringList("history"));
    history.add(java.time.Instant.now() + " " + id + " " + description);
    while (history.size() > 200) history.remove(0);
    next.set("history", history);
    YamlConfiguration journal = new YamlConfiguration();
    journal.set("id", id);
    journal.set("description", description);
    journal.set("evidence", evidence);
    journal.set("before", state.saveToString());
    journal.set("after", next.saveToString());
    AtomicFiles.write(directory.resolve("pending.yml"), journal.saveToString());
    pending = journal;
  }

  public void commit() throws Exception {
    if (pending == null) throw new IllegalStateException("No prepared transaction.");
    YamlConfiguration next = new YamlConfiguration();
    next.loadFromString(pending.getString("after"));
    AtomicFiles.write(directory.resolve("state.yml"), next.saveToString());
    state = next;
    clearPending();
  }

  public void abort() throws Exception {
    clearPending();
  }

  private void clearPending() throws IOException {
    Files.deleteIfExists(directory.resolve("pending.yml"));
    pending = null;
  }

  public void save(YamlConfiguration next, String description) throws Exception {
    prepare(next, description, "Ledger-only change.");
    commit();
  }

  public void reconcile(boolean commit) throws Exception {
    if (pending == null) throw new IllegalStateException("No pending transaction.");
    // Preserve the complete evidence before allowing an explicit operator decision.
    AtomicFiles.write(
        directory.resolve("recovery/" + pending.getString("id") + ".yml"), pending.saveToString());
    if (commit) commit();
    else abort();
  }
}
