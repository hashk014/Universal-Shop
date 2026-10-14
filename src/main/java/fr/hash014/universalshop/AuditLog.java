package fr.hash014.universalshop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Immutable, checksummed audit records are also the ledger's durable commit records. */
final class AuditLog {
  private final Path directory;

  AuditLog(Path directory) throws IOException {
    this.directory = directory;
    Files.createDirectories(directory);
  }

  Path path(long sequence, String id) {
    if (sequence < 1 || !id.matches("[a-fA-F0-9-]{36}"))
      throw new IllegalArgumentException("Invalid audit identity.");
    return directory.resolve(number(sequence / 1000)).resolve(number(sequence) + "-" + id + ".yml");
  }

  void publish(YamlConfiguration record) throws Exception {
    Path path = path(record.getLong("sequence"), record.getString("id", ""));
    if (Files.exists(path)) {
      YamlConfiguration existing = read(path);
      if (!SafeYaml.write(existing).equals(SafeYaml.write(record)))
        throw new IOException("Conflicting durable transaction record: " + path.getFileName());
      return;
    }
    write(path, record);
  }

  private static void write(Path path, YamlConfiguration record) throws Exception {
    String payload = SafeYaml.write(record);
    YamlConfiguration envelope = new YamlConfiguration();
    envelope.set("format", 1);
    envelope.set("sha256", checksum(payload));
    envelope.set("payload", payload);
    AtomicFiles.write(path, SafeYaml.write(envelope));
  }

  static YamlConfiguration read(Path path) throws Exception {
    YamlConfiguration envelope = new YamlConfiguration();
    envelope.load(path.toFile());
    String payload = envelope.getString("payload");
    if (envelope.getInt("format") != 1 || payload == null
        || !checksum(payload).equals(envelope.getString("sha256")))
      throw new IOException("Corrupt audit record (checksum/format): " + path);
    YamlConfiguration record = new YamlConfiguration();
    record.loadFromString(payload);
    if (record.getString("id", "").isEmpty() || record.getString("outcome", "").isEmpty())
      throw new IOException("Incomplete audit record: " + path);
    String fileName = path.getFileName().toString();
    if (fileName.matches("[0-9]{20}-[a-fA-F0-9-]{36}\\.yml")
        && (!fileName.substring(21, 57).equals(record.getString("id"))
            || Long.parseLong(fileName.substring(0, 20)) != record.getLong("sequence")))
      throw new IOException("Audit filename/identity mismatch: " + path);
    return record;
  }

