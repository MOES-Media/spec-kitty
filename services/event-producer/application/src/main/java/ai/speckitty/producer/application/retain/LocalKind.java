package ai.speckitty.producer.application.retain;

/** The kinds of local-tier file retention may list, and the group each is ranked in ("the newest {@code keep_runs} of its kind"). */
public enum LocalKind {
    /** {@code .kittify/events/publish/missions/<missionid>.jsonl}: one Mission is one run. */
    PUBLISH_MISSION(Group.PUBLISH),
    /** {@code .kittify/events/publish/project-<project_uuid>.jsonl}: the project file counts as one run. */
    PUBLISH_PROJECT(Group.PUBLISH),
    /** {@code .kittify/events/faults/<UTC date>.jsonl}: one UTC day is one run. */
    FAULT_DAY(Group.FAULT),
    /** {@code .kittify/runtime/runs/<run_id>/run.events.jsonl}: one run. */
    RUN_JOURNAL(Group.JOURNAL);

    /** What a rank is counted within. */
    public enum Group {
        PUBLISH,
        FAULT,
        JOURNAL
    }

    private final Group group;

    LocalKind(Group group) {
        this.group = group;
    }

    public Group group() {
        return group;
    }
}
