package ai.speckitty.producer.adapter.fs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Child process of {@link ExclusiveLockTest}. {@code count <lock> <counter> <times>} increments a counter file read-modify-write
 * under the lock, with a pause in the middle that makes an unlocked run lose updates. {@code hold <lock> <ready> <millis>} takes
 * the lock, writes the ready file and sleeps. {@code try <lock> <millis>} waits at most that long and exits 0 when it got the lock, 4
 * when it did not.
 */
public final class LockChild {

    private LockChild() {}

    public static void main(String[] args) throws Exception {
        Path lock = Path.of(args[1]);
        switch (args[0]) {
            case "count" -> {
                Path counter = Path.of(args[2]);
                int times = Integer.parseInt(args[3]);
                for (int i = 0; i < times; i++) {
                    Optional<ExclusiveLock> held = ExclusiveLock.acquire(lock, 60_000);
                    if (held.isEmpty()) {
                        System.exit(3);
                    }
                    try (ExclusiveLock ignored = held.get()) {
                        int value = Integer.parseInt(Files.readString(counter).trim());
                        Thread.sleep(2);
                        Files.writeString(counter, Integer.toString(value + 1));
                    }
                }
            }
            case "hold" -> {
                Optional<ExclusiveLock> held = ExclusiveLock.acquire(lock, 60_000);
                if (held.isEmpty()) {
                    System.exit(3);
                }
                try (ExclusiveLock ignored = held.get()) {
                    Files.writeString(Path.of(args[2]), "held");
                    Thread.sleep(Long.parseLong(args[3]));
                }
            }
            case "try" -> {
                Optional<ExclusiveLock> held = ExclusiveLock.acquire(lock, Long.parseLong(args[2]));
                if (held.isEmpty()) {
                    System.exit(4);
                }
                held.get().close();
            }
            default -> System.exit(2);
        }
    }
}
