package ai.speckitty.producer.domain.order;

import java.util.Objects;

/** Thrown by {@link RowKeys} for a row that cannot be keyed. */
public final class UnorderableRow extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final OrderErrorReason reason;

    public UnorderableRow(OrderErrorReason reason, String detail) {
        super(Objects.requireNonNull(reason, "reason").wire() + ": " + detail, null, false, false);
        this.reason = reason;
    }

    public OrderErrorReason reason() {
        return reason;
    }
}
