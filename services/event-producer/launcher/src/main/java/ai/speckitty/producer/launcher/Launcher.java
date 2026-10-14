package ai.speckitty.producer.launcher;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.domain.fault.ParseResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * The stdin/stdout protocol (plan section 5): one UTF-8 JSON object in, one out. Exit codes: 0 a reply was written (a refusal is a
 * reply); 64 the request is unreadable or its version unsupported (no reply); 75 the deadline passed (the watchdog halts the
 * process); 70 an internal failure (no reply). The client treats every other exit, a signal and an empty stdout as a crash.
 * Nothing is written to standard error, and no reply carries a row, an absolute path or a stack trace.
 */
public final class Launcher {

    public static final int EXIT_OK = 0;
    public static final int EXIT_UNREADABLE = 64;
    public static final int EXIT_INTERNAL = 70;
    public static final int EXIT_DEADLINE = 75;

    /** The largest request read; a larger one is unreadable. */
    public static final int MAX_REQUEST_BYTES = 16 * 1024 * 1024;

    private final Supplier<Dispatcher> dispatcher;
    private final IntConsumer halt;

    /** A launcher that halts the process by the deadline. */
    public Launcher(Supplier<Dispatcher> dispatcher) {
        this(dispatcher, code -> Runtime.getRuntime().halt(code));
    }

    /** A launcher whose deadline action is {@code halt}, for tests. */
    public Launcher(Supplier<Dispatcher> dispatcher, IntConsumer halt) {
        this.dispatcher = dispatcher;
        this.halt = halt;
    }

    /** Reads one request from {@code in}, writes at most one reply to {@code out}, and returns the exit code. */
    public int run(InputStream in, OutputStream out) {
        byte[] bytes;
        try {
            bytes = readBounded(in);
        } catch (IOException e) {
            return EXIT_UNREADABLE;
        }
        if (bytes == null || !(Json.parseObject(bytes) instanceof ParseResult.Accepted<Map<String, Object>> parsed)) {
            return EXIT_UNREADABLE;
        }
        Map<String, Object> request = parsed.value();
        if (!Fields.isVersionOne(request.get("v")) || !(request.get("op") instanceof String operation)) {
            return EXIT_UNREADABLE;
        }
        int deadlineMs = Fields.deadlineMs(request.get("deadline_ms"));
        if (deadlineMs < 1) {
            return EXIT_UNREADABLE;
        }
        Dispatcher operations;
        try {
            operations = dispatcher.get();
        } catch (RuntimeException e) {
            return EXIT_INTERNAL;
        }
        Optional<OperationHandler> handler = operations.find(operation);

        Watchdog watchdog = Watchdog.start(deadlineMs, () -> halt.accept(EXIT_DEADLINE));
        Map<String, Object> reply;
        try {
            reply = handler.isPresent() ? handler.get().handle(request) : Replies.unsupported();
        } catch (RequestException e) {
            watchdog.cancel();
            return EXIT_UNREADABLE;
        } catch (RuntimeException | Error e) {
            watchdog.cancel();
            return EXIT_INTERNAL;
        }
        watchdog.cancel();
        try {
            out.write((Json.render(reply) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException | RuntimeException e) {
            return EXIT_INTERNAL;
        }
        return EXIT_OK;
    }

    /** All of {@code in}, or null when it exceeds {@link #MAX_REQUEST_BYTES}. */
    private static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (collected.size() + read > MAX_REQUEST_BYTES) {
                return null;
            }
            collected.write(buffer, 0, read);
        }
        return collected.toByteArray();
    }
}
