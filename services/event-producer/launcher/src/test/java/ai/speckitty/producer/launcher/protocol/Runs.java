package ai.speckitty.producer.launcher.protocol;

import ai.speckitty.producer.launcher.Dispatcher;
import ai.speckitty.producer.launcher.Launcher;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Runs the launcher in process over a request. */
final class Runs {

    /** The outcome of one run. */
    record Result(int exit, byte[] stdout, List<Integer> halts) {
        String text() {
            return new String(stdout, StandardCharsets.UTF_8);
        }
    }

    private Runs() {}

    static Result run(Dispatcher dispatcher, String request) {
        return run(dispatcher, request.getBytes(StandardCharsets.UTF_8));
    }

    static Result run(Dispatcher dispatcher, byte[] request) {
        List<Integer> halts = new ArrayList<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = new Launcher(() -> dispatcher, halts::add).run(new ByteArrayInputStream(request), out);
        return new Result(exit, out.toByteArray(), halts);
    }
}
