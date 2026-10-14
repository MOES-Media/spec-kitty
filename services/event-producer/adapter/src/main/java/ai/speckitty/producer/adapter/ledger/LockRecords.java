package ai.speckitty.producer.adapter.ledger;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.retention.LockState;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads what a Python-owned lock sidecar says about a run: its content, never its presence (FR-029, R-21). It reads bytes only.
 * It never takes or tests an OS lock: {@code FileChannel.tryLock} and Python's {@code fcntl.flock} are different advisory-lock
 * families, so a Java lock test would report a live Python holder as free. {@code clock.lock} is never read here.
 *
 * <p>A live lock is a non-empty, parsable record (the keys {@code schema_version}, {@code pid}, {@code started_at}, {@code host},
 * {@code version} of {@code kernel.locks.LockRecord}) no older than {@link #STALE_AFTER}. An empty, truncated or malformed sidecar
 * holds no lock, as in {@code read_lock_record}. A sidecar that cannot be read keeps the run; so does a well-formed record whose
 * start time this reader cannot understand, because the Python side might.
 */
public final class LockRecords {

    /** {@code kernel.locks.STALE_AFTER_S_DEFAULT}: a record older than this is stale. */
    public static final Duration STALE_AFTER = Duration.ofSeconds(60);

    /** The primitive reads at most 64 KiB of a sidecar; a larger one is not a record it wrote. */
    static final int MAX_BYTES = 64 * 1024;

    private static final Pattern INTEGER_TEXT = Pattern.compile("\\s*[+-]?[0-9]+\\s*");

    private LockRecords() {}

    public static LockState read(Path sidecar, Instant now) {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(sidecar)) {
            bytes = in.readNBytes(MAX_BYTES + 1);
        } catch (NoSuchFileException e) {
            return LockState.NONE;
        } catch (IOException | RuntimeException e) {
            return LockState.UNREADABLE;
        }
        if (bytes.length > MAX_BYTES) {
            return LockState.UNREADABLE;
        }
        if (!(Json.parseObject(bytes) instanceof ParseResult.Accepted<Map<String, Object>> parsed)) {
            return LockState.NONE;
        }
        Map<String, Object> record = parsed.value();
        if (!isIntegerLike(record.get("schema_version"))
                || !isIntegerLike(record.get("pid"))
                || !(record.get("started_at") instanceof String startedText)
                || !(record.get("host") instanceof String)
                || !(record.get("version") instanceof String)) {
            return LockState.NONE;
        }
        Instant started = parseStart(startedText);
        if (started == null) {
            return LockState.UNREADABLE;
        }
        Duration age = Duration.between(started, now);
        return age.compareTo(STALE_AFTER) > 0 ? LockState.NONE : LockState.LIVE;
    }

    /** What Python's {@code int(...)} accepts without raising: a number, a boolean, or text of digits. */
    private static boolean isIntegerLike(Object value) {
        return value instanceof JsonNumber
                || value instanceof Boolean
                || (value instanceof String text && INTEGER_TEXT.matcher(text).matches());
    }

    /** An ISO-8601 time; a time without an offset is UTC (as {@code _record_from_payload} does); null when not understood. */
    private static Instant parseStart(String text) {
        String normalised = text.length() > 10 && text.charAt(10) == ' ' ? text.substring(0, 10) + "T" + text.substring(11) : text;
        try {
            return OffsetDateTime.parse(normalised).toInstant();
        } catch (DateTimeException withOffset) {
            try {
                return LocalDateTime.parse(normalised).toInstant(ZoneOffset.UTC);
            } catch (DateTimeException naive) {
                return null;
            }
        }
    }
}
