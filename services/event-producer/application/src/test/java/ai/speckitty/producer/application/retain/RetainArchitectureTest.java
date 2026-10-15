package ai.speckitty.producer.application.retain;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import ai.speckitty.producer.application.retain.planted.PlantedFileReader;
import ai.speckitty.producer.application.retain.planted.PlantedStreamUser;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The retain use case never touches the file system: it decides from facts and asks ports. So it opens nothing for write by
 * construction (FR-031), and the committed ledgers are reached only through {@link LedgerFacts}.
 */
class RetainArchitectureTest {

    private static final String PACKAGE = "ai.speckitty.producer.application.retain";

    private static final ArchRule NO_FILE_SYSTEM = noClasses()
            .that().resideInAPackage(PACKAGE + "..")
            .should().dependOnClassesThat().resideInAnyPackage("java.io..", "java.nio.channels..")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.Files")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.nio.file.FileSystems");

    private static JavaClasses real() {
        return new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(PACKAGE);
    }

    @Test
    void theScanSeesTheUseCaseAndItsPorts() {
        List<String> names = real().stream().map(JavaClass::getSimpleName).collect(Collectors.toList());
        assertThat(names).contains("Retain", "LedgerFacts", "LocalTier", "ClockGate", "TrackedFiles", "LocalTierPaths");
    }

    @Test
    void theUseCaseAndItsPortsDoNotTouchTheFileSystem() {
        EvaluationResult result = NO_FILE_SYSTEM.evaluate(real());
        assertThat(result.hasViolation()).as(result.getFailureReport().toString()).isFalse();
    }

    @Test
    void theRuleGoesRedOnPlantedFileSystemUse() {
        assertThat(NO_FILE_SYSTEM.evaluate(new ClassFileImporter().importClasses(PlantedFileReader.class)).hasViolation()).isTrue();
        assertThat(NO_FILE_SYSTEM.evaluate(new ClassFileImporter().importClasses(PlantedStreamUser.class)).hasViolation()).isTrue();
    }
}
