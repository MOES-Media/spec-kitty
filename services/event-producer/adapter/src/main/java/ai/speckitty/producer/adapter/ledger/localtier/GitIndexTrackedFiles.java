package ai.speckitty.producer.adapter.ledger.localtier;

import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.application.retain.TrackedFiles;
import ai.speckitty.producer.domain.protocol.RelativePath;
import java.util.Collection;
import java.util.Map;

/** Tracked paths read from the git index (stub: the tests are written first). */
public final class GitIndexTrackedFiles implements TrackedFiles {

    @Override
    public Map<RelativePath, Tracking> of(RepoLayout layout, Collection<RelativePath> paths) {
        return Map.of();
    }
}
