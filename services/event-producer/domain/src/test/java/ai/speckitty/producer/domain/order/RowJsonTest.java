package ai.speckitty.producer.domain.order;

import static ai.speckitty.producer.domain.order.Rows.list;
import static ai.speckitty.producer.domain.order.Rows.num;
import static ai.speckitty.producer.domain.order.Rows.obj;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RowJsonTest {

    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    private static final String BS = String.valueOf((char) 92);

    @Test
    void scalarsSerialiseAsJson() {
        assertThat(RowJson.serialize(null)).isEqualTo("null");
        assertThat(RowJson.serialize(true)).isEqualTo("true");
        assertThat(RowJson.serialize(false)).isEqualTo("false");
        assertThat(RowJson.serialize("a b")).isEqualTo("\"a b\"");
    }

    @Test
    void membersAreSortedAndCompact() {
        assertThat(RowJson.serialize(obj("b", num("1"), "a", list(true, null, "x"), "c", obj()))).isEqualTo("{\"a\":[true,null,\"x\"],\"b\":1,\"c\":{}}");
        assertThat(RowJson.serialize(list())).isEqualTo("[]");
    }

    @Test
    void keysAreSortedByCodePointNotByUtf16Unit() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(cp(0x10000), num("1"));
        row.put(cp(0xFFFF), num("2"));
        assertThat(RowJson.serialize(row)).isEqualTo("{\"" + BS + "uffff\":2,\"" + BS + "ud800" + BS + "udc00\":1}");
    }

    @Test
    void theCommonEscapesAreShortAndOtherControlCharactersAreFourHexDigits() {
        assertThat(RowJson.serialize("\"" + BS + "\n\r\t\b\f")).isEqualTo("\"" + BS + "\"" + BS + BS + BS + "n" + BS + "r" + BS + "t" + BS + "b" + BS + "f\"");
        assertThat(RowJson.serialize(String.valueOf((char) 0x1f))).isEqualTo("\"" + BS + "u001f\"");
        assertThat(RowJson.serialize(String.valueOf((char) 0))).isEqualTo("\"" + BS + "u0000\"");
    }

    @Test
    void theEdgesOfTheUnescapedRangeAreSpaceAndTilde() {
        assertThat(RowJson.serialize(" ~")).isEqualTo("\" ~\"");
        assertThat(RowJson.serialize(String.valueOf((char) 0x7f))).isEqualTo("\"" + BS + "u007f\"");
        assertThat(RowJson.serialize(String.valueOf((char) 0x1f))).startsWith("\"" + BS + "u");
    }

    @Test
    void nonAsciiIsLowerCaseHexWithSurrogatePairsForAstralPoints() {
        assertThat(RowJson.serialize(cp(0xE9))).isEqualTo("\"" + BS + "u00e9\"");
        assertThat(RowJson.serialize(cp(0xFFFF))).isEqualTo("\"" + BS + "uffff\"");
        assertThat(RowJson.serialize(cp(0x1F600))).isEqualTo("\"" + BS + "ud83d" + BS + "ude00\"");
    }

    @Test
    void aLoneSurrogateSerialisesLikeAnyOtherCharacter() {
        assertThat(RowJson.serialize(String.valueOf((char) 0xD800))).isEqualTo("\"" + BS + "ud800\"");
    }

    @Test
    void integersAreExact() {
        assertThat(RowJson.serialize(num("0"))).isEqualTo("0");
        assertThat(RowJson.serialize(num("-0"))).isEqualTo("0");
        assertThat(RowJson.serialize(num("-17"))).isEqualTo("-17");
        assertThat(RowJson.serialize(num("123456789012345678901234567890"))).isEqualTo("123456789012345678901234567890");
    }

    @Test
    void otherNumbersUseTheShortestRoundTripForm() {
        String[][] table = {
            {"5.0", "5.0"}, {"0.5", "0.5"}, {"1e2", "100.0"}, {"1E2", "100.0"}, {"1e15", "1000000000000000.0"},
            {"1e16", "1e+16"}, {"0.0001", "0.0001"}, {"0.00001", "1e-05"}, {"1.5e-7", "1.5e-07"}, {"2.5e-5", "2.5e-05"},
            {"123456789012345678.0", "1.2345678901234568e+17"}, {"12345678901234567.0", "1.2345678901234568e+16"},
            {"1234567890123456.0", "1234567890123456.0"}, {"1e22", "1e+22"}, {"5e-324", "5e-324"},
            {"1.7976931348623157e308", "1.7976931348623157e+308"}, {"0.1", "0.1"}, {"-0.0", "-0.0"}, {"0.0", "0.0"},
            {"-1.5", "-1.5"}, {"9007199254740993.0", "9007199254740992.0"}, {"3.14159", "3.14159"}, {"123e-2", "1.23"},
            {"1e400", "Infinity"}, {"-1e400", "-Infinity"}, {"1e-400", "0.0"}
        };
        for (String[] row : table) {
            assertThat(RowJson.serialize(num(row[0]))).as(row[0]).isEqualTo(row[1]);
        }
    }

    @Test
    void theBoundaryBetweenPlainAndExponentFormIsSixteenDigits() {
        assertThat(RowJson.pythonFloat("9999999999999998.0")).isEqualTo("9999999999999998.0");
        assertThat(RowJson.pythonFloat("1e16")).isEqualTo("1e+16");
        assertThat(RowJson.pythonFloat("0.0001")).isEqualTo("0.0001");
        assertThat(RowJson.pythonFloat("0.00009999")).isEqualTo("9.999e-05");
    }

    @Test
    void hostObjectsOutsideTheModelAreRefused() {
        assertThatThrownBy(() -> RowJson.serialize(5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RowJson.serialize(new Object())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RowJson.serialize(Map.of(1, "x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RowJson.serialize(List.of(5.5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RowJson.serialize(obj("k", new Object()))).isInstanceOf(IllegalArgumentException.class);
    }
}
