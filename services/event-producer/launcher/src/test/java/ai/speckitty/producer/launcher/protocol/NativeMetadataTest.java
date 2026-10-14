package ai.speckitty.producer.launcher.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.launcher.OperationHandler;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The launcher's own native-image directory holds the one registration it owns: the operation providers' services file. */
class NativeMetadataTest {

    private static final String METADATA =
            "/META-INF/native-image/ai.speckitty/producer-launcher/reachability-metadata.json";

    @Test
    void theLauncherOwnsADirectoryOfItsOwnAndNoSpikeDirectoryRemains() throws IOException {
        try (InputStream in = NativeMetadataTest.class.getResourceAsStream(METADATA)) {
            assertThat(in).as(METADATA).isNotNull();
        }
        assertThat(NativeMetadataTest.class.getResource("/META-INF/native-image/ai.speckitty/producer-spike/reachability-metadata.json")).isNull();
    }

    @Test
    void theMetadataIncludesTheServicesFileOfTheOperationProviders() throws IOException {
        try (InputStream in = NativeMetadataTest.class.getResourceAsStream(METADATA)) {
            assertThat(in).isNotNull();
            ParseResult<Map<String, Object>> parsed = Json.parseObject(in.readAllBytes());
            assertThat(parsed).isInstanceOf(ParseResult.Accepted.class);
            Map<String, Object> metadata = ((ParseResult.Accepted<Map<String, Object>>) parsed).value();
            assertThat(metadata.get("resources")).isInstanceOf(List.class);
            assertThat(Json.render(metadata.get("resources"))).contains("META-INF/services/" + OperationHandler.class.getName());
        }
    }
}
