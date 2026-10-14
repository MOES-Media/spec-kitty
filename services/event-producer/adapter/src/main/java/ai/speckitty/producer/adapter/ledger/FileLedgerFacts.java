package ai.speckitty.producer.adapter.ledger;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.application.retain.LedgerFacts;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.retention.MissionState;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The committed-ledger reader behind {@link LedgerFacts}: the only code of the producer that opens a committed ledger, read-only
 * and minimally (OD-RETAIN-FACTS). It is not the #5529 read model; #6193 replaces it wholesale.
 *
 * <p>A work package is open when its latest transition row, the one with the greatest {@code at} (the later line breaks a tie), is
 * in a lane other than {@code done} or {@code canceled}. The Mission directory is found by the {@code mission_id} of its
 * {@code meta.json}. The ledger of the canonical checkout and the copy in a coordination worktree
 * ({@code .worktrees/*-coord}) are both read; the Mission is open if either says so. Whatever cannot be read, parsed or found, or
 * holds no work package, makes the Mission's files kept (facts unavailable), unless another copy already says it is open.
 */
public final class FileLedgerFacts implements LedgerFacts {

    private static final String SPECS = "kitty-specs";
    private static final String LEDGER = "status.events.jsonl";
    private static final String META = "meta.json";
    private static final String COORDINATION_SUFFIX = "-coord";
    private static final long MAX_LEDGER_BYTES = 64L * 1024 * 1024;
    private static final long MAX_META_BYTES = 1024L * 1024;
    private static final int MID8 = 8;

    @Override
    public MissionState missionState(RepoLayout layout, String missionId) {
        Path root = layout.canonicalRoot();
        Path specs = root.resolve(SPECS);
        if (!Files.isDirectory(specs, LinkOption.NOFOLLOW_LINKS)) {
            return MissionState.UNAVAILABLE;
        }
        List<String> directories = new ArrayList<>();
        boolean uncertain = false;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(specs)) {
            for (Path child : children) {
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String name = child.getFileName().toString();
                Optional<String> id = metaMissionId(child.resolve(META));
                if (id.isPresent()) {
                    if (id.get().equals(missionId)) {
                        directories.add(name);
                    }
                } else if (couldBe(name, missionId) && Files.exists(child.resolve(META), LinkOption.NOFOLLOW_LINKS)) {
                    uncertain = true;
                }
            }
        } catch (IOException | RuntimeException e) {
            return MissionState.UNAVAILABLE;
        }
        if (directories.isEmpty()) {
            return MissionState.UNAVAILABLE;
        }
        boolean open = false;
        boolean unavailable = uncertain;
        for (String name : directories) {
            for (Copy copy : copiesOf(root, name)) {
                MissionState copyState = readCopy(copy.path(), copy.required());
                if (copyState == MissionState.OPEN) {
                    open = true;
                } else if (copyState == MissionState.UNAVAILABLE) {
                    unavailable = true;
                }
            }
        }
        if (open) {
            return MissionState.OPEN;
        }
        return unavailable ? MissionState.UNAVAILABLE : MissionState.CLOSED;
    }

    /** A copy of a ledger to read. A copy in a coordination worktree is read only when it exists. */
    private record Copy(Path path, boolean required) {}

    private static List<Copy> copiesOf(Path root, String directory) {
        List<Copy> copies = new ArrayList<>();
        copies.add(new Copy(root.resolve(SPECS).resolve(directory).resolve(LEDGER), true));
        Path worktrees = root.resolve(".worktrees");
        if (Files.isDirectory(worktrees, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(worktrees)) {
                for (Path child : children) {
                    if (child.getFileName().toString().endsWith(COORDINATION_SUFFIX)) {
                        Path ledger = child.resolve(SPECS).resolve(directory).resolve(LEDGER);
                        if (Files.exists(ledger, LinkOption.NOFOLLOW_LINKS)) {
                            copies.add(new Copy(ledger, false));
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                copies.add(new Copy(worktrees.resolve("unreadable").resolve(LEDGER), true));
            }
        }
        return copies;
    }

    private static boolean couldBe(String directoryName, String missionId) {
        return missionId.length() >= MID8 && directoryName.contains(missionId.substring(0, MID8));
    }

    private static Optional<String> metaMissionId(Path meta) {
        try {
            if (!Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS) || Files.size(meta) > MAX_META_BYTES) {
                return Optional.empty();
            }
            if (Json.parseObject(Files.readAllBytes(meta)) instanceof ParseResult.Accepted<Map<String, Object>> parsed
                    && parsed.value().get("mission_id") instanceof String id) {
                return Optional.of(id);
            }
        } catch (IOException | RuntimeException e) {
            // an unreadable meta.json names no Mission
        }
        return Optional.empty();
    }

    private static MissionState readCopy(Path ledger, boolean required) {
        if (!Files.exists(ledger, LinkOption.NOFOLLOW_LINKS)) {
            return required ? MissionState.UNAVAILABLE : MissionState.CLOSED;
        }
        try {
            if (!Files.isRegularFile(ledger, LinkOption.NOFOLLOW_LINKS) || Files.size(ledger) > MAX_LEDGER_BYTES) {
                return MissionState.UNAVAILABLE;
            }
            return stateOf(ledger);
        } catch (IOException | RuntimeException e) {
            return MissionState.UNAVAILABLE;
        }
    }

    /** Reads the transition rows, line by line, and reduces them to the latest per work package. */
    private static MissionState stateOf(Path ledger) throws IOException {
        Map<String, Latest> latest = new HashMap<>();
        try (InputStream in = Files.newInputStream(ledger);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        in, StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (!(Json.parseObject(line) instanceof ParseResult.Accepted<Map<String, Object>> parsed)) {
                    return MissionState.UNAVAILABLE;
                }
                Map<String, Object> row = parsed.value();
                if (!row.containsKey("to_lane")) {
                    continue;
                }
                if (!(row.get("wp_id") instanceof String wp) || wp.isEmpty() || !(row.get("to_lane") instanceof String lane)
                        || !(row.get("at") instanceof String atText)) {
                    return MissionState.UNAVAILABLE;
                }
                Instant at;
                try {
                    at = OffsetDateTime.parse(atText).toInstant();
                } catch (DateTimeException e) {
                    return MissionState.UNAVAILABLE;
                }
                Latest seen = latest.get(wp);
                if (seen == null || !at.isBefore(seen.at)) {
                    latest.put(wp, new Latest(at, lane));
                }
            }
        } catch (CharacterCodingException e) {
            return MissionState.UNAVAILABLE;
        }
        if (latest.isEmpty()) {
            return MissionState.UNAVAILABLE;
        }
        for (Latest row : latest.values()) {
            if (!row.lane.equals("done") && !row.lane.equals("canceled")) {
                return MissionState.OPEN;
            }
        }
        return MissionState.CLOSED;
    }

    private record Latest(Instant at, String lane) {}
}
