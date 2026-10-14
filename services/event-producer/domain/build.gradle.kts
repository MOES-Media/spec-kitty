// :domain -- pure model. Main code depends on java.base ONLY (verifyModuleDependencyRules in the root build pins it).
// java-test-fixtures hosts the shared comparator mutants and fixture loader that WP02 places under src/testFixtures; :adapter
// tests read them through testFixtures(project(":domain")) on the TEST classpath only.
plugins {
    `java-test-fixtures`
}

extra["jacocoLineFloorDefault"] = "0.95"
extra["jacocoBranchFloorDefault"] = "0.90"
apply(from = rootProject.file("tools/jacoco-floors.gradle.kts"))

dependencies {
    testImplementation(platform("org.junit:junit-bom:${rootProject.extra["junitBomVersion"]}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${rootProject.extra["assertjVersion"]}")
    testImplementation("com.tngtech.archunit:archunit-junit5:${rootProject.extra["archunitVersion"]}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
