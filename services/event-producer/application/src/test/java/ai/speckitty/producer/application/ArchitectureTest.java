package ai.speckitty.producer.application;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.io.IOException;
import java.lang.constant.ClassDesc;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code :application} imports no {@code :adapter}, no {@code :launcher} and no JSON library. The rule has a floor (the scan sees
 * the use case and the ports) and a self-mutation: classes generated at test time that do import them must be flagged, and a
 * generated clean control must not be.
 */
class ArchitectureTest {

    private static final String ROOT = "ai.speckitty.producer.application";
    private static final ImportOption MAIN_ONLY =
            location -> location.contains("/classes/java/main/") || location.contains("/libs/application.jar!");

    private static final List<String> FORBIDDEN = List.of(
            "ai.speckitty.producer.adapter..",
            "ai.speckitty.producer.launcher..",
            "tools.jackson..",
            "com.fasterxml.jackson..",
            "com.networknt..");

    private static final int CLASS_FLOOR = 8;

    private static final ArchRule RULE = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(FORBIDDEN.toArray(String[]::new))
            .as("the application imports no adapter, launcher or JSON library");

    private static JavaClasses mainClasses() {
        return new ClassFileImporter().withImportOption(MAIN_ONLY).importPackages(ROOT);
    }

    @Test
    void theRealApplicationSatisfiesTheRule() {
        EvaluationResult result = RULE.evaluate(mainClasses());
        assertThat(result.hasViolation()).as(result.getFailureReport().getDetails().toString()).isFalse();
    }

    @Test
    void theScanIsNotVacuous() {
        List<String> names = mainClasses().stream().map(JavaClass::getName).collect(Collectors.toList());
        assertThat(names).hasSizeGreaterThanOrEqualTo(CLASS_FLOOR);
        assertThat(names).contains(
                ROOT + ".port.ClockStore",
                ROOT + ".port.PublishLog",
                ROOT + ".port.RowSchemaValidator",
                ROOT + ".produce.Produce");
        assertThat(names).noneMatch(n -> n.endsWith("Test"));
    }

    @Test
    void theRuleGoesRedOnEveryKindOfPlantedImport(@TempDir Path dir) throws IOException {
        for (String target : List.of(
                "ai/speckitty/producer/adapter/json/Json",
                "ai/speckitty/producer/launcher/Main",
                "tools/jackson/databind/ObjectMapper",
                "com/fasterxml/jackson/databind/ObjectMapper",
                "com/networknt/schema/Schema")) {
            String name = "Planted" + target.substring(target.lastIndexOf('/') + 1) + target.length();
            JavaClasses planted = plant(dir, name, target);
            EvaluationResult result = RULE.evaluate(planted);
            assertThat(result.hasViolation()).as("planted import of " + target).isTrue();
            assertThat(result.getFailureReport().getDetails().toString()).contains(name);
        }
    }

    @Test
    void theRuleStaysGreenOnAPlantedControl(@TempDir Path dir) throws IOException {
        JavaClasses control = plant(dir, "PlantedControl", "java/util/List");
        assertThat(control).hasSize(1);
        assertThat(RULE.evaluate(control).hasViolation()).isFalse();
    }

    /** A generated class holding one field of type {@code target}: a dependency the compiler never saw. */
    private static JavaClasses plant(Path dir, String simpleName, String target) throws IOException {
        Path out = dir.resolve(simpleName);
        Files.createDirectories(out.resolve("planted"));
        byte[] bytes = ClassFile.of().build(ClassDesc.of("planted." + simpleName), builder ->
                builder.withField("held", ClassDesc.ofInternalName(target), ClassFile.ACC_PUBLIC));
        Files.write(out.resolve("planted").resolve(simpleName + ".class"), bytes);
        return new ClassFileImporter().importPath(out);
    }
}
