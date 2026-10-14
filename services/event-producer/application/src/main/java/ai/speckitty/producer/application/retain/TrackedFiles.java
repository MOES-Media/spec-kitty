package ai.speckitty.producer.application.retain;

import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.domain.protocol.RelativePath;
import java.util.Collection;
import java.util.Map;

/** Which paths git tracks (FR-031): read from the index, never by starting git. */
public interface TrackedFiles {

    /** Whether a path is in the index. */
    enum Tracking {
        TRACKED,
        UNTRACKED,
        /** The index cannot be read or is in a form this reader does not follow: the file is kept. */
        UNKNOWN
    }

    /** The answer for every given path. */
    Map<RelativePath, Tracking> of(RepoLayout layout, Collection<RelativePath> paths);
}
