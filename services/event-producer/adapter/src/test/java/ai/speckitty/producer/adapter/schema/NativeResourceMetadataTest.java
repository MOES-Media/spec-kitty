package ai.speckitty.producer.adapter.schema;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.domain.fault.ParseResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The embedded contract only exists in the native binary if its resources are registered: native-image drops every classpath
 * resource nobody lists. The schema adapter owns {@code META-INF/native-image/ai.speckitty/producer-schema/}; this test holds the
 * glob list against the files the build really embeds, so a narrowed glob or a new embedded folder cannot go unnoticed. The native
 * run itself (native-smoke) is the end-to-end proof; this is its JVM-side guard.
 */
class NativeResourceMetadataTest {

    private static final String METADATA = "/META-INF/native-image/ai.speckitty/producer-schema/reachability-metadata.json";

    @SuppressWarnings("unchecked")
    private static List<String> globs() throws IOException {
        try (InputStream in = NativeResourceMetadataTest.class.getResourceAsStream(METADATA)) {
            assertThat(in).as(METADATA).isNotNull();
            ParseResult<Map<String, Object>> parsed = Json.parseObject(in.readAllBytes());
            assertThat(parsed).isInstanceOf(ParseResult.Accepted.class);
            Object resources = ((ParseResult.Accepted<Map<String, Object>>) parsed).value().get("resources");
            assertThat(resources).isInstanceOf(List.class);
            List<String> globs = new ArrayList<>();
            for (Object item : (List<Object>) resources) {
                globs.add((String) ((Map<String, Object>) item).get("glob"));
            }
            return globs;
        }
    }

    /** GraalVM globs: {@code *} stays inside one path element, {@code **} crosses them. */
    static boolean matches(String glob, String resource) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return resource.matches(regex.toString());
    }

    @Test
    void theGlobMatcherKeepsASingleStarInsideOnePathElement() {
        assertThat(matches("a/b/**", "a/b/c/d.json")).isTrue();
        assertThat(matches("a/b/*", "a/b/c.json")).isTrue();
        assertThat(matches("a/b/*", "a/b/c/d.json")).isFalse();
        assertThat(matches("a/b/**", "a/x/c.json")).isFalse();
    }

    @Test
    void everyResourceTheBuildEmbedsIsCoveredByAGlobOfTheSchemaMetadata() throws IOException {
        Path contract = Path.of(System.getProperty("events.contract.dir"));
        List<String> embedded = new ArrayList<>(List.of(ContractPaths.EMBEDDED_ROOT + ContractPaths.REGISTRY));
        try (Stream<Path> rows = Files.list(contract.resolve("composed/rows"))) {
            rows.map(p -> ContractPaths.EMBEDDED_ROOT + "composed/rows/" + p.getFileName()).sorted().forEach(embedded::add);
        }
        assertThat(embedded.size()).as("floor: the registry and one row schema per type").isGreaterThanOrEqualTo(28);

        List<String> globs = globs();
        assertThat(globs).isNotEmpty();
        for (String resource : embedded) {
            assertThat(globs.stream().anyMatch(glob -> matches(glob, resource))).as(resource + " is registered as a native-image resource").isTrue();
            assertThat(NativeResourceMetadataTest.class.getClassLoader().getResource(resource)).as(resource + " is on the class path").isNotNull();
        }
    }

    @Test
    void theMetadataRegistersNothingBeyondTheEmbeddedContract() throws IOException {
        assertThat(globs()).allSatisfy(glob -> assertThat(glob).startsWith(ContractPaths.EMBEDDED_ROOT));
    }
}
