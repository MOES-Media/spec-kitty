package ai.speckitty.producer.domain.envelope;

import ai.speckitty.producer.domain.fault.ParseResult;

/** A CloudEvents attribute value: a String, a 32-bit Integer or a Boolean. Never null, never a structured value (FR-010). */
public sealed interface AttributeValue {

    /** A String attribute. */
    record Text(String value) implements AttributeValue {}

    /** An Integer attribute (32-bit signed, the CloudEvents range). */
    record Integer32(int value) implements AttributeValue {}

    /** A Boolean attribute. */
    record Flag(boolean value) implements AttributeValue {}

    /** The attribute value for a parsed row value; null, a fractional number, a list and an object are refused as {@code rejected}. */
    static ParseResult<AttributeValue> fromRowValue(Object value) {
        throw new UnsupportedOperationException("red stub");
    }
}
