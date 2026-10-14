package ai.speckitty.producer.domain.envelope;

import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.ids.EventId;
import ai.speckitty.producer.domain.order.Rfc3339;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** A CloudEvents 1.0 structured-JSON envelope: context attributes only, names and types checked, absent values omitted. */
public record Envelope(Map<String, AttributeValue> attributes) {

    private static final Pattern TYPE = Pattern.compile("ai\\.spec-kitty\\.[a-z0-9]+(\\.[a-z0-9]+)*\\.v[1-9][0-9]*");

    public Envelope {
        attributes = Collections.unmodifiableMap(new TreeMap<>(attributes));
    }

    /**
     * Builds an envelope from raw attributes. A {@code null} value is an absent attribute and is omitted. Refused as
     * {@code rejected}: a name outside the rule, a value of a type CloudEvents attributes do not have, a missing core attribute
     * ({@code specversion} 1.0, {@code id} ULID, {@code source}, {@code type}), a bad {@code source} or an unparseable {@code time}.
     */
    public static ParseResult<Envelope> build(Map<String, Object> raw) {
        Map<String, AttributeValue> built = new TreeMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (!AttributeName.isValid(entry.getKey())) {
                return refused("attribute name outside ^[a-z0-9]{1,20}$");
            }
            if (entry.getValue() == null) {
                continue;
            }
            ParseResult<AttributeValue> value = AttributeValue.fromRowValue(entry.getValue());
            if (value instanceof ParseResult.Refused<AttributeValue> refusal) {
                return refused("attribute " + entry.getKey() + ": " + refusal.detail());
            }
            built.put(entry.getKey(), ((ParseResult.Accepted<AttributeValue>) value).value());
        }
        String problem = coreProblem(built);
        return problem == null ? new ParseResult.Accepted<>(new Envelope(built)) : refused(problem);
    }

    private static String coreProblem(Map<String, AttributeValue> built) {
        if (!(built.get("specversion") instanceof AttributeValue.Text spec) || !"1.0".equals(spec.value())) {
            return "specversion must be 1.0";
        }
        if (!(built.get("id") instanceof AttributeValue.Text id) || !(EventId.parseUlid(id.value()) instanceof ParseResult.Accepted<EventId>)) {
            return "id must be a ULID";
        }
        if (!(built.get("source") instanceof AttributeValue.Text source) || !EnvelopeSource.isValid(source.value())) {
            return "source must be a Mission or project source";
        }
        if (!(built.get("type") instanceof AttributeValue.Text type) || !TYPE.matcher(type.value()).matches()) {
            return "type must be ai.spec-kitty.<name>.v<N>";
        }
        if (built.containsKey("time")
                && !(built.get("time") instanceof AttributeValue.Text time && Rfc3339.parse(time.value()).isPresent())) {
            return "time must be an RFC 3339 timestamp";
        }
        if (built.containsKey("lamportclock")
                && !(built.get("lamportclock") instanceof AttributeValue.Integer32 clock && clock.value() >= 0)) {
            return "lamportclock must be an integer in 0..2^31-1";
        }
        return null;
    }

    private static ParseResult<Envelope> refused(String detail) {
        return new ParseResult.Refused<>(FaultReason.REJECTED, detail);
    }

    public AttributeValue get(String name) {
        return attributes.get(name);
    }
}
