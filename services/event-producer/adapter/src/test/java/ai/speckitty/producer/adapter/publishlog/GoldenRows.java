package ai.speckitty.producer.adapter.publishlog;

import ai.speckitty.producer.domain.value.JsonNumber;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The two rows of {@code publishlog/golden-lines.jsonl}, which Python's {@code json.dumps} wrote from the same values. */
final class GoldenRows {

    private GoldenRows() {}

    static List<Map<String, Object>> rows() {
        String note = "tab\there \"quoted\" back\\slash /slash " + new String(Character.toChars(0x1F600)) + " " + (char) 0x2028 + " " + (char) 0x7f;
        Map<String, Object> first = envelope("01JAAAA0000000000000000ABC", "2026-10-13T10:00:00+02:00", 7);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("files", Arrays.asList("a/b.py", "c" + (char) 0xe9 + ".py"));
        evidence.put("count", new JsonNumber("2"));
        first.put("data", data("planned", "claimed", Boolean.FALSE, null, evidence, note));
        Map<String, Object> second = envelope("01JAAAA0000000000000000ABE", "2026-10-13T10:00:01Z", 8);
        second.put("data", data("claimed", "in_progress", Boolean.TRUE, "x", new LinkedHashMap<String, Object>(), ""));
        return List.of(first, second);
    }

    private static Map<String, Object> envelope(String id, String time, int clock) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("specversion", "1.0");
        row.put("id", id);
        row.put("source", PublishLogFixtures.SOURCE);
        row.put("type", "ai.spec-kitty.lane.transitioned.v1");
        row.put("subject", "WP01");
        row.put("time", time);
        row.put("datacontenttype", "application/json");
        row.put("lamportclock", new JsonNumber(Integer.toString(clock)));
        row.put("nodeid", PublishLogFixtures.NODE);
        return row;
    }

    private static Map<String, Object> data(String from, String to, Boolean force, String reason, Map<String, Object> evidence, String note) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from_lane", from);
        data.put("to_lane", to);
        data.put("force", force);
        data.put("reason", reason);
        data.put("evidence", evidence);
        data.put("note", note);
        return data;
    }
}
