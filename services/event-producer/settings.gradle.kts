// Standalone build of the event producer (strangler; #6185). Deliberately NOT included in contracts/ or acceptance-jvm/
// (separate dependency set, separate verification metadata). No toolchain resolver plugin: no implicit JDK download.
// Dependencies come from Maven Central and the Gradle plugin portal only.
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

rootProject.name = "event-producer"

include(":domain", ":application", ":adapter", ":launcher")
