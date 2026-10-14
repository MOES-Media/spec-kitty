package ai.speckitty.producer.domain.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves the {@code :domain} architecture rules: the real classes satisfy them, each rule scans something (floor), and each rule
 * goes red on a planted offender and stays green on a planted control (self-mutation).
 */
class DomainArchitectureTest {

    /** The scan must see at least this many domain classes; the current count is higher, so a shrinking scan fails. */
    private static final int CLASS_FLOOR = 60;

    private static final int DEPENDENCY_FLOOR = 300;

    private static final Set<String> SENTINELS = Set.of(
            "ai.speckitty.producer.domain.order.TotalOrder",
            "ai.speckitty.producer.domain.ids.EventId",
            "ai.speckitty.producer.domain.clock.LamportClock",
            "ai.speckitty.producer.domain.envelope.Envelope",
            "ai.speckitty.producer.domain.retention.RetentionEligibility",
            "ai.speckitty.producer.domain.protocol.ProduceReply",
            "ai.speckitty.producer.domain.fault.FaultReason");

    private static final JavaClasses DOMAIN = DomainRules.scan(DomainRules.MAIN_ONLY);

    static Stream<DomainRules.Named> rules() {
        return DomainRules.all().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void theRealDomainSatisfiesTheRule(DomainRules.Named named) {
        EvaluationResult result = named.rule().evaluate(DOMAIN);
        assertThat(result.hasViolation()).as(named.name() + ": " + result.getFailureReport().getDetails()).isFalse();
    }

    @Test
    void theScanIsNotVacuous() {
        List<String> names = DOMAIN.stream().map(JavaClass::getName).collect(Collectors.toList());
        assertThat(names).hasSizeGreaterThanOrEqualTo(CLASS_FLOOR);
        assertThat(names).containsAll(SENTINELS);
        long edges = DOMAIN.stream().mapToLong(c -> c.getDirectDependenciesFromSelf().size()).sum();
        assertThat(edges).isGreaterThanOrEqualTo(DEPENDENCY_FLOOR);
        long javaTargets = DOMAIN.stream()
                .flatMap(c -> c.getDirectDependenciesFromSelf().stream())
                .map(Dependency::getTargetClass)
                .filter(t -> t.getPackageName().startsWith("java."))
                .count();
        assertThat(javaTargets).as("dependencies on java.* that the java.base rule judges").isPositive();
    }

    @Test
    void theScanSeesOnlyMainClassesNeverTheTestOnesOrThePlantedOffenders() {
        assertThat(DOMAIN.stream().map(JavaClass::getName)).noneMatch(n -> n.contains(".arch."));
        assertThat(DOMAIN.stream().map(JavaClass::getName)).noneMatch(n -> n.endsWith("Test"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void theRuleGoesRedOnItsPlantedOffender(DomainRules.Named named) {
        JavaClasses planted = DomainRules.scan(DomainRules.PLANTED_ONLY);
        assertThat(planted.stream().map(JavaClass::getSimpleName)).contains(named.plantedOffender(), "PlantedControl");
        ArchRule rule = named.rule();
        EvaluationResult result = rule.evaluate(planted);
        assertThat(result.hasViolation()).as(named.name() + " should flag " + named.plantedOffender()).isTrue();
        assertThat(result.getFailureReport().getDetails().toString()).contains(named.plantedOffender());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void theRuleStaysGreenOnThePlantedControl(DomainRules.Named named) {
        JavaClasses control = new com.tngtech.archunit.core.importer.ClassFileImporter()
                .withImportOption(DomainRules.PLANTED_ONLY)
                .importPackages(DomainRules.ROOT + ".arch.planted")
                .that(com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName("PlantedControl"));
        assertThat(control).hasSize(1);
        assertThat(named.rule().evaluate(control).hasViolation()).as(named.name()).isFalse();
    }

    @Test
    void everyPlantedOffenderIsFlaggedByAtLeastOneRule() {
        JavaClasses planted = DomainRules.scan(DomainRules.PLANTED_ONLY);
        for (String offender : List.of(
                "PlantedClockRead", "PlantedSystemTime", "PlantedEnvironmentRead", "PlantedFileRead", "PlantedOutsideDependency")) {
            JavaClasses one = planted.that(com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName(offender));
            assertThat(one).as(offender).hasSize(1);
            long flagging = DomainRules.all().stream().filter(n -> n.rule().evaluate(one).hasViolation()).count();
            assertThat(flagging).as(offender).isPositive();
        }
    }

    @Test
    void theTestFixturesAreJavaBaseOnlyToo() {
        JavaClasses fixtures = DomainRules.scan(DomainRules.FIXTURES_ONLY);
        assertThat(fixtures.stream().map(JavaClass::getName)).contains(
                "ai.speckitty.producer.domain.order.OrderConformance", "ai.speckitty.producer.domain.order.MutantOrderings");
        EvaluationResult result = DomainRules.javaBaseOnly().rule().evaluate(fixtures);
        assertThat(result.hasViolation()).as(result.getFailureReport().getDetails().toString()).isFalse();
    }
}
