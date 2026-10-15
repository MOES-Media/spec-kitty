// Stub: the fetch task of the released spec-kitty-events artifact (WP11). Registered so the tests that drive it can be red first.
tasks.register("fetchEventsContract")

pluginManager.withPlugin("java") {
    dependencies.add("testImplementation", dependencies.gradleTestKit())
}
