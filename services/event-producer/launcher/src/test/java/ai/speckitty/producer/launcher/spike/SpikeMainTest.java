package ai.speckitty.producer.launcher.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SpikeMainTest {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Test
    void acceptsTheInlineDefaultRequestOfTheSmokeAction() throws IOException {
        JsonNode reply = MAPPER.readTree(SpikeMain.handle("{\"v\":1,\"op\":\"produce\",\"start_path\":\".\"}"));
        assertThat(reply.get("ok").asBoolean()).isTrue();
        assertThat(reply.get("locked").asBoolean()).isTrue();
        assertThat(reply.get("hostname_ok").asBoolean()).isTrue();
        assertThat(reply.get("op").asText()).isEqualTo("produce");
    }

    @Test
    void refusesARequestThatBreaksTheSchema() throws IOException {
        JsonNode reply = MAPPER.readTree(SpikeMain.handle("{\"v\":2,\"op\":\"produce\"}"));
        assertThat(reply.get("ok").asBoolean()).isFalse();
        assertThat(reply.get("error").asText()).isEqualTo("schema");
        assertThat(reply.get("errors").asInt()).isPositive();
    }

    @Test
    void hostNameIsNonEmpty() {
        assertThat(HostName.get()).isNotEmpty();
    }

    @Test
    void theClassNamedByTheProducerMainClassPropertyExistsAndHasMain() throws ClassNotFoundException, NoSuchMethodException {
        String name = System.getProperty("producer.mainClass");
        assertThat(name).as("producer.mainClass system property, set from producerMainClass in launcher/build.gradle.kts").isNotBlank();
        Method main = Class.forName(name).getMethod("main", String[].class);
        assertThat(Modifier.isStatic(main.getModifiers())).isTrue();
        assertThat(main.getReturnType()).isEqualTo(void.class);
    }
}
