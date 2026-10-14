# Event producer

The native event producer behind the thin Python client (mission `event-producer-reroute`, spec-kitty#6185). One JSON request on
stdin, one JSON reply on stdout. Java 25, built to a GraalVM native image and shipped inside the platform wheels.

This directory is a standalone Gradle build (Kotlin DSL), deliberately not part of `contracts/` or `acceptance-jvm/`:

| Subproject | Holds | Depends on |
|---|---|---|
| `:domain` | the pure model; main code uses `java.base` only | nothing |
| `:application` | use cases and ports | `:domain` (exposed) |
| `:adapter` | JSON, schema validation, file system, lock, git, hostname through the Foreign Function and Memory API | `:application` |
| `:launcher` | `main`, protocol, exit codes, native-image configuration | all |

## Build and gates

The Gradle distribution is pinned in `tools/pins.json` and by the committed wrapper (`gradle/wrapper/`, with
`distributionSha256Sum`); `contracts/tools/gradle_pin_check.py` keeps the two equal. Every dependency and plugin is verified against the
generated `gradle/verification-metadata.xml` (strict; never edited by hand). Regenerate it from a cold Gradle home so a clean CI cache
resolves everything:

```
export JAVA_HOME=<GraalVM Community JDK 25.0.2 from tools/pins.json>
GRADLE_USER_HOME=$(mktemp -d) ./gradlew --write-verification-metadata sha256 build cyclonedxBom :launcher:nativeCompile :launcher:run
```

- `./gradlew build --rerun-tasks` is the local gate; CI runs `./gradlew check` on Linux, macOS and Windows. `check` includes the JaCoCo
  floors (`tools/jacoco-floors.gradle.kts`) and the module dependency rules (`verifyModuleDependencyRules`).
- `./gradlew :launcher:nativeCompile` builds `launcher/build/native/nativeCompile/spec-kitty-event-producer`; `event-producer.yml`
  builds the three native legs (Linux inside the pinned `manylinux_2_28` image); its `mode` input (`pr` or `release`) is how `release.yml` reuses it.
- `tools/expect-failure.sh` plants a defect per gate (coverage breach, tampered checksum, adapter-to-domain dependency, bad SBOM, floor
  breach) and passes only when the build goes red for the right reason. `tools/measure-start.sh` is the warm start-time protocol.
- Gradle 8.14.5, the version `contracts/` and `acceptance-jvm/` pin, cannot run Kotlin DSL on JDK 25 and predates three advisories fixed
  in 8.14.6 and 9.8.1, so this build pins 9.8.1.
