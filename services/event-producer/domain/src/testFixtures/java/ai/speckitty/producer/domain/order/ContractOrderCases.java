package ai.speckitty.producer.domain.order;

import static ai.speckitty.producer.domain.order.Rows.list;
import static ai.speckitty.producer.domain.order.Rows.num;
import static ai.speckitty.producer.domain.order.Rows.obj;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The thirteen cases of the merged total-order contract (spec-kitty-events contracts/total-order.md, comparator fixtures
 * total_order/ 01 to 13), written out as Java values. They are mirrored here, not read from the events release: the release
 * artifact is not tagged yet, and the released-fixture run is a later work package (WP11), which reuses {@link OrderConformance}.
 * Rows are in the contract's labelled order; an expected label names a row, and a row with the same bytes stands for it.
 */
public final class ContractOrderCases {

    private ContractOrderCases() {}

    public static List<OrderCase> all() {
        List<OrderCase> cases = new ArrayList<>();
        cases.add(new OrderCase(
                "01-two-clockless",
                "Two clockless rows at one time: id decides.",
                List.of(
                        new LabeledRow("A", obj("id", "01J00000000000000000000ABB", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("B", obj("id", "01J00000000000000000000ABA", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1"))),
                List.of("B", "A"),
                Map.of(),
                0,
                List.of(),
                2));
        cases.add(new OrderCase(
                "02-two-clocked",
                "Clock beats time; equal clock and time: id decides.",
                List.of(
                        new LabeledRow("A", obj("id", "01J00000000000000000000ABB", "time", "2026-10-13T09:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "lamportclock", num("5"))),
                        new LabeledRow("B", obj("id", "01J00000000000000000000ABC", "time", "2026-10-13T11:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "lamportclock", num("4"))),
                        new LabeledRow("C", obj("id", "01J00000000000000000000ABA", "time", "2026-10-13T09:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "lamportclock", num("5")))),
                List.of("B", "C", "A"),
                Map.of(),
                0,
                List.of(),
                3));
        cases.add(new OrderCase(
                "03-mixed",
                "Every clockless row sorts before every clocked row, whatever the times.",
                List.of(
                        new LabeledRow("A", obj("id", "01J00000000000000000000ABA", "time", "2026-10-13T23:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("B", obj("id", "01J00000000000000000000ABB", "time", "2026-10-13T08:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "lamportclock", num("1")))),
                List.of("A", "B"),
                Map.of(),
                0,
                List.of(),
                2));
        cases.add(new OrderCase(
                "04-naive-counter-example",
                "A comparator that uses the clock only when both rows have one is not transitive here (C<A by clock, A<B by time, B<C by time). The total order is B, C, A for every permutation.",
                List.of(
                        new LabeledRow("A", obj("id", "01J00000000000000000000ABA", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "lamportclock", num("5"))),
                        new LabeledRow("B", obj("id", "01J00000000000000000000ABB", "time", "2026-10-13T10:30:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("C", obj("id", "01J00000000000000000000ABC", "time", "2026-10-13T11:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "lamportclock", num("3")))),
                List.of("B", "C", "A"),
                Map.of(),
                0,
                List.of(),
                3));
        cases.add(new OrderCase(
                "05-offsets-and-ids",
                "Instants, not text: A (12:00+02:00 = 10:00Z) before B (10:00:00.5Z) although the text sorts the other way. Ids are normalised before comparing: lower-case ULID C before D; a legacy UUID (U) sorts before a ULID by code point.",
                List.of(
                        new LabeledRow("A", obj("id", "01J00000000000000000000ABA", "time", "2026-10-13T12:00:00+02:00", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("B", obj("id", "01J00000000000000000000ABB", "time", "2026-10-13T10:00:00.5Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("C", obj("id", "01J00000000000000000000abc", "time", "2026-10-13T11:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("D", obj("id", "01J00000000000000000000ABD", "time", "2026-10-13T11:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("U", obj("id", "0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b", "time", "2026-10-13T11:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1"))),
                List.of("A", "B", "U", "C", "D"),
                Map.of(),
                0,
                List.of(),
                5));
        cases.add(new OrderCase(
                "06-error-row-and-duplicate",
                "An unparseable time is an error row, never sorted or dropped. D is byte-identical to C and collapses into it. 4 read = 2 ordered + 1 error + 1 collapsed. 'C' in ordered stands for C or D (equal bytes).",
                List.of(
                        new LabeledRow("A", obj("id", "01J00000000000000000000ABA", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("B", obj("id", "01J00000000000000000000ABB", "time", "yesterday", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("C", obj("id", "01J00000000000000000000ABC", "time", "2026-10-13T11:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("D", obj("id", "01J00000000000000000000ABC", "time", "2026-10-13T11:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1"))),
                List.of("A", "C"),
                Map.of("B", "time_unparseable"),
                1,
                List.of(),
                4));
        cases.add(new OrderCase(
                "07-source-tie-break",
                "Same clock, time and id from three sources: source is the final tie-break, the empty source (no source member) first. Without it the permutations disagree.",
                List.of(
                        new LabeledRow("A", obj("id", "01J00000000000000000000ABA", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/projects/P1", "lamportclock", num("7"))),
                        new LabeledRow("B", obj("id", "01J00000000000000000000ABA", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/M1", "lamportclock", num("7"))),
                        new LabeledRow("C", obj("id", "01J00000000000000000000ABA", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "lamportclock", num("7")))),
                List.of("C", "B", "A"),
                Map.of(),
                0,
                List.of(),
                3));
        cases.add(new OrderCase(
                "08-two-spellings-of-one-id",
                "Two spellings of one id in one source are one row: B (upper-case, smaller bytes) survives, A collapses into it. Not a conflict: the rows differ only in id.",
                List.of(
                        new LabeledRow("A", obj("id", "01J00000000000000000000abc", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "lamportclock", num("3"), "data", obj("x", num("1")))),
                        new LabeledRow("B", obj("id", "01J00000000000000000000ABC", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "lamportclock", num("3"), "data", obj("x", num("1")))),
                        new LabeledRow("C", obj("id", "01J00000000000000000000ABD", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "lamportclock", num("3"), "data", obj("x", num("1"))))),
                List.of("B", "C"),
                Map.of(),
                1,
                List.of(),
                3));
        cases.add(new OrderCase(
                "09-conflict-survivor-and-every-error-class",
                "G and H share an id and differ in a member: the one with the smaller bytes (G) survives and the group is a conflict. X (same id, empty time) is an error row and cannot displace G. K has no id; M has lamportclock 2^31; N has an id that does not normalise. 7 read = 2 ordered + 4 errors + 1 collapsed.",
                List.of(
                        new LabeledRow("G", obj("id", "01J00000000000000000000ABA", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "data", obj("x", "g"))),
                        new LabeledRow("H", obj("id", "01J00000000000000000000ABA", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "data", obj("y", "h"))),
                        new LabeledRow("X", obj("id", "01J00000000000000000000ABA", "time", "", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("K", obj("time", "2026-10-13T10:30:00Z", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "type", "ai.spec-kitty.lane.transitioned.v1")),
                        new LabeledRow("M", obj("id", "01J00000000000000000000ABB", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "lamportclock", num("2147483648"))),
                        new LabeledRow("N", obj("id", "nope", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("Z", obj("id", "01J00000000000000000000ABC", "time", "2026-10-13T11:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1"))),
                List.of("G", "Z"),
                Map.of("X", "time_unparseable", "K", "id_missing", "M", "clock_out_of_range", "N", "id_not_normalizable"),
                1,
                List.of(new SourceId("/spec-kitty/missions/01J0000000000000000000MSN1", "01J00000000000000000000ABA")),
                7));
        cases.add(new OrderCase(
                "10-clock-value-types",
                "A lamportclock member that is present must be an integer in 0..2^31-1. null, a boolean, a string, a negative number, 2^31 and a number written with a decimal point (5.0) are error rows, not clockless rows. 0 and 2^31-1 are valid.",
                List.of(
                        new LabeledRow("Z0", obj("id", "01J00000000000000000000AB0", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "lamportclock", num("0"))),
                        new LabeledRow("MAX", obj("id", "01J00000000000000000000AB1", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "lamportclock", num("2147483647"))),
                        new LabeledRow("NUL", obj("id", "01J00000000000000000000AB2", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "lamportclock", null)),
                        new LabeledRow("BOOL", obj("id", "01J00000000000000000000AB3", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "lamportclock", true)),
                        new LabeledRow("STR", obj("id", "01J00000000000000000000AB4", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "lamportclock", "5")),
                        new LabeledRow("FLT", obj("id", "01J00000000000000000000AB5", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "lamportclock", num("5.0"))),
                        new LabeledRow("NEG", obj("id", "01J00000000000000000000AB6", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "lamportclock", num("-1"))),
                        new LabeledRow("OVF", obj("id", "01J00000000000000000000AB7", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1", "lamportclock", num("2147483648")))),
                List.of("Z0", "MAX"),
                Map.of("NUL", "clock_out_of_range", "BOOL", "clock_out_of_range", "STR", "clock_out_of_range", "FLT", "clock_out_of_range", "NEG", "clock_out_of_range", "OVF", "clock_out_of_range"),
                0,
                List.of(),
                8));
        cases.add(new OrderCase(
                "11-time-forms",
                "RFC 3339 only. A leap second (:60) is the next second; t and z may be lower case; up to nine fractional digits. Ten fractional digits, a space separator, a missing offset, a date alone, hour 24, a trailing newline and year 0000 are error rows. LEAP, NEXT and LOWER are one instant: id decides.",
                List.of(
                        new LabeledRow("LEAP", obj("id", "01J00000000000000000000ABA", "time", "2026-06-30T23:59:60Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("NEXT", obj("id", "01J00000000000000000000ABB", "time", "2026-07-01T00:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("LOWER", obj("id", "01J00000000000000000000ABC", "time", "2026-07-01t00:00:00z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("FRAC9", obj("id", "01J00000000000000000000ABD", "time", "2026-07-01T00:00:00.000000001Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("FRAC10", obj("id", "01J00000000000000000000ABE", "time", "2026-07-01T00:00:00.0000000001Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("SPACE", obj("id", "01J00000000000000000000ABF", "time", "2026-07-01 00:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("NOOFF", obj("id", "01J00000000000000000000ABG", "time", "2026-07-01T00:00:00", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("DATE", obj("id", "01J00000000000000000000ABH", "time", "2026-07-01", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("H24", obj("id", "01J00000000000000000000ABJ", "time", "2026-07-01T24:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("NEWLINE", obj("id", "01J00000000000000000000ABK", "time", "2026-07-01T00:00:00Z\n", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("YEAR0", obj("id", "01J00000000000000000000ABM", "time", "0000-01-01T00:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1"))),
                List.of("LEAP", "NEXT", "LOWER", "FRAC9"),
                Map.of("FRAC10", "time_unparseable", "SPACE", "time_unparseable", "NOOFF", "time_unparseable", "DATE", "time_unparseable", "H24", "time_unparseable", "NEWLINE", "time_unparseable", "YEAR0", "time_unparseable"),
                0,
                List.of(),
                11));
        cases.add(new OrderCase(
                "12-source-forms",
                "An absent source, a null source and an empty source are all the empty source: one identity with one id, so they collapse, and since they differ in a member other than id the group is a conflict. The survivor is the row with the smallest bytes (the empty string). A source that is not a string is an error row.",
                List.of(
                        new LabeledRow("ABSENT", obj("id", "01J00000000000000000000ABA", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1")),
                        new LabeledRow("NUL", obj("id", "01J00000000000000000000ABA", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", null)),
                        new LabeledRow("EMPTY", obj("id", "01J00000000000000000000ABA", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "")),
                        new LabeledRow("OTHER", obj("id", "01J00000000000000000000ABA", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/M1")),
                        new LabeledRow("NUM", obj("id", "01J00000000000000000000ABB", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", num("5")))),
                List.of("EMPTY", "OTHER"),
                Map.of("NUM", "source_invalid"),
                2,
                List.of(new SourceId("", "01J00000000000000000000ABA")),
                5));
        cases.add(new OrderCase(
                "13-rows-that-are-not-objects",
                "A line that parsed to something other than a JSON object is an error row.",
                List.of(
                        new LabeledRow("A", obj("id", "01J00000000000000000000ABA", "time", "2026-10-13T10:00:00Z", "type", "ai.spec-kitty.lane.transitioned.v1", "source", "/spec-kitty/missions/01J0000000000000000000MSN1")),
                        new LabeledRow("ARR", list(num("1"), num("2"))),
                        new LabeledRow("STR", "text"),
                        new LabeledRow("NUL", null)),
                List.of("A"),
                Map.of("ARR", "row_not_object", "STR", "row_not_object", "NUL", "row_not_object"),
                0,
                List.of(),
                4));
        return List.copyOf(cases);
    }
}
