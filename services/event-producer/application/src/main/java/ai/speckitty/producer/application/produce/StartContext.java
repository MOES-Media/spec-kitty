package ai.speckitty.producer.application.produce;

import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * What the producer reads beside the row (FR-009): the {@code meta.json} of the start directory, and the project uuid of the
 * repository. A collaborator of the use case that the launcher implements over the file system; it is not a second port of the
 * seam, and it is never given the seam's keyword arguments.
 */
public interface StartContext {

    /**
     * The parsed {@code meta.json} of the directory {@code startPath} when that directory holds one, else empty. A file that is
     * absent or unreadable is empty, never an error: a Mission without it publishes under the project source.
     */
    Optional<Map<String, Object>> metaJson(Path startPath);

    /** The project uuid the repository records in {@code .kittify/config.yaml}, else empty. */
    Optional<String> projectUuid(RepoLayout layout);
}
