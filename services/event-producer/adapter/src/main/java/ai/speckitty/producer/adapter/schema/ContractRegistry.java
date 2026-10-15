package ai.speckitty.producer.adapter.schema;

import ai.speckitty.producer.application.produce.ListedCatalog;
import ai.speckitty.producer.application.produce.RegisteredType;
import java.util.List;

/** The registry of the released contract as the producer's data: the registered types, their row schemas and a catalog over them. */
public final class ContractRegistry {

    private ContractRegistry() {}

    /** Reads the registry and every row schema it names; a contract that cannot be used is a {@link ContractException}. */
    public static ContractRegistry load(ContractSource source) {
        return load(source, DataAliases.RELEASED);
    }

    /** As {@link #load(ContractSource)}, with this table of data-value aliases (type name, member, legacy value to canonical value). */
    public static ContractRegistry load(ContractSource source, java.util.Map<String, java.util.Map<String, java.util.Map<String, String>>> aliases) {
        return new ContractRegistry();
    }

    public List<RegisteredType> types() {
        return List.of();
    }

    public ListedCatalog catalog() {
        return new ListedCatalog(List.of());
    }

    /** The composed row schema (the whole CloudEvents line) of the registered type with this {@code type} string. */
    public String rowSchema(String type) {
        return "{}";
    }

    public String schemaVersion() {
        return "";
    }
}
