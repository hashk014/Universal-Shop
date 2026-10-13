package fr.hash014.universalshop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class AtomicFiles {
  private AtomicFiles() {}

  public static void write(Path path, String text) throws IOException {
    Files.createDirectories(path.toAbsolutePath().getParent());
    Path temp = path.resolveSibling(path.getFileName() + ".tmp");
    try (FileChannel channel =
        FileChannel.open(
            temp,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
      while (bytes.hasRemaining()) channel.write(bytes);
      channel.force(true);
    }
    // Do not fall back to an unsafe truncate-and-write operation.
    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }
}
