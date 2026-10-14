package ai.speckitty.producer.domain.order;

/** Why a row was set aside instead of ordered; the names are those of the merged total-order contract. */
public enum OrderErrorReason {
    TIME_UNPARSEABLE("time_unparseable"),
    CLOCK_OUT_OF_RANGE("clock_out_of_range"),
    ID_MISSING("id_missing"),
    ID_NOT_NORMALIZABLE("id_not_normalizable"),
    SOURCE_INVALID("source_invalid"),
    ROW_NOT_OBJECT("row_not_object");

    private final String wire;

    OrderErrorReason(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
