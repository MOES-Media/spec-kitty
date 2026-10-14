package ai.speckitty.producer.domain.arch;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/** The architecture rules of {@code :domain} (plan section 1) and the scans they run over. */
final class DomainRules {

    static final String ROOT = "ai.speckitty.producer.domain";

    /**
     * Only the compiled main classes of {@code :domain}: Gradle puts them on the test classpath as {@code domain.jar}. An unknown
     * layout gives an empty scan, which the rules and the floor refuse.
     */
    static final ImportOption MAIN_ONLY =
            location -> location.contains("/classes/java/main/") || location.contains("/libs/domain.jar!");

    /** Only the compiled test-fixture classes. */
    static final ImportOption FIXTURES_ONLY =
            location -> location.contains("/classes/java/testFixtures/") || location.contains("/libs/domain-test-fixtures.jar!");

    /** Only the planted offenders of the self-mutation cases. */
    static final ImportOption PLANTED_ONLY = location -> location.contains("/classes/java/test/") && location.contains("/arch/planted/");

    private DomainRules() {}

    static JavaClasses scan(ImportOption option) {
        return new ClassFileImporter().withImportOption(option).importPackages(ROOT);
    }

    /** A rule with the planted class that must break it. */
    record Named(String name, ArchRule rule, String plantedOffender) {}

    static final DescribedPredicate<JavaClass> JAVA_BASE_ONLY =
            resideInAnyPackage(ROOT + "..", "java.lang..", "java.util..", "java.math..", "java.time..")
                    .as("classes of the domain or of java.lang, java.util, java.math and java.time");

    static Named javaBaseOnly() {
        return new Named(
                "domain depends on java.base only",
                classes().should().onlyDependOnClassesThat(JAVA_BASE_ONLY),
                "PlantedOutsideDependency");
    }

    static Named noClockRead() {
        return new Named(
                "no class reads a clock",
                noClasses().should().dependOnClassesThat(equivalentTo(java.time.Clock.class))
                        .orShould().callMethodWhere(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                                HasName.Predicates.nameMatching("now|currentTimeMillis|nanoTime"))),
                "PlantedClockRead");
    }

    static Named noSystemTimer() {
        return new Named(
                "no class touches System or Runtime",
                noClasses().should().dependOnClassesThat(equivalentTo(System.class))
                        .orShould().dependOnClassesThat(equivalentTo(Runtime.class))
                        .orShould().dependOnClassesThat(equivalentTo(ProcessBuilder.class)),
                "PlantedSystemTime");
    }

    static Named noEnvironmentRead() {
        return new Named(
                "no class reads an environment variable or a system property",
                noClasses().should().callMethodWhere(com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                        HasName.Predicates.nameMatching("getenv|getProperty|getProperties"))),
                "PlantedEnvironmentRead");
    }

    static Named noFileAccess() {
        return new Named(
                "no class touches the file system or the network",
                noClasses().should().dependOnClassesThat(resideInAnyPackage("java.io..", "java.nio..", "java.net..", "java.sql..")),
                "PlantedFileRead");
    }

    static java.util.List<Named> all() {
        return java.util.List.of(javaBaseOnly(), noClockRead(), noSystemTimer(), noEnvironmentRead(), noFileAccess());
    }

    /** A predicate that is always true, so a rule can be evaluated over a class set without further narrowing. */
    static DescribedPredicate<JavaClass> everything() {
        return alwaysTrue();
    }
}
