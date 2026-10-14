package fr.hash014.universalshop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class AtomicFiles {
  private AtomicFiles() {}

  public static void write(Path path, String text) throws IOException {
    write(path, text.getBytes(StandardCharsets.UTF_8));
  }

  public static void write(Path path, byte[] content) throws IOException {
    Files.createDirectories(path.toAbsolutePath().getParent());
    Path temp = Files.createTempFile(path.toAbsolutePath().getParent(), path.getFileName() + ".", ".tmp");
    boolean published = false;
    try {
      try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        ByteBuffer bytes = ByteBuffer.wrap(content);
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
      }
      // Do not fall back to an unsafe truncate-and-write operation.
      replace(temp, path);
      published = true;
    } finally {
      if (!published) Files.deleteIfExists(temp);
    }
  }

  /** Publish an already-written temporary file without truncating the existing destination. */
  public static void replace(Path temp, Path path) throws IOException {
    try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    forceDirectory(path.toAbsolutePath().getParent());
  }

  public static void delete(Path path) throws IOException {
    Files.deleteIfExists(path);
    forceDirectory(path.toAbsolutePath().getParent());
  }

  private static void forceDirectory(Path directory) throws IOException {
    // Windows does not expose directory fsync through FileChannel. File contents are still
    // forced before every atomic move. Unix filesystems receive the directory barrier too.
    if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return;
    try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
      channel.force(true);
    }
  }
}
