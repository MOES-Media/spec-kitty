package ai.speckitty.producer.domain.order;

import java.util.Objects;

/** A row set aside because it cannot be keyed: never dropped, never sorted, never a survivor. */
public record ErrorRow(int position, OrderErrorReason reason, Object row) {

    public ErrorRow {
        Objects.requireNonNull(reason, "reason");
        if (position < 0) {
            throw new IllegalArgumentException("a line position is not negative");
        }
    }
}
