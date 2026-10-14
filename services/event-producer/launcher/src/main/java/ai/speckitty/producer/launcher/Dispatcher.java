package ai.speckitty.producer.launcher;

import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;

/** The operations the launcher answers, by name. Registering the same operation twice fails closed. */
public final class Dispatcher {

    private final TreeMap<String, OperationHandler> byOperation = new TreeMap<>();

    private Dispatcher() {}

    /** A dispatcher over exactly these handlers. */
    public static Dispatcher of(OperationHandler... handlers) {
        Dispatcher dispatcher = new Dispatcher();
        for (OperationHandler handler : handlers) {
            dispatcher.register(handler);
        }
        return dispatcher;
    }

    /**
     * {@code produce} registered directly, then every provider {@code loader} lists. A provider that claims an operation already
     * held, or that cannot be loaded, is a defect of the build: this fails rather than replacing a handler.
     */
    public static Dispatcher discover(OperationHandler produce, ClassLoader loader) {
        Dispatcher dispatcher = of(produce);
        try {
            for (OperationHandler provider : ServiceLoader.load(OperationHandler.class, loader)) {
                dispatcher.register(provider);
            }
        } catch (ServiceConfigurationError e) {
            throw new IllegalStateException("an operation provider could not be loaded", e);
        }
        return dispatcher;
    }

    private void register(OperationHandler handler) {
        String operation = handler.operation();
        if (byOperation.putIfAbsent(operation, handler) != null) {
            throw new IllegalStateException("two handlers claim the operation " + operation);
        }
    }

    public Optional<OperationHandler> find(String operation) {
        return Optional.ofNullable(byOperation.get(operation));
    }

    /** The registered operations, in name order. */
    public Set<String> operations() {
        return byOperation.navigableKeySet();
    }
}
