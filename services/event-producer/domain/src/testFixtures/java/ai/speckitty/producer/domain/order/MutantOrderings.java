package ai.speckitty.producer.domain.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Deliberately broken comparators, one flaw each, assembled from the domain's own key readers. The conformance suite must go red
 * on every one of them, so a passing run proves the suite exercises the rule the flaw breaks. One table, shared by construction:
 * the adapter's released-fixture test runs the same classes.
 */
public final class MutantOrderings {

    /** The rule each mutant ignores or breaks. */
    public enum Flaw {
        /** Uses the clock only when both rows have one: not transitive (contract case 4). */
        NAIVE_COMPARATOR,
        /** Leaves {@code source} out of the key (case 7). */
        NO_SOURCE_TIEBREAK,
        /** Groups duplicates by the raw {@code id} text instead of the normalised one (case 8). */
        DEDUPE_ON_RAW_ID,
        /** Lets a row that cannot be keyed take part in deduplication (case 9). */
        ERROR_ROWS_JOIN_DEDUPE,
        /** Never reports a conflict (case 9). */
        NO_CONFLICT_REPORT,
        /** Drops the rows it cannot key (accounting, AC-009). */
        DROP_ERROR_ROWS,
        /** Compares {@code time} as text instead of as an instant (case 5). */
        TEXT_TIME_ORDER,
        /** Compares {@code source} by UTF-16 code unit instead of by code point (the extra astral case). */
        UTF16_SOURCE_ORDER,
        /** Reads a {@code null} clock as no clock (case 10). */
        NULL_CLOCK_IS_CLOCKLESS,
        /** Coerces a string clock to an integer (case 10). */
        STRING_CLOCK_COERCED,
        /** Compares the id as written instead of normalised (case 5). */
        RAW_ID_IN_KEY,
        /** Sorts clockless rows after clocked ones (case 3). */
        CLOCKLESS_LAST
    }

    private MutantOrderings() {}

    /** A mutant ordering with {@code flaw}. */
    public static RowOrdering with(Flaw flaw) {
        return new Mutant(flaw);
    }

    private record Entry(
            int position, Object row, int h, int c, Instant t, String rawTime, String rawId, String id, String source) {}

    private record Mutant(Flaw flaw) implements RowOrdering {

        @Override
        public OrderResult order(List<?> rows) {
            List<Entry> entries = new ArrayList<>();
            List<ErrorRow> errors = new ArrayList<>();
            for (int position = 0; position < rows.size(); position++) {
                Object row = rows.get(position);
                try {
                    entries.add(entry(position, row));
                } catch (UnorderableRow e) {
                    if (flaw == Flaw.ERROR_ROWS_JOIN_DEDUPE && e.reason() == OrderErrorReason.TIME_UNPARSEABLE) {
                        Map<?, ?> map = (Map<?, ?>) row;
                        entries.add(new Entry(position, row, 0, 0, Instant.EPOCH, "", rawId(map), RowKeys.id(map), RowKeys.source(map)));
                    } else if (flaw != Flaw.DROP_ERROR_ROWS) {
                        errors.add(new ErrorRow(position, e.reason(), row));
                    }
                }
            }
            Map<List<String>, List<Entry>> groups = new LinkedHashMap<>();
            for (Entry entry : entries) {
                String idPart = flaw == Flaw.DEDUPE_ON_RAW_ID ? entry.rawId() : entry.id();
                groups.computeIfAbsent(List.of(entry.source(), idPart), key -> new ArrayList<>()).add(entry);
            }
            List<Entry> survivors = new ArrayList<>();
            List<SourceId> conflicts = new ArrayList<>();
            int collapsed = 0;
            for (List<Entry> group : groups.values()) {
                Entry best = group.get(0);
                for (Entry candidate : group) {
                    if (RowJson.serialize(candidate.row()).compareTo(RowJson.serialize(best.row())) < 0) {
                        best = candidate;
                    }
                }
                survivors.add(best);
                collapsed += group.size() - 1;
                Map<String, Integer> variants = new HashMap<>();
                for (Entry member : group) {
                    variants.put(RowJson.serialize(withoutId(member.row())), 1);
                }
                if (variants.size() > 1 && flaw != Flaw.NO_CONFLICT_REPORT) {
                    conflicts.add(new SourceId(best.source(), best.id()));
                }
            }
            conflicts.sort(Comparator.comparing(SourceId::source, CodePoints::compare).thenComparing(SourceId::id, CodePoints::compare));
            if (flaw == Flaw.NAIVE_COMPARATOR) {
                insertionSort(survivors);
            } else {
                survivors.sort(this::compare);
            }
            List<Object> ordered = new ArrayList<>();
            for (Entry survivor : survivors) {
                ordered.add(survivor.row());
            }
            return new OrderResult(ordered, errors, collapsed, conflicts, rows.size());
        }

