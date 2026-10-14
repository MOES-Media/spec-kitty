// Standalone probe for the JaCoCo coverage-floor convention (tools/jacoco-floors.gradle.kts). It has code and a test, so
// jacocoTestCoverageVerification really runs here; the real subprojects are empty at WP01 and would skip it.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "floor-probe"
