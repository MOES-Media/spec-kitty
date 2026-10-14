package ai.speckitty.producer.launcher.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.launcher.FileStartContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileStartContextTest {

    private static final String UUID = "8a4a7da6-a97c-4bb4-893a-b31664abfee4";
    private final FileStartContext context = new FileStartContext();

    private static RepoLayout layout(Path root) {
        return new RepoLayout(root, root.resolve(".git"));
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    @Test
    void metaJsonOfTheStartDirectoryIsParsed(@TempDir Path dir) throws IOException {
        write(dir.resolve("meta.json"), "{\"mission_id\":\"01J0000000000000000000MSN1\",\"n\":5.0}");
        assertThat(context.metaJson(dir)).isPresent();
        assertThat(context.metaJson(dir).orElseThrow()).containsEntry("mission_id", "01J0000000000000000000MSN1");
        assertThat(context.metaJson(dir).orElseThrow().get("n")).isEqualTo(new ai.speckitty.producer.domain.value.JsonNumber("5.0"));
    }

    @Test
    void aDirectoryWithoutMetaJsonOrAMissingOneIsEmptyNotAnError(@TempDir Path dir) {
        assertThat(context.metaJson(dir)).isEmpty();
        assertThat(context.metaJson(dir.resolve("absent"))).isEmpty();
    }

    @Test
    void aMetaJsonIsLookedForInTheStartDirectoryOnlyNeverAbove(@TempDir Path dir) throws IOException {
        write(dir.resolve("meta.json"), "{\"mission_id\":\"x\"}");
        Path child = Files.createDirectories(dir.resolve("child/grandchild"));
        assertThat(context.metaJson(child)).isEmpty();
    }

    @Test
    void aStartPathThatIsAFileHasNoMetaJson(@TempDir Path dir) throws IOException {
        write(dir.resolve("meta.json"), "{\"mission_id\":\"x\"}");
        write(dir.resolve("file.txt"), "text");
        assertThat(context.metaJson(dir.resolve("file.txt"))).isEmpty();
    }

    @Test
    void anUnparseableOrNonObjectMetaJsonIsEmptyNotAnError(@TempDir Path dir) throws IOException {
        for (String text : new String[] {"", "{", "[1]", "null", "not json", "{\"a\":1,\"a\":2}"}) {
            write(dir.resolve("meta.json"), text);
            assertThat(context.metaJson(dir)).as(text).isEmpty();
        }
    }

    @Test
    void anOversizeMetaJsonIsEmpty(@TempDir Path dir) throws IOException {
        write(dir.resolve("meta.json"), "{\"pad\":\"" + "x".repeat(FileStartContext.MAX_META_BYTES) + "\"}");
        assertThat(context.metaJson(dir)).isEmpty();
    }

    @Test
    void theProjectUuidIsReadFromTheProjectBlockOfTheConfig(@TempDir Path dir) throws IOException {
        write(dir.resolve(".kittify/config.yaml"), "vcs:\n  type: git\nproject:\n  uuid: " + UUID + "\n  slug: demo\n");
        assertThat(context.projectUuid(layout(dir))).contains(UUID);
    }

    @Test
    void quotesCommentsAndCarriageReturnsAroundTheUuidAreTolerated(@TempDir Path dir) throws IOException {
        for (String value : new String[] {"\"" + UUID + "\"", "'" + UUID + "'", UUID + " # the id", "\"" + UUID + "\"   # c"}) {
            write(dir.resolve(".kittify/config.yaml"), "project:\r\n  uuid: " + value + "\r\n");
            assertThat(context.projectUuid(layout(dir))).as(value).contains(UUID);
        }
    }

    @Test
    void aUuidOutsideTheProjectBlockOrNotAUuidIsNotTaken(@TempDir Path dir) throws IOException {
        write(dir.resolve(".kittify/config.yaml"), "other:\n  uuid: " + UUID + "\nproject:\n  slug: demo\nlater:\n  uuid: " + UUID + "\n");
        assertThat(context.projectUuid(layout(dir))).isEmpty();
        write(dir.resolve(".kittify/config.yaml"), "project:\n  uuid: not-a-uuid\n");
        assertThat(context.projectUuid(layout(dir))).isEmpty();
        write(dir.resolve(".kittify/config.yaml"), "project:\n  uuid:\n");
        assertThat(context.projectUuid(layout(dir))).isEmpty();
        write(dir.resolve(".kittify/config.yaml"), "uuid: " + UUID + "\n");
        assertThat(context.projectUuid(layout(dir))).as("a top-level uuid key is not the project's").isEmpty();
    }

    @Test
    void aMissingOrUnreadableConfigIsEmpty(@TempDir Path dir) throws IOException {
        assertThat(context.projectUuid(layout(dir))).isEqualTo(Optional.empty());
        Files.createDirectories(dir.resolve(".kittify/config.yaml"));
        assertThat(context.projectUuid(layout(dir))).isEmpty();
    }

    @Test
    void anUpperCaseUuidIsLowerCased(@TempDir Path dir) throws IOException {
        write(dir.resolve(".kittify/config.yaml"), "project:\n  uuid: " + UUID.toUpperCase() + "\n");
        assertThat(context.projectUuid(layout(dir))).contains(UUID);
    }
}
