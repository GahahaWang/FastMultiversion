package cc.gahaha.fastmultiversion.intellij.wizard

import cc.gahaha.fastmultiversion.versioning.McVersion

/** What the New Project wizard asks for: the mod, the Minecraft versions and the loaders it is built for. */
data class ModSettings(
    /** The Gradle root project's name, e.g. `MyMod`. */
    val projectName: String,
    /** The mod id, e.g. `mymod`. */
    val modId: String,
    /** The mod's display name, e.g. `My Mod`. */
    val modName: String,
    /** The Java package and Maven group, e.g. `com.example.mymod`. */
    val group: String,
    /** The Minecraft versions, ascending, e.g. `26.1.2`, `26.2`. */
    val versions: List<String>,
    /** The loaders, in [LOADERS] order. */
    val loaders: List<String>,
    /**
     * With several loaders, the main one: the common projects compile the shared code against Minecraft as its Gradle
     * plugin sets it up (Loom, or ModDevGradle's NeoForm), and the mod's one file of access changes is in its format
     * (a class tweaker, or an access transformer), which FastMultiversion converts for the other loader.
     */
    val mainLoader: String = "fabric",
    /** The mod's license: fabric.mod.json, neoforge.mods.toml and the LICENSE file. */
    val license: License = License.ALL_RIGHTS_RESERVED,
    /** Optional: what the mod does, its authors and where it is on the web, for fabric.mod.json and neoforge.mods.toml. */
    val description: String = "",
    val authors: List<String> = emptyList(),
    val website: String = "",
    val repository: String = "",
    val issueTracker: String = "",
) {
    /** The main class's simple name, from the mod name: `My Mod` → `MyMod`. */
    val modClass: String
        get() {
            val words = modName.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
            val name = words.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
            return when {
                name.isEmpty() -> "Mod"
                name.first().isDigit() -> "Mod$name"
                else -> name
            }
        }

    /** With several loaders, the shared code compiles in common projects (Minecraft without a loader). */
    val common: Boolean get() = loaders.size > 1

    val fabric: Boolean get() = "fabric" in loaders
    val neoforge: Boolean get() = "neoforge" in loaders

    /** The common projects use ModDevGradle's NeoForm (NeoForge is the main loader) rather than Loom. */
    val neoFormCommon: Boolean get() = common && mainLoader == "neoforge"

    companion object {
        val LOADERS = listOf("fabric", "neoforge")
        private val MOD_ID = Regex("[a-z][a-z0-9_]{1,63}")
        private val PACKAGE = Regex("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)*")
        private val OLDEST = McVersion.parse("26.1")

        /** Authors from a comma-separated list: `Alex, Steve` → `Alex`, `Steve`. */
        fun parseAuthors(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        /** A mod id from a project name: `My Mod` → `mymod`. */
        fun suggestModId(projectName: String): String {
            val id = projectName.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.trimStart { it.isDigit() || it == '_' }
            return if (id.length >= 2) id.take(64) else "mymod"
        }

        /** Why [modId] is not a valid mod id, or `null`. */
        fun checkModId(modId: String): String? =
            if (MOD_ID.matches(modId)) null else "2 to 64 lower-case letters, digits and '_', starting with a letter"

        /** Why [group] is not a valid Java package, or `null`. */
        fun checkGroup(group: String): String? =
            if (PACKAGE.matches(group)) null else "A Java package in lower case, e.g. com.example.mymod"

        /** The versions in [text] ("26.1.2, 26.2"), ascending, or why they are not usable. */
        fun parseVersions(text: String): Result<List<String>> {
            val parts = text.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) {
                return Result.failure(IllegalArgumentException("List at least one Minecraft version"))
            }
            val versions = mutableListOf<McVersion>()
            for (part in parts) {
                val version = try {
                    McVersion.parse(part)
                } catch (e: IllegalArgumentException) {
                    return Result.failure(IllegalArgumentException("'$part' is not a Minecraft version"))
                }
                if (version < OLDEST) {
                    return Result.failure(IllegalArgumentException("FastMultiversion supports Minecraft 26.1 and newer, not $part"))
                }
                if (versions.any { it.compareTo(version) == 0 }) {
                    return Result.failure(IllegalArgumentException("$part is listed twice"))
                }
                versions += version
            }
            return Result.success(versions.sorted().map { it.toString() })
        }
    }
}
