package ai.speckitty.producer.domain.order;

import java.util.List;

/**
 * The total order of the merged contract: set aside the rows that cannot be keyed, group by {@code (source, id)}, keep of each
 * group the row with the smallest serialisation, sort the survivors by {@link OrderKey}.
 */
public final class TotalOrder implements RowOrdering {

    public static final TotalOrder INSTANCE = new TotalOrder();

    private TotalOrder() {}

    @Override
    public OrderResult order(List<?> rows) {
        throw new UnsupportedOperationException("red stub");
    }
}
