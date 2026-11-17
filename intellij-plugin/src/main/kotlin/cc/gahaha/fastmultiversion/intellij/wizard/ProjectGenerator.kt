package cc.gahaha.fastmultiversion.intellij.wizard

import java.nio.file.Files
import java.nio.file.Path

/** Where a new project gets the FastMultiversion Gradle plugin from. */
sealed interface PluginRepository {
    val version: String

    /** The version settings.gradle asks for. */
    val requested: String

    /**
     * FastMultiversion's Maven repository on GitHub Pages, of the channel of [version]: for a release, the
     * releases, of which the project uses the newest; for a pre-release (x.y.z-dev.n), the dev channel, of
     * which it uses the newest pre-release or release.
     */
    data class Published(override val version: String) : PluginRepository {
        val dev: Boolean get() = "-dev." in version
        val url: String get() = if (dev) DEV_URL else URL
        override val requested: String get() = if (dev) "latest.integration" else "latest.release"
    }

    /** The local Maven repository: a build of FastMultiversion installed with publishToMavenLocal, a snapshot. */
    data class MavenLocal(override val version: String) : PluginRepository {
        override val requested: String get() = version
    }

    companion object {
        const val URL = "https://gahahawang.github.io/FastMultiversion/maven"
        const val DEV_URL = "https://gahahawang.github.io/FastMultiversion/maven-dev"

        /** The channel of [version], this IntelliJ plugin's own version; for a build from source (a snapshot), the local Maven repository. */
        fun of(version: String): PluginRepository = if (version.endsWith("-SNAPSHOT")) MavenLocal(version) else Published(version)
    }
}

/**
 * Writes a FastMultiversion mod project, with build scripts in gradle/<kind>/, a Gradle wrapper, and a mod that
 * logs which Minecraft versions it was built for. With several loaders, the layout of the FastMultiversion
 * template: a project per version and loader, shared code in src/main and each loader's in src/<loader>. With
 * one loader, a project per version and everything in src/main.
 */
object ProjectGenerator {
    fun generate(dir: Path, settings: ModSettings, versions: DependencyVersions, plugin: PluginRepository) {
        val values = values(settings, versions, plugin)
        fun write(path: String, template: String, extra: Map<String, String> = emptyMap()) {
            val file = dir.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, fill(template(template), values + extra))
        }

