package ai.speckitty.producer.launcher;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The deadline watchdog: a daemon thread that runs {@code onExpire} when {@code deadlineMs} has passed, unless it is cancelled
 * first. The client gives each child a deadline and expects the child to end by itself, so no child outlives an abandoned seam call.
 */
final class Watchdog {

    private enum State {
        PENDING,
        CANCELLED,
        FIRED
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
    private final Thread thread;

    private Watchdog(int deadlineMs, Runnable onExpire) {
        this.thread = new Thread(() -> {
            try {
                Thread.sleep(deadlineMs);
            } catch (InterruptedException e) {
                return;
            }
            if (state.compareAndSet(State.PENDING, State.FIRED)) {
                onExpire.run();
            }
        }, "deadline-watchdog");
        this.thread.setDaemon(true);
    }

    static Watchdog start(int deadlineMs, Runnable onExpire) {
        Watchdog watchdog = new Watchdog(deadlineMs, onExpire);
        watchdog.thread.start();
        return watchdog;
    }

    /** Stops the watchdog. True when it had not fired. */
    boolean cancel() {
        boolean cancelled = state.compareAndSet(State.PENDING, State.CANCELLED);
        thread.interrupt();
        return cancelled;
    }
}
