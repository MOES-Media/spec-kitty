package ai.speckitty.producer.application.retain;

import ai.speckitty.producer.application.port.Clock;
import ai.speckitty.producer.application.port.RepoLocator;
import ai.speckitty.producer.domain.protocol.RetainRequest;

/** Retention of the local tier (stub: the tests are written first). */
public final class Retain {

    public Retain(RepoLocator locator, Clock clock, LedgerFacts ledgers, LocalTier tier, TrackedFiles tracked, ClockGate gate) {}

    public RetainResult retain(RetainRequest request) {
        return new RetainResult.RootUnresolvable();
    }
}
