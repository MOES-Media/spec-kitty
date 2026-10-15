package ai.speckitty.producer.application.retain;

import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.domain.retention.MissionState;

/**
 * What the committed ledgers say about a Mission (FR-029, OD-RETAIN-FACTS). The only code that opens a committed ledger, read-only
 * and minimally; #6193 replaces its implementation wholesale. It never returns {@code NOT_APPLICABLE}.
 */
public interface LedgerFacts {

    /** {@code OPEN}, {@code CLOSED}, or {@code UNAVAILABLE} when any ledger of the Mission cannot be read, parsed or found. */
    MissionState missionState(RepoLayout layout, String missionId);
}
