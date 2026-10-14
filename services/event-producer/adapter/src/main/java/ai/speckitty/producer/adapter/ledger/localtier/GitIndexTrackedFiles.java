package ai.speckitty.producer.adapter.ledger.localtier;

import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.application.retain.TrackedFiles;
import ai.speckitty.producer.domain.protocol.RelativePath;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracked paths read from the git index, without starting git (FR-031). Version 2 and 3 indexes are followed entry by entry; a
 * version 4 index, a split or sparse index, or one that does not parse leaves every answer {@code UNKNOWN}, which keeps the file.
 * A path is also treated as tracked when its bytes occur anywhere in the index, so a misreading can only keep more files.
 */
public final class GitIndexTrackedFiles implements TrackedFiles {

    /** An index larger than this is not read. */
    static final long MAX_INDEX_BYTES = 256L * 1024 * 1024;

    private static final int FIXED = 62;
    private static final int EXTENDED_FLAG = 0x4000;
    private static final int NAME_MASK = 0x0FFF;

    @Override
    public Map<RelativePath, Tracking> of(RepoLayout layout, Collection<RelativePath> paths) {
        Map<RelativePath, Tracking> answers = new HashMap<>();
        byte[] index;
        try {
            index = readIndex(layout.gitCommonDir().resolve("index"));
        } catch (IOException | RuntimeException e) {
            for (RelativePath path : paths) {
                answers.put(path, Tracking.UNKNOWN);
            }
            return answers;
        }
        if (index == null) {
            for (RelativePath path : paths) {
                answers.put(path, Tracking.UNTRACKED);
            }
            return answers;
        }
        Set<String> entries = entriesOf(index);
        for (RelativePath path : paths) {
            if (entries == null) {
                answers.put(path, Tracking.UNKNOWN);
            } else if (entries.contains(path.value()) || contains(index, path.value().getBytes(StandardCharsets.UTF_8))) {
                answers.put(path, Tracking.TRACKED);
            } else {
                answers.put(path, Tracking.UNTRACKED);
            }
        }
        return answers;
    }

    /** The index bytes; null when there is no index (nothing was ever staged). */
    private static byte[] readIndex(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes((int) Math.min(MAX_INDEX_BYTES, Integer.MAX_VALUE - 8) + 1);
            if (bytes.length > MAX_INDEX_BYTES) {
                throw new IOException("index too large");
            }
            return bytes;
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /** The entry paths of a version 2 or 3 index; null when the index is of another form or does not parse. */
    static Set<String> entriesOf(byte[] index) {
        if (index.length < 32 || index[0] != 'D' || index[1] != 'I' || index[2] != 'R' || index[3] != 'C') {
            return null;
        }
        long version = u32(index, 4);
        long count = u32(index, 8);
        if (version != 2 && version != 3) {
            return null;
        }
        Set<String> paths = new HashSet<>();
        int at = 12;
        int end = index.length - 20;
        for (long i = 0; i < count; i++) {
            if (at + FIXED > end) {
                return null;
            }
            int flags = ((index[at + 60] & 0xff) << 8) | (index[at + 61] & 0xff);
            int header = FIXED + ((flags & EXTENDED_FLAG) != 0 && version == 3 ? 2 : 0);
            int nameStart = at + header;
            int nameEnd = nameStart;
            while (nameEnd < end && index[nameEnd] != 0) {
                nameEnd++;
            }
            if (nameEnd >= end) {
                return null;
            }
            int declared = flags & NAME_MASK;
            if (declared != NAME_MASK && declared != nameEnd - nameStart) {
                return null;
            }
            paths.add(new String(index, nameStart, nameEnd - nameStart, StandardCharsets.UTF_8));
            at += (header + (nameEnd - nameStart) + 8) & ~7;
        }
        // Extensions: a split index ("link") or a sparse index ("sdir") hides entries from this reader.
        while (at + 8 <= end) {
            String signature = new String(index, at, 4, StandardCharsets.ISO_8859_1);
            long size = u32(index, at + 4);
            if (signature.equals("link") || signature.equals("sdir")) {
                return null;
            }
            if (size < 0 || at + 8 + size > end) {
                return null;
            }
            at += (int) (8 + size);
        }
        return at == end ? paths : null;
    }

    private static long u32(byte[] bytes, int at) {
        return ((long) (bytes[at] & 0xff) << 24) | ((bytes[at + 1] & 0xff) << 16) | ((bytes[at + 2] & 0xff) << 8) | (bytes[at + 3] & 0xff);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
