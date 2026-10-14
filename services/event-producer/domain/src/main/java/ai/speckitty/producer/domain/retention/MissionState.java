package ai.speckitty.producer.domain.retention;

/** What the committed ledgers say about the Mission a local-tier file belongs to. */
public enum MissionState {
    /** Some work package's latest lane is neither done nor canceled. */
    OPEN,
    /** Every work package is done or canceled. */
    CLOSED,
    /** The ledger is unreadable, unparseable or absent: the files are kept ("facts unavailable"). */
    UNAVAILABLE,
    /** The file belongs to no Mission (for example a fault-log day file). */
    NOT_APPLICABLE
}
