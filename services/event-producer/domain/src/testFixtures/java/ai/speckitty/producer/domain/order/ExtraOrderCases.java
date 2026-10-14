package ai.speckitty.producer.domain.order;

import static ai.speckitty.producer.domain.order.Rows.obj;

import java.util.List;
import java.util.Map;

/** Cases beyond the thirteen of the contract, pinning rules the contract states in prose. Built from code points, not escapes. */
public final class ExtraOrderCases {

    private static final String ID = "01J00000000000000000000ABA";
    private static final String TIME = "2026-10-13T10:00:00Z";

    private ExtraOrderCases() {}

    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    /** Sources that differ only in an astral code point and a high BMP code point: code point order is the reverse of UTF-16 order. */
    public static OrderCase astralSource() {
        return new OrderCase(
                "x1-astral-source",
                "Strings compare by code point: U+FFFF sorts before U+10000, although its UTF-16 unit is the larger.",
                List.of(
                        new LabeledRow("ASTRAL", obj("id", ID, "time", TIME, "source", "/s/" + cp(0x10000))),
                        new LabeledRow("BMP", obj("id", ID, "time", TIME, "source", "/s/" + cp(0xFFFF)))),
                List.of("BMP", "ASTRAL"),
                Map.of(),
                0,
                List.of(),
                2);
    }

    /** The survivor of a group is chosen on the escaped bytes, where a non-ASCII character is a backslash sequence. */
    public static OrderCase escapedSurvivor() {
        return new OrderCase(
                "x2-escaped-survivor",
                "The escaped form of a non-ASCII member starts with a backslash (0x5C), which sorts before 'z' (0x7A): E survives.",
                List.of(
                        new LabeledRow("Z", obj("id", ID, "time", TIME, "source", "/s/1", "data", obj("v", "z"))),
                        new LabeledRow("E", obj("id", ID, "time", TIME, "source", "/s/1", "data", obj("v", cp(0xE9))))),
                List.of("E"),
                Map.of(),
                1,
                List.of(new SourceId("/s/1", ID)),
                2);
    }

    public static List<OrderCase> all() {
        return List.of(astralSource(), escapedSurvivor());
    }
}
