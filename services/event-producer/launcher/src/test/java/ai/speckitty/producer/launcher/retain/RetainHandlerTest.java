package ai.speckitty.producer.launcher.retain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.speckitty.producer.adapter.json.Json;
import ai.speckitty.producer.application.retain.RetainResult;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.protocol.Protocol;
import ai.speckitty.producer.domain.protocol.RelativePath;
import ai.speckitty.producer.domain.protocol.RetainItem;
import ai.speckitty.producer.domain.protocol.RetainReply;
import ai.speckitty.producer.domain.protocol.RetainRequest;
import ai.speckitty.producer.domain.retention.PolicyError;
import ai.speckitty.producer.domain.value.JsonNumber;
import ai.speckitty.producer.launcher.RequestException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** The retain handler decodes a request, runs the use case and renders the reply of plan section 5. */
class RetainHandlerTest {

    private static final RelativePath ELIGIBLE = new RelativePath(".kittify/events/faults/2024-01-01.jsonl");
    private static final RelativePath KEPT = new RelativePath(".kittify/events/faults/2026-10-14.jsonl");

    private final List<RetainRequest> seen = new ArrayList<>();
    private RetainResult answer = new RetainResult.Replied(new RetainReply(Protocol.VERSION, true, List.of(), List.of(), List.of(), null));
    private final Function<RetainRequest, RetainResult> useCase = request -> {
        seen.add(request);
        return answer;
    };
    private final RetainHandler handler = new RetainHandler(useCase);

    @SuppressWarnings("unchecked")
    private static Map<String, Object> request(String json) {
        ParseResult<Map<String, Object>> parsed = Json.parseObject(json);
        assertThat(parsed).isInstanceOf(ParseResult.Accepted.class);
        return ((ParseResult.Accepted<Map<String, Object>>) parsed).value();
    }

    private static final String GOOD = "{\"v\":1,\"op\":\"retain\",\"deadline_ms\":5000,\"start_path\":\"start\"";

    @Test
    void theOperationIsRetain() {
        assertThat(handler.operation()).isEqualTo("retain");
        assertThat(new RetainHandler().operation()).as("the no-argument form the service loader calls").isEqualTo("retain");
    }

    @Test
    void aRetainRequestIsDecodedAndThePolicyIsForwardedRawAndUnread() throws Exception {
        handler.handle(request(GOOD + ",\"dry_run\":false,\"policy\":{\"keep_runs\":\"abc\",\"max_age_days\":7,\"other\":[1]}}"));
        RetainRequest decoded = seen.get(0);
        assertThat(decoded.startPath()).isEqualTo("start");
        assertThat(decoded.dryRun()).isFalse();
        assertThat(decoded.deadlineMs()).isEqualTo(5000);
        assertThat(decoded.version()).isEqualTo(Protocol.VERSION);
        assertThat(decoded.policy()).containsEntry("keep_runs", "abc").containsEntry("max_age_days", new JsonNumber("7"));
    }

    @Test
    void dryRunIsTrueUnlessTheClientSentTheJsonValueFalse() throws Exception {
        for (String dry : List.of("", ",\"dry_run\":true", ",\"dry_run\":null", ",\"dry_run\":\"false\"", ",\"dry_run\":0", ",\"dry_run\":[]", ",\"dry_run\":{}")) {
            seen.clear();
            handler.handle(request(GOOD + dry + "}"));
            assertThat(seen.get(0).dryRun()).as("dry_run" + dry).isTrue();
        }
        seen.clear();
        handler.handle(request(GOOD + ",\"dry_run\":false}"));
        assertThat(seen.get(0).dryRun()).isFalse();
    }

    @Test
    void anAbsentPolicyIsAnEmptyOneNotADefaultTheClientMadeUp() throws Exception {
        handler.handle(request(GOOD + "}"));
        assertThat(seen.get(0).policy()).isEmpty();
    }

