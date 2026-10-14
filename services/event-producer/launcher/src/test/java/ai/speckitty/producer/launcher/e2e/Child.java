package ai.speckitty.producer.launcher.e2e;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs a launcher entry in a child JVM (or a native binary) over a request, as the Python client would. */
final class Child {

    /** What the child did. */
    record Run(int exit, byte[] stdout, String stderr, long millis) {
        String out() {
            return new String(stdout, StandardCharsets.UTF_8);
        }
    }

    private static final long TIMEOUT_SECONDS = 180;

    private Child() {}

    /** The command of a JVM running {@code mainClass} with {@code args}; the test JVM's own class path and Java. */
    static List<String> jvm(String mainClass, String... args) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass);
        command.addAll(List.of(args));
        return command;
    }

    static Run run(List<String> command, Path cwd, byte[] stdin) throws IOException, InterruptedException {
        Process process = start(command, cwd);
        return finish(process, stdin);
    }

    static Process start(List<String> command, Path cwd) throws IOException {
        return new ProcessBuilder(command).directory(cwd.toFile()).start();
    }

    static Run finish(Process process, byte[] stdin) throws IOException, InterruptedException {
        long started = System.nanoTime();
        byte[][] stderr = new byte[1][];
        Thread errors = new Thread(() -> {
            try {
                stderr[0] = process.getErrorStream().readAllBytes();
            } catch (IOException e) {
                stderr[0] = new byte[0];
            }
        });
        errors.start();
        try (var stdinStream = process.getOutputStream()) {
            stdinStream.write(stdin);
        } catch (IOException e) {
            // the child may exit without reading its input
        }
        byte[] stdout = process.getInputStream().readAllBytes();
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("the child did not exit within " + TIMEOUT_SECONDS + " s");
        }
        errors.join();
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return new Run(process.exitValue(), stdout, new String(stderr[0] == null ? new byte[0] : stderr[0], StandardCharsets.UTF_8), millis);
    }
}
