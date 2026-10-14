import org.gradle.api.artifacts.ProjectDependency

// Root build of the event producer (#6185): version pins, the shared Java convention, the module dependency rule check and the SBOM.
// The Gradle distribution is pinned in tools/pins.json and the wrapper; every dependency and plugin is verified against the
// GENERATED gradle/verification-metadata.xml (never hand-edited). Later work packages add source files, not build lines.
plugins {
    base
    id("org.cyclonedx.bom") version "3.5.1"
}

// Dependency versions, exact (no ranges). Subprojects read them from the root extras.
extra["jacksonBomVersion"] = "3.2.1"
extra["jsonSchemaVersion"] = "3.0.8"
extra["junitBomVersion"] = "5.14.3"
extra["assertjVersion"] = "3.27.7"
extra["archunitVersion"] = "1.4.2"
extra["slf4jVersion"] = "2.0.19"

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release = 25
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        testLogging {
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------------
// Module dependency rules (plan section 1). Pinned by this Gradle task, not by ArchUnit: an ArchUnit test would need a src/test tree
// in a subproject a later work package owns.
//   * `:domain` main declares NO dependency at all (java.base only).
//   * `:adapter` main (api, implementation, compileOnly, compileOnlyApi, runtimeOnly) declares no `project(":domain")` and no
//     `testFixtures(project(":domain"))`; `:adapter` reaches `:domain` through `:application`. The test classpath may.
// `-PplantAdapterDomainMain=true` adds the forbidden dependency on purpose; tools/expect-failure.sh proves the task goes red on it.
// ---------------------------------------------------------------------------------------------------------------------------

val mainConfigurationNames = listOf("api", "implementation", "compileOnly", "compileOnlyApi", "runtimeOnly")

if (providers.gradleProperty("plantAdapterDomainMain").orNull == "true") {
    project(":adapter").dependencies.add("implementation", project(":domain"))
}

tasks.register("verifyModuleDependencyRules") {
    group = "verification"
    description = "Fails when :domain main declares a dependency or :adapter main declares :domain."
    val inspected = listOf(":domain", ":application", ":adapter", ":launcher")
    doLast {
        val violations = mutableListOf<String>()
        inspected.forEach { path ->
            val module = project(path)
            mainConfigurationNames.forEach { name ->
                val configuration = module.configurations.findByName(name) ?: return@forEach
                configuration.allDependencies.forEach { dependency ->
                    if (path == ":domain") {
                        violations += "$path main configuration '$name' declares ${dependency.group}:${dependency.name}"
                    }
                    if (path == ":adapter" && dependency is ProjectDependency && dependency.path == ":domain") {
                        violations += "$path main configuration '$name' declares project(\":domain\")"
                    }
                }
            }
        }
        println("verifyModuleDependencyRules: inspected ${inspected.size} projects: ${inspected.joinToString(" ")}")
        if (inspected.size != 4 || subprojects.size != 4) {
            throw GradleException("verifyModuleDependencyRules: expected 4 subprojects, found ${subprojects.size}")
        }
        if (violations.isNotEmpty()) {
            throw GradleException("verifyModuleDependencyRules VIOLATION: " + violations.joinToString("; "))
        }
    }
}

tasks.named("check") {
    dependsOn("verifyModuleDependencyRules")
}

// ---------------------------------------------------------------------------------------------------------------------------
// SBOM (AC-040): `cyclonedxBom` lists the Java dependencies; event-producer.yml uploads it beside the binary as `producer-sbom`.
// ---------------------------------------------------------------------------------------------------------------------------
tasks.cyclonedxBom {
    projectType = org.cyclonedx.model.Component.Type.APPLICATION
    jsonOutput = layout.buildDirectory.file("sbom/producer-sbom.json")
    xmlOutput = layout.buildDirectory.file("sbom/producer-sbom.xml")
}
