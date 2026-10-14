package ai.speckitty.producer.domain.order;

import java.util.List;
import java.util.Map;

/**
 * One conformance case: labelled rows and the pinned result of ordering them in any permutation.
 *
 * @param errors label to the wire name of the reason the row is set aside
 * @param conflicts the {@code (source, id)} keys reported as conflicts
 */
public record OrderCase(
        String id,
        String description,
        List<LabeledRow> rows,
        List<String> ordered,
        Map<String, String> errors,
        int collapsed,
        List<SourceId> conflicts,
        int read) {}
