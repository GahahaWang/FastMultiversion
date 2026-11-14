package cc.gahaha.fastmultiversion.intellij.wizard

import cc.gahaha.fastmultiversion.versioning.McVersion
import com.google.gson.JsonParser
import java.util.Properties

/** Reads a URL as text; `null` if it cannot. */
fun interface Fetcher {
    fun fetch(url: String): String?
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
) {
    /** What could not be found, to tell the user. */
    fun missing(settings: ModSettings): List<String> = settings.versions.flatMap { version ->
        listOfNotNull(
            if (settings.fabric && fabricApi[version] == null) "Fabric API for Minecraft $version (mc.$version.fabric_api)" else null,
            if (settings.neoforge && neoforge[version] == null) "NeoForge for Minecraft $version (mc.$version.neoforge)" else null,
        )
    }
}

/**
 * Looks up the newest Fabric Loader, and Fabric API and NeoForge for each Minecraft version, from the loaders'
 * Maven repositories. Whatever it cannot reach comes from the defaults the plugin ships
 * (`fastmultiversion/template/defaults.properties`). Fabric Loom and ModDevGradle are not looked up: their
 * newest versions may be pre-releases, so the defaults are versions known to build FastMultiversion mods.
 */
class VersionLookup(private val fetcher: Fetcher) {
    fun resolve(settings: ModSettings): DependencyVersions {
        val fabricApiVersions = if (settings.fabric) mavenVersions(FABRIC_API) else emptyList()
        val neoforgeVersions = if (settings.neoforge) mavenVersions(NEOFORGE) else emptyList()
        return DependencyVersions(
            fabricLoom = DEFAULTS.getProperty("fabric_loom"),
            modDevGradle = DEFAULTS.getProperty("moddevgradle"),
            fabricLoader = latestFabricLoader() ?: DEFAULTS.getProperty("fabric_loader"),
            fabricApi = settings.versions.associateWith { version ->
                newestFabricApi(fabricApiVersions, version) ?: DEFAULTS.getProperty("mc.$version.fabric_api")
            },
            neoforge = settings.versions.associateWith { version ->
                newestNeoForge(neoforgeVersions, version) ?: DEFAULTS.getProperty("mc.$version.neoforge")
            },
        )
    }

    private fun latestFabricLoader(): String? {
        val json = fetcher.fetch(FABRIC_LOADER) ?: return null
        return try {
            JsonParser.parseString(json).asJsonArray
                .map { it.asJsonObject }
                .firstOrNull { it.get("stable")?.asBoolean == true }
                ?.get("version")?.asString
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun mavenVersions(metadataUrl: String): List<String> {
        val xml = fetcher.fetch(metadataUrl) ?: return emptyList()
        return Regex("<version>([^<]+)</version>").findAll(xml).map { it.groupValues[1].trim() }.toList()
    }

    companion object {
        const val FABRIC_LOADER = "https://meta.fabricmc.net/v2/versions/loader"
        const val FABRIC_API = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml"
        const val NEOFORGE = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"

        val DEFAULTS: Properties = Properties().apply {
            VersionLookup::class.java.getResourceAsStream("/fastmultiversion/template/defaults.properties")!!.use { load(it) }
        }

        /** The Minecraft versions the wizard proposes. */
        val DEFAULT_MINECRAFT_VERSIONS: List<String> = DEFAULTS.getProperty("minecraft_versions").split(',').map { it.trim() }

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
