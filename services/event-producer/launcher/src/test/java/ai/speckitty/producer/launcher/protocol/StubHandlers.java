package ai.speckitty.producer.launcher.protocol;

import ai.speckitty.producer.launcher.OperationHandler;
import ai.speckitty.producer.launcher.RequestException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Operation handlers for protocol tests. */
final class StubHandlers {

    private StubHandlers() {}

    /** A handler for {@code operation} that answers by {@code body}. */
    static OperationHandler of(String operation, Function<Map<String, Object>, Map<String, Object>> body) {
        return new OperationHandler() {
            @Override
            public String operation() {
                return operation;
            }

            @Override
            public Map<String, Object> handle(Map<String, Object> request) throws RequestException {
                return body.apply(request);
            }
        };
    }

    /** A handler that answers a fixed small reply. */
    static OperationHandler fixed(String operation) {
        return of(operation, request -> {
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("v", new ai.speckitty.producer.domain.value.JsonNumber("1"));
            reply.put("op", operation);
            reply.put("outcome", "produced");
            return reply;
        });
    }

    /** A handler that refuses the request as unreadable. */
    static OperationHandler unreadable(String operation) {
        return new OperationHandler() {
            @Override
            public String operation() {
                return operation;
            }

            @Override
            public Map<String, Object> handle(Map<String, Object> request) throws RequestException {
                throw new RequestException("the request is unreadable");
            }
        };
    }
}
