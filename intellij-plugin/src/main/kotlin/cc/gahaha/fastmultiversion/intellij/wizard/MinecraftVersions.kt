package cc.gahaha.fastmultiversion.intellij.wizard

import cc.gahaha.fastmultiversion.versioning.McVersion
import com.google.gson.JsonParser

/** A Minecraft version the wizard offers, e.g. `26.2`, or the snapshot `26.3-snapshot-1`. */
data class MinecraftVersion(val id: String, val snapshot: Boolean)

/** The Minecraft versions FastMultiversion supports (26.1 and newer), from Mojang's version list. */
object MinecraftVersions {
    const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private val OLDEST = McVersion.parse("26.1")

    /** The versions the plugin knows, for when Mojang's list cannot be read: releases, newest first. */
    val FALLBACK: List<MinecraftVersion> =
        VersionLookup.DEFAULT_MINECRAFT_VERSIONS.sortedByDescending { McVersion.parse(it) }.map { MinecraftVersion(it, false) }

    /** The supported versions on Mojang's list, newest first; `null` if it cannot be read. */
    fun fetch(fetcher: Fetcher): List<MinecraftVersion>? = fetcher.fetch(MANIFEST)?.let(::parse)

    /**
     * The supported versions in a version manifest, newest first; `null` if it is not one. Mojang lists
     * pre-releases and release candidates as snapshots, too; older names (`25w14a`, `1.21.11`) are left out.
     */
    fun parse(json: String): List<MinecraftVersion>? = try {
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("versions")
            .map { it.asJsonObject }
            .mapNotNull { entry ->
                val id = entry.get("id")?.asString ?: return@mapNotNull null
                val type = entry.get("type")?.asString
                val version = try {
                    McVersion.parse(id)
                } catch (e: IllegalArgumentException) {
                    return@mapNotNull null
                }
                if (version < OLDEST || type !in setOf("release", "snapshot")) null else version to MinecraftVersion(id, type == "snapshot")
            }
            .sortedByDescending { it.first }
            .map { it.second }
    } catch (e: RuntimeException) {
        null
    }

    /**
     * The versions ticked at first: the newest release of each of the [lines] newest Minecraft lines, e.g.
     * 26.1.2, 26.2 and 26.3.
     */
    fun proposed(versions: List<MinecraftVersion>, lines: Int = 3): List<String> =
        versions.filter { !it.snapshot }
            .groupBy { line(it.id) }
            .values
            .map { releases -> releases.maxBy { McVersion.parse(it.id) } }
            .sortedByDescending { McVersion.parse(it.id) }
            .take(lines)
            .map { it.id }

    /** The line of a version: its first two numbers, `26.1` for 26.1.2. */
    private fun line(id: String): String = id.substringBefore('-').split('.').take(2).joinToString(".")
}

/**
 * What the wizard's version list shows and which versions are ticked: Mojang's versions once loaded, the
 * snapshots only on request. Versions ticked and then hidden (snapshots) are not chosen.
 */
class VersionChoice(available: List<MinecraftVersion> = MinecraftVersions.FALLBACK) {
    var available: List<MinecraftVersion> = available
        private set
    private val ticked = LinkedHashSet(MinecraftVersions.proposed(available))
    private var touched = false

    /** Whether the list shows snapshots, pre-releases and release candidates. */
    var snapshots = false

    /** The versions shown, newest first. */
    fun shown(): List<MinecraftVersion> = available.filter { snapshots || !it.snapshot }

    fun isTicked(id: String): Boolean = id in ticked

    fun tick(id: String, ticked: Boolean) {
        touched = true
        if (ticked) this.ticked += id else this.ticked -= id
    }

    /** Mojang's versions have been read: they replace the fallback, and are proposed unless the user chose already. */
    fun loaded(versions: List<MinecraftVersion>) {
        available = versions
        if (!touched) {
            ticked.clear()
            ticked += MinecraftVersions.proposed(versions)
        }
    }

    /** The ticked versions shown, ascending. */
    fun chosen(): List<String> = shown().filter { it.id in ticked }.map { it.id }.sortedBy { McVersion.parse(it) }
}
