package ai.speckitty.producer.application.retain;

import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.domain.protocol.RelativePath;
import java.util.List;
import java.util.Optional;

/**
 * The local-tier directories as retention sees them: list the candidate files and delete one whole file. It never opens a file for
 * write and it never reaches {@code kitty-specs/}. It outlives the committed-ledger reader that #6193 replaces.
 */
public interface LocalTier {

    /** One listed candidate. */
    sealed interface Entry {
        RelativePath path();

        /** A candidate whose facts were read. */
        record Present(LocalFile file) implements Entry {
            @Override
            public RelativePath path() {
                return file.path();
            }
        }

        /** A candidate whose facts could not be read: it is kept, never deleted. */
        record Unreadable(RelativePath path) implements Entry {}
    }

    /** Every candidate file of the local-tier directories; none when a directory is absent. */
    List<Entry> list(RepoLayout layout);

    /** Reads the facts of one candidate afresh; empty when it vanished, is not a regular file or cannot be read. */
    Optional<LocalFile> inspect(RepoLayout layout, RelativePath path);

    /** Deletes the whole file; false when it could not be deleted. Refuses (false) any path that is not a local-tier candidate. */
    boolean delete(RepoLayout layout, RelativePath path);
}
