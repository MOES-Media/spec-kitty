package ai.speckitty.producer.adapter.repo;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/** The producer reads {@code .git} and {@code commondir}; it never starts git or any other process (plan section 7). */
class NoProcessSpawnTest {

    private static final ArchRule NO_PROCESS = noClasses()
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.lang.ProcessBuilder")
            .orShould()
            .callMethod(Runtime.class, "exec", String.class)
            .orShould()
            .callMethod(Runtime.class, "exec", String[].class);

    private static final int MAIN_CLASS_FLOOR = 6;

    private static JavaClasses adapterMain() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ai.speckitty.producer.adapter");
    }

    @Test
    void noAdapterMainClassSpawnsAProcess() {
        JavaClasses main = adapterMain();
        assertThat(main.size()).as("main classes scanned").isGreaterThanOrEqualTo(MAIN_CLASS_FLOOR);
        assertThat(main.contain(ai.speckitty.producer.adapter.repo.GitRepoLocator.class)).isTrue();
        EvaluationResult result = NO_PROCESS.evaluate(main);
        assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
    }

    @Test
    void theRuleGoesRedOnAPlantedSpawner() {
        JavaClasses planted = new ClassFileImporter().importClasses(GitFixtures.class);
        assertThat(NO_PROCESS.evaluate(planted).hasViolation()).isTrue();
    }
}
