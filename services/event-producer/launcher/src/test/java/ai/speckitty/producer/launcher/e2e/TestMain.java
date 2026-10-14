package ai.speckitty.producer.launcher.e2e;

import ai.speckitty.producer.application.port.RowSchemaValidator;
import ai.speckitty.producer.application.produce.Produce;
import ai.speckitty.producer.launcher.Dispatcher;
import ai.speckitty.producer.launcher.Launcher;
import ai.speckitty.producer.launcher.ProduceHandler;
import ai.speckitty.producer.launcher.Wiring;
import java.util.Map;
import java.util.Optional;

/**
 * The launcher entry with a test-local validator injected through the test-only {@link Wiring} factory. {@code accept} validates the
 * shape of the document; {@code sleep} never decides in time (for the deadline).
 */
public final class TestMain {

    private TestMain() {}

    public static void main(String[] args) throws Exception {
        RowSchemaValidator validator = switch (args[0]) {
            case "accept" -> TestMain::acceptShape;
            case "sleep" -> document -> {
                try {
                    Thread.sleep(120_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Optional.empty();
            };
            default -> throw new IllegalArgumentException("unknown mode");
        };
        Produce produce = Wiring.withValidator(validator, E2eTypes.catalog());
        Dispatcher dispatcher = Dispatcher.discover(new ProduceHandler(produce::produce), TestMain.class.getClassLoader());
        int exit = new Launcher(() -> dispatcher).run(System.in, System.out);
        System.out.flush();
        if (exit != 0) {
            System.exit(exit);
        }
    }

    private static Optional<String> acceptShape(Map<String, Object> document) {
        if (!"1.0".equals(document.get("specversion")) || !(document.get("data") instanceof Map<?, ?>) || !(document.get("id") instanceof String)) {
            return Optional.of("/: not a CloudEvents document");
        }
        return Optional.empty();
    }
}
