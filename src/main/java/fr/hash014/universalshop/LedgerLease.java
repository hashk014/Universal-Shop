package fr.hash014.universalshop;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;

/** An OS-held lease prevents two server processes from writing the same shop data. */
final class LedgerLease implements AutoCloseable {
  private final FileChannel channel;
  private final FileLock lock;
  LedgerLease(Path directory) throws IOException {
    Files.createDirectories(directory);
    FileChannel opened = FileChannel.open(directory.resolve("ledger.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    FileLock acquired;
    try {
      acquired = opened.tryLock();
      if (acquired == null) throw new IOException("Another server process is using this UniversalShop directory.");
    } catch (IOException | OverlappingFileLockException error) {
      opened.close();
      throw new IOException("UniversalShop data is already in use; each server needs its own directory.", error);
    }
    channel = opened; lock = acquired;
  }
  @Override public void close() throws IOException {
    try { if (lock.isValid()) lock.release(); }
    finally { channel.close(); }
  }
}
