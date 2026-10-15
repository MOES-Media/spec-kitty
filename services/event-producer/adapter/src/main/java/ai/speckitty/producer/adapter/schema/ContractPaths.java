package ai.speckitty.producer.adapter.schema;

/** The layout of the producer contract and of its embedded copy. */
final class ContractPaths {

    /** The classpath folder the build embeds the contract under (gradle/events-fetch.gradle.kts). */
    static final String EMBEDDED_ROOT = "ai/speckitty/producer/contract/";

    static final String REGISTRY = "registry.json";

    private ContractPaths() {}

    /** The composed row schema of a registry entry {@code name} such as {@code lane.transitioned.v1}. */
    static String rowSchema(String name) {
        return "composed/rows/" + name + ".row.schema.json";
    }
}
