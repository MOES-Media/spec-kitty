package ai.speckitty.producer.adapter.ledger.localtier;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.adapter.ledger.LockRecords;
import ai.speckitty.producer.application.port.Clock;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.application.retain.LocalFile;
import ai.speckitty.producer.application.retain.LocalKind;
import ai.speckitty.producer.application.retain.LocalTier;
import ai.speckitty.producer.application.retain.LocalTierPaths;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.protocol.RelativePath;
import ai.speckitty.producer.domain.retention.LockState;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The local tier on the file system. Every file is opened for reading only; a candidate is removed with one {@code delete} of the
 * whole file, never trimmed or rewritten. Only paths of {@link LocalTierPaths} are listed, inspected or deleted, and a symbolic
 * link, a directory or any other non-regular file is never deleted.
 */
public final class FileLocalTier implements LocalTier {

    private static final String JSONL = ".jsonl";
    private static final String COMPLETED = "MissionRunCompleted";

    private final Clock clock;

    public FileLocalTier(Clock clock) {
        this.clock = clock;
    }

    @Override
    public List<Entry> list(RepoLayout layout) {
        Path root = layout.canonicalRoot();
        List<RelativePath> found = new ArrayList<>();
        collect(root, LocalTierPaths.MISSIONS_DIR, found);
        collect(root, LocalTierPaths.PUBLISH_DIR, found);
        collect(root, LocalTierPaths.FAULTS_DIR, found);
        collectRuns(root, found);
        found.sort(Comparator.comparing(RelativePath::value));
        List<Entry> entries = new ArrayList<>();
        for (RelativePath path : found) {
            Optional<LocalFile> file = inspect(layout, path);
            entries.add(file.<Entry>map(Entry.Present::new).orElseGet(() -> new Entry.Unreadable(path)));
        }
        return entries;
    }

    /** Names in {@code directory} that have the shape of a candidate; whether they can be read is decided later. */
    private static void collect(Path root, String directory, List<RelativePath> into) {
        Path dir = root.resolve(directory);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (DirectoryStream<Path> names = Files.newDirectoryStream(dir)) {
            for (Path name : names) {
                String fileName = name.getFileName().toString();
                if (!fileName.endsWith(JSONL)) {
                    continue;
                }
                candidate(directory + "/" + fileName).ifPresent(into::add);
            }
        } catch (IOException | RuntimeException e) {
            // an unreadable directory lists nothing: nothing in it is deleted
        }
    }

    private static void collectRuns(Path root, List<RelativePath> into) {
        Path runs = root.resolve(LocalTierPaths.RUNS_DIR);
        if (!Files.isDirectory(runs, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (DirectoryStream<Path> names = Files.newDirectoryStream(runs)) {
            for (Path run : names) {
                String id = run.getFileName().toString();
                Path journal = run.resolve(LocalTierPaths.JOURNAL_FILE);
                if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
                    candidate(LocalTierPaths.RUNS_DIR + "/" + id + "/" + LocalTierPaths.JOURNAL_FILE).ifPresent(into::add);
                }
            }
        } catch (IOException | RuntimeException e) {
            // an unreadable directory lists nothing
        }
    }

    private static Optional<RelativePath> candidate(String path) {
        if (!RelativePath.isValid(path)) {
            return Optional.empty();
        }
        RelativePath relative = new RelativePath(path);
        return LocalTierPaths.classify(relative).isPresent() ? Optional.of(relative) : Optional.empty();
    }

    @Override
    public Optional<LocalFile> inspect(RepoLayout layout, RelativePath path) {
        Optional<LocalTierPaths.Shape> shape = LocalTierPaths.classify(path);
        if (shape.isEmpty()) {
            return Optional.empty();
        }
        Path root = layout.canonicalRoot();
        Path file = resolve(root, path);
        if (file == null || !regularFile(file)) {
            return Optional.empty();
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            LocalKind kind = shape.get().kind();
            Instant modified = attributes.lastModifiedTime().toInstant();
            switch (kind) {
                case FAULT_DAY -> {
                    LocalDate day = LocalTierPaths.faultDay(file.getFileName().toString());
                    Instant endOfDay = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                    return Optional.of(new LocalFile(path, attributes.size(), endOfDay, LockState.NONE, false));
                }
                case RUN_JOURNAL -> {
                    Scan scan = scan(file, "timestamp");
                    LockState lock = LockRecords.read(resolve(root, LocalTierPaths.runLockOf(shape.get().id())), clock.now());
                    return Optional.of(new LocalFile(path, attributes.size(), scan.newest != null ? scan.newest : modified, lock, !scan.completed));
                }
                default -> {
                    Scan scan = scan(file, "time");
                    return Optional.of(new LocalFile(path, attributes.size(), scan.newest != null ? scan.newest : modified, LockState.NONE, false));
                }
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean delete(RepoLayout layout, RelativePath path) {
        if (LocalTierPaths.classify(path).isEmpty()) {
            return false;
        }
        Path file = resolve(layout.canonicalRoot(), path);
        if (file == null || !regularFile(file)) {
            return false;
        }
        try {
            Files.delete(file);
            return true;
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static Path resolve(Path root, RelativePath path) {
        try {
            return root.resolve(path.value());
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static boolean regularFile(Path file) {
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
    }

    /** What reading a file's rows found. */
    private static final class Scan {
        Instant newest;
        boolean completed;
    }

    private Scan scan(Path file, String timeMember) throws IOException {
        Scan scan = new Scan();
        eachRow(file, row -> {
            if (row.get(timeMember) instanceof String text) {
                Instant at = instant(text);
                if (at != null && (scan.newest == null || at.isAfter(scan.newest))) {
                    scan.newest = at;
                }
            }
            if (COMPLETED.equals(row.get("event_type"))) {
                scan.completed = true;
            }
        });
        return scan;
    }

    /** Each parsable JSON-object line, read-only; a torn or non-JSON line is skipped. */
    private static void eachRow(Path file, Consumer<Map<String, Object>> row) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new java.io.InputStreamReader(
                        Files.newInputStream(file),
                        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank() && Json.parseObject(line) instanceof ParseResult.Accepted<Map<String, Object>> parsed) {
                    row.accept(parsed.value());
                }
            }
        }
    }

    private static Instant instant(String text) {
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeException e) {
            return null;
        }
    }
}
