package ai.speckitty.producer.domain.envelope;

import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.math.BigInteger;

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
        if (value instanceof String text) {
            return new ParseResult.Accepted<>(new Text(text));
        }
        if (value instanceof Boolean flag) {
            return new ParseResult.Accepted<>(new Flag(flag));
        }
        if (value instanceof JsonNumber number && number.isInteger()) {
            BigInteger exact = number.integerValue().orElseThrow();
            if (exact.bitLength() <= Integer.SIZE - 1) {
                return new ParseResult.Accepted<>(new Integer32(exact.intValueExact()));
            }
            return new ParseResult.Refused<>(FaultReason.REJECTED, "integer attribute outside the 32-bit range");
        }
        return new ParseResult.Refused<>(FaultReason.REJECTED, "value has no CloudEvents attribute type");
    }
}
