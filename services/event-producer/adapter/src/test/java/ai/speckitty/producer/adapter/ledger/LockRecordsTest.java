package ai.speckitty.producer.adapter.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.domain.retention.LockState;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lock fact is the content of the sidecar (R-21). {@code held.lock} and {@code released.lock} were written by the real
 * {@code kernel.locks} primitive: the first is its record while held (the host name replaced), the second the sidecar after
 * release, which truncates and never unlinks.
 */
class LockRecordsTest {

    /** The {@code started_at} of {@code ledger/locks/held.lock}. */
    private static final Instant STARTED = Instant.parse("2026-10-14T23:12:20.642370Z");

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = LockRecordsTest.class.getResourceAsStream("/ledger/locks/" + name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        }
    }

    private static Path sidecar(Path dir, byte[] bytes) throws IOException {
        Path file = dir.resolve("state.json.lock");
        Files.write(file, bytes);
        return file;
    }

    private static Path sidecar(Path dir, String text) throws IOException {
        return sidecar(dir, text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theSidecarAfterTheRealPrimitiveReleasedItIsEmptyAndHoldsNoLock(@TempDir Path dir) throws IOException {
        byte[] released = fixture("released.lock");
        assertThat(released).as("the real primitive truncates on release").isEmpty();
        assertThat(LockRecords.read(sidecar(dir, released), STARTED)).isEqualTo(LockState.NONE);
    }

    @Test
    void aRecordYoungerThanSixtySecondsIsALiveLock(@TempDir Path dir) throws IOException {
        Path file = sidecar(dir, fixture("held.lock"));
        assertThat(LockRecords.read(file, STARTED)).isEqualTo(LockState.LIVE);
        assertThat(LockRecords.read(file, STARTED.plusSeconds(10))).isEqualTo(LockState.LIVE);
        assertThat(LockRecords.read(file, STARTED.plusSeconds(60))).as("exactly the threshold is not stuck").isEqualTo(LockState.LIVE);
    }

    @Test
    void aRecordOlderThanSixtySecondsIsStaleAndHoldsNoLock(@TempDir Path dir) throws IOException {
        Path file = sidecar(dir, fixture("held.lock"));
        assertThat(LockRecords.read(file, STARTED.plusSeconds(60).plusMillis(1))).isEqualTo(LockState.NONE);
        assertThat(LockRecords.read(file, STARTED.plus(Duration.ofDays(3)))).isEqualTo(LockState.NONE);
    }

    @Test
    void aRecordFromTheFutureIsLiveNotStale(@TempDir Path dir) throws IOException {
        assertThat(LockRecords.read(sidecar(dir, fixture("held.lock")), STARTED.minusSeconds(300))).isEqualTo(LockState.LIVE);
    }

    @Test
    void aMissingSidecarHoldsNoLock(@TempDir Path dir) {
        assertThat(LockRecords.read(dir.resolve("state.json.lock"), STARTED)).isEqualTo(LockState.NONE);
    }

    @Test
    void aSidecarThatCannotBeReadKeepsTheRun(@TempDir Path dir) throws IOException {
        Path asDirectory = Files.createDirectory(dir.resolve("state.json.lock"));
        assertThat(LockRecords.read(asDirectory, STARTED)).isEqualTo(LockState.UNREADABLE);
        byte[] huge = new byte[70 * 1024];
        Arrays.fill(huge, (byte) ' ');
        Path big = Files.write(dir.resolve("big.lock"), huge);
        assertThat(LockRecords.read(big, STARTED)).as("larger than the primitive ever writes").isEqualTo(LockState.UNREADABLE);
    }

    @Test
    void aTruncatedOrMalformedRecordIsNotALock(@TempDir Path dir) throws IOException {
        String held = new String(fixture("held.lock"), StandardCharsets.UTF_8);
        for (String text : new String[] {
            held.substring(0, held.length() / 2),
            "{",
            "[]",
            "null",
            "\"text\"",
            "   ",
            held.replace("\"pid\": 831734, ", ""),
            held.replace("\"schema_version\": 1, ", ""),
            held.replace("\"host\": \"fixture-host\"", "\"host\": 7"),
            held.replace("\"version\": \"4.0.0rc7\"", "\"version\": null"),
            held.replace("\"pid\": 831734", "\"pid\": \"abc\""),
            held.replace("\"pid\": 831734", "\"pid\": {}"),
            held.replace("\"started_at\": \"2026-10-14T23:12:20.642370+00:00\"", "\"started_at\": 12"),
        }) {
            assertThat(LockRecords.read(sidecar(dir, text), STARTED)).as(text).isEqualTo(LockState.NONE);
        }
        assertThat(LockRecords.read(sidecar(dir, new byte[] {(byte) 0xff, (byte) 0xfe, '{'}), STARTED)).isEqualTo(LockState.NONE);
    }

    @Test
    void aWellFormedRecordWhoseStartTimeCannotBeReadKeepsTheRun(@TempDir Path dir) throws IOException {
        String held = new String(fixture("held.lock"), StandardCharsets.UTF_8);
        String garbled = held.replace("2026-10-14T23:12:20.642370+00:00", "yesterday-ish");
        assertThat(LockRecords.read(sidecar(dir, garbled), STARTED)).isEqualTo(LockState.UNREADABLE);
    }

    @Test
    void theSpellingsOfAStartTimeThePythonSideWritesAreAllUnderstood(@TempDir Path dir) throws IOException {
        String held = new String(fixture("held.lock"), StandardCharsets.UTF_8);
        for (String stamp : Map.of(
                        "with an offset", "2026-10-14T23:12:20.642370+00:00",
                        "zulu", "2026-10-14T23:12:20.642370Z",
                        "naive is UTC", "2026-10-14T23:12:20.642370",
                        "no fraction", "2026-10-14T23:12:20+00:00",
                        "space separator", "2026-10-14 23:12:20.642370+00:00",
                        "another offset", "2026-10-15T01:12:20.642370+02:00")
                .values()) {
            String text = held.replace("2026-10-14T23:12:20.642370+00:00", stamp);
            Path file = sidecar(dir, text);
            assertThat(LockRecords.read(file, STARTED.plusSeconds(5))).as(stamp).isEqualTo(LockState.LIVE);
            assertThat(LockRecords.read(file, STARTED.plusSeconds(120))).as(stamp).isEqualTo(LockState.NONE);
        }
    }

    @Test
    void thePresenceOfTheFileAloneIsNeverALock(@TempDir Path dir) throws IOException {
        Path file = sidecar(dir, fixture("released.lock"));
        assertThat(Files.exists(file)).isTrue();
        assertThat(LockRecords.read(file, STARTED)).isEqualTo(LockState.NONE);
    }

    @Test
    void readingTheSidecarLeavesItsBytesAndTimeUntouched(@TempDir Path dir) throws IOException {
        Path file = sidecar(dir, fixture("held.lock"));
        byte[] before = Files.readAllBytes(file);
        Instant modified = Files.getLastModifiedTime(file).toInstant();
        LockRecords.read(file, STARTED);
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(file).toInstant()).isEqualTo(modified);
    }
}
