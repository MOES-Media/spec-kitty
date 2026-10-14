// :adapter -- JSON (Jackson, only here), schema validation, file system, FileChannel lock, git common-dir, hostname through FFM.
// Main code reaches :domain through :application; only the TEST classpath names :domain and its test fixtures directly
// (verifyModuleDependencyRules in the root build pins both halves).
extra["jacocoLineFloorDefault"] = "0.80"
apply(from = rootProject.file("tools/jacoco-floors.gradle.kts"))

// WP11 owns gradle/events-fetch.gradle.kts (the released-artifact fetch task); applied when present so that WP never edits this file.
file("../gradle/events-fetch.gradle.kts").takeIf { it.exists() }?.let { apply(from = it) }

dependencies {
    implementation(project(":application"))
    implementation(platform("tools.jackson:jackson-bom:${rootProject.extra["jacksonBomVersion"]}"))
    implementation("tools.jackson.core:jackson-databind")
    implementation("com.networknt:json-schema-validator:${rootProject.extra["jsonSchemaVersion"]}")

    testImplementation(project(":domain"))
    testImplementation(testFixtures(project(":domain")))
    testImplementation(platform("org.junit:junit-bom:${rootProject.extra["junitBomVersion"]}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${rootProject.extra["assertjVersion"]}")
    testImplementation("com.tngtech.archunit:archunit-junit5:${rootProject.extra["archunitVersion"]}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
