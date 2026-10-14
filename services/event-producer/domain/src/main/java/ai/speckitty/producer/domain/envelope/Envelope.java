package ai.speckitty.producer.domain.envelope;

import ai.speckitty.producer.domain.fault.ParseResult;
import java.util.Map;

/** A CloudEvents 1.0 structured-JSON envelope: context attributes only, names and types checked, absent values omitted. */
public record Envelope(Map<String, AttributeValue> attributes) {

    public Envelope {
        attributes = Map.copyOf(attributes);
    }

    /**
     * Builds an envelope from raw attributes. A {@code null} value is an absent attribute and is omitted. Refused as
     * {@code rejected}: a name outside the rule, a value of a type CloudEvents attributes do not have, a missing core attribute
     * ({@code specversion} 1.0, {@code id} ULID, {@code source}, {@code type}), a bad {@code source} or an unparseable {@code time}.
     */
    public static ParseResult<Envelope> build(Map<String, Object> raw) {
        throw new UnsupportedOperationException("red stub");
    }

    public AttributeValue get(String name) {
        return attributes.get(name);
    }
}
