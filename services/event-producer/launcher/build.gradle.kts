import org.graalvm.buildtools.gradle.dsl.GraalVMExtension

// :launcher -- main, stdin/stdout protocol, exit codes, deadline watchdog, native-image configuration. No coverage floor (plan 13).
plugins {
    application
    id("org.graalvm.buildtools.native") version "1.1.8"
}

// The ONE place the entry class is named: the native image and the JVM application both read it. WP01 points it at the spike;
// WP04 changes this single line to ai.speckitty.producer.launcher.Main in the commit that deletes the spike.
val producerMainClass = "ai.speckitty.producer.launcher.spike.SpikeMain"

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":adapter"))
    // The spike (WP01) reads and validates JSON itself; WP04 replaces it with the real entry that goes through :adapter.
    implementation(platform("tools.jackson:jackson-bom:${rootProject.extra["jacksonBomVersion"]}"))
    implementation("tools.jackson.core:jackson-databind")
    implementation("com.networknt:json-schema-validator:${rootProject.extra["jsonSchemaVersion"]}")
    // json-schema-validator logs through slf4j-api; without a provider it writes three warning lines to stderr on every start.
    runtimeOnly("org.slf4j:slf4j-nop:${rootProject.extra["slf4jVersion"]}")

    testImplementation(platform("org.junit:junit-bom:${rootProject.extra["junitBomVersion"]}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${rootProject.extra["assertjVersion"]}")
    testImplementation("com.tngtech.archunit:archunit-junit5:${rootProject.extra["archunitVersion"]}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = producerMainClass
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<Test>().configureEach {
    systemProperty("producer.mainClass", producerMainClass)
}

// \`gradle run\` feeds the request on stdin, as the native binary receives it (the JVM-versus-native comparison of WP01 T004).
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

extensions.configure<GraalVMExtension> {
    // The image comes from JAVA_HOME (the pinned GraalVM archive in tools/pins.json), never from toolchain detection.
    toolchainDetection = false
    binaries {
        named("main") {
            imageName = "spec-kitty-event-producer"
            mainClass = producerMainClass
            // java-library/application builds an executable; refuse a shared library build explicitly.
            sharedLibrary = false
            verbose = true
            buildArgs.addAll("--no-fallback", "-O2", "--enable-native-access=ALL-UNNAMED")
            // macOS: the wheel tag floor is macOS 11 (scripts/release/dist-set.json). The JDK's static libraries are built for minos 11.0, but
            // the final link takes its minos from the linker's deployment target, which defaults to the build host's OS (14.0 on macos-14);
            // the run of 2026-10-14 measured 14.0 although MACOSX_DEPLOYMENT_TARGET was set. Pass the target on the linker and the C
            // compiler command lines. Both are native-image options (-H:NativeLinkerOption, -H:CCompilerOption), macOS only.
            if (System.getProperty("os.name").startsWith("Mac")) {
                buildArgs.addAll("-H:NativeLinkerOption=-mmacosx-version-min=11.0", "-H:CCompilerOption=-mmacosx-version-min=11.0")
            }
        }
    }
}
