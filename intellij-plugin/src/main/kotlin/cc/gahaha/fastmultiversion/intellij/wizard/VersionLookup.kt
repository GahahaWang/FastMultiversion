package cc.gahaha.fastmultiversion.intellij.wizard

import cc.gahaha.fastmultiversion.versioning.McVersion
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Reads a URL as text; `null` if it cannot. */
fun interface Fetcher {
    fun fetch(url: String): String?
}

/** What the wizard last read from each URL, for when it cannot reach it (e.g. offline). */
interface ResponseCache {
    fun load(url: String): String?
    fun store(url: String, text: String)

    companion object {
        val NONE: ResponseCache = object : ResponseCache {
            override fun load(url: String): String? = null
            override fun store(url: String, text: String) {}
        }
    }
}

/** A [ResponseCache] in [dir], a file per URL. Trouble reading or writing it only loses the cached text. */
class FileResponseCache(private val dir: Path) : ResponseCache {
    override fun load(url: String): String? = try {
        file(url).takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }
    } catch (e: IOException) {
        null
    }

    override fun store(url: String, text: String) {
        try {
            Files.createDirectories(dir)
            val temporary = Files.createTempFile(dir, "download", ".tmp")
            Files.writeString(temporary, text)
            Files.move(temporary, file(url), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            // the next lookup reads it online again
        }
    }

    private fun file(url: String): Path = dir.resolve(url.replace(Regex("[^A-Za-z0-9.-]"), "_"))
}

/**
 * [parse] of what [url] holds now; if it cannot be read, or what it returns does not parse (e.g. a login page of
 * a public network), of what it held the last time it was read. What parses is kept in [cache] for the next time.
 */
fun <T : Any> Fetcher.read(url: String, cache: ResponseCache, parse: (String) -> T?): T? {
    val text = fetch(url)
    val parsed = text?.let(parse)
    if (text != null && parsed != null) {
        cache.store(url, text)
        return parsed
    }
    return cache.load(url)?.let(parse)
}

/** The versions of the build plugins and loaders a new project uses. */
data class DependencyVersions(
    val fabricLoom: String,
    val modDevGradle: String,
    val fabricLoader: String,
    /** Per Minecraft version: Fabric API, e.g. `0.161.0+26.2`; `null` if none was found. */
    val fabricApi: Map<String, String?>,
    /** Per Minecraft version: NeoForge, e.g. `26.2.0.93`; `null` if none was found. */
    val neoforge: Map<String, String?>,
    /** Per Minecraft version: NeoForm, for common projects on ModDevGradle, e.g. `26.2-2`; `null` if none was found. */
    val neoform: Map<String, String?> = emptyMap(),
    /** Mixin (Fabric's, which both loaders run) and MixinExtras, for the common projects' code, e.g. `0.17.5+mixin.0.8.7`. */
    val mixin: String = VersionLookup.DEFAULTS.getProperty("mixin"),
    val mixinExtras: String = VersionLookup.DEFAULTS.getProperty("mixinextras"),
) {
    /** What could not be found, to tell the user. */
    fun missing(settings: ModSettings): List<String> = settings.versions.flatMap { version ->
        listOfNotNull(
            if (settings.fabric && fabricApi[version] == null) "Fabric API for Minecraft $version (mc.$version.fabric_api)" else null,
            if (settings.neoforge && neoforge[version] == null) "NeoForge for Minecraft $version (mc.$version.neoforge)" else null,
            if (settings.neoFormCommon && neoform[version] == null) "NeoForm for Minecraft $version (mc.$version.neoform)" else null,
        )
    }
}

/**
 * Looks up the newest Fabric Loom, ModDevGradle and Fabric Loader (releases), and Fabric API, NeoForge and NeoForm
 * for each Minecraft version, from the loaders' Maven repositories. What it reads is kept in [cache]: without a
 * network, the versions come from what it read last time, and only before it ever read them from the defaults the
 * plugin ships (`fastmultiversion/template/defaults.properties`).
 */
class VersionLookup(private val cache: ResponseCache = ResponseCache.NONE, private val fetcher: Fetcher) {
    fun resolve(settings: ModSettings): DependencyVersions {
        val fabricApiVersions = if (settings.fabric) mavenVersions(FABRIC_API) else emptyList()
        val neoforgeVersions = if (settings.neoforge) mavenVersions(NEOFORGE) else emptyList()
        val neoformVersions = if (settings.neoFormCommon) mavenVersions(NEOFORM) else emptyList()
        return DependencyVersions(
            fabricLoom = (if (settings.fabric) newestRelease(mavenVersions(FABRIC_LOOM)) else null) ?: DEFAULTS.getProperty("fabric_loom"),
            modDevGradle = (if (settings.neoforge) newestRelease(mavenVersions(MOD_DEV_GRADLE)) else null) ?: DEFAULTS.getProperty("moddevgradle"),
            fabricLoader = (if (settings.fabric) latestFabricLoader() else null) ?: DEFAULTS.getProperty("fabric_loader"),
            fabricApi = settings.versions.associateWith { version ->
                newestFabricApi(fabricApiVersions, version) ?: DEFAULTS.getProperty("mc.$version.fabric_api")
            },
            neoforge = settings.versions.associateWith { version ->
                newestNeoForge(neoforgeVersions, version) ?: DEFAULTS.getProperty("mc.$version.neoforge")
            },
            neoform = if (!settings.neoFormCommon) emptyMap() else settings.versions.associateWith { version ->
                newestNeoForm(neoformVersions, version) ?: DEFAULTS.getProperty("mc.$version.neoform")
            },
            mixin = (if (settings.common) newestSpongeMixin(mavenVersions(SPONGE_MIXIN)) else null) ?: DEFAULTS.getProperty("mixin"),
            mixinExtras = (if (settings.common) newestRelease(mavenVersions(MIXIN_EXTRAS)) else null) ?: DEFAULTS.getProperty("mixinextras"),
        )
    }

