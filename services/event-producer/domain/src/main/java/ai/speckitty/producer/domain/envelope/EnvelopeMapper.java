package ai.speckitty.producer.domain.envelope;

import ai.speckitty.producer.domain.fault.ParseResult;
import java.util.Map;

/** Splits a persisted row into context attributes and {@code data} as a registry entry says, with no per-type code. */
public final class EnvelopeMapper {

    private EnvelopeMapper() {}

    /**
     * @param attributes the lifted members, by attribute name; members whose value is {@code null} are absent
     * @param data the remaining members, verbatim, under their data names
     */
    public record Mapped(Map<String, Object> attributes, Map<String, Object> data) {}

    public static ParseResult<Mapped> map(TypeEntry entry, Map<String, Object> row) {
        throw new UnsupportedOperationException("red stub");
    }
}
