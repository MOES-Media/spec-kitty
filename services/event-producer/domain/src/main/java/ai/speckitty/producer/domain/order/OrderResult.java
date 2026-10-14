package ai.speckitty.producer.domain.order;

import java.util.List;

/**
 * The outcome of ordering rows. {@code ordered.size() + errors.size() + collapsed == read} always holds (AC-009).
 *
 * @param ordered the survivors in total order
 * @param errors the rows set aside, with their positions in the input
 * @param collapsed how many rows were folded into another row of the same {@code (source, id)}
 * @param conflicts the {@code (source, id)} groups whose rows differ in a member other than {@code id}, sorted
 * @param read how many rows were given
 */
public record OrderResult(List<Object> ordered, List<ErrorRow> errors, int collapsed, List<SourceId> conflicts, int read) {

    public OrderResult {
        ordered = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(ordered));
        errors = List.copyOf(errors);
        conflicts = List.copyOf(conflicts);
    }
}
