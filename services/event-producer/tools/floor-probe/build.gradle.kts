plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// The probe is exactly half covered, so its own floor is 0.50: green as shipped, red for any higher -PjacocoLineFloor.
extra["jacocoLineFloorDefault"] = "0.50"
apply(from = file("../jacoco-floors.gradle.kts"))

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.14.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
