package ai.speckitty.producer.adapter.ledger;

import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.application.retain.LedgerFacts;
import ai.speckitty.producer.domain.retention.MissionState;

/** The committed-ledger reader behind {@link LedgerFacts} (stub: the tests are written first). */
public final class FileLedgerFacts implements LedgerFacts {

    @Override
    public MissionState missionState(RepoLayout layout, String missionId) {
        return MissionState.CLOSED;
    }
}
