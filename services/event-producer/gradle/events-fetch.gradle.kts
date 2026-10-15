// Script plugin of the released spec-kitty-events artifact (WP11; FR-014, FR-040, C-008). :adapter applies it when present, and a
// test asserts it is present, so the optional hook cannot silently skip it.
//
//   fetchEventsContract   downloads the wheel named in gradle/events-pin.properties (https only, one release channel), checks its
//                         sha256 BEFORE the archive is opened, extracts spec_kitty_events/producer_contract/ into
//                         build/events/contract/, and refuses an output a validator could not be built from (no registry, no
//                         types, a type without its row schema). Nothing of the events repository is committed.
//   -PeventsWheel=<path>  development only: take a local wheel instead of the pinned one. Refused when CI is set.
//
// With the java plugin present, the registry and the composed row schemas are embedded as classpath resources under
// ai/speckitty/producer/contract/ (native-image resource config: META-INF/native-image/ai.speckitty/producer-schema/), and every
// test task runs after the fetch with -Devents.contract.dir pointing at the whole extracted contract (fixtures included).
import groovy.json.JsonSlurper
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile

abstract class FetchEventsContract : DefaultTask() {

    @get:InputFile
    abstract val pinFile: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val developmentWheel: Property<String>

    /** Where a downloaded wheel is kept between runs. Not an output: a re-run checks it again and keeps it. */
    @get:Internal
    abstract val wheelCache: DirectoryProperty

    @get:OutputDirectory
    abstract val contractDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val output = contractDir.get().asFile
        val wheel = chooseWheel()
        extract(wheel, output)
        try {
            requireUsable(output)
        } catch (e: GradleException) {
            output.deleteRecursively()
            throw e
        }
    }

    private fun chooseWheel(): File {
        val development = developmentWheel.orNull
        if (development != null) {
            if (!System.getenv("CI").isNullOrEmpty()) {
                throw GradleException("-PeventsWheel is for development only and is refused when CI is set")
            }
            val file = File(development)
            if (!file.isFile) {
                throw GradleException("-PeventsWheel names no file")
            }
            logger.warn("fetchEventsContract: using a development wheel, NOT the pinned release")
            return file
        }
        val pin = Properties().apply { pinFile.get().asFile.inputStream().use { load(it) } }
        val version = pin.getProperty("version")?.trim().orEmpty()
        val url = pin.getProperty("url")?.trim().orEmpty()
        val sha256 = pin.getProperty("sha256")?.trim().orEmpty()
        val name = "spec_kitty_events-$version-py3-none-any.whl"
        if (version.isEmpty() || !Regex("[0-9][0-9A-Za-z.+-]*").matches(version)) {
            throw GradleException("events-pin.properties: version is missing or malformed")
        }
        if (!Regex("[0-9a-f]{64}").matches(sha256)) {
            throw GradleException("events-pin.properties: sha256 must be 64 lower-case hex characters")
        }
        if (!url.startsWith("https://") || !url.endsWith("/$name")) {
            throw GradleException("events-pin.properties: url must be an https URL of $name")
        }
        val cached = wheelCache.get().asFile.resolve(name)
        if (cached.isFile) {
            val actual = digest(cached)
            if (actual != sha256) {
                throw GradleException("the cached wheel's sha256 does not match events-pin.properties (pinned $sha256, got $actual); nothing was unzipped. Delete ${cached.parentFile} to fetch it again")
            }
            return cached
        }
        cached.parentFile.mkdirs()
        val partial = File(cached.parentFile, "$name.part")
        val connection = URI(url).toURL().openConnection()
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.getInputStream().use { Files.copy(it, partial.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        val actual = digest(partial)
        if (actual != sha256) {
            partial.delete()
            throw GradleException("the downloaded wheel's sha256 does not match events-pin.properties (pinned $sha256, got $actual); nothing was unzipped")
        }
        Files.move(partial.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return cached
    }

    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sha.update(buffer, 0, read)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extract(wheel: File, output: File) {
        output.deleteRecursively()
        output.mkdirs()
        val root = output.canonicalFile
        val prefix = "spec_kitty_events/producer_contract/"
        ZipFile(wheel).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory || !entry.name.startsWith(prefix) || entry.name.endsWith(".py")) continue
                val target = File(root, entry.name.removePrefix(prefix)).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) {
                    output.deleteRecursively()
                    throw GradleException("the wheel entry '${entry.name}' escapes the output directory")
                }
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            }
        }
    }

    private fun requireUsable(output: File) {
        val registry = File(output, "registry.json")
        if (!registry.isFile || registry.length() == 0L) {
            throw GradleException("the wheel's registry.json is missing or empty; a validator built from it would accept everything")
        }
        val parsed = try {
            JsonSlurper().parse(registry)
        } catch (e: Exception) {
            throw GradleException("the wheel's registry.json is not JSON")
        }
        val types = (parsed as? Map<*, *>)?.get("types") as? List<*>
        if (types == null || types.isEmpty()) {
            throw GradleException("the wheel's registry holds no types; a validator built from it would accept everything")
        }
        for (entry in types) {
            val name = (entry as? Map<*, *>)?.get("name") as? String
            if (name == null || !Regex("[a-z0-9]+(\\.[a-z0-9]+)*\\.v[0-9]+").matches(name)) {
                throw GradleException("the wheel's registry holds a type with no usable name")
            }
            val rowSchema = File(output, "composed/rows/$name.row.schema.json")
            if (!rowSchema.isFile || rowSchema.length() < 2L) {
                throw GradleException("the wheel holds no row schema for the registered type $name")
            }
        }
    }
}

val fetchEventsContract = tasks.register<FetchEventsContract>("fetchEventsContract") {
    group = "build"
    description = "Fetches the pinned spec-kitty-events wheel, verifies its sha256, and extracts the producer contract into build/."
    pinFile = rootProject.layout.projectDirectory.file("gradle/events-pin.properties")
    developmentWheel = providers.gradleProperty("eventsWheel")
    wheelCache = layout.buildDirectory.dir("events/wheel")
    contractDir = layout.buildDirectory.dir("events/contract")
}

val contractDirectory = layout.buildDirectory.dir("events/contract")
val thisScript = buildscript.sourceFile

pluginManager.withPlugin("java") {
    dependencies.add("testImplementation", dependencies.gradleTestKit())

    tasks.named<ProcessResources>("processResources") {
        from(fetchEventsContract.flatMap { it.contractDir }) {
            include("registry.json", "composed/rows/*.row.schema.json")
            into("ai/speckitty/producer/contract")
        }
    }

    tasks.withType<Test>().configureEach {
        dependsOn(fetchEventsContract)
        // The fetch tests exercise this very script, so editing it must rerun them.
        thisScript?.let { inputs.file(it) }
        systemProperty("events.contract.dir", contractDirectory.get().asFile.absolutePath)
    }
}

// The launcher's tests read the same extracted contract (the fixtures of every in-scope type) without a build line of their own.
rootProject.findProject(":launcher")?.tasks?.withType<Test>()?.configureEach {
    dependsOn(fetchEventsContract)
    systemProperty("events.contract.dir", contractDirectory.get().asFile.absolutePath)
}
