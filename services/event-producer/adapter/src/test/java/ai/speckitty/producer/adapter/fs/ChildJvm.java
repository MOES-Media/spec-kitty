package ai.speckitty.producer.adapter.fs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Starts a JVM on the test class path, for the tests that prove a lock between real processes. */
public final class ChildJvm {

    private ChildJvm() {}

    /** Starts {@code mainClass} with {@code args}; stdout and stderr go to {@code log}. */
    public static Process start(Class<?> mainClass, Path log, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-Xshare:auto");
        command.add("-XX:TieredStopAtLevel=1");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass.getName());
        command.addAll(List.of(args));
        return new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
    }

    /** Waits for every process and returns the exit codes, failing the test on a timeout. */
    public static int[] awaitAll(List<Process> processes, long seconds) throws InterruptedException {
        int[] codes = new int[processes.size()];
        for (int i = 0; i < codes.length; i++) {
            if (!processes.get(i).waitFor(seconds, TimeUnit.SECONDS)) {
                processes.forEach(Process::destroyForcibly);
                throw new AssertionError("a child JVM did not finish in " + seconds + " s");
            }
            codes[i] = processes.get(i).exitValue();
        }
        return codes;
    }
}
