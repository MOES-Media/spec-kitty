package ai.speckitty.producer.domain.order;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The total order of the merged contract: set aside the rows that cannot be keyed, group by {@code (source, id)}, keep of each
 * group the row with the smallest serialisation, sort the survivors by {@link OrderKey}.
 */
public final class TotalOrder implements RowOrdering {

    public static final TotalOrder INSTANCE = new TotalOrder();

    private TotalOrder() {}

    private record Keyed(Object row, OrderKey key, String bytes) {}

    @Override
    public OrderResult order(List<?> rows) {
        List<ErrorRow> errors = new ArrayList<>();
        Map<SourceId, List<Keyed>> groups = new LinkedHashMap<>();
        for (int position = 0; position < rows.size(); position++) {
            Object row = rows.get(position);
            OrderKey key;
            try {
                key = RowKeys.of(row);
            } catch (UnorderableRow e) {
                errors.add(new ErrorRow(position, e.reason(), row));
                continue;
            }
            groups.computeIfAbsent(new SourceId(key.s(), key.i()), identity -> new ArrayList<>())
                    .add(new Keyed(row, key, RowJson.serialize(row)));
        }
        List<Keyed> survivors = new ArrayList<>();
        List<SourceId> conflicts = new ArrayList<>();
        int collapsed = 0;
        for (Map.Entry<SourceId, List<Keyed>> group : groups.entrySet()) {
            List<Keyed> members = group.getValue();
            Keyed best = members.get(0);
            Set<String> variants = new HashSet<>();
            for (Keyed member : members) {
                if (member.bytes().compareTo(best.bytes()) < 0) {
                    best = member;
                }
                variants.add(RowJson.serialize(withoutId((Map<?, ?>) member.row())));
            }
            survivors.add(best);
            collapsed += members.size() - 1;
            if (variants.size() > 1) {
                conflicts.add(group.getKey());
            }
        }
        survivors.sort(Comparator.comparing(Keyed::key));
        conflicts.sort(Comparator.comparing(SourceId::source, CodePoints::compare).thenComparing(SourceId::id, CodePoints::compare));
        List<Object> ordered = new ArrayList<>();
        for (Keyed survivor : survivors) {
            ordered.add(survivor.row());
        }
        return new OrderResult(ordered, errors, collapsed, conflicts, rows.size());
    }

    private static Map<Object, Object> withoutId(Map<?, ?> row) {
        Map<Object, Object> copy = new LinkedHashMap<>(row);
        copy.remove("id");
        return copy;
    }
}
