// :application -- use cases and ports. Exposes :domain (the port types are domain types); imports no :adapter and no Jackson.
extra["jacocoLineFloorDefault"] = "0.90"
apply(from = rootProject.file("tools/jacoco-floors.gradle.kts"))

dependencies {
    api(project(":domain"))

    testImplementation(platform("org.junit:junit-bom:${rootProject.extra["junitBomVersion"]}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${rootProject.extra["assertjVersion"]}")
    testImplementation("com.tngtech.archunit:archunit-junit5:${rootProject.extra["archunitVersion"]}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
