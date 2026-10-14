package ai.speckitty.producer.launcher;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.application.port.RepoLocator.RepoLayout;
import ai.speckitty.producer.application.produce.StartContext;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.ids.EventIdForms;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The start context over the file system: the {@code meta.json} of the start directory, and the project uuid of the config. */
public final class FileStartContext implements StartContext {

    /** The largest {@code meta.json} or config read; a larger file is taken as absent. */
    public static final int MAX_META_BYTES = 1024 * 1024;

    private static final Pattern UUID_LINE = Pattern.compile("^[ \\t]+uuid:[ \\t]*(.*)$");

    @Override
    public Optional<Map<String, Object>> metaJson(Path startPath) {
        Path file = startPath.resolve("meta.json");
        try {
            if (!Files.isDirectory(startPath) || !Files.isRegularFile(file) || Files.size(file) > MAX_META_BYTES) {
                return Optional.empty();
            }
            if (Json.parseObject(Files.readAllBytes(file)) instanceof ParseResult.Accepted<Map<String, Object>> parsed) {
                return Optional.of(parsed.value());
            }
        } catch (IOException | InvalidPathException | SecurityException e) {
            // an unreadable meta.json is an absent one: the row then publishes under the project source
        }
        return Optional.empty();
    }

    @Override
    public Optional<String> projectUuid(RepoLayout layout) {
        Path config = layout.canonicalRoot().resolve(".kittify").resolve("config.yaml");
        List<String> lines;
        try {
            if (!Files.isRegularFile(config) || Files.size(config) > MAX_META_BYTES) {
                return Optional.empty();
            }
            lines = Files.readAllLines(config, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        boolean inProject = false;
        for (String raw : lines) {
            String line = raw.stripTrailing();
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            if (!Character.isWhitespace(line.charAt(0))) {
                inProject = line.startsWith("project:") && withoutComment(line.substring("project:".length())).isBlank();
                continue;
            }
            Matcher matcher = UUID_LINE.matcher(line);
            if (inProject && matcher.matches()) {
                String value = unquoted(withoutComment(matcher.group(1)).strip());
                return EventIdForms.isHyphenatedUuid(value) ? Optional.of(value.toLowerCase(Locale.ROOT)) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static String withoutComment(String text) {
        int hash = text.indexOf('#');
        return hash < 0 ? text : text.substring(0, hash);
    }

    private static String unquoted(String text) {
        if (text.length() >= 2 && (text.charAt(0) == '"' || text.charAt(0) == '\'') && text.charAt(text.length() - 1) == text.charAt(0)) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
}
