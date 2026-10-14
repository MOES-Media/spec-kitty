package ai.speckitty.producer.launcher.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.speckitty.producer.launcher.Dispatcher;
import ai.speckitty.producer.launcher.OperationHandler;
import ai.speckitty.producer.launcher.RequestException;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DispatcherTest {

    /** A provider the ServiceLoader can instantiate: public, with a public no-argument constructor. */
    public static final class RetainProvider implements OperationHandler {
        @Override
        public String operation() {
            return "retain";
        }

        @Override
        public Map<String, Object> handle(Map<String, Object> request) throws RequestException {
            return Map.of("answered", "by-provider");
        }
    }

    /** A second provider claiming an operation another handler already holds. */
    public static final class ProduceThief implements OperationHandler {
        @Override
        public String operation() {
            return "produce";
        }

        @Override
        public Map<String, Object> handle(Map<String, Object> request) throws RequestException {
            return Map.of();
        }
    }

    /** A second retain provider. */
    public static final class RetainTwin implements OperationHandler {
        @Override
        public String operation() {
            return "retain";
        }

        @Override
        public Map<String, Object> handle(Map<String, Object> request) throws RequestException {
            return Map.of();
        }
    }

    private static ClassLoader loaderWith(Path dir, Class<?>... providers) throws IOException {
        Path services = Files.createDirectories(dir.resolve("META-INF/services"));
        StringBuilder names = new StringBuilder();
        for (Class<?> provider : providers) {
            names.append(provider.getName()).append('\n');
        }
        Files.writeString(services.resolve(OperationHandler.class.getName()), names.toString());
        return new URLClassLoader(new URL[] {dir.toUri().toURL()}, DispatcherTest.class.getClassLoader());
    }

    @Test
    void theOperationOfAHandlerIsLookedUpByName() {
        Dispatcher dispatcher = Dispatcher.of(StubHandlers.fixed("produce"));
        assertThat(dispatcher.find("produce")).isPresent();
        assertThat(dispatcher.find("retain")).isEmpty();
        assertThat(dispatcher.find("")).isEmpty();
    }

    @Test
    void produceIsRegisteredDirectlyAndNoProviderFileMeansNoOtherOperation(@TempDir Path dir) throws IOException {
        ClassLoader empty = new URLClassLoader(new URL[] {dir.toUri().toURL()}, null);
        Dispatcher dispatcher = Dispatcher.discover(StubHandlers.fixed("produce"), empty);
        assertThat(dispatcher.operations()).containsExactly("produce");
    }

    @Test
    void aProviderListedInTheServicesFileIsDiscoveredAndAnswers(@TempDir Path dir) throws Exception {
        Dispatcher dispatcher = Dispatcher.discover(StubHandlers.fixed("produce"), loaderWith(dir, RetainProvider.class));
        assertThat(dispatcher.operations()).containsExactlyInAnyOrder("produce", "retain");
        assertThat(dispatcher.find("retain").orElseThrow().handle(Map.of())).containsEntry("answered", "by-provider");
    }

    @Test
    void aProviderThatClaimsAnOperationAlreadyHeldFailsClosedRatherThanReplacingIt(@TempDir Path dir) throws Exception {
        ClassLoader thief = loaderWith(dir, ProduceThief.class);
        assertThatThrownBy(() -> Dispatcher.discover(StubHandlers.fixed("produce"), thief))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("produce");
    }

    @Test
    void twoProvidersForOneOperationFailClosed(@TempDir Path dir) throws Exception {
        ClassLoader twins = loaderWith(dir, RetainProvider.class, RetainTwin.class);
        assertThatThrownBy(() -> Dispatcher.discover(StubHandlers.fixed("produce"), twins))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("retain");
    }

    @Test
    void theOperationsOfAnExplicitDispatcherAreExactlyTheOnesGiven() {
        assertThat(Dispatcher.of(StubHandlers.fixed("produce"), StubHandlers.fixed("retain")).operations())
                .containsExactlyInAnyOrder("produce", "retain");
        assertThatThrownBy(() -> Dispatcher.of(StubHandlers.fixed("produce"), StubHandlers.fixed("produce")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theHandlersAreListedInAStableOrder() {
        assertThat(List.copyOf(Dispatcher.of(StubHandlers.fixed("b"), StubHandlers.fixed("a")).operations())).containsExactly("a", "b");
    }
}
