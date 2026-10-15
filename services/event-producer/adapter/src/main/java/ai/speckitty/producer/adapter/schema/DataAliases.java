package ai.speckitty.producer.adapter.schema;

import java.util.Map;

/**
 * Data-value aliases the released registry does not carry (FR-018): a legacy {@code Lane} value is published under its canonical one,
 * so {@code doing} is written as {@code in_progress}. The alias is vocabulary, not per-type logic: it is a table of strings keyed by
 * registry type name, member and legacy value. {@link ContractRegistry} refuses a table that names a type the registry no longer
 * holds, so a registry change cannot silently drop an alias. The released schema's enum has no {@code doing} and no {@code
 * uninitialized}; the latter is refused by the schema and never aliased.
 */
final class DataAliases {

    static final Map<String, Map<String, Map<String, String>>> RELEASED = Map.of(
            "lane.transitioned.v1",
            Map.of("from_lane", Map.of("doing", "in_progress"), "to_lane", Map.of("doing", "in_progress")));

    private DataAliases() {}
}
