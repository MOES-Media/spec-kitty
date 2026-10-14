package ai.speckitty.producer.domain.protocol;

import static ai.speckitty.producer.domain.order.Rows.num;
import static ai.speckitty.producer.domain.order.Rows.obj;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestTest {

    private static final Map<String, Object> ROW = obj("event_id", "01J00000000000000000000ABA");

    @Test
    void aProduceRequestCarriesTheRowAndContextUninterpreted() {
        ProduceRequest request = new ProduceRequest(1, ROW, Slot.STATUS, obj("repo_root", "x"), "/work/repo", 1500);
        assertThat(request.row()).isSameAs(ROW);
        assertThat(request.slot()).isEqualTo(Slot.STATUS);
        assertThat(request.startPath()).isEqualTo("/work/repo");
        assertThat(request.deadlineMs()).isEqualTo(1500);
        assertThat(request.version()).isEqualTo(Protocol.VERSION);
    }

    @Test
    void theProtocolVersionIsOneAndAnotherVersionIsRefused() {
        assertThat(Protocol.VERSION).isEqualTo(1);
        for (int v : new int[] {0, 2, -1}) {
            assertThatThrownBy(() -> new ProduceRequest(v, ROW, Slot.STATUS, Map.of(), "/p", 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RetainRequest(v, "/p", true, Map.of(), 1)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theDeadlineIsAtLeastOneMillisecond() {
        assertThat(new ProduceRequest(1, ROW, Slot.LIFECYCLE, Map.of(), "/p", 1).deadlineMs()).isEqualTo(1);
        assertThat(new RetainRequest(1, "/p", true, Map.of(), 1).deadlineMs()).isEqualTo(1);
        for (int ms : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertThatThrownBy(() -> new ProduceRequest(1, ROW, Slot.STATUS, Map.of(), "/p", ms)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RetainRequest(1, "/p", true, Map.of(), ms)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theStartPathAndRowAreRequired() {
        assertThatThrownBy(() -> new ProduceRequest(1, ROW, Slot.STATUS, Map.of(), "", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProduceRequest(1, ROW, Slot.STATUS, Map.of(), null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProduceRequest(1, null, Slot.STATUS, Map.of(), "/p", 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProduceRequest(1, ROW, null, Map.of(), "/p", 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RetainRequest(1, "", true, Map.of(), 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSlotsAreStatusAndLifecycleOnly() {
        assertThat(Slot.fromWire("status")).contains(Slot.STATUS);
        assertThat(Slot.fromWire("lifecycle")).contains(Slot.LIFECYCLE);
        assertThat(Slot.fromWire("resolved-binding")).isEmpty();
        assertThat(Slot.fromWire(null)).isEmpty();
        assertThat(Slot.values()).hasSize(2);
        assertThat(Slot.STATUS.wire()).isEqualTo("status");
    }

    @Test
    void dryRunIsTrueUnlessTheClientSentFalse() {
        assertThat(RetainRequest.dryRunFrom(false)).isFalse();
        assertThat(RetainRequest.dryRunFrom(true)).isTrue();
        assertThat(RetainRequest.dryRunFrom(null)).isTrue();
        for (Object other : new Object[] {"false", "no", 0, num("0"), "", Map.of()}) {
            assertThat(RetainRequest.dryRunFrom(other)).as("%s", other).isTrue();
        }
    }

    @Test
    void aRetainRequestCarriesRawPolicyValuesAndNoFacts() {
        Map<String, Object> policy = obj("keep_runs", "20", "max_age_days", null);
        RetainRequest request = new RetainRequest(1, "/p", true, policy, 20000);
        assertThat(request.policy()).containsEntry("keep_runs", "20").containsKey("max_age_days");
        assertThat(java.util.Arrays.stream(RetainRequest.class.getRecordComponents()).map(c -> c.getName()).toList())
                .containsExactly("version", "startPath", "dryRun", "policy", "deadlineMs");
    }

    @Test
    void theRequestsAreOneSealedFamily() {
        assertThat(Request.class.isSealed()).isTrue();
        assertThat(Request.class.getPermittedSubclasses()).containsExactlyInAnyOrder(ProduceRequest.class, RetainRequest.class);
    }
}
