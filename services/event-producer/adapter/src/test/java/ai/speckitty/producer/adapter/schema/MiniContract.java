package ai.speckitty.producer.adapter.schema;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** A small in-memory contract in the shape of the released one, for the unit tests of the adapter. */
final class MiniContract implements ContractSource {

    static final String LANE = "ai.spec-kitty.lane.transitioned.v1";
    static final String CREATED = "ai.spec-kitty.mission.created.v1";
    static final String BASE = "https://schemas.example.invalid/spec-kitty/";

    final Map<String, String> files = new LinkedHashMap<>();

    MiniContract() {
        files.put("registry.json", registry(laneType(), createdType()));
        files.put("composed/rows/lane.transitioned.v1.row.schema.json", rowSchema(LANE, "lane.transitioned.v1"));
        files.put("composed/rows/mission.created.v1.row.schema.json", rowSchema(CREATED, "mission.created.v1"));
    }

    @Override
    public Optional<String> read(String relativePath) {
        return Optional.ofNullable(files.get(relativePath));
    }

    static String registry(String... types) {
        return "{\"registry_version\":\"1.0.0\",\"specversion\":\"1.0\",\"type_prefix\":\"ai.spec-kitty\",\"dataschema_base\":\"" + BASE
                + "\",\"schemaversion\":\"4.0.0\",\"legacy_shapes\":{\"flat\":{\"form\":\"flat\"},\"lifecycle_envelope\":{\"form\":\"payload_envelope\","
                + "\"payload_member\":\"payload\",\"discriminator_member\":\"event_type\",\"members\":{\"event_id\":{},\"event_type\":{},"
                + "\"aggregate_id\":{},\"timestamp\":{},\"project_uuid\":{}}}},\"types\":[" + String.join(",", types) + "]}";
    }

    static String laneType() {
        return "{\"type\":\"" + LANE + "\",\"name\":\"lane.transitioned.v1\",\"dataschema\":\"" + BASE + "lane.transitioned.v1\","
                + "\"schema\":\"data/lane.transitioned.v1.schema.json\",\"scope\":\"mission\",\"subject\":\"wp_id\","
                + "\"required_attributes\":[\"executionmode\"],\"legacy\":{\"shape\":\"flat\",\"constants\":{}},\"lifted\":["
                + "{\"member\":\"event_id\",\"attribute\":\"id\"},{\"member\":\"at\",\"attribute\":\"time\"},"
                + "{\"member\":\"wp_id\",\"attribute\":\"subject\"},"
                + "{\"member\":\"actor\",\"attribute\":\"actor\",\"projection\":\"actor_identity\",\"binding_member\":\"actorbinding\"},"
                + "{\"member\":\"execution_mode\",\"attribute\":\"executionmode\"}]}";
    }

    static String createdType() {
        return "{\"type\":\"" + CREATED + "\",\"name\":\"mission.created.v1\",\"dataschema\":\"" + BASE + "mission.created.v1\","
                + "\"schema\":\"data/mission.created.v1.schema.json\",\"scope\":\"mission\",\"subject\":\"none\","
                + "\"required_attributes\":[],\"legacy\":{\"shape\":\"lifecycle_envelope\",\"discriminator_value\":\"MissionCreated\"},\"lifted\":["
                + "{\"member\":\"event_id\",\"attribute\":\"id\"},{\"member\":\"timestamp\",\"attribute\":\"time\"},"
                + "{\"member\":\"event_type\",\"attribute\":\"type\"}]}";
    }

    /** A row schema: the type is pinned, {@code data} is closed with one enum member. */
    static String rowSchema(String type, String name) {
        return "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"$id\":\"" + BASE + name + ".row\",\"type\":\"object\","
                + "\"properties\":{\"type\":{\"type\":\"string\",\"const\":\"" + type + "\"},"
                + "\"lamportclock\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647},"
                + "\"data\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"enum\":[\"a\",\"b\"]}},"
                + "\"required\":[\"name\"],\"additionalProperties\":false}},\"required\":[\"type\",\"data\"]}";
    }
}
