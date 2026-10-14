package ai.speckitty.producer.adapter.clock;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.adapter.fs.ChildJvm;
import ai.speckitty.producer.adapter.repo.GitFixtures;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** NFR-006 / AC-035: nothing the producer writes, and no fixture it ships, holds an absolute home path or a user name. */
class PathLeakScannerTest {

    private static final int FILE_FLOOR = 4;

    @TempDir
    Path base;

    /** The scanner under test: the reasons {@code text} leaks, empty when it is clean. */
    static List<String> leaks(String text, Path tempRoot) {
        List<String> found = new ArrayList<>();
        String home = System.getProperty("user.home", "");
        if (!home.isBlank() && !home.equals("/") && text.contains(home)) {
            found.add("user.home");
        }
        if (Pattern.compile("(/home/|/Users/|[A-Za-z]:\\\\Users\\\\|[A-Za-z]:/Users/)").matcher(text).find()) {
            found.add("home-style path");
        }
        if (text.contains(tempRoot.toString())) {
            found.add("absolute temp path");
        }
        String user = System.getProperty("user.name", "");
        if (user.length() >= 4 && Pattern.compile("\\b" + Pattern.quote(user) + "\\b").matcher(text).find()) {
            found.add("user name");
        }
        return found;
    }

    @Test
    void theScannerFlagsEachPlantedLeakAndPassesACleanLine() {
        String home = System.getProperty("user.home");
        assertThat(leaks("{\"path\":\"" + home + "/x\"}", base)).contains("user.home");
        assertThat(leaks("see /home/someone/work", base)).contains("home-style path");
        assertThat(leaks("see /Users/someone/work", base)).contains("home-style path");
        assertThat(leaks("see C:\\Users\\someone\\work", base)).contains("home-style path");
        assertThat(leaks("at " + base.resolve("repo"), base)).contains("absolute temp path");
        assertThat(leaks("{\"id\":\"01JAAAA0000000000000000ABC\",\"source\":\"/spec-kitty/missions/01JAAAA0000000000000000ABD\"}", base)).isEmpty();
    }

    @Test
    void everythingAProducerRunWritesIsFreeOfLeaks() throws Exception {
        Path primary = GitFixtures.primary(base, "primary");
        Path lane = GitFixtures.linkedWorktree(primary, base.resolve("lane"), "lane");
        List<Process> children = List.of(
                ChildJvm.start(ClockChild.class, base.resolve("a.log"), "stamp", primary.toString(), "5", "A"),
                ChildJvm.start(ClockChild.class, base.resolve("b.log"), "stamp", lane.toString(), "5", "B"));
        assertThat(ChildJvm.awaitAll(children, 120)).containsOnly(0);

        List<Path> written = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(primary.resolve(".kittify"))) {
            walk.filter(Files::isRegularFile).forEach(written::add);
        }
        try (Stream<Path> walk = Files.walk(primary.resolve(".git/spec-kitty"))) {
            walk.filter(Files::isRegularFile).forEach(written::add);
        }
        written.addAll(shippedFixtures());
        assertThat(written).as("files scanned").hasSizeGreaterThanOrEqualTo(FILE_FLOOR);
        for (Path file : written) {
            assertThat(leaks(Files.readString(file, StandardCharsets.UTF_8), base)).as(file.getFileName().toString()).isEmpty();
        }
    }

    private static List<Path> shippedFixtures() throws IOException {
        List<Path> fixtures = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Path.of("src/test/resources"))) {
            walk.filter(Files::isRegularFile).forEach(fixtures::add);
        }
        assertThat(fixtures).as("shipped fixtures").hasSizeGreaterThanOrEqualTo(2);
        return fixtures;
    }
}