        private Entry entry(int position, Object row) {
            if (!(row instanceof Map<?, ?> map)) {
                throw new UnorderableRow(OrderErrorReason.ROW_NOT_OBJECT, "not an object");
            }
            String id = RowKeys.id(map);
            OptionalInt clock = clock(map);
            Instant time = RowKeys.time(map);
            String source = RowKeys.source(map);
            return new Entry(position, row, clock.isPresent() ? 1 : 0, clock.orElse(0), time, (String) map.get("time"), rawId(map), id, source);
        }

        private OptionalInt clock(Map<?, ?> map) {
            Object value = map.get("lamportclock");
            if (flaw == Flaw.NULL_CLOCK_IS_CLOCKLESS && map.containsKey("lamportclock") && value == null) {
                return OptionalInt.empty();
            }
            if (flaw == Flaw.STRING_CLOCK_COERCED && value instanceof String text && text.matches("[0-9]{1,9}")) {
                return OptionalInt.of(Integer.parseInt(text));
            }
            return RowKeys.clock(map);
        }

        private static String rawId(Map<?, ?> map) {
            return String.valueOf(map.get("id"));
        }

        private static Object withoutId(Object row) {
            Map<Object, Object> copy = new LinkedHashMap<>((Map<?, ?>) row);
            copy.remove("id");
            return copy;
        }

        private int compare(Entry a, Entry b) {
            int h = flaw == Flaw.CLOCKLESS_LAST ? Integer.compare(b.h(), a.h()) : Integer.compare(a.h(), b.h());
            if (h != 0) {
                return h;
            }
            int c = Integer.compare(a.c(), b.c());
            if (c != 0) {
                return c;
            }
            int t = flaw == Flaw.TEXT_TIME_ORDER ? a.rawTime().compareTo(b.rawTime()) : a.t().compareTo(b.t());
            if (t != 0) {
                return t;
            }
            int i = flaw == Flaw.RAW_ID_IN_KEY ? CodePoints.compare(a.rawId(), b.rawId()) : CodePoints.compare(a.id(), b.id());
            if (i != 0 || flaw == Flaw.NO_SOURCE_TIEBREAK) {
                return i;
            }
            return flaw == Flaw.UTF16_SOURCE_ORDER ? a.source().compareTo(b.source()) : CodePoints.compare(a.source(), b.source());
        }

        /** The naive rule: (clock, time, id) when both rows have a clock, else (time, id). Insertion sort, so it never throws. */
        private static int naive(Entry a, Entry b) {
            if (a.h() == 1 && b.h() == 1) {
                int c = Integer.compare(a.c(), b.c());
                if (c != 0) {
                    return c;
                }
            }
            int t = a.t().compareTo(b.t());
            return t != 0 ? t : CodePoints.compare(a.id(), b.id());
        }

        private static void insertionSort(List<Entry> list) {
            for (int i = 1; i < list.size(); i++) {
                Entry moving = list.get(i);
                int j = i - 1;
                while (j >= 0 && naive(list.get(j), moving) > 0) {
                    list.set(j + 1, list.get(j));
                    j--;
                }
                list.set(j + 1, moving);
            }
        }
    }
}
