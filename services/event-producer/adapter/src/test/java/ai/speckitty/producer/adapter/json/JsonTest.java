package ai.speckitty.producer.adapter.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.speckitty.producer.domain.fault.FaultReason;
import ai.speckitty.producer.domain.fault.ParseResult;
import ai.speckitty.producer.domain.value.JsonNumber;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {

    private static final String SMILEY = new String(Character.toChars(0x1F600));
    private static final String FULLWIDTH_TILDE = new String(Character.toChars(0xFF5E));

    private static Map<String, Object> parsed(String text) {
        ParseResult<Map<String, Object>> result = Json.parseObject(text);
        assertThat(result).as(text).isInstanceOf(ParseResult.Accepted.class);
        return ((ParseResult.Accepted<Map<String, Object>>) result).value();
    }

    private static void assertRefused(ParseResult<?> result) {
        assertThat(result).isInstanceOf(ParseResult.Refused.class);
        assertThat(((ParseResult.Refused<?>) result).reason()).isEqualTo(FaultReason.REJECTED);
    }

    @Test
    void numberTextIsPreservedSoFiveDotZeroIsNotFive() {
        Map<String, Object> row = parsed("{\"a\":5.0,\"b\":5,\"c\":1E5,\"d\":-0,\"e\":12345678901234567890123}");
        assertThat(row.get("a")).isEqualTo(new JsonNumber("5.0"));
        assertThat(((JsonNumber) row.get("a")).isInteger()).isFalse();
        assertThat(row.get("b")).isEqualTo(new JsonNumber("5"));
        assertThat(((JsonNumber) row.get("b")).isInteger()).isTrue();
        assertThat(row.get("c")).isEqualTo(new JsonNumber("1E5"));
        assertThat(row.get("d")).isEqualTo(new JsonNumber("-0"));
        assertThat(row.get("e")).isEqualTo(new JsonNumber("12345678901234567890123"));
        assertThat(row.get("a")).isNotEqualTo(row.get("b"));
    }

    @Test
    void theValueModelIsTheDomainModel() {
        Map<String, Object> row = parsed("{\"s\":\"x\",\"t\":true,\"f\":false,\"n\":null,\"l\":[1,\"a\",[]],\"o\":{\"k\":{}}}");
        assertThat(row.get("s")).isEqualTo("x");
        assertThat(row.get("t")).isEqualTo(Boolean.TRUE);
        assertThat(row.get("f")).isEqualTo(Boolean.FALSE);
        assertThat(row).containsKey("n");
        assertThat(row.get("n")).isNull();
        assertThat(row.get("l")).isEqualTo(Arrays.asList(new JsonNumber("1"), "a", List.of()));
        assertThat(row.get("o")).isEqualTo(Map.of("k", Map.of()));
        assertThat(new ArrayList<>(row.keySet())).containsExactly("s", "t", "f", "n", "l", "o");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"a\":1,\"a\":2}",
        "{\"o\":{\"x\":1,\"x\":1}}",
        "{\"a\":1} {\"b\":2}",
        "{\"a\":1}x",
        "{\"a\":NaN}",
        "{\"a\":Infinity}",
        "{\"a\":01}",
        "{\"a\":1.}",
        "{'a':1}",
        "{\"a\":1,}",
        "[1,2]",
        "\"text\"",
        "5",
        "null",
        "",
        "   ",
        "{\"a\":",
        "\uFEFF{\"a\":1}",
        "{\"a\":\"\ud800\"}"
    })
    void everyMalformedOrNonObjectDocumentIsRefused(String text) {
        assertRefused(Json.parseObject(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"a\":1}", " {\"a\" : [ 1 , 2 ] } ", "{\"a\":\"\\u00e9\\ud83d\\ude00\"}", "{\"a\":-1.5e-3}"})
    void everyWellFormedObjectIsAccepted(String text) {
        parsed(text);
    }

    @Test
    void bytesWithABomOrInvalidUtf8AreRefused() {
        assertRefused(Json.parseObject(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'}));
        assertRefused(Json.parseObject(new byte[] {'{', '"', 'a', '"', ':', '"', (byte) 0xC3, '"', '}'}));
        assertThat(Json.parseObject("{}".getBytes(StandardCharsets.UTF_8))).isInstanceOf(ParseResult.Accepted.class);
    }

    @Test
    void renderMatchesPythonJsonDumpsForTheSameValue() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("b", new JsonNumber("1"));
        row.put("a", "\u00e9\n\u0001/" + SMILEY + "\u007f");
        row.put("k", Arrays.asList(Boolean.TRUE, null, "x"));
        // python3: json.dumps(d, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
        assertThat(Json.render(row))
                .isEqualTo("{\"a\":\"\\u00e9\\n\\u0001/\\ud83d\\ude00\\u007f\",\"b\":1,\"k\":[true,null,\"x\"]}");
    }

    @Test
    void membersAreSortedByCodePointNotByUtf16Unit() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(SMILEY, new JsonNumber("1"));
        row.put(FULLWIDTH_TILDE, new JsonNumber("2"));
        row.put("z", new JsonNumber("3"));
        assertThat(Json.render(row)).isEqualTo("{\"z\":3,\"\\uff5e\":2,\"\\ud83d\\ude00\":1}");
    }

    @Test
    void sameValueInAnyInsertionOrderGivesIdenticalBytes() {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("x", Map.of("p", "1", "q", "2"));
        one.put("y", new JsonNumber("2.50"));
        Map<String, Object> two = new LinkedHashMap<>();
        two.put("y", new JsonNumber("2.50"));
        two.put("x", new LinkedHashMap<>(Map.of("q", "2", "p", "1")));
        assertThat(Json.render(two)).isEqualTo(Json.render(one));
    }

    @Test
    void numberTextSurvivesARoundTripUnchanged() {
        String text = "{\"a\":5.0,\"b\":5,\"c\":1E5,\"d\":-0,\"e\":2.50}";
        assertThat(Json.render(parsed(text))).isEqualTo(text);
    }

    @Test
    void outputIsCompactAsciiWithNoBomNewlineOrSpace() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("text", "line1\nline2\r\ttab " + SMILEY);
        String out = Json.render(row);
        assertThat(out).doesNotContain("\n", "\r", "\t");
        assertThat(out.chars().allMatch(c -> c >= 0x20 && c < 0x7f)).isTrue();
        assertThat(out).doesNotStartWith("\uFEFF");
        assertThat(out).isEqualTo("{\"text\":\"line1\\nline2\\r\\ttab \\ud83d\\ude00\"}");
    }

    @Test
    void renderRefusesAValueOutsideTheModel() {
        assertThatThrownBy(() -> Json.render(Map.of("a", 5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.render(Map.of("a", 5.0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.render(Map.of("a", new Object()))).isInstanceOf(IllegalArgumentException.class);
        Map<Object, Object> badKey = new LinkedHashMap<>();
        badKey.put(1, "x");
        assertThatThrownBy(() -> Json.render(badKey)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renderThenParseIsTheIdentityOnTheModel() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("n", null);
        row.put("l", Arrays.asList(new JsonNumber("7"), "\u00e9" + SMILEY, Map.of("k", Boolean.FALSE)));
        row.put("s", "a\"b\\c/d");
        assertThat(parsed(Json.render(row))).isEqualTo(row);
    }
}
