package ai.speckitty.producer.domain.order;

import java.util.List;

/** Orders rows. The domain's {@link TotalOrder} is the one implementation; test fixtures hold deliberately broken ones. */
public interface RowOrdering {

    /** Orders {@code rows}, each a parsed JSON value (see {@link RowJson}). */
    OrderResult order(List<?> rows);
}
