package ai.speckitty.producer.adapter.publishlog;

import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.SOURCE;
import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.file;
import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.layout;
import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.line;
import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.mission;
import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.project;
import static ai.speckitty.producer.adapter.publishlog.PublishLogFixtures.ulid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.speckitty.producer.adapter.fs.AtomicFile;
import ai.speckitty.producer.application.port.PublishLog;
import ai.speckitty.producer.application.port.PublishLog.Probe;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FilePublishLogTest {

    @TempDir
    Path root;

    private final FilePublishLog log = new FilePublishLog();
    private RepoLayout layout;

    @BeforeEach
    void setUp() throws IOException {
        layout = layout(root);
    }

    private ParseResult<Boolean> append(PublishLog.Target target, String line) {
        return log.append(layout, target, line);
    }

    @Test
    void theTargetsLiveUnderTheLocalTierEventsDirectory() {
        assertThat(mission().relativePath().value()).isEqualTo(".kittify/events/publish/missions/01JAAAA0000000000000000ABD.jsonl");
        assertThat(project().relativePath().value()).isEqualTo(".kittify/events/publish/project-0190a1b2-0000-4000-8000-000000000001.jsonl");
    }

    @Test
    void aTargetIdIsAnUpperCaseUlidOrALowerCaseUuidAndNothingElse() {
        for (String bad : new String[] {"", "../x", "01jaaaa0000000000000000abd", "01JAAAA0000000000000000AB", "01JAAAA0000000000000000ABD/.."}) {
            assertThatThrownBy(() -> new PublishLog.Target(PublishLog.Target.Kind.MISSION, bad)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String bad : new String[] {"01JAAAA0000000000000000ABD", "0190A1B2-0000-4000-8000-000000000001", "../../etc"}) {
            assertThatThrownBy(() -> new PublishLog.Target(PublishLog.Target.Kind.PROJECT, bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void appendWritesTheLineAndOneLfAndCreatesTheDirectories() throws IOException {
        assertThat(append(mission(), line(ulid(1), 1))).isEqualTo(new ParseResult.Accepted<>(false));
        byte[] bytes = Files.readAllBytes(file(layout, mission()));
        assertThat(bytes).isEqualTo((line(ulid(1), 1) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void laterAppendsNeverTouchTheBytesBeforeThem() throws IOException {
        append(mission(), line(ulid(1), 1));
        byte[] first = Files.readAllBytes(file(layout, mission()));
        append(mission(), line(ulid(2), 2));
        append(mission(), line(ulid(3), 3));
        byte[] all = Files.readAllBytes(file(layout, mission()));
        assertThat(all).startsWith(first);
        assertThat(new String(all, StandardCharsets.UTF_8).split("\n")).containsExactly(line(ulid(1), 1), line(ulid(2), 2), line(ulid(3), 3));
    }

    @Test
    void appendForcesTheFileToDisk() {
        long before = AtomicFile.forcedCount();
        append(mission(), line(ulid(1), 1));
        assertThat(AtomicFile.forcedCount()).isGreaterThan(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":1}\n{\"b\":2}", "{\"a\":1}\r", "\uFEFF{\"a\":1}", "not json", "[1]", "", "{\"a\":1} {\"b\":2}", "{\"a\":"})
    void aLineThatIsNotOneCleanJsonObjectIsRefusedAndNothingIsWritten(String bad) {
        ParseResult<Boolean> result = append(mission(), bad);
        assertThat(result).isInstanceOf(ParseResult.Refused.class);
        assertThat(((ParseResult.Refused<Boolean>) result).reason()).isEqualTo(FaultReason.REJECTED);
        assertThat(Files.exists(file(layout, mission()))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"a\":1}", "{\"a\":\"\\n\"}", "{\"a\":[1,2,{\"b\":null}]}"})
    void aCleanJsonObjectLineIsAccepted(String good) {
        assertThat(append(mission(), good)).isInstanceOf(ParseResult.Accepted.class);
    }

    @Test
    void probeFindsADuplicateBySourceAndIdOnly() {
        append(mission(), line(ulid(1), 1));
        assertThat(log.probe(layout, mission(), SOURCE, ulid(1)).duplicate()).isTrue();
        assertThat(log.probe(layout, mission(), SOURCE, ulid(2)).duplicate()).isFalse();
        assertThat(log.probe(layout, mission(), "/spec-kitty/missions/01JAAAA0000000000000000ABE", ulid(1)).duplicate()).isFalse();
        assertThat(log.probe(layout, mission(), "", ulid(1)).duplicate()).isFalse();
    }

    @Test
    void probeOfAFileThatDoesNotExistIsNeitherDuplicateNorTorn() {
        assertThat(log.probe(layout, mission(), SOURCE, ulid(1))).isEqualTo(new Probe(false, null));
    }

    @Test
    void aFinalLineWithoutLfIsTornAtItsOneBasedPosition() throws IOException {
        append(mission(), line(ulid(1), 1));
        append(mission(), line(ulid(2), 2));
        Path path = file(layout, mission());
        Files.write(path, "{\"id\":\"01JAAAA000000000".getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        Probe probe = log.probe(layout, mission(), SOURCE, ulid(9));
        assertThat(probe.duplicate()).isFalse();
        assertThat(probe.torn()).isEqualTo(new PublishLog.TornLine(3));
    }

    @Test
    void appendAfterATornLineLeavesItInPlaceAndStartsANewLine() throws IOException {
        append(mission(), line(ulid(1), 1));
        Path path = file(layout, mission());
        byte[] before = Files.readAllBytes(path);
        byte[] fragment = "{\"id\":\"01JAAAA000000000".getBytes(StandardCharsets.UTF_8);
        Files.write(path, fragment, java.nio.file.StandardOpenOption.APPEND);
        assertThat(append(mission(), line(ulid(2), 2))).isEqualTo(new ParseResult.Accepted<>(true));
        byte[] after = Files.readAllBytes(path);
        String[] lines = new String(after, StandardCharsets.UTF_8).split("\n", -1);
        assertThat(after).startsWith(before);
        assertThat(lines).containsExactly(line(ulid(1), 1), new String(fragment, StandardCharsets.UTF_8), line(ulid(2), 2), "");
        assertThat(log.probe(layout, mission(), SOURCE, ulid(9)).torn()).isNull();
    }

    @Test
    void aCompleteJsonObjectWithoutItsLfIsStillTornAndStillSkippedByDedupeAndRecovery() throws IOException {
        Path path = file(layout, mission());
        Files.createDirectories(path.getParent());
        Files.writeString(path, line(ulid(1), 5));
        Probe probe = log.probe(layout, mission(), SOURCE, ulid(1));
        assertThat(probe.torn()).isEqualTo(new PublishLog.TornLine(1));
        assertThat(probe.duplicate()).isFalse();
        assertThat(log.highestClock(layout)).isEmpty();
    }

    @Test
    void aNonJsonCompleteLineIsSkippedNotFatal() throws IOException {
        Path path = file(layout, mission());
        Files.createDirectories(path.getParent());
        Files.writeString(path, "garbage\n\n" + line(ulid(1), 4) + "\n");
        assertThat(log.probe(layout, mission(), SOURCE, ulid(1)).duplicate()).isTrue();
        assertThat(log.highestClock(layout)).isEqualTo(OptionalInt.of(4));
    }

    @Test
    void highestClockSpansEveryPublishLogFile() {
        assertThat(log.highestClock(layout)).isEmpty();
        append(mission(), line(ulid(1), 3));
        append(project(), line(ulid(2), 9));
        append(mission(), line(ulid(3), 4));
        assertThat(log.highestClock(layout)).isEqualTo(OptionalInt.of(9));
    }

    @Test
    void highestClockReadsIntegersInRangeOnly() throws IOException {
        Path path = file(layout, mission());
        Files.createDirectories(path.getParent());
        String[] lines = {
            "{\"lamportclock\":5.0}", "{\"lamportclock\":\"6\"}", "{\"lamportclock\":-1}", "{\"lamportclock\":2147483648}",
            "{\"lamportclock\":true}", "{\"lamportclock\":null}", "{\"other\":99}", "{\"lamportclock\":2}", "{\"lamportclock\":2147483647}"
        };
        Files.writeString(path, String.join("\n", lines) + "\n");
        assertThat(log.highestClock(layout)).isEqualTo(OptionalInt.of(Integer.MAX_VALUE));
        Files.writeString(path, String.join("\n", java.util.Arrays.copyOf(lines, 8)) + "\n");
        assertThat(log.highestClock(layout)).isEqualTo(OptionalInt.of(2));
    }

    @Test
    void theGoldenLinesAreByteIdenticalToWhatPythonsJsonDumpsWrote() throws Exception {
        byte[] golden;
        try (var in = FilePublishLogTest.class.getResourceAsStream("/publishlog/golden-lines.jsonl")) {
            golden = in.readAllBytes();
        }
        assertThat(golden).isNotEmpty();
        for (var row : GoldenRows.rows()) {
            assertThat(append(mission(), ai.speckitty.producer.adapter.json.Json.render(row))).isInstanceOf(ParseResult.Accepted.class);
        }
        byte[] written = Files.readAllBytes(file(layout, mission()));
        assertThat(written).isEqualTo(golden);
        assertThat(written).doesNotContain((byte) '\r');
        assertThat(written).doesNotContain((byte) 0xEF);
        for (byte b : written) {
            assertThat(b >= 0 && (b >= 0x20 || b == '\n')).as("ASCII, LF only").isTrue();
        }
        assertThat(written[written.length - 1]).isEqualTo((byte) '\n');
    }
}
