package ai.speckitty.producer.domain.order;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Runs an {@link OrderCase} against a {@link RowOrdering} over permutations of its rows and reports every way the result differs
 * from the pinned one. It is plain {@code java.base} so that {@code :domain} main stays dependency-free and so that the adapter's
 * released-fixture test can reuse it unchanged. The failure list is empty exactly when the ordering conforms.
 */
public final class OrderConformance {

    /** Up to this many rows every permutation is tried; above it, rotations, reversals and seeded shuffles. */
    static final int EXHAUSTIVE_LIMIT = 8;

    private static final int SAMPLED_SHUFFLES = 400;

    private OrderConformance() {}

    /** The failures of {@code ordering} on {@code orderCase}, stopping at the first permutation that fails. */
    public static List<String> check(RowOrdering ordering, OrderCase orderCase) {
        int n = orderCase.rows().size();
        Map<String, String> serialized = new HashMap<>();
        for (LabeledRow row : orderCase.rows()) {
            serialized.put(row.label(), RowJson.serialize(row.row()));
        }
        for (int[] permutation : permutations(n)) {
            List<String> failures = checkOne(ordering, orderCase, permutation, serialized);
            if (!failures.isEmpty()) {
                return failures;
            }
        }
        return List.of();
    }

    /** The number of permutations {@link #check} tries for {@code n} rows. */
    public static int permutationCount(int n) {
        return permutations(n).size();
    }

    /** Every case of {@code cases}: the case id and failure for the first case that fails, else empty. */
    public static List<String> checkAll(RowOrdering ordering, List<OrderCase> cases) {
        List<String> failures = new ArrayList<>();
        for (OrderCase orderCase : cases) {
            for (String failure : check(ordering, orderCase)) {
                failures.add(orderCase.id() + ": " + failure);
            }
        }
        return failures;
    }

    static List<String> checkOne(RowOrdering ordering, OrderCase orderCase, int[] permutation, Map<String, String> serialized) {
        List<Object> input = new ArrayList<>();
        for (int index : permutation) {
            input.add(orderCase.rows().get(index).row());
        }
        OrderResult result;
        try {
            result = ordering.order(input);
        } catch (RuntimeException e) {
            return List.of("order threw " + e.getClass().getSimpleName() + " for permutation " + describe(permutation));
        }
        List<String> failures = new ArrayList<>();
        String where = " for permutation " + describe(permutation);
        List<String> expectedOrdered = new ArrayList<>();
        for (String label : orderCase.ordered()) {
            expectedOrdered.add(serialized.get(label));
        }
        List<String> actualOrdered = new ArrayList<>();
        for (Object row : result.ordered()) {
            actualOrdered.add(RowJson.serialize(row));
        }
        if (!expectedOrdered.equals(actualOrdered)) {
            failures.add("ordered " + actualOrdered + " expected " + expectedOrdered + where);
        }
        List<String> expectedErrors = new ArrayList<>();
        for (Map.Entry<String, String> error : orderCase.errors().entrySet()) {
            expectedErrors.add(error.getValue() + "|" + serialized.get(error.getKey()));
        }
        List<String> actualErrors = new ArrayList<>();
        for (ErrorRow error : result.errors()) {
            actualErrors.add(error.reason().wire() + "|" + RowJson.serialize(error.row()));
            if (error.position() >= input.size()
                    || !RowJson.serialize(input.get(error.position())).equals(RowJson.serialize(error.row()))) {
                failures.add("error row position " + error.position() + " does not index its row" + where);
            }
        }
        Collections.sort(expectedErrors);
        Collections.sort(actualErrors);
        if (!expectedErrors.equals(actualErrors)) {
            failures.add("errors " + actualErrors + " expected " + expectedErrors + where);
        }
        if (result.collapsed() != orderCase.collapsed()) {
            failures.add("collapsed " + result.collapsed() + " expected " + orderCase.collapsed() + where);
        }
        if (!result.conflicts().equals(orderCase.conflicts())) {
            failures.add("conflicts " + result.conflicts() + " expected " + orderCase.conflicts() + where);
        }
        if (result.read() != orderCase.read()) {
            failures.add("read " + result.read() + " expected " + orderCase.read() + where);
        }
        if (result.ordered().size() + result.errors().size() + result.collapsed() != result.read()) {
            failures.add("ordered + errors + collapsed != read" + where);
        }
        return failures;
    }

    private static String describe(int[] permutation) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < permutation.length; i++) {
            text.append(i == 0 ? "" : " ").append(permutation[i]);
        }
        return text.append(']').toString();
    }

    static List<int[]> permutations(int n) {
        List<int[]> all = new ArrayList<>();
        if (n <= EXHAUSTIVE_LIMIT) {
            heap(identity(n), n, all);
            return all;
        }
        int[] reversed = identity(n);
        for (int i = 0; i < n; i++) {
            reversed[i] = n - 1 - i;
        }
        all.add(identity(n));
        all.add(reversed);
        for (int shift = 1; shift < n; shift++) {
            int[] rotated = new int[n];
            for (int i = 0; i < n; i++) {
                rotated[i] = (i + shift) % n;
            }
            all.add(rotated);
        }
        Random random = new Random(6185L);
        for (int k = 0; k < SAMPLED_SHUFFLES; k++) {
            int[] shuffled = identity(n);
            for (int i = n - 1; i > 0; i--) {
                int j = random.nextInt(i + 1);
                int swap = shuffled[i];
                shuffled[i] = shuffled[j];
                shuffled[j] = swap;
            }
            all.add(shuffled);
        }
        return all;
    }

    private static int[] identity(int n) {
        int[] values = new int[n];
        for (int i = 0; i < n; i++) {
            values[i] = i;
        }
        return values;
    }

    /** Heap's algorithm: every permutation of the first {@code k} elements, recorded as copies. */
    private static void heap(int[] values, int k, List<int[]> out) {
        if (k <= 1) {
            out.add(values.clone());
            return;
        }
        for (int i = 0; i < k - 1; i++) {
            heap(values, k - 1, out);
            int swapIndex = (k % 2 == 0) ? i : 0;
            int swap = values[swapIndex];
            values[swapIndex] = values[k - 1];
            values[k - 1] = swap;
        }
        heap(values, k - 1, out);
    }
}