    private fun latestFabricLoader(): String? = fetcher.read(FABRIC_LOADER, cache) { json ->
        try {
            JsonParser.parseString(json).asJsonArray
                .map { it.asJsonObject }
                .firstOrNull { it.get("stable")?.asBoolean == true }
                ?.get("version")?.asString
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun mavenVersions(metadataUrl: String): List<String> = fetcher.read(metadataUrl, cache) { xml ->
        Regex("<version>([^<]+)</version>").findAll(xml).map { it.groupValues[1].trim() }.toList().ifEmpty { null }
    } ?: emptyList()

    companion object {
        const val FABRIC_LOADER = "https://meta.fabricmc.net/v2/versions/loader"
        const val FABRIC_API = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml"
        const val NEOFORGE = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"
        const val NEOFORM = "https://maven.neoforged.net/releases/net/neoforged/neoform/maven-metadata.xml"
        const val SPONGE_MIXIN = "https://maven.fabricmc.net/net/fabricmc/sponge-mixin/maven-metadata.xml"
        const val MIXIN_EXTRAS = "https://repo1.maven.org/maven2/io/github/llamalad7/mixinextras-common/maven-metadata.xml"

        /** The Gradle plugin markers: the versions of the plugin ids the build scripts apply. */
        const val FABRIC_LOOM = "https://maven.fabricmc.net/net/fabricmc/fabric-loom/net.fabricmc.fabric-loom.gradle.plugin/maven-metadata.xml"
        const val MOD_DEV_GRADLE = "https://maven.neoforged.net/releases/net/neoforged/moddev/net.neoforged.moddev.gradle.plugin/maven-metadata.xml"

        val DEFAULTS: Properties = Properties().apply {
            VersionLookup::class.java.getResourceAsStream("/fastmultiversion/template/defaults.properties")!!.use { load(it) }
        }

        /** The Minecraft versions the wizard proposes. */
        val DEFAULT_MINECRAFT_VERSIONS: List<String> = DEFAULTS.getProperty("minecraft_versions").split(',').map { it.trim() }

        /** The newest release: no qualifier such as `-alpha.31` or `-beta`, e.g. `1.19.4` rather than `1.20.0-alpha.31`. */
        fun newestRelease(versions: List<String>): String? =
            versions.filter { '-' !in it }.maxWithOrNull(NUMBERS)

        /** Fabric's Mixin builds name the Mixin they are based on after a '+': `0.17.5+mixin.0.8.7`. */
        fun newestSpongeMixin(versions: List<String>): String? =
            versions.filter { '-' !in it }.maxWithOrNull(compareBy(NUMBERS) { it.substringBefore('+') })

        /** NeoForm builds are their Minecraft version and a build number: `26.2-2`, newer than `26.2-1`. */
        fun newestNeoForm(versions: List<String>, minecraft: String): String? =
            versions.filter { it.startsWith("$minecraft-") && it.substringAfter("$minecraft-").toIntOrNull() != null }
                .maxByOrNull { it.substringAfter("$minecraft-").toInt() }

        /** Fabric API builds name their Minecraft version after a '+': `0.161.0+26.2`. */
        fun newestFabricApi(versions: List<String>, minecraft: String): String? =
            versions.filter { it.endsWith("+$minecraft") }
                .maxWithOrNull(compareBy(NUMBERS) { it.substringBefore('+') })

        /**
         * NeoForge builds start with their Minecraft version in three parts: `26.2.0.93` for 26.2,
         * `26.1.2.126` for 26.1.2. Releases win over betas.
         */
        fun newestNeoForge(versions: List<String>, minecraft: String): String? {
            val numbers = minecraft.split('.')
            if (numbers.size !in 2..3 || numbers.any { it.toIntOrNull() == null }) {
                return null
            }
            val prefix = (numbers + listOf("0")).take(3).joinToString(".") + "."
            val matching = versions.filter { it.startsWith(prefix) }
            val releases = matching.filter { '-' !in it }
            return (releases.ifEmpty { matching }).maxWithOrNull(compareBy(NUMBERS) { it.substringBefore('-') })
        }

        /** Compares dotted version numbers part by part: `0.161.10` after `0.161.9`. */
        private val NUMBERS = Comparator<String> { a, b ->
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            (0 until maxOf(x.size, y.size)).asSequence()
                .map { (x.getOrElse(it) { 0 }).compareTo(y.getOrElse(it) { 0 }) }
                .firstOrNull { it != 0 } ?: 0
        }

        /** The range of Minecraft versions a build for [version] supports, for fabric.mod.json: `>=26.1.2 <26.2`. */
        fun fabricRange(version: String): String = ">=$version <${nextMinor(version)}"

        /** The same range for neoforge.mods.toml: `[26.1.2,26.2)`. */
        fun neoforgeRange(version: String): String = "[$version,${nextMinor(version)})"

        private fun nextMinor(version: String): String {
            val numbers = McVersion.parse(version).toString().substringBefore('-').split('.')
            return "${numbers[0]}.${(numbers.getOrNull(1)?.toIntOrNull() ?: 0) + 1}"
        }
    }
}
