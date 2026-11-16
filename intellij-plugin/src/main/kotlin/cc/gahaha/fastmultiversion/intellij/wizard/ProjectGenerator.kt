package cc.gahaha.fastmultiversion.intellij.wizard

import java.nio.file.Files
import java.nio.file.Path

/** Where a new project gets the FastMultiversion Gradle plugin from. */
sealed interface PluginRepository {
    val version: String

    /** The version settings.gradle asks for. */
    val requested: String

    /** FastMultiversion's Maven repository on GitHub Pages, which has every release: the project uses the newest. */
    data class Published(override val version: String) : PluginRepository {
        override val requested: String get() = "latest.release"
    }

    /** The local Maven repository: a build of FastMultiversion installed with publishToMavenLocal, a snapshot. */
    data class MavenLocal(override val version: String) : PluginRepository {
        override val requested: String get() = version
    }

    companion object {
        const val URL = "https://gahahawang.github.io/FastMultiversion/maven"

        /** The release of [version], this IntelliJ plugin's own version; for a build from source (a snapshot), the local Maven repository. */
        fun of(version: String): PluginRepository = if (version.endsWith("-SNAPSHOT")) MavenLocal(version) else Published(version)
    }
}

/**
 * Writes a FastMultiversion mod project: the layout of the FastMultiversion template, with build scripts in
 * gradle/<kind>/, a Gradle wrapper, and a mod that logs which Minecraft versions it was built for.
 */
object ProjectGenerator {
    fun generate(dir: Path, settings: ModSettings, versions: DependencyVersions, plugin: PluginRepository) {
        val values = values(settings, versions, plugin)
        fun write(path: String, template: String) {
            val file = dir.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, fill(template(template), values))
        }

        write("settings.gradle", "settings.gradle")
        write("build.gradle", "build.gradle")
        write("gradle.properties", "gradle.properties")
        write(".gitignore", "gitignore")
        if (settings.common) {
            write("gradle/common/common.gradle", "common.gradle")
        }
        val sources = settings.group.replace('.', '/')
        write("src/main/java/$sources/${settings.modClass}.java", "Mod.java")
        if (settings.fabric) {
            write("gradle/fabric/fabric.gradle", "fabric.gradle")
            write("src/fabric/java/$sources/fabric/${settings.modClass}Fabric.java", "ModFabric.java")
            write("src/fabric/resources/fabric.mod.json", "fabric.mod.json")
        }
        if (settings.neoforge) {
            write("gradle/neoforge/neoforge.gradle", "neoforge.gradle")
            write("src/neoforge/java/$sources/neoforge/${settings.modClass}NeoForge.java", "ModNeoForge.java")
            write("src/neoforge/resources/META-INF/neoforge.mods.toml", "neoforge.mods.toml")
        }
        copyWrapper(dir)
    }

    private fun values(settings: ModSettings, versions: DependencyVersions, plugin: PluginRepository): Map<String, String> {
        val loom = settings.fabric || settings.common
        val pluginRepositories = buildList {
            when (plugin) {
                is PluginRepository.Published -> add("maven { url = \"${PluginRepository.URL}\" }   // FastMultiversion")
                is PluginRepository.MavenLocal -> add("mavenLocal()   // FastMultiversion, installed with its publishToMavenLocal")
            }
            if (loom) add("maven { url = \"https://maven.fabricmc.net/\" }")
            if (settings.neoforge) add("maven { url = \"https://maven.neoforged.net/releases\" }")
        }
        val loaderPlugins = buildList {
            if (loom) add("id 'net.fabricmc.fabric-loom' version \"\${fabric_loom_version}\" apply false")
            if (settings.neoforge) add("id 'net.neoforged.moddev' version \"\${moddevgradle_version}\" apply false")
        }
        val pluginVersions = buildList {
            if (loom) add("fabric_loom_version=${versions.fabricLoom}")
            if (settings.neoforge) add("moddevgradle_version=${versions.modDevGradle}")
            if (settings.fabric) add("fabric_loader_version=${versions.fabricLoader}")
        }
        val perVersion = settings.versions.joinToString("\n\n") { version ->
            buildList {
                if (settings.fabric) {
                    add("mc.$version.fabric_api=${versions.fabricApi[version] ?: "UNKNOWN"}")
                    add("mc.$version.range_fabric=${VersionLookup.fabricRange(version)}")
                }
                if (settings.neoforge) {
                    add("mc.$version.neoforge=${versions.neoforge[version] ?: "UNKNOWN"}")
                    add("mc.$version.range_neoforge=${VersionLookup.neoforgeRange(version)}")
                }
            }.joinToString("\n")
        }
        return mapOf(
            "projectName" to settings.projectName,
            "modId" to settings.modId,
            "modName" to settings.modName,
            "modClass" to settings.modClass,
            "group" to settings.group,
            "fastmultiversionVersion" to plugin.requested,
            "pluginRepositories" to pluginRepositories.joinToString("\n") { "        $it" },
            "loaderPlugins" to loaderPlugins.joinToString("\n") { "    $it" },
            "pluginVersions" to pluginVersions.joinToString("\n"),
            "perVersion" to perVersion,
            "versions" to settings.versions.joinToString(", ") { "'$it'" },
            "loaders" to settings.loaders.joinToString(", ") { "'$it'" },
            "exampleVersion" to settings.versions.last(),
            "exampleLoader" to settings.loaders.first(),
            "initBody" to initBody(settings),
        )
    }

    /** Logs the version the mod was built for, through Mc.is if there are several. */
    private fun initBody(settings: ModSettings): String {
        val versions = settings.versions
        if (versions.size == 1) {
            return "        LOGGER.info(\"${escape(settings.modName)} for Minecraft ${versions[0]}\");"
        }
        val newest = versions.last()
        return """
            |        // Each version's build keeps only the branch for that version.
            |        if (Mc.is("[$newest,)")) {
            |            LOGGER.info("${escape(settings.modName)} for Minecraft $newest and newer");
            |        } else {
            |            LOGGER.info("${escape(settings.modName)} for Minecraft before $newest");
            |        }
        """.trimMargin()
    }

    private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

    /** The wrapper this plugin was built with: gradlew, gradlew.bat and gradle/wrapper. */
    private fun copyWrapper(dir: Path) {
        for (path in listOf("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")) {
            val target = dir.resolve(path)
            Files.createDirectories(target.parent)
            resource("/fastmultiversion/wrapper/$path").use { Files.copy(it, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        }
        dir.resolve("gradlew").toFile().setExecutable(true)
    }

    private fun template(name: String): String =
        resource("/fastmultiversion/template/$name.tmpl").use { String(it.readAllBytes(), Charsets.UTF_8) }

    private fun resource(path: String) =
        ProjectGenerator::class.java.getResourceAsStream(path) ?: throw IllegalStateException("missing resource $path")

    /** Replaces each `{{name}}` with its value. */
    internal fun fill(template: String, values: Map<String, String>): String =
        Regex("\\{\\{(\\w+)}}").replace(template) { match ->
            values[match.groupValues[1]] ?: throw IllegalArgumentException("no value for {{${match.groupValues[1]}}}")
        }
}
