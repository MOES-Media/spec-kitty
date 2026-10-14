// JaCoCo coverage-floor convention, applied by every subproject that carries a floor (`:domain`, `:application`, `:adapter`) and by the
// standalone floor probe in tools/floor-probe. The applying build sets the default floors BEFORE applying this script:
//
//     extra["jacocoLineFloorDefault"] = "0.95"
//     extra["jacocoBranchFloorDefault"] = "0.90"     // optional; no branch rule when absent
//
// `-PjacocoLineFloor=<ratio>` overrides the line floor and exists for the tamper test only (tools/expect-failure.sh); an unparseable
// value fails the build (fail closed). The floors are attached to `check`: `gradle check` is the gate, `test` alone is not.
// `jacocoTestCoverageVerification` is skipped when a project has no execution data, which is why the probe (one class, one test)
// proves the wiring with a positive control before the breach run.
import java.math.BigDecimal
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

apply(plugin = "jacoco")

extensions.configure<JacocoPluginExtension> {
    toolVersion = "0.8.15"
}

fun parseFloor(label: String, raw: Any?): BigDecimal {
    val text = raw?.toString()?.trim().orEmpty()
    val value = try {
        BigDecimal(text)
    } catch (_: NumberFormatException) {
        throw GradleException("jacoco-floors: $label is not a decimal ratio: '$text'")
    }
    if (value.signum() <= 0) {
        throw GradleException("jacoco-floors: $label must be positive: '$text'")
    }
    return value
}

val lineFloor: BigDecimal = parseFloor(
    "the line floor",
    findProperty("jacocoLineFloor") ?: findProperty("jacocoLineFloorDefault")
        ?: throw GradleException("jacoco-floors: ${project.path} sets no jacocoLineFloorDefault"),
)
val branchFloor: BigDecimal? = findProperty("jacocoBranchFloorDefault")?.let { parseFloor("the branch floor", it) }

tasks.withType<JacocoCoverageVerification>().configureEach {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = lineFloor
            }
        }
        if (branchFloor != null) {
            rule {
                limit {
                    counter = "BRANCH"
                    value = "COVEREDRATIO"
                    minimum = branchFloor
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}
