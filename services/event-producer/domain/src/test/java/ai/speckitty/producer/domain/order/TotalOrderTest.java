package ai.speckitty.producer.domain.order;

import static ai.speckitty.producer.domain.order.Rows.list;
import static ai.speckitty.producer.domain.order.Rows.num;
import static ai.speckitty.producer.domain.order.Rows.obj;
import static ai.speckitty.producer.domain.order.Rows.with;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TotalOrderTest {

    private static final String SOURCE = "/spec-kitty/missions/01J0000000000000000000MSN1";

    private static Map<String, Object> row(String id, String time, Object... more) {
        Map<String, Object> row = obj("id", id, "time", time, "source", SOURCE);
        for (int i = 0; i < more.length; i += 2) {
            row.put((String) more[i], more[i + 1]);
        }
        return row;
    }

    @Test
    void noRowsGiveAnEmptyResult() {
        OrderResult result = TotalOrder.INSTANCE.order(List.of());
        assertThat(result.ordered()).isEmpty();
        assertThat(result.errors()).isEmpty();
        assertThat(result.collapsed()).isZero();
        assertThat(result.conflicts()).isEmpty();
        assertThat(result.read()).isZero();
    }

    @Test
    void errorRowsKeepTheirLinePositionInTheInput() {
        List<Object> input = list(
                row("01J00000000000000000000ABA", "2026-10-13T10:00:00Z"),
                row("01J00000000000000000000ABB", "yesterday"),
                null,
                row("01J00000000000000000000ABC", "2026-10-13T11:00:00Z"),
                row("nope", "2026-10-13T11:00:00Z"));
        OrderResult result = TotalOrder.INSTANCE.order(input);
        assertThat(result.errors()).extracting(ErrorRow::position, ErrorRow::reason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, OrderErrorReason.TIME_UNPARSEABLE),
                        org.assertj.core.groups.Tuple.tuple(2, OrderErrorReason.ROW_NOT_OBJECT),
                        org.assertj.core.groups.Tuple.tuple(4, OrderErrorReason.ID_NOT_NORMALIZABLE));
        assertThat(result.ordered()).hasSize(2);
        assertThat(result.read()).isEqualTo(5);
    }

    @Test
    void anErrorRowIsNeverASurvivorAndNeverCountsAsCollapsed() {
        Object valid = row("01J00000000000000000000ABA", "2026-10-13T10:00:00Z");
        Object invalidSameId = row("01J00000000000000000000ABA", "");
        OrderResult result = TotalOrder.INSTANCE.order(list(invalidSameId, valid));
        assertThat(result.ordered()).containsExactly(valid);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.collapsed()).isZero();
        assertThat(result.conflicts()).isEmpty();
    }

    @Test
    void identicalDuplicatesCollapseWithoutAConflict() {
        Object a = row("01J00000000000000000000ABA", "2026-10-13T10:00:00Z", "data", obj("x", num("1")));
        Object b = row("01J00000000000000000000ABA", "2026-10-13T10:00:00Z", "data", obj("x", num("1")));
        OrderResult result = TotalOrder.INSTANCE.order(list(a, b));
        assertThat(result.ordered()).hasSize(1);
        assertThat(result.collapsed()).isEqualTo(1);
        assertThat(result.conflicts()).isEmpty();
    }

    @Test
    void theSameIdFromTwoSourcesIsTwoEvents() {
        OrderResult result = TotalOrder.INSTANCE.order(list(
                row("01J00000000000000000000ABA", "2026-10-13T10:00:00Z"),
                with(row("01J00000000000000000000ABA", "2026-10-13T10:00:00Z"), "source", "/spec-kitty/projects/P1")));
        assertThat(result.ordered()).hasSize(2);
        assertThat(result.collapsed()).isZero();
    }

    @Test
    void aGroupOfThreeCollapsesTwoAndReportsOneConflict() {
        OrderResult result = TotalOrder.INSTANCE.order(list(
                row("01J00000000000000000000ABA", "2026-10-13T10:00:00Z", "data", obj("v", "3")),
                row("01J00000000000000000000ABA", "2026-10-13T10:00:00Z", "data", obj("v", "1")),
                row("01J00000000000000000000ABA", "2026-10-13T10:00:00Z", "data", obj("v", "2"))));
        assertThat(result.collapsed()).isEqualTo(2);
        assertThat(result.conflicts()).containsExactly(new SourceId(SOURCE, "01J00000000000000000000ABA"));
        assertThat(RowJson.serialize(result.ordered().get(0))).contains("\"v\":\"1\"");
    }

    @Test
    void conflictsAreSortedByCodePointOfSourceThenId() {
        List<Object> rows = new ArrayList<>();
        for (String source : new String[] {"/s/b", "/s/a"}) {
            for (String id : new String[] {"01J00000000000000000000ABB", "01J00000000000000000000ABA"}) {
                rows.add(with(row(id, "2026-10-13T10:00:00Z", "data", obj("v", "x")), "source", source));
                rows.add(with(row(id, "2026-10-13T10:00:00Z", "data", obj("v", "y")), "source", source));
            }
        }
        OrderResult result = TotalOrder.INSTANCE.order(rows);
        assertThat(result.conflicts()).containsExactly(
                new SourceId("/s/a", "01J00000000000000000000ABA"), new SourceId("/s/a", "01J00000000000000000000ABB"),
                new SourceId("/s/b", "01J00000000000000000000ABA"), new SourceId("/s/b", "01J00000000000000000000ABB"));
    }

    @Test
    void theOrderedListAndTheResultCannotBeChangedByTheCaller() {
        List<Object> input = list(row("01J00000000000000000000ABA", "2026-10-13T10:00:00Z"));
        OrderResult result = TotalOrder.INSTANCE.order(input);
        input.clear();
        assertThat(result.ordered()).hasSize(1);
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> result.ordered().add("x"));
    }

    @Test
    void aSixDigitMicrosecondTimestampOrdersByItsInstant() {
        OrderResult result = TotalOrder.INSTANCE.order(list(
                row("01J00000000000000000000ABB", "2026-10-13T10:00:00.000002+00:00"),
                row("01J00000000000000000000ABA", "2026-10-13T10:00:00.000003Z")));
        assertThat(((Map<?, ?>) result.ordered().get(0)).get("id")).isEqualTo("01J00000000000000000000ABB");
    }

    @Test
    void errorRowPositionsMustBeNonNegative() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new ErrorRow(-1, OrderErrorReason.ID_MISSING, null));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> new ErrorRow(0, null, null));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> new SourceId(null, "x"));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> new SourceId("x", null));
    }

    /**
     * AC-008 against a test-local reference: random rows, shuffled many times; the real ordering is permutation-invariant and
     * agrees with a reference that parses times with {@link OffsetDateTime} and sorts with a plain comparator.
     */
    @Test
    void randomRowsAreOrderedLikeTheReferenceInEveryShuffle() {
        Random random = new Random(20261014L);
        for (int round = 0; round < 40; round++) {
            List<Object> rows = randomRows(random, 12);
            OrderResult baseline = TotalOrder.INSTANCE.order(rows);
            List<Object> expected = reference(rows);
            assertThat(baseline.ordered().stream().map(RowJson::serialize).toList())
                    .isEqualTo(expected.stream().map(RowJson::serialize).toList());
            for (int shuffle = 0; shuffle < 25; shuffle++) {
                List<Object> shuffled = new ArrayList<>(rows);
                Collections.shuffle(shuffled, random);
                OrderResult again = TotalOrder.INSTANCE.order(shuffled);
                assertThat(again.ordered().stream().map(RowJson::serialize).toList())
                        .isEqualTo(baseline.ordered().stream().map(RowJson::serialize).toList());
                assertThat(again.collapsed()).isEqualTo(baseline.collapsed());
                assertThat(again.conflicts()).isEqualTo(baseline.conflicts());
                assertThat(again.errors().stream().map(e -> e.reason().wire() + "|" + RowJson.serialize(e.row())).sorted().toList())
                        .isEqualTo(baseline.errors().stream().map(e -> e.reason().wire() + "|" + RowJson.serialize(e.row())).sorted().toList());
                assertThat(again.ordered().size() + again.errors().size() + again.collapsed()).isEqualTo(again.read());
            }
        }
    }

    private static List<Object> randomRows(Random random, int count) {
        String[] ids = {"01J00000000000000000000AB0", "01J00000000000000000000AB1", "01j00000000000000000000ab1", "01J00000000000000000000AB2"};
        String[] times = {
            "2026-10-13T10:00:00Z", "2026-10-13T12:00:00+02:00", "2026-10-13T10:00:00.5Z", "2026-10-13T09:59:59-00:30", "2026-10-13T10:00:01Z"
        };
        String[] sources = {SOURCE, "/spec-kitty/projects/P1", null};
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", ids[random.nextInt(ids.length)]);
            row.put("time", times[random.nextInt(times.length)]);
            String source = sources[random.nextInt(sources.length)];
            if (source != null) {
                row.put("source", source);
            }
            int clock = random.nextInt(4);
            if (clock > 0) {
                row.put("lamportclock", num(Integer.toString(clock)));
            }
            row.put("data", obj("n", num(Integer.toString(random.nextInt(2)))));
            rows.add(row);
        }
        return rows;
    }

    /** Independent reference: no shared code with the domain beyond the byte serialisation used as the survivor rule. */
    private static List<Object> reference(List<Object> rows) {
        Map<String, Map<String, Object>> survivors = new HashMap<>();
        for (Object o : rows) {
            Map<?, ?> row = (Map<?, ?>) o;
            String id = ((String) row.get("id")).toUpperCase();
            String source = row.get("source") == null ? "" : (String) row.get("source");
            String key = source + "#" + id;
            Map<String, Object> existing = survivors.get(key);
            if (existing == null || RowJson.serialize(o).compareTo(RowJson.serialize(existing.get("row"))) < 0) {
                survivors.put(key, new HashMap<>(Map.of("row", o)));
            }
        }
        List<Object> ordered = new ArrayList<>();
        for (Map<String, Object> s : survivors.values()) {
            ordered.add(s.get("row"));
        }
        ordered.sort(Comparator
                .<Object, Integer>comparing(r -> ((Map<?, ?>) r).containsKey("lamportclock") ? 1 : 0)
                .thenComparing(r -> ((Map<?, ?>) r).containsKey("lamportclock") ? Integer.parseInt(((ai.speckitty.producer.domain.value.JsonNumber) ((Map<?, ?>) r).get("lamportclock")).literal()) : 0)
                .thenComparing(r -> OffsetDateTime.parse((String) ((Map<?, ?>) r).get("time")).toInstant())
                .thenComparing(r -> ((String) ((Map<?, ?>) r).get("id")).toUpperCase())
                .thenComparing(r -> ((Map<?, ?>) r).get("source") == null ? "" : (String) ((Map<?, ?>) r).get("source")));
        return ordered;
    }
}
