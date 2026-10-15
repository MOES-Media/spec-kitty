package ai.speckitty.producer.launcher.retain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Runs a main class of the test class path in its own JVM. */
final class ChildJvm {

    private ChildJvm() {}

    static List<String> command(String mainClass, String... args) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass);
        command.addAll(List.of(args));
        return command;
    }

    static Process start(List<String> command, Path cwd) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_CEILING_DIRECTORIES", cwd.getParent().toString());
        return builder.start();
    }

    /** The whole of the process's output, read on its own thread so that the pipe never fills. */
    static CompletableFuture<String> collect(Process process) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw Repos.unchecked(e);
            }
        });
    }
}
