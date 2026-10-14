package ai.speckitty.producer.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The conformance suite must go red on every deliberately broken ordering: the mutant table of T013. */
class MutantOrderingsTest {

    /** The case that targets each flaw. */
    private static final Map<MutantOrderings.Flaw, String> TARGET = new EnumMap<>(MutantOrderings.Flaw.class);

    static {
        TARGET.put(MutantOrderings.Flaw.NAIVE_COMPARATOR, "04-naive-counter-example");
        TARGET.put(MutantOrderings.Flaw.NO_SOURCE_TIEBREAK, "07-source-tie-break");
        TARGET.put(MutantOrderings.Flaw.DEDUPE_ON_RAW_ID, "08-two-spellings-of-one-id");
        TARGET.put(MutantOrderings.Flaw.ERROR_ROWS_JOIN_DEDUPE, "09-conflict-survivor-and-every-error-class");
        TARGET.put(MutantOrderings.Flaw.NO_CONFLICT_REPORT, "09-conflict-survivor-and-every-error-class");
        TARGET.put(MutantOrderings.Flaw.DROP_ERROR_ROWS, "06-error-row-and-duplicate");
        TARGET.put(MutantOrderings.Flaw.TEXT_TIME_ORDER, "05-offsets-and-ids");
        TARGET.put(MutantOrderings.Flaw.UTF16_SOURCE_ORDER, "x1-astral-source");
        TARGET.put(MutantOrderings.Flaw.NULL_CLOCK_IS_CLOCKLESS, "10-clock-value-types");
        TARGET.put(MutantOrderings.Flaw.STRING_CLOCK_COERCED, "10-clock-value-types");
        TARGET.put(MutantOrderings.Flaw.RAW_ID_IN_KEY, "05-offsets-and-ids");
        TARGET.put(MutantOrderings.Flaw.CLOCKLESS_LAST, "03-mixed");
    }

    private static List<OrderCase> everyCase() {
        List<OrderCase> cases = new ArrayList<>(ContractOrderCases.all());
        cases.addAll(ExtraOrderCases.all());
        return cases;
    }

    private static OrderCase byId(String id) {
        return everyCase().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void theTargetTableCoversEveryFlaw() {
        assertThat(TARGET.keySet()).containsExactlyInAnyOrder(MutantOrderings.Flaw.values());
        assertThat(MutantOrderings.Flaw.values()).hasSize(12);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(MutantOrderings.Flaw.class)
    void everyMutantFailsTheCaseThatTargetsIt(MutantOrderings.Flaw flaw) {
        OrderCase target = byId(TARGET.get(flaw));
        assertThat(OrderConformance.check(MutantOrderings.with(flaw), target)).as("%s on %s", flaw, target.id()).isNotEmpty();
    }

    @Test
    void theRealOrderingPassesTheCasesTheMutantsFailOn() {
        for (String id : TARGET.values().stream().distinct().toList()) {
            assertThat(OrderConformance.check(TotalOrder.INSTANCE, byId(id))).as(id).isEmpty();
        }
    }

    @Test
    void aMutantFailsOnlyWhereItsFlawShows() {
        // Controls: a mutant still orders the cases its flaw does not touch, so the suite is not failing everything blindly.
        for (String id : List.of("01-two-clockless", "02-two-clocked")) {
            assertThat(OrderConformance.check(MutantOrderings.with(MutantOrderings.Flaw.NO_SOURCE_TIEBREAK), byId(id))).as(id).isEmpty();
            assertThat(OrderConformance.check(MutantOrderings.with(MutantOrderings.Flaw.UTF16_SOURCE_ORDER), byId(id))).as(id).isEmpty();
        }
        assertThat(OrderConformance.check(MutantOrderings.with(MutantOrderings.Flaw.UTF16_SOURCE_ORDER), byId("07-source-tie-break"))).isEmpty();
    }

    @Test
    void aMutantThatThrowsCountsAsRed() {
        RowOrdering broken = rows -> {
            throw new IllegalStateException("broken");
        };
        assertThat(OrderConformance.check(broken, ContractOrderCases.all().get(0))).singleElement().asString().contains("threw");
    }

    @Test
    void anOrderingThatMislocatesAnErrorRowIsCaught() {
        RowOrdering wrongPosition = rows -> {
            OrderResult real = TotalOrder.INSTANCE.order(rows);
            List<ErrorRow> shifted = real.errors().stream().map(e -> new ErrorRow(e.position() + rows.size(), e.reason(), e.row())).toList();
            return new OrderResult(real.ordered(), shifted, real.collapsed(), real.conflicts(), real.read());
        };
        assertThat(OrderConformance.check(wrongPosition, byId("06-error-row-and-duplicate"))).anyMatch(f -> f.contains("position"));
    }

    @Test
    void anOrderingThatDuplicatesAnErrorRowBreaksTheAccounting() {
        RowOrdering duplicating = rows -> {
            OrderResult real = TotalOrder.INSTANCE.order(rows);
            List<ErrorRow> twice = new ArrayList<>(real.errors());
            twice.addAll(real.errors());
            return new OrderResult(real.ordered(), twice, real.collapsed(), real.conflicts(), real.read());
        };
        assertThat(OrderConformance.check(duplicating, byId("06-error-row-and-duplicate"))).isNotEmpty();
    }
}
