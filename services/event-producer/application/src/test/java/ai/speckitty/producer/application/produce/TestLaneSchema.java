package ai.speckitty.producer.application.produce;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A test-local stand-in for the lane `data` schema's enum: the persisted vocabulary, without `uninitialized` and `doing`. */
final class TestLaneSchema {

    static final List<String> PERSISTED = List.of(
            "genesis", "planned", "claimed", "in_progress", "for_review", "in_review", "approved", "done", "blocked", "canceled");

    private TestLaneSchema() {}

    static Optional<String> check(Map<String, Object> document) {
        if (!(document.get("data") instanceof Map<?, ?> data)) {
            return Optional.of("/data: not an object");
        }
        for (String member : List.of("from_lane", "to_lane")) {
            if (data.containsKey(member) && !PERSISTED.contains(data.get(member))) {
                return Optional.of("/data/" + member + ": not one of the persisted lanes");
            }
        }
        return Optional.empty();
    }
}
