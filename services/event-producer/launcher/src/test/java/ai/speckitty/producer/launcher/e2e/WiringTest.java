package ai.speckitty.producer.launcher.e2e;

import static ai.speckitty.producer.launcher.e2e.Requests.EVENT;
import static ai.speckitty.producer.launcher.e2e.Requests.MISSION;
import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.application.produce.Produce;
import ai.speckitty.producer.domain.protocol.Outcome;
import ai.speckitty.producer.domain.protocol.ProduceReply;
import ai.speckitty.producer.domain.protocol.ProduceRequest;
import ai.speckitty.producer.domain.protocol.RefusalReason;
import ai.speckitty.producer.domain.protocol.Slot;
import ai.speckitty.producer.launcher.Wiring;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The composition root. These tests reach the fail-closed fallback only through {@link Wiring#failClosedFallback()}, never through
 * {@code production()}, so binding the released schema adapter to {@code production()} later turns none of them red.
 */
class WiringTest {

    private static Path repo(Path parent) throws IOException {
        Path root = Files.createDirectories(parent.resolve("repo")).toRealPath();
        Files.createDirectories(root.resolve(".git"));
        return root;
    }

    private static ProduceReply run(Produce produce, Path root) {
        return produce.produce(new ProduceRequest(1, Requests.laneRow(EVENT), Slot.STATUS, Requests.obj(), root.toString(), 5000));
    }

    private static boolean wroteAnything(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.anyMatch(p -> p.startsWith(root.resolve(".kittify")) || p.startsWith(root.resolve(".git/spec-kitty")));
        }
    }

    @Test
    void theFailClosedFallbackRefusesAWellFormedRowAndWritesNothing(@TempDir Path dir) throws Exception {
        Path root = repo(dir);
        ProduceReply reply = run(Wiring.failClosedFallback(), root);
        assertThat(reply.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(reply.reason()).isEqualTo(RefusalReason.VALIDATOR_UNAVAILABLE);
        assertThat(reply.reasonClass().wire()).isEqualTo("rejected");
        assertThat(wroteAnything(root)).isFalse();
    }

    @Test
    void anAcceptAllValidatorBoundInItsPlaceWouldWriteTheSameRowSoTheRefusalIsTheFallbacksDoing(@TempDir Path dir) throws Exception {
        Path root = repo(dir);
        Produce permissive = Wiring.withValidator(document -> Optional.empty(), E2eTypes.catalog());
        ProduceReply reply = run(permissive, root);
        assertThat(reply.outcome()).isEqualTo(Outcome.PRODUCED);
        assertThat(Files.exists(root.resolve(".kittify/events/publish/missions/" + MISSION + ".jsonl"))).isTrue();
    }

    @Test
    void aValidatorThatRejectsEverythingBoundThroughTheTestFactoryRefusesAsSchemaInvalid(@TempDir Path dir) throws Exception {
        Path root = repo(dir);
        ProduceReply reply = run(Wiring.withValidator(document -> Optional.of("/: no"), E2eTypes.catalog()), root);
        assertThat(reply.reason()).isEqualTo(RefusalReason.SCHEMA_INVALID);
        assertThat(wroteAnything(root)).isFalse();
    }

    @Test
    void theFallbackHasNoRegistryEvenWhenTheRowIsOfAKnownType(@TempDir Path dir) throws Exception {
        Path root = repo(dir);
        for (String eventId : new String[] {EVENT, "01J0000000000000000000F002"}) {
            ProduceReply reply = produce(root, eventId);
            assertThat(reply.reason()).isEqualTo(RefusalReason.VALIDATOR_UNAVAILABLE);
        }
    }

    private static ProduceReply produce(Path root, String eventId) {
        return Wiring.failClosedFallback().produce(
                new ProduceRequest(1, Requests.laneRow(eventId), Slot.LIFECYCLE, Requests.obj(), root.toString(), 5000));
    }
}
