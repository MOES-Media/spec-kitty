package ai.speckitty.producer.adapter.publishlog;

import ai.speckitty.producer.adapter.fs.AtomicFile;
import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.application.port.PublishLog;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.Stream;

/**
 * The publish log on the file system: append-only, one JSON object per line, LF only, the producer its single writer. No method
 * rewrites bytes that are already in a file. A final line without its LF is a torn fragment: it is reported, left where it is,
 * and skipped by dedupe and clock recovery; the next row starts on a new line.
 */
public final class FilePublishLog implements PublishLog {

    private static final byte LF = '\n';
    private static final String EVENTS_PUBLISH = ".kittify/events/publish";

    @Override
    public Probe probe(RepoLayout layout, Target target, String source, String id) {
        Path file = fileOf(layout, target);
        Scan scan = scan(file);
        boolean duplicate = false;
        for (Map<String, Object> row : scan.rows()) {
            if (source.equals(row.get("source")) && id.equals(row.get("id"))) {
                duplicate = true;
                break;
            }
        }
        return new Probe(duplicate, scan.tornLine() == 0 ? null : new TornLine(scan.tornLine()));
    }

    @Override
    public ParseResult<Boolean> append(RepoLayout layout, Target target, String line) {
        String problem = problemWith(line);
        if (problem != null) {
            return new ParseResult.Refused<>(FaultReason.REJECTED, problem);
        }
        Path file = fileOf(layout, target);
        try {
            Files.createDirectories(file.getParent());
            boolean torn = endsInTornLine(file);
            byte[] body = line.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(body.length + 2);
            if (torn) {
                buffer.put(LF);
            }
            buffer.put(body).put(LF).flip();
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                AtomicFile.force(channel);
            }
            return new ParseResult.Accepted<>(torn);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public OptionalInt highestClock(RepoLayout layout) {
        Path directory = layout.canonicalRoot().resolve(EVENTS_PUBLISH);
        if (!Files.isDirectory(directory)) {
            return OptionalInt.empty();
        }
        long highest = -1;
        try (Stream<Path> walk = Files.walk(directory)) {
            List<Path> files = walk.filter(p -> p.getFileName().toString().endsWith(".jsonl") && Files.isRegularFile(p)).toList();
            for (Path file : files) {
                for (Map<String, Object> row : scan(file).rows()) {
                    if (row.get("lamportclock") instanceof JsonNumber number && number.isInteger()) {
                        BigInteger value = number.integerValue().orElseThrow();
                        if (value.signum() >= 0 && value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0) {
                            highest = Math.max(highest, value.longValueExact());
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return highest < 0 ? OptionalInt.empty() : OptionalInt.of((int) highest);
    }

    private static Path fileOf(RepoLayout layout, Target target) {
        return layout.canonicalRoot().resolve(target.relativePath().value());
    }

    /** Why {@code line} may not be appended, or null when it is exactly one JSON object with no CR, LF or BOM. */
    private static String problemWith(String line) {
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            return "a publish-log line holds no raw line break";
        }
        if (!line.isEmpty() && line.charAt(0) == '﻿') {
            return "a publish-log line has no byte order mark";
        }
        if (!(Json.parseObject(line) instanceof ParseResult.Accepted<Map<String, Object>>)) {
            return "a publish-log line is one JSON object";
        }
        return null;
    }

    private static boolean endsInTornLine(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size == 0) {
                return false;
            }
            ByteBuffer last = ByteBuffer.allocate(1);
            channel.read(last, size - 1);
            return last.get(0) != LF;
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    /** The complete JSON-object lines of a file, and the 1-based position of its torn final line (0 when it has none). */
    private record Scan(List<Map<String, Object>> rows, int tornLine) {}

    private static Scan scan(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return new Scan(List.of(), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int lineNumber = 0;
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == LF) {
                lineNumber++;
                parse(bytes, start, i, rows);
                start = i + 1;
            }
        }
        int torn = start < bytes.length ? lineNumber + 1 : 0;
        return new Scan(rows, torn);
    }

    private static void parse(byte[] bytes, int from, int to, List<Map<String, Object>> rows) {
        if (to > from && Json.parseObject(java.util.Arrays.copyOfRange(bytes, from, to)) instanceof ParseResult.Accepted<Map<String, Object>> row) {
            rows.add(row.value());
        }
    }
}
