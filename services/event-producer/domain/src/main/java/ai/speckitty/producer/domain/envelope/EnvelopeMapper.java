package ai.speckitty.producer.domain.envelope;

import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Splits a persisted row into context attributes and {@code data} as a registry entry says, with no per-type code. */
public final class EnvelopeMapper {

    private EnvelopeMapper() {}

    /**
     * @param attributes the lifted members, by attribute name; members whose value is {@code null} are absent
     * @param data the remaining members, verbatim, under their data names
     */
    public record Mapped(Map<String, Object> attributes, Map<String, Object> data) {
        public Mapped {
            attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
            data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
        }
    }

    public static ParseResult<Mapped> map(TypeEntry entry, Map<String, Object> row) {
        Map<String, String> attributeOf = new HashMap<>();
        for (TypeEntry.Lift lift : entry.lifts()) {
            attributeOf.put(lift.rowMember(), lift.attribute());
        }
        Set<String> dropped = new HashSet<>(entry.dropped());
        Map<String, Object> attributes = new LinkedHashMap<>();
        Map<String, Object> data = new LinkedHashMap<>();
        for (Map.Entry<String, Object> member : row.entrySet()) {
            String key = member.getKey();
            if (attributeOf.containsKey(key)) {
                if (member.getValue() != null) {
                    attributes.put(attributeOf.get(key), member.getValue());
                }
            } else if (!dropped.contains(key)) {
                String dataName = entry.dataRenames().getOrDefault(key, key);
                if (data.containsKey(dataName)) {
                    return new ParseResult.Refused<>(FaultReason.REJECTED, "data member " + dataName + " would be written twice");
                }
                data.put(dataName, member.getValue());
            }
        }
        return new ParseResult.Accepted<>(new Mapped(attributes, data));
    }
}
