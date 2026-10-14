package ai.speckitty.producer.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The thirteen cases of the merged contract plus the extra ones, each over every permutation (or a pinned sample above eight rows). */
class TotalOrderContractTest {

    static Stream<OrderCase> contractCases() {
        return ContractOrderCases.all().stream();
    }

    static Stream<OrderCase> extraCases() {
        return ExtraOrderCases.all().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contractCases")
    void theRealOrderingConformsToEveryContractCase(OrderCase orderCase) {
        assertThat(OrderConformance.check(TotalOrder.INSTANCE, orderCase)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("extraCases")
    void theRealOrderingConformsToTheExtraCases(OrderCase orderCase) {
        assertThat(OrderConformance.check(TotalOrder.INSTANCE, orderCase)).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void theMirrorHoldsExactlyTheThirteenContractCasesInOrder() {
        List<String> ids = ContractOrderCases.all().stream().map(OrderCase::id).collect(Collectors.toList());
        assertThat(ids).hasSize(13);
        assertThat(ids.stream().map(id -> id.substring(0, 2)).toList())
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 13).mapToObj(n -> String.format("%02d", n)).toList());
        assertThat(ContractOrderCases.all().stream().mapToInt(c -> c.rows().size()).sum()).isEqualTo(60);
    }

    @org.junit.jupiter.api.Test
    void everyCaseAccountsForEveryRow() {
        for (OrderCase orderCase : ContractOrderCases.all()) {
            assertThat(orderCase.ordered().size() + orderCase.errors().size() + orderCase.collapsed())
                    .as(orderCase.id()).isEqualTo(orderCase.read());
            assertThat(orderCase.rows()).as(orderCase.id()).hasSize(orderCase.read());
        }
    }

    @org.junit.jupiter.api.Test
    void thePermutationRunIsExhaustiveUpToEightRowsAndSampledAbove() {
        assertThat(OrderConformance.permutationCount(1)).isEqualTo(1);
        assertThat(OrderConformance.permutationCount(3)).isEqualTo(6);
        assertThat(OrderConformance.permutationCount(8)).isEqualTo(40320);
        assertThat(OrderConformance.permutationCount(11)).isEqualTo(2 + 10 + 400);
        assertThat(ContractOrderCases.all().stream().filter(c -> c.rows().size() == 8).count()).isEqualTo(1);
    }
}
