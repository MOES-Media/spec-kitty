package ai.speckitty.producer.launcher.retain;

import ai.speckitty.producer.application.retain.RetainResult;
import ai.speckitty.producer.domain.protocol.RetainRequest;
import ai.speckitty.producer.launcher.OperationHandler;
import ai.speckitty.producer.launcher.RequestException;
import java.util.Map;
import java.util.function.Function;

/** The {@code retain} operation (stub: the tests are written first). */
public final class RetainHandler implements OperationHandler {

    public RetainHandler() {}

    public RetainHandler(Function<RetainRequest, RetainResult> useCase) {}

    @Override
    public String operation() {
        return "retain";
    }

    @Override
    public Map<String, Object> handle(Map<String, Object> request) throws RequestException {
        throw new RequestException("not implemented");
    }
}
