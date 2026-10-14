package ai.speckitty.producer.adapter.fs;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An exclusive lock on a lock file, between threads and between processes, with a bounded wait. Between processes it is a
 * {@link FileChannel} lock (POSIX {@code fcntl} byte-range, Windows {@code LockFileEx}) on one descriptor held for the whole span.
 * Inside one JVM a second channel on the same file would raise {@link OverlappingFileLockException}, so threads queue on a
 * per-file monitor first. The lock file is created empty and never replaced or truncated.
 */
public final class ExclusiveLock implements AutoCloseable {

    private static final ConcurrentMap<Path, ReentrantLock> MONITORS = new ConcurrentHashMap<>();
    private static final long POLL_MILLIS = 5;

    private final ReentrantLock monitor;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    private ExclusiveLock(ReentrantLock monitor, FileChannel channel, FileLock lock) {
        this.monitor = monitor;
        this.channel = channel;
        this.lock = lock;
    }

    /** Takes the lock, waiting at most {@code waitMillis}; empty when it could not be had in time. */
    public static Optional<ExclusiveLock> acquire(Path lockFile, long waitMillis) throws IOException {
        Path key = lockFile.toAbsolutePath().normalize();
        Files.createDirectories(key.getParent());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        ReentrantLock monitor = MONITORS.computeIfAbsent(key, k -> new ReentrantLock());
        try {
            if (!monitor.tryLock(waitMillis, TimeUnit.MILLISECONDS)) {
                return Optional.empty();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        FileChannel channel = null;
        try {
            channel = FileChannel.open(key, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            while (true) {
                FileLock held = tryLock(channel);
                if (held != null) {
                    return Optional.of(new ExclusiveLock(monitor, channel, held));
                }
                if (System.nanoTime() - deadline >= 0) {
                    break;
                }
                Thread.sleep(POLL_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            release(monitor, channel, null);
            throw e;
        }
        release(monitor, channel, null);
        return Optional.empty();
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }

    private static void release(ReentrantLock monitor, FileChannel channel, FileLock lock) {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
            // closing the channel releases the lock as well
        } finally {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException ignored) {
                // nothing left to release
            } finally {
                monitor.unlock();
            }
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            release(monitor, channel, lock);
        }
    }
}