    @Test
    void aRequestTheOperationCannotReadIsUnreadable() {
        for (String bad : List.of(
                "{\"v\":1,\"op\":\"retain\",\"deadline_ms\":5000}",
                "{\"v\":1,\"op\":\"retain\",\"deadline_ms\":5000,\"start_path\":\"\"}",
                "{\"v\":1,\"op\":\"retain\",\"deadline_ms\":5000,\"start_path\":7}",
                "{\"v\":1,\"op\":\"retain\",\"deadline_ms\":5000,\"start_path\":null}",
                GOOD + ",\"policy\":[]}",
                GOOD + ",\"policy\":\"keep_runs\"}",
                GOOD + ",\"policy\":null}",
                "{\"v\":1,\"op\":\"retain\",\"start_path\":\"s\"}",
                "{\"v\":1,\"op\":\"retain\",\"deadline_ms\":0,\"start_path\":\"s\"}",
                "{\"v\":1,\"op\":\"retain\",\"deadline_ms\":1.5,\"start_path\":\"s\"}",
                "{\"v\":1,\"op\":\"retain\",\"deadline_ms\":\"5000\",\"start_path\":\"s\"}")) {
            assertThatThrownBy(() -> handler.handle(request(bad))).as(bad).isInstanceOf(RequestException.class);
        }
        assertThat(seen).as("the use case never ran").isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theReplyListsEligibleKeptAndDeletedWithRepoRelativePathsOnly() throws Exception {
        answer = new RetainResult.Replied(new RetainReply(
                Protocol.VERSION, false,
                List.of(new RetainItem(ELIGIBLE, "closed-mission-outside-limits")),
                List.of(new RetainItem(KEPT, "within-max-age")),
                List.of(ELIGIBLE), null));
        Map<String, Object> reply = handler.handle(request(GOOD + ",\"dry_run\":false}"));
        assertThat(Json.render(reply)).isEqualTo(
                "{\"deleted\":[\"" + ELIGIBLE.value() + "\"],\"dry_run\":false,"
                        + "\"eligible\":[{\"path\":\"" + ELIGIBLE.value() + "\",\"reason\":\"closed-mission-outside-limits\"}],"
                        + "\"kept\":[{\"path\":\"" + KEPT.value() + "\",\"reason\":\"within-max-age\"}],\"op\":\"retain\",\"v\":1}");
        assertThat(((Map<String, Object>) reply).get("policy_error")).isNull();
    }

    @Test
    void aDryRunReplyRepeatsTheFlagAndDeletesNothing() throws Exception {
        answer = new RetainResult.Replied(new RetainReply(
                Protocol.VERSION, true, List.of(new RetainItem(ELIGIBLE, "closed-mission-outside-limits")), List.of(), List.of(), null));
        String text = Json.render(handler.handle(request(GOOD + "}")));
        assertThat(text).contains("\"dry_run\":true").contains("\"deleted\":[]");
    }

    @Test
    void aPolicyErrorIsNamedWithItsKeyAndCodeAndNeverEchoesTheValue() throws Exception {
        answer = new RetainResult.Replied(new RetainReply(
                Protocol.VERSION, true, List.of(), List.of(), List.of(), new PolicyError("ledger.retention.keep_runs", "invalid-value")));
        String text = Json.render(handler.handle(request(GOOD + ",\"policy\":{\"keep_runs\":\"hunter2\"}}")));
        assertThat(text).contains("\"policy_error\":{\"code\":\"invalid-value\",\"key\":\"ledger.retention.keep_runs\","
                + "\"message\":\"policy_error: ledger.retention.keep_runs must be a positive integer\"}");
        assertThat(text).doesNotContain("hunter2").contains("\"eligible\":[]").contains("\"kept\":[]").contains("\"deleted\":[]");
    }

    @Test
    void noRepositoryIsARefusedReplyWithAClosedReasonAndNoPath() throws Exception {
        answer = new RetainResult.RootUnresolvable();
        Map<String, Object> reply = handler.handle(request(GOOD + "}"));
        assertThat(Json.render(reply)).isEqualTo("{\"op\":\"retain\",\"outcome\":\"refused\",\"reason\":\"root-unresolvable\",\"v\":1}");
        assertThat(new LinkedHashMap<>(reply)).doesNotContainKey("start_path");
    }

    @Test
    void theRequestNeverCarriesFactsSoAFactMemberIsIgnoredNotObeyed() throws Exception {
        handler.handle(request(GOOD + ",\"mission_open\":false,\"locked\":false,\"eligible\":[\"x\"],\"facts\":{}}"));
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).policy()).isEmpty();
    }
}
