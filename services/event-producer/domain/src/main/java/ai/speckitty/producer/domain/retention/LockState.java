package ai.speckitty.producer.domain.retention;

/** What a Python-owned lock sidecar says about a run; its content, never its presence. */
public enum LockState {
    /** An empty sidecar, or none, or a record older than the stale threshold. */
    NONE,
    /** A non-empty, parsable lock record younger than the stale threshold. */
    LIVE,
    /** The sidecar could not be read: the run is kept. */
    UNREADABLE
}