  List<Path> pathsAfter(long sequence) throws IOException {
    List<Path> paths = new ArrayList<Path>();
    try (DirectoryStream<Path> segments = Files.newDirectoryStream(directory)) {
      for (Path segment : segments) {
        String name = segment.getFileName().toString();
        if (!name.matches("[0-9]{20}") || !Files.isDirectory(segment)
            || Long.parseLong(name) < sequence / 1000) continue;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(segment, "*.yml")) {
          for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (!fileName.matches("[0-9]{20}-[a-fA-F0-9-]{36}\\.yml"))
              throw new IOException("Unexpected audit filename: " + file);
            if (Long.parseLong(fileName.substring(0, 20)) > sequence) paths.add(file);
          }
        }
      }
    }
    Collections.sort(paths, Comparator.comparing(p -> p.getFileName().toString()));
    return paths;
  }

  void importLegacy(List<String> history) throws Exception {
    Set<String> existingIds = new HashSet<String>();
    for (Path existing : pathsAfter(0)) existingIds.add(read(existing).getString("id"));
    int index = 0;
    for (String line : history) {
      YamlConfiguration record = new YamlConfiguration();
      String[] fields = line.split(" ", 3);
      record.set("sequence", 0);
      record.set("id", fields.length > 1 ? fields[1] : "legacy-" + index);
      if (existingIds.contains(record.getString("id"))) { index++; continue; }
      record.set("created", fields.length > 0 ? fields[0] : Instant.EPOCH.toString());
      record.set("finished", record.getString("created"));
      record.set("outcome", "LEGACY_COMMITTED");
      record.set("description", fields.length > 2 ? fields[2] : line);
      record.set("evidence", "Imported from the legacy 200-entry history; detailed evidence unavailable.");
      record.set("changes", Collections.emptyList());
      Path file = directory.resolve("legacy").resolve(String.format(Locale.ROOT, "%04d-", index++) + checksum(line) + ".yml");
      if (!Files.exists(file)) write(file, record);
      else read(file); // An interrupted migration resumes without replacing or duplicating records.
    }
  }

  private List<Path> allPaths() throws IOException {
    List<Path> result = pathsAfter(0);
    Path legacy = directory.resolve("legacy");
    if (Files.isDirectory(legacy))
      try (DirectoryStream<Path> files = Files.newDirectoryStream(legacy, "*.yml")) {
        List<Path> imported = new ArrayList<Path>();
        for (Path file : files) imported.add(file);
        Collections.sort(imported);
        result.addAll(0, imported);
      }
    return result;
  }

  List<String> history(String filter, int limit) throws Exception {
    if (limit < 1 || limit > 10000) throw new IllegalArgumentException("History limit must be 1 to 10000.");
    Filter matcher = new Filter(filter);
    List<Path> paths = allPaths();
    List<String> result = new ArrayList<String>();
    for (int i = paths.size() - 1; i >= 0 && result.size() < limit; i--) {
      YamlConfiguration record = read(paths.get(i));
      if (matcher.matches(record))
        result.add(record.getString("finished", record.getString("created", "")) + " "
            + record.getString("outcome") + " [" + record.getString("id") + "] "
            + singleLine(record.getString("description", "")));
    }
    return result;
  }

  String csv(String filter) throws Exception {
    Filter matcher = new Filter(filter);
    StringBuilder result = new StringBuilder("sequence,transaction_id,created,finished,outcome,description,evidence,changes\r\n");
    for (Path path : allPaths()) {
      YamlConfiguration record = read(path);
      if (!matcher.matches(record)) continue;
      String[] columns = {String.valueOf(record.getLong("sequence")), record.getString("id", ""),
          record.getString("created", ""), record.getString("finished", ""),
          record.getString("outcome", ""), record.getString("description", ""),
          record.getString("evidence", ""), String.valueOf(record.getList("changes", Collections.emptyList()))};
      for (int i = 0; i < columns.length; i++) {
        if (i > 0) result.append(',');
        // Neutralize spreadsheet formulas in arbitrary player/item names and evidence.
        String value = columns[i];
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) value = "'" + value;
        result.append('"').append(value.replace("\"", "\"\"")).append('"');
      }
      result.append("\r\n");
    }
    return result.toString();
  }

  long count() throws IOException { return allPaths().size(); }

  private static String number(long n) { return String.format(Locale.ROOT, "%020d", n); }
  private static String singleLine(String s) { return s.replace('\n', ' ').replace('\r', ' '); }

  private static String checksum(String text) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    StringBuilder result = new StringBuilder(64);
    for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 255));
    return result.toString();
  }

  private static final class Filter {
    private Instant from, to;
    private String status;
    private final List<String> terms = new ArrayList<String>();
    Filter(String filter) {
      if (filter == null || filter.trim().isEmpty()) return;
      for (String token : filter.trim().split("\\s+")) {
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.startsWith("from:")) from = date(token.substring(5), false);
        else if (lower.startsWith("to:")) to = date(token.substring(3), true);
        else if (lower.startsWith("player:")) terms.add(lower.substring(7));
        else if (lower.startsWith("uuid:")) terms.add(lower.substring(5));
        else if (lower.startsWith("product:")) terms.add(lower.substring(8));
        else if (lower.startsWith("status:")) status = lower.substring(7);
        else terms.add(lower);
      }
      if (from != null && to != null && !from.isBefore(to))
        throw new IllegalArgumentException("History from date must precede to date.");
    }
    boolean matches(YamlConfiguration record) throws IOException {
      if (status != null && !status.equals(record.getString("outcome", "").toLowerCase(Locale.ROOT))) return false;
      if (from != null || to != null) {
        Instant time;
        try { time = Instant.parse(record.getString("created", "")); }
        catch (DateTimeParseException ex) { throw new IOException("Invalid audit timestamp.", ex); }
        if (from != null && time.isBefore(from) || to != null && !time.isBefore(to)) return false;
      }
      String text = (SafeYaml.write(record) + " " + record.getString("description", "") + " "
          + record.getString("evidence", "")).toLowerCase(Locale.ROOT);
      for (String term : terms) if (!text.contains(term)) return false;
      return true;
    }
    private static Instant date(String value, boolean end) {
      try {
        if (value.length() == 10) {
          LocalDate day = LocalDate.parse(value);
          return (end ? day.plusDays(1) : day).atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        return Instant.parse(value);
      } catch (DateTimeParseException ex) {
        throw new IllegalArgumentException("History dates use YYYY-MM-DD or an ISO UTC timestamp.");
      }
    }
  }
}
