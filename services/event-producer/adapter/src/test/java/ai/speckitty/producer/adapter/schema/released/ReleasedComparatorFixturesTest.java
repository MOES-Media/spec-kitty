package ai.speckitty.producer.adapter.schema.released;

import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.domain.order.ContractOrderCases;
import ai.speckitty.producer.domain.order.LabeledRow;
import ai.speckitty.producer.domain.order.MutantOrderings;
import ai.speckitty.producer.domain.order.MutantOrderings.Flaw;
import ai.speckitty.producer.domain.order.OrderCase;
import ai.speckitty.producer.domain.order.OrderConformance;
import ai.speckitty.producer.domain.order.RowJson;
import ai.speckitty.producer.domain.order.SourceId;
import ai.speckitty.producer.domain.order.TotalOrder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * AC-008 against the released copy: the comparator specification and fixtures of spec-kitty-events run through the {@code :domain}
 * order package. The mutant table of WP02 is the one in {@code testFixtures(project(":domain"))}; no copy exists here, so the two
 * runs cannot drift.
 */
class ReleasedComparatorFixturesTest {

    /** The five comparators with one flaw each that WP02 T013 names: every one must be red against the released fixtures. */
    private static final List<Flaw> REQUIRED_MUTANTS = List.of(
            Flaw.NAIVE_COMPARATOR, Flaw.NO_SOURCE_TIEBREAK, Flaw.DEDUPE_ON_RAW_ID, Flaw.ERROR_ROWS_JOIN_DEDUPE, Flaw.DROP_ERROR_ROWS);

    @SuppressWarnings("unchecked")
    private static List<OrderCase> released() {
        Path folder = Released.dir().resolve("fixtures/total_order");
        try (Stream<Path> files = Files.list(folder)) {
            List<OrderCase> cases = new ArrayList<>();
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                Map<String, Object> json = Released.json(file);
                List<LabeledRow> rows = new ArrayList<>();
                for (Object item : (List<Object>) json.get("rows")) {
                    Map<String, Object> row = (Map<String, Object>) item;
                    rows.add(new LabeledRow((String) row.get("label"), row.get("row")));
                }
                Map<String, Object> expected = (Map<String, Object>) json.get("expected");
                Map<String, String> errors = new LinkedHashMap<>();
                ((Map<String, Object>) expected.get("errors")).forEach((label, reason) -> errors.put(label, (String) reason));
                List<SourceId> conflicts = new ArrayList<>();
                for (Object conflict : (List<Object>) expected.get("conflicts")) {
                    List<Object> pair = (List<Object>) conflict;
                    conflicts.add(new SourceId((String) pair.get(0), (String) pair.get(1)));
                }
                cases.add(new OrderCase(
                        (String) json.get("id"),
                        (String) json.get("description"),
                        rows,
                        ((List<Object>) expected.get("ordered")).stream().map(String.class::cast).toList(),
                        errors,
                        ((ai.speckitty.producer.domain.value.JsonNumber) expected.get("collapsed")).integerValue().orElseThrow().intValueExact(),
                        conflicts,
                        ((ai.speckitty.producer.domain.value.JsonNumber) expected.get("read")).integerValue().orElseThrow().intValueExact()));
            }
            return cases;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void theReleasedFixturesHoldAllThirteenCasesAndTheNineWorkedExamplesAmongThem() {
        List<OrderCase> cases = released();

        assertThat(cases).hasSize(13);
        assertThat(cases.stream().map(c -> c.id().substring(0, 2)).toList()).containsExactly("01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13");
        assertThat(cases.stream().limit(9).map(OrderCase::id).toList()).as("the nine worked examples of AC-008").hasSize(9);
    }

    @Test
    void theDomainOrderingConformsToEveryReleasedCaseOverEveryPermutation() {
        assertThat(OrderConformance.checkAll(TotalOrder.INSTANCE, released())).isEmpty();
    }

    @Test
    void eachOfTheFiveMutantsIsRedAgainstTheReleasedFixtures() {
        List<OrderCase> cases = released();
        Set<Flaw> iterated = new LinkedHashSet<>();
        for (Flaw flaw : REQUIRED_MUTANTS) {
            assertThat(OrderConformance.checkAll(MutantOrderings.with(flaw), cases)).as("mutant " + flaw + " is red").isNotEmpty();
            iterated.add(flaw);
        }
        assertThat(iterated).as("floor: the five mutants were iterated").hasSize(5).containsExactlyInAnyOrderElementsOf(REQUIRED_MUTANTS);
    }

    @Test
    void theReleasedCasesAreTheCasesTheDomainTestsAlreadyRun() {
        List<OrderCase> released = released();
        List<OrderCase> mirrored = ContractOrderCases.all();

        assertThat(mirrored).hasSameSizeAs(released);
        for (int i = 0; i < released.size(); i++) {
            OrderCase a = released.get(i);
            OrderCase b = mirrored.get(i);
            assertThat(b.id()).isEqualTo(a.id());
            assertThat(b.rows().stream().map(r -> r.label() + "=" + RowJson.serialize(r.row())).toList())
                    .as(a.id() + " rows").isEqualTo(a.rows().stream().map(r -> r.label() + "=" + RowJson.serialize(r.row())).toList());
            assertThat(b.ordered()).as(a.id() + " ordered").isEqualTo(a.ordered());
            assertThat(b.errors()).as(a.id() + " errors").isEqualTo(a.errors());
            assertThat(b.collapsed()).as(a.id() + " collapsed").isEqualTo(a.collapsed());
            assertThat(b.conflicts()).as(a.id() + " conflicts").isEqualTo(a.conflicts());
            assertThat(b.read()).as(a.id() + " read").isEqualTo(a.read());
        }
    }

    /**
     * The confirmation list for the events side (INTERIM names, the {@code source} tie-break, {@code schemaversion}, the {@code
     * dataschema} base URI): each item is a fact of the released artifact that this repository relies on, so it is asserted here.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theConfirmationListForTheEventsSideHoldsOfTheReleasedArtifact() throws IOException {
        Map<String, Object> registry = Released.json(Released.dir().resolve("registry.json"));

        assertThat(registry.get("type_prefix")).as("INTERIM names: the ruled prefix").isEqualTo("ai.spec-kitty");
        assertThat(registry.get("schemaversion")).as("schemaversion").isEqualTo("4.0.0");
        assertThat((String) registry.get("dataschema_base")).as("dataschema base URI: a placeholder until a domain is named").startsWith("https://schemas.example.invalid/");
        assertThat(Files.readString(Released.dir().resolve("total-order.md"))).as("the source tie-break").contains("source");
        assertThat(released().get(6).id()).as("case 7 pins the source tie-break").isEqualTo("07-source-tie-break");
    }
}
