package ai.speckitty.producer.launcher.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.speckitty.producer.launcher.ProduceHandler;
import ai.speckitty.producer.launcher.RequestException;
import ai.speckitty.producer.domain.protocol.ProduceRequest;
import ai.speckitty.producer.domain.protocol.Slot;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProduceHandlerTest {

    private static Map<String, Object> request() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("v", new JsonNumber("1"));
        request.put("op", "produce");
        request.put("deadline_ms", new JsonNumber("1500"));
        request.put("start_path", "missions/m1");
        request.put("slot", "status");
        request.put("seam_args", new LinkedHashMap<>(Map.of("bound", "x")));
        request.put("row", new LinkedHashMap<>(Map.of("event_id", "E")));
        return request;
    }

    private static List<ProduceRequest> seen;

    private static ProduceHandler handler() {
        seen = new ArrayList<>();
        return new ProduceHandler(captured -> {
            seen.add(captured);
            return new ai.speckitty.producer.domain.protocol.ProduceReply(
                    1, ai.speckitty.producer.domain.protocol.Outcome.PRODUCED, null, "ai.spec-kitty.x.v1", null, null, null);
        });
    }

    @Test
    void theOperationIsProduce() {
        assertThat(handler().operation()).isEqualTo("produce");
    }

    @Test
    void aWellFormedRequestIsDecodedFieldForField() throws Exception {
        handler().handle(request());
        assertThat(seen).hasSize(1);
        ProduceRequest decoded = seen.get(0);
        assertThat(decoded.version()).isEqualTo(1);
        assertThat(decoded.startPath()).isEqualTo("missions/m1");
        assertThat(decoded.deadlineMs()).isEqualTo(1500);
        assertThat(decoded.slot()).isEqualTo(Slot.STATUS);
        assertThat(decoded.row()).containsEntry("event_id", "E");
        assertThat(decoded.seamArgs()).containsEntry("bound", "x");
    }

    @Test
    void bothSlotsAreAccepted() throws Exception {
        Map<String, Object> request = request();
        request.put("slot", "lifecycle");
        handler().handle(request);
        assertThat(seen.get(0).slot()).isEqualTo(Slot.LIFECYCLE);
    }

    @Test
    void anAbsentSeamArgsIsEmptyBecauseTheProducerNeverReadsIt() throws Exception {
        Map<String, Object> request = request();
        request.remove("seam_args");
        handler().handle(request);
        assertThat(seen.get(0).seamArgs()).isEmpty();
    }

    @Test
    void aNullSeamArgsIsEmptyToo() throws Exception {
        Map<String, Object> request = request();
        request.put("seam_args", null);
        handler().handle(request);
        assertThat(seen.get(0).seamArgs()).isEmpty();
    }

    private static void assertUnreadable(Map<String, Object> request, String why) {
        assertThatThrownBy(() -> handler().handle(request)).as(why).isInstanceOf(RequestException.class);
        assertThat(seen).as(why + ": the use case is not reached").isEmpty();
    }

    @Test
    void aRequestMissingARequiredFieldOrHoldingTheWrongTypeIsUnreadable() {
        Map<String, Object> noRow = request();
        noRow.remove("row");
        assertUnreadable(noRow, "no row");
        Map<String, Object> rowList = request();
        rowList.put("row", List.of());
        assertUnreadable(rowList, "row not an object");
        Map<String, Object> rowNull = request();
        rowNull.put("row", null);
        assertUnreadable(rowNull, "row null");
        for (Object slot : new Object[] {null, "", "binding", "STATUS", 3, List.of("status")}) {
            Map<String, Object> bad = request();
            bad.put("slot", slot);
            assertUnreadable(bad, "slot " + slot);
        }
        Map<String, Object> noSlot = request();
        noSlot.remove("slot");
        assertUnreadable(noSlot, "no slot");
        for (Object path : new Object[] {null, "", 7, List.of("x")}) {
            Map<String, Object> bad = request();
            bad.put("start_path", path);
            assertUnreadable(bad, "start_path " + path);
        }
        Map<String, Object> noPath = request();
        noPath.remove("start_path");
        assertUnreadable(noPath, "no start_path");
        Map<String, Object> seamList = request();
        seamList.put("seam_args", List.of());
        assertUnreadable(seamList, "seam_args not an object");
        Map<String, Object> noDeadline = request();
        noDeadline.remove("deadline_ms");
        assertUnreadable(noDeadline, "no deadline");
    }

    @Test
    void theRepliesOfTheUseCaseBecomeTheReplyMap() throws Exception {
        ProduceHandler handler = new ProduceHandler(request -> new ai.speckitty.producer.domain.protocol.ProduceReply(
                1, ai.speckitty.producer.domain.protocol.Outcome.REFUSED, null, null,
                ai.speckitty.producer.domain.protocol.RefusalReason.TYPE_UNKNOWN, null, "no registered type matches the row"));
        Map<String, Object> reply = handler.handle(request());
        assertThat(reply).containsEntry("outcome", "refused").containsEntry("reason", "type-unknown").containsEntry("op", "produce");
    }
}
