package ai.speckitty.producer.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The conformance suite must go red on every deliberately broken ordering: the mutant table of T013. */
class MutantOrderingsTest {

    private static List<OrderCase> everyCase() {
        List<OrderCase> cases = new ArrayList<>(ContractOrderCases.all());
        cases.addAll(ExtraOrderCases.all());
        return cases;
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(MutantOrderings.Flaw.class)
    void everyMutantFailsTheSuite(MutantOrderings.Flaw flaw) {
        assertThat(OrderConformance.checkAll(MutantOrderings.with(flaw), everyCase())).as("%s", flaw).isNotEmpty();
    }

    @org.junit.jupiter.api.Test
    void eachMutantIsCaughtByTheCaseThatTargetsIt() {
        assertThat(failingCases(MutantOrderings.Flaw.NAIVE_COMPARATOR)).contains("04-naive-counter-example");
        assertThat(failingCases(MutantOrderings.Flaw.NO_SOURCE_TIEBREAK)).contains("07-source-tie-break");
        assertThat(failingCases(MutantOrderings.Flaw.DEDUPE_ON_RAW_ID)).contains("08-two-spellings-of-one-id");
        assertThat(failingCases(MutantOrderings.Flaw.ERROR_ROWS_JOIN_DEDUPE)).contains("09-conflict-survivor-and-every-error-class");
        assertThat(failingCases(MutantOrderings.Flaw.NO_CONFLICT_REPORT)).contains("09-conflict-survivor-and-every-error-class");
        assertThat(failingCases(MutantOrderings.Flaw.DROP_ERROR_ROWS)).contains("06-error-row-and-duplicate");
        assertThat(failingCases(MutantOrderings.Flaw.TEXT_TIME_ORDER)).contains("05-offsets-and-ids");
        assertThat(failingCases(MutantOrderings.Flaw.UTF16_SOURCE_ORDER)).contains("x1-astral-source");
        assertThat(failingCases(MutantOrderings.Flaw.NULL_CLOCK_IS_CLOCKLESS)).contains("10-clock-value-types");
        assertThat(failingCases(MutantOrderings.Flaw.STRING_CLOCK_COERCED)).contains("10-clock-value-types");
        assertThat(failingCases(MutantOrderings.Flaw.RAW_ID_IN_KEY)).contains("05-offsets-and-ids");
        assertThat(failingCases(MutantOrderings.Flaw.CLOCKLESS_LAST)).contains("03-mixed");
    }

    @org.junit.jupiter.api.Test
    void aMutantFailsOnlyWhereItsFlawShows() {
        // Controls: the source tie-break mutant still orders case 1 correctly, so the suite is not failing everything blindly.
        assertThat(failingCases(MutantOrderings.Flaw.NO_SOURCE_TIEBREAK)).doesNotContain("01-two-clockless", "02-two-clocked");
        assertThat(failingCases(MutantOrderings.Flaw.UTF16_SOURCE_ORDER)).doesNotContain("01-two-clockless", "07-source-tie-break");
    }

    @org.junit.jupiter.api.Test
    void everyFlawIsInTheTableSoNoneCanBeSilentlySkipped() {
        assertThat(Stream.of(MutantOrderings.Flaw.values()).count()).isEqualTo(12);
    }

    @org.junit.jupiter.api.Test
    void aMutantThatThrowsCountsAsRed() {
        RowOrdering broken = rows -> {
            throw new IllegalStateException("broken");
        };
        assertThat(OrderConformance.check(broken, ContractOrderCases.all().get(0))).singleElement().asString().contains("threw");
    }

    private static List<String> failingCases(MutantOrderings.Flaw flaw) {
        List<String> failing = new ArrayList<>();
        for (OrderCase orderCase : everyCase()) {
            if (!OrderConformance.check(MutantOrderings.with(flaw), orderCase).isEmpty()) {
                failing.add(orderCase.id());
            }
        }
        return failing;
    }
}
