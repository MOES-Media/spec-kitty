package ai.speckitty.producer.adapter.fs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Whole-file replacement that is never seen half written: temp file in the same directory, fsync, atomic move. */
public final class AtomicFile {

    private static final AtomicLong FORCED = new AtomicLong();
    private static final int MOVE_ATTEMPTS = 40;
    private static final long MOVE_RETRY_MILLIS = 5;

    private AtomicFile() {}

    /** Replaces {@code target} with {@code content}, creating missing parent directories. */
    public static void write(Path target, byte[] content) throws IOException {
        Path directory = target.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temp = directory.resolve(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                force(channel);
            }
            move(temp, target);
            syncDirectory(directory);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Forces a channel's content and metadata to disk. */
    static void force(FileChannel channel) throws IOException {
        channel.force(true);
        FORCED.incrementAndGet();
    }

    /** How many times a file was forced to disk in this process; for tests. */
    public static long forcedCount() {
        return FORCED.get();
    }

    /**
     * The atomic rename. On Windows a rename over a file another process has open can fail transiently with an access error, so
     * it is retried briefly; no other failure is retried and no non-atomic fallback exists.
     */
    private static void move(Path from, Path to) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException e) {
                if (!isWindows() || attempt == MOVE_ATTEMPTS) {
                    throw e;
                }
                try {
                    Thread.sleep(MOVE_RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Makes the rename itself durable. A directory cannot be opened for sync on Windows, where the call is skipped. */
    static void syncDirectory(Path directory) throws IOException {
        if (isWindows()) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
            FORCED.incrementAndGet();
        }
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
