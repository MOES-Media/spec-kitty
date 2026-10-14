package ai.speckitty.producer.launcher.e2e;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Requests and rows of the end-to-end tests, rendered by the producer's own JSON writer. */
final class Requests {

    static final String MISSION = "01J0000000000000000000MSN1";
    static final String EVENT = "01J0000000000000000000F001";
    static final String AT = "2026-01-01T00:00:00+00:00";
    static final String PROJECT = "5c0f6f0e-3b1a-4d8e-9a55-0d7e3f2b9c11";

    private Requests() {}

    static Map<String, Object> obj(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    static JsonNumber num(String literal) {
        return new JsonNumber(literal);
    }

    static Map<String, Object> laneRow(String eventId) {
        return obj(
                "event_id", eventId,
                "mission_slug", "041-reducer-unification",
                "mission_id", MISSION,
                "wp_id", "WP01",
                "from_lane", "planned",
                "to_lane", "claimed",
                "at", AT,
                "actor", "claude",
                "force", Boolean.FALSE,
                "execution_mode", "worktree",
                "reason", null,
                "review_ref", null,
                "evidence", null,
                "policy_metadata", obj("agent", "claude", "weight", num("5.0")));
    }

    static byte[] produce(Map<String, Object> row, String startPath, int deadlineMs) {
        Map<String, Object> request = obj(
                "v", num("1"), "op", "produce", "deadline_ms", num(Integer.toString(deadlineMs)),
                "start_path", startPath, "slot", "status", "seam_args", obj(), "row", row);
        return Json.render(request).getBytes(StandardCharsets.UTF_8);
    }
}