        write("settings.gradle", "settings.gradle")
        write("build.gradle", "build.gradle")
        write("gradle.properties", "gradle.properties")
        write(".gitignore", "gitignore")
        Files.writeString(dir.resolve("LICENSE"), settings.license.text(settings.authors.joinToString(", ").ifEmpty { settings.modName }))
        val sources = settings.group.replace('.', '/')
        if (settings.common) {
            write("gradle/common/common.gradle", if (settings.neoFormCommon) "common-neoforge.gradle" else "common.gradle")
            write("src/main/java/$sources/${settings.modClass}.java", "Mod.java")
            if (settings.fabric) {
                write("gradle/fabric/fabric.gradle", "fabric.gradle", mapOf("projects" to ":<version>:fabric"))
                write("src/fabric/java/$sources/fabric/${settings.modClass}Fabric.java", "ModFabric.java")
                write("src/fabric/resources/fabric.mod.json", "fabric.mod.json")
            }
            if (settings.neoforge) {
                write("gradle/neoforge/neoforge.gradle", "neoforge.gradle", mapOf("projects" to ":<version>:neoforge"))
                write("src/neoforge/java/$sources/neoforge/${settings.modClass}NeoForge.java", "ModNeoForge.java")
                write("src/neoforge/resources/META-INF/neoforge.mods.toml", "neoforge.mods.toml")
            }
        } else if (settings.fabric) {
            write("gradle/version/version.gradle", "fabric.gradle", mapOf("projects" to ":<version>"))
            write("src/main/java/$sources/${settings.modClass}.java", "FabricMod.java")
            write("src/main/resources/fabric.mod.json", "fabric.mod.json")
        } else {
            write("gradle/version/version.gradle", "neoforge.gradle", mapOf("projects" to ":<version>"))
            write("src/main/java/$sources/${settings.modClass}.java", "NeoForgeMod.java")
            write("src/main/resources/META-INF/neoforge.mods.toml", "neoforge.mods.toml")
        }
        // The one file of access changes, empty, in the main loader's format: FastMultiversion converts it for the other.
        if (settings.accessTransformerMain) {
            Files.createDirectories(dir.resolve("src/main/resources/META-INF"))
            Files.writeString(dir.resolve("src/main/resources/META-INF/accesstransformer.cfg"), "")
        } else {
            Files.createDirectories(dir.resolve("src/main/resources"))
            Files.writeString(dir.resolve("src/main/resources/${settings.modId}.classtweaker"), "classTweaker v1 official\n")
        }
        // An empty mixin package, and its config, which fabric.mod.json and neoforge.mods.toml name.
        Files.createDirectories(dir.resolve("src/main/java/$sources/mixin"))
        write("src/main/resources/${settings.modId}.mixins.json", "mixins.json")
        copyWrapper(dir)
    }

    /**
     * The `multiversion` block of settings.gradle: with several loaders, a project per version and loader; with
     * one, a project per version, running gradle/version/version.gradle (the loader's build script).
     */
    private fun multiversionBlock(settings: ModSettings): String {
        val version = settings.versions.last()
        val loader = settings.loaders.first()
        val layout = if (settings.common) {
            """
            |// One project per Minecraft version and loader, e.g. :$version:$loader in versions/$version/$loader,
            |// running gradle/$loader/$loader.gradle. Shared code is in src/main, loader code in src/<loader>.
            """.trimMargin()
        } else {
            """
            |// One project per Minecraft version, e.g. :$version in versions/$version, running gradle/version/version.gradle.
            |// All code is in src/main.
            """.trimMargin()
        }
        val loaders = if (settings.common) "\n    loaders ${settings.loaders.joinToString(", ") { "'$it'" }}" else ""
        return "$layout\nmultiversion {\n    versions ${settings.versions.joinToString(", ") { "'$it'" }}$loaders\n}"
    }

    private fun values(settings: ModSettings, versions: DependencyVersions, plugin: PluginRepository): Map<String, String> {
        val loom = settings.fabric
        val pluginRepositories = buildList {
            when (plugin) {
                is PluginRepository.Published -> add("maven { url = \"${plugin.url}\" }   // FastMultiversion")
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
            if (settings.common) add("mixin_version=${versions.mixin}")
            if (settings.common) add("mixinextras_version=${versions.mixinExtras}")
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
                if (settings.neoFormCommon) {
                    add("mc.$version.neoform=${versions.neoform[version] ?: "UNKNOWN"}")
                }
            }.joinToString("\n")
        }
        // Fabric gets the access widener FastMultiversion makes, under this name: the class tweaker's, or one made from
        // the access transformer.
        val accessWidener = if (settings.accessTransformerMain) "${settings.modId}.accesswidener" else "${settings.modId}.classtweaker"
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
            "multiversion" to multiversionBlock(settings),
            // With one loader, the mod's own class is the entrypoint.
            "fabricEntrypoint" to if (settings.common) "${settings.group}.fabric.${settings.modClass}Fabric" else "${settings.group}.${settings.modClass}",
            "mcImport" to if (settings.versions.size > 1) "import cc.gahaha.fastmultiversion.Mc;\n" else "",
            "accessWidenerEntry" to "    \"accessWidener\": \"$accessWidener\",\n",
            // fabric.mod.json and neoforge.mods.toml: the license and the optional settings.
            "licenseId" to settings.license.id,
            "modNameJson" to quoted(settings.modName),
            "descriptionJson" to quoted(settings.description.trim()),
            "authorsJson" to settings.authors.joinToString(", ", "[", "]") { quoted(it) },
            "contactEntry" to contactEntry(settings),
            "modNameToml" to quoted(settings.modName),
            "descriptionToml" to quoted(settings.description.trim().ifEmpty { settings.modName }),
            "issueTrackerEntry" to if (settings.issueTracker.isBlank()) "" else "issueTrackerURL = ${quoted(settings.issueTracker.trim())}\n",
            "modLinksEntry" to buildString {
                if (settings.website.isNotBlank()) append("displayURL = ${quoted(settings.website.trim())}\n")
                if (settings.authors.isNotEmpty()) append("authors = ${quoted(settings.authors.joinToString(", "))}\n")
            },
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

    /** fabric.mod.json's `contact`, with what the optional settings give: website, repository, issue tracker. */
    private fun contactEntry(settings: ModSettings): String {
        val contact = listOf("homepage" to settings.website, "sources" to settings.repository, "issues" to settings.issueTracker)
            .filter { it.second.isNotBlank() }
        if (contact.isEmpty()) {
            return ""
        }
        return "    \"contact\": {\n" + contact.joinToString(",\n") { (key, value) -> "        \"$key\": ${quoted(value.trim())}" } + "\n    },\n"
    }

    /**
     * [text] as a string of fabric.mod.json or neoforge.mods.toml, which the build expands as Groovy templates
     * (`processResources { expand(...) }`): escaped as a JSON and TOML string, and then `\` and `$` for the template.
     */
    internal fun quoted(text: String): String {
        val escaped = buildString {
            for (c in text) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
        }
        return "\"" + escaped.replace("\\", "\\\\").replace("$", "\\$") + "\""
    }

    /** The wrapper this plugin was built with: gradlew, gradlew.bat and gradle/wrapper. */
    private fun copyWrapper(dir: Path) {
        for (path in listOf("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties")) {
            val target = dir.resolve(path)
            Files.createDirectories(target.parent)
            resource("/fastmultiversion/wrapper/$path").use { Files.copy(it, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        }
        dir.resolve("gradlew").toFile().setExecutable(true)
    }

    /** A template, with LF line ends also where the plugin was built from a checkout with CRLF ones. */
    private fun template(name: String): String =
        resource("/fastmultiversion/template/$name.tmpl").use { String(it.readAllBytes(), Charsets.UTF_8).replace("\r\n", "\n") }

    private fun resource(path: String) =
        ProjectGenerator::class.java.getResourceAsStream(path) ?: throw IllegalStateException("missing resource $path")

    /** Replaces each `{{name}}` with its value. */
    internal fun fill(template: String, values: Map<String, String>): String =
        Regex("\\{\\{(\\w+)}}").replace(template) { match ->
            values[match.groupValues[1]] ?: throw IllegalArgumentException("no value for {{${match.groupValues[1]}}}")
        }
}
