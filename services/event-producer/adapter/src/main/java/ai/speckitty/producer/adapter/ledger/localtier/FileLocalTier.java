package ai.speckitty.producer.adapter.ledger.localtier;

import ai.speckitty.producer.application.port.Clock;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.application.retain.LocalFile;
import ai.speckitty.producer.application.retain.LocalTier;
import ai.speckitty.producer.domain.protocol.RelativePath;
import java.util.List;
import java.util.Optional;

/** The local tier on the file system (stub: the tests are written first). */
public final class FileLocalTier implements LocalTier {

    public FileLocalTier(Clock clock) {}

    @Override
    public List<Entry> list(RepoLayout layout) {
        return List.of();
    }

    @Override
    public Optional<LocalFile> inspect(RepoLayout layout, RelativePath path) {
        return Optional.empty();
    }

    @Override
    public boolean delete(RepoLayout layout, RelativePath path) {
        return false;
    }
}
