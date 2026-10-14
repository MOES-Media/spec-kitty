package ai.speckitty.producer.launcher;

import ai.speckitty.producer.domain.protocol.ProduceReply;
import ai.speckitty.producer.domain.protocol.ProduceRequest;
import ai.speckitty.producer.domain.protocol.Protocol;
import ai.speckitty.producer.domain.protocol.Slot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** The {@code produce} operation: decodes the request, runs the use case, renders the reply. */
public final class ProduceHandler implements OperationHandler {

    private final Function<ProduceRequest, ProduceReply> useCase;

    public ProduceHandler(Function<ProduceRequest, ProduceReply> useCase) {
        this.useCase = useCase;
    }

    @Override
    public String operation() {
        return "produce";
    }

    @Override
    public Map<String, Object> handle(Map<String, Object> request) throws RequestException {
        return Replies.produce(useCase.apply(decode(request)));
    }

    private static ProduceRequest decode(Map<String, Object> request) throws RequestException {
        Map<String, Object> row = Fields.objectOrNull(request.get("row"));
        if (row == null) {
            throw new RequestException("the row is not an object");
        }
        Object rawSeamArgs = request.get("seam_args");
        Map<String, Object> seamArgs = rawSeamArgs == null ? new LinkedHashMap<>() : Fields.objectOrNull(rawSeamArgs);
        if (seamArgs == null) {
            throw new RequestException("seam_args is not an object");
        }
        if (!(request.get("slot") instanceof String slotName) || Slot.fromWire(slotName).isEmpty()) {
            throw new RequestException("the slot is not a registered slot");
        }
        if (!(request.get("start_path") instanceof String startPath) || startPath.isEmpty()) {
            throw new RequestException("the start path is not a non-empty string");
        }
        int deadline = Fields.deadlineMs(request.get("deadline_ms"));
        if (deadline < 1) {
            throw new RequestException("the deadline is not a positive integer");
        }
        return new ProduceRequest(Protocol.VERSION, row, Slot.fromWire(slotName).orElseThrow(), seamArgs, startPath, deadline);
    }
}
