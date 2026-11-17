package cc.gahaha.fastmultiversion.intellij.wizard

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The New Project wizard's settings, version lookup and generated project, without the IDE. */
class WizardTest {
    private val both = ModSettings("TestMod", "testmod", "Test Mod", "com.example.testmod", listOf("26.1.2", "26.2", "26.3"), listOf("fabric", "neoforge"))

    @Test
    fun derivesNamesAndChecksInput() {
        assertEquals("mymod", ModSettings.suggestModId("My Mod"))
        assertEquals("mymod", ModSettings.suggestModId("12"))
        assertEquals("TestMod", both.modClass)
        assertEquals("Mod2Fast", both.copy(modName = "2 fast").modClass)
        assertNull(ModSettings.checkModId("test_mod"))
        assertTrue(ModSettings.checkModId("Test") != null)
        assertNull(ModSettings.checkGroup("com.example.test_mod"))
        assertTrue(ModSettings.checkGroup("com.Example") != null)
        assertEquals(listOf("26.1.2", "26.2", "26.3"), ModSettings.parseVersions("26.3, 26.1.2 26.2").getOrThrow())
        assertTrue(ModSettings.parseVersions("1.21").exceptionOrNull()!!.message!!.contains("26.1 and newer"))
        assertTrue(ModSettings.parseVersions("26.2, 26.2").isFailure)
        assertTrue(ModSettings.parseVersions("").isFailure)
    }

    @Test
    fun listsTheMinecraftVersionsOfMojang() {
        val manifest = """
            {"latest": {"release": "26.3", "snapshot": "26.4-snapshot-2"}, "versions": [
              {"id": "26.4-snapshot-2", "type": "snapshot"},
              {"id": "26.3", "type": "release"},
              {"id": "26.3-rc-1", "type": "snapshot"},
              {"id": "26.3-pre-2", "type": "snapshot"},
              {"id": "26.2.1", "type": "release"},
              {"id": "26.2", "type": "release"},
              {"id": "26.1.2", "type": "release"},
              {"id": "26.1", "type": "release"},
              {"id": "26.1-snapshot-1", "type": "snapshot"},
              {"id": "25w46a", "type": "snapshot"},
              {"id": "1.21.11", "type": "release"},
              {"id": "b1.7.3", "type": "old_beta"}
            ]}
        """.trimIndent()
        val versions = MinecraftVersions.parse(manifest)!!
        // 26.1 and newer, newest first: 26.1-snapshot-1 is older than 26.1.
        assertEquals(listOf("26.4-snapshot-2", "26.3", "26.3-rc-1", "26.3-pre-2", "26.2.1", "26.2", "26.1.2", "26.1"), versions.map { it.id })
        assertEquals(listOf("26.4-snapshot-2", "26.3-rc-1", "26.3-pre-2"), versions.filter { it.snapshot }.map { it.id })
        assertEquals(listOf("26.3", "26.2.1", "26.1.2"), MinecraftVersions.proposed(versions))
        assertNull(MinecraftVersions.parse("not json"))
        assertNull(MinecraftVersions.fetch { null })
    }

    @Test
    fun choosesVersionsFromTheList() {
        val choice = VersionChoice()
        // Before Mojang's list is read (or without it): the versions the plugin knows, all ticked.
        assertEquals(VersionLookup.DEFAULT_MINECRAFT_VERSIONS, choice.chosen())

        val versions = listOf(
            MinecraftVersion("26.4-snapshot-1", true), MinecraftVersion("26.3", false), MinecraftVersion("26.2.1", false),
            MinecraftVersion("26.2", false), MinecraftVersion("26.1.2", false),
        )
        choice.loaded(versions)
        assertEquals(listOf("26.3", "26.2.1", "26.2", "26.1.2"), choice.shown().map { it.id }) // no snapshots by default
        assertEquals(listOf("26.1.2", "26.2.1", "26.3"), choice.chosen())

        choice.snapshots = true
        assertEquals("26.4-snapshot-1", choice.shown().first().id)
        choice.tick("26.4-snapshot-1", true)
        choice.tick("26.2.1", false)
        assertEquals(listOf("26.1.2", "26.3", "26.4-snapshot-1"), choice.chosen())
        choice.snapshots = false
        assertEquals(listOf("26.1.2", "26.3"), choice.chosen()) // hidden snapshots are not chosen

        // A choice made before the list arrives is kept.
        val early = VersionChoice()
        early.tick("26.1.2", false)
        early.loaded(versions)
        assertEquals(listOf("26.2", "26.3"), early.chosen())
    }

    @Test
    fun looksUpTheNewestVersions() {
        val settings = both.copy(versions = listOf("26.1.2", "26.2", "26.3"))
        val versions = VersionLookup { RESPONSES[it] }.resolve(settings)

        assertEquals("1.19.4", versions.fabricLoom) // releases only
        assertEquals("2.0.148", versions.modDevGradle)
        assertEquals("0.20.9", versions.fabricLoader)
        assertEquals("0.161.10+26.2", versions.fabricApi["26.2"])
        assertEquals("26.2.0.101", versions.neoforge["26.2"]) // releases before betas
        assertEquals("26.3.0.1-beta", versions.neoforge["26.3"]) // a beta if that is all there is
        assertEquals("26.1.2.126", versions.neoforge["26.1.2"])
        assertEquals("0.155.3+26.1.2", versions.fabricApi["26.1.2"]) // not listed: the default
        assertTrue(versions.neoform.isEmpty()) // Fabric is the main loader: no NeoForm

        val neoform = VersionLookup { RESPONSES[it] }.resolve(settings.copy(mainLoader = "neoforge")).neoform
        assertEquals(mapOf("26.1.2" to "26.1.2-1", "26.2" to "26.2-2", "26.3" to "26.3-1"), neoform) // not 26.3-rc-1-2
        assertEquals("0.17.10+mixin.0.8.7", versions.mixin) // by Fabric's version, before the +
        assertEquals("0.5.5", versions.mixinExtras)
    }

    @Test
    fun remembersWhatItReadForWhenOffline() {
        val cache = FileResponseCache(Files.createTempDirectory("fmv-cache"))
        val settings = both.copy(versions = listOf("26.2", "26.3"))
        val online = VersionLookup(cache) { RESPONSES[it] }.resolve(settings)
        val minecraft = MinecraftVersions.fetch(cache) { MANIFEST }

        // Offline, or behind a login page that is no version list: what it read last time, not the defaults.
        for (fetcher in listOf(Fetcher { null }, Fetcher { "<html>Log in to the network</html>" })) {
            assertEquals(online, VersionLookup(cache, fetcher).resolve(settings))
            assertEquals(minecraft, MinecraftVersions.fetch(cache, fetcher))
        }
        assertEquals("1.19.4", online.fabricLoom)
        assertEquals("0.170.0+26.3", online.fabricApi["26.3"])
        assertEquals(listOf("26.3", "26.2"), minecraft!!.map { it.id })

        // Never read: the defaults.
        val never = VersionLookup(FileResponseCache(Files.createTempDirectory("fmv-cache"))) { null }.resolve(settings)
        assertEquals(VersionLookup.DEFAULTS.getProperty("fabric_loom"), never.fabricLoom)
        assertEquals(VersionLookup.DEFAULTS.getProperty("moddevgradle"), never.modDevGradle)
    }

    @Test
    fun fallsBackToTheDefaultsOffline() {
        val versions = VersionLookup { null }.resolve(both.copy(versions = listOf("26.2", "26.9")))
        assertEquals(VersionLookup.DEFAULTS.getProperty("fabric_loader"), versions.fabricLoader)
        assertEquals("0.161.0+26.2", versions.fabricApi["26.2"])
        assertNull(versions.neoforge["26.9"])
        assertEquals(
            listOf("Fabric API for Minecraft 26.9 (mc.26.9.fabric_api)", "NeoForge for Minecraft 26.9 (mc.26.9.neoforge)"),
            versions.missing(both.copy(versions = listOf("26.2", "26.9"))),
        )
    }

    @Test
    fun usesTheChannelOfThisPluginOrMavenLocal() {
        val release = PluginRepository.of("1.0.0") as PluginRepository.Published
        assertEquals("https://gahahawang.github.io/FastMultiversion/maven", release.url)
        assertEquals("latest.release", release.requested)
        // A pre-release: the dev channel, whose newest pre-release or release the project uses.
        val dev = PluginRepository.of("1.1.0-dev.3") as PluginRepository.Published
        assertEquals("https://gahahawang.github.io/FastMultiversion/maven-dev", dev.url)
        assertEquals("latest.integration", dev.requested)
        assertEquals(PluginRepository.MavenLocal("1.0.0-SNAPSHOT"), PluginRepository.of("1.0.0-SNAPSHOT"))
    }

    @Test
    fun generatesAProjectForSeveralLoaders() {
        val dir = sampleDir("TestMod")
        ProjectGenerator.generate(dir, both, VersionLookup { null }.resolve(both), PluginRepository.MavenLocal(pluginVersion()))

        val settings = dir.resolve("settings.gradle").readText()
        assertTrue(settings, settings.contains("mavenLocal()"))
        assertTrue(settings, settings.contains("id 'cc.gahaha.fastmultiversion' version '${pluginVersion()}'"))
        assertTrue(settings, settings.contains("versions '26.1.2', '26.2', '26.3'"))
        assertTrue(settings, settings.contains("multiversion {\n    versions '26.1.2', '26.2', '26.3'\n    loaders 'fabric', 'neoforge'\n}"))
        assertTrue(dir.resolve("gradle/fabric/fabric.gradle").readText().startsWith("// The build script of every :<version>:fabric project;"))
        assertTrue(dir.resolve("src/fabric/resources/fabric.mod.json").readText().contains("\"com.example.testmod.fabric.TestModFabric\""))
        // Fabric is the main loader: the common projects use Loom, and the one file of access changes is a class tweaker.
        assertTrue(dir.resolve("gradle/common/common.gradle").readText().contains("net.fabricmc.fabric-loom"))
        assertEquals("classTweaker v1 official\n", dir.resolve("src/main/resources/testmod.classtweaker").readText())
        assertMixins(dir, "src/fabric/resources/fabric.mod.json", "src/neoforge/resources/META-INF/neoforge.mods.toml")
        // The scripts give Loom and ModDevGradle the access files FastMultiversion makes, and the common projects Mixin.
        assertTrue(dir.resolve("gradle/fabric/fabric.gradle").readText().contains("    accessWidenerPath = multiversion.accessWidener\n"))
        assertTrue(dir.resolve("gradle/neoforge/neoforge.gradle").readText().contains("    accessTransformers = multiversion.accessTransformers\n"))
        val common = dir.resolve("gradle/common/common.gradle").readText()
        assertTrue(common, common.contains("    accessWidenerPath = multiversion.accessWidener\n")
            && common.contains("compileOnly \"net.fabricmc:sponge-mixin:\${mixin_version}\"")
            && common.contains("compileOnly \"io.github.llamalad7:mixinextras-common:\${mixinextras_version}\""))
        val gradleProperties = dir.resolve("gradle.properties").readText()
        assertTrue(gradleProperties, gradleProperties.contains("mixin_version=0.17.5+mixin.0.8.7\nmixinextras_version=0.5.5\n"))
        assertFalse(dir.resolve("src/main/resources/META-INF/accesstransformer.cfg").exists())
        assertTrue(dir.resolve("src/fabric/resources/fabric.mod.json").readText().contains("\"accessWidener\": \"testmod.classtweaker\","))
        val properties = dir.resolve("gradle.properties").readText()
        assertTrue(properties, properties.contains("mc.26.2.fabric_api=0.161.0+26.2"))
        assertTrue(properties, properties.contains("mc.26.1.2.range_fabric=>=26.1.2 <26.2"))
        assertTrue(properties, properties.contains("mc.26.3.range_neoforge=[26.3,26.4)"))
        val mod = dir.resolve("src/main/java/com/example/testmod/TestMod.java").readText()
        assertTrue(mod, mod.contains("if (Mc.is(\"[26.3,)\"))"))
        for (path in listOf(
            "build.gradle", ".gitignore", "gradle/common/common.gradle", "gradle/fabric/fabric.gradle", "gradle/neoforge/neoforge.gradle",
            "src/fabric/java/com/example/testmod/fabric/TestModFabric.java", "src/fabric/resources/fabric.mod.json",
            "src/neoforge/java/com/example/testmod/neoforge/TestModNeoForge.java", "src/neoforge/resources/META-INF/neoforge.mods.toml",
            "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
        )) {
            assertTrue(path, dir.resolve(path).exists())
        }
        assertNoPlaceholders(dir)
    }

    @Test
    fun writesTheLicenseAndTheOptionalSettings() {
        // By default: All Rights Reserved, held by the mod; no optional settings.
        val plain = sampleDir("Plain")
        ProjectGenerator.generate(plain, both, VersionLookup { null }.resolve(both), PluginRepository.Published("1.0.0"))
        val year = java.time.Year.now().value
        assertEquals("Copyright (c) $year Test Mod\nAll rights reserved.\n", plain.resolve("LICENSE").readText())
        val plainJson = plain.resolve("src/fabric/resources/fabric.mod.json").readText()
        assertTrue(plainJson, plainJson.contains("\"description\": \"\",\n    \"authors\": [],\n    \"license\": \"All-Rights-Reserved\","))
        assertFalse(plainJson, plainJson.contains("contact"))
        val plainToml = plain.resolve("src/neoforge/resources/META-INF/neoforge.mods.toml").readText()
        assertTrue(plainToml, plainToml.contains("license = \"All-Rights-Reserved\"\n\n[[mods]]"))
        assertTrue(plainToml, plainToml.contains("displayName = \"Test Mod\"\ndescription = \"Test Mod\"\n"))

        // Chosen ones, with text that JSON, TOML and the build's template expansion all need escaped.
        val settings = both.copy(projectName = "Licensed", license = License.MIT, description = "Counts \"entities\" for \$5 \\ more",
            authors = ModSettings.parseAuthors(" Alex,  Steve ,"), website = "https://example.com", repository = "https://github.com/example/mod",
            issueTracker = "https://github.com/example/mod/issues")
        val dir = sampleDir("Licensed")
        ProjectGenerator.generate(dir, settings, VersionLookup { null }.resolve(settings), PluginRepository.Published("1.0.0"))

        assertTrue(dir.resolve("LICENSE").readText().startsWith("The MIT License (MIT)\n\nCopyright (c) $year Alex, Steve\n"))
        val json = dir.resolve("src/fabric/resources/fabric.mod.json").readText()
        for (line in listOf(
            "\"description\": \"Counts \\\\\"entities\\\\\" for \\\$5 \\\\\\\\ more\",",
            "\"authors\": [\"Alex\", \"Steve\"],",
            "\"contact\": {\n        \"homepage\": \"https://example.com\",\n        \"sources\": \"https://github.com/example/mod\",\n"
                + "        \"issues\": \"https://github.com/example/mod/issues\"\n    },\n    \"license\": \"MIT\",",
        )) {
            assertTrue(line + "\n" + json, json.contains(line))
        }
        val toml = dir.resolve("src/neoforge/resources/META-INF/neoforge.mods.toml").readText()
        for (line in listOf("license = \"MIT\"\nissueTrackerURL = \"https://github.com/example/mod/issues\"\n",
            "displayURL = \"https://example.com\"\nauthors = \"Alex, Steve\"\ndescription = \"Counts \\\\\"entities\\\\\" for \\\$5 \\\\\\\\ more\"\n")) {
            assertTrue(line + "\n" + toml, toml.contains(line))
        }
        for (license in License.entries) {
            assertFalse(license.id, license.text("Alex").contains("{{"))
        }
        assertTrue(License.CC_BY_4.text("Alex", 2026).startsWith("Copyright (c) 2026 Alex\n\nAttribution 4.0 International\n"))
    }

    @Test
    fun generatesAProjectWithNeoForgeAsTheMainLoader() {
        val settings = both.copy(projectName = "NeoForgeMain", versions = listOf("26.1.2", "26.3"), mainLoader = "neoforge")
        val dir = sampleDir("NeoForgeMain")
        val versions = VersionLookup { RESPONSES[it] }.resolve(settings)
        ProjectGenerator.generate(dir, settings, versions, PluginRepository.MavenLocal(pluginVersion()))

        // The common projects compile the shared code against NeoForm; the access changes are an access transformer.
        val common = dir.resolve("gradle/common/common.gradle").readText()
        assertTrue(common, common.contains("apply plugin: 'net.neoforged.moddev'") && common.contains("neoFormVersion = project.property(\"mc.\${multiversion.version}.neoform\")"))
        val properties = dir.resolve("gradle.properties").readText()
        assertTrue(properties, properties.contains("mc.26.3.neoform=26.3-1") && properties.contains("mc.26.1.2.neoform=26.1.2-1"))
        val transformer = dir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText()
        assertEquals("", transformer)
        assertTrue(common, common.contains("    accessTransformers = multiversion.accessTransformers\n")
            && common.contains("compileOnly \"net.fabricmc:sponge-mixin:\${mixin_version}\""))
        assertFalse(dir.resolve("src/main/resources/testmod.classtweaker").exists())
        // Fabric gets an access widener made from it.
        assertTrue(dir.resolve("src/fabric/resources/fabric.mod.json").readText().contains("\"accessWidener\": \"testmod.accesswidener\","))
        assertNoPlaceholders(dir)
    }

    @Test
    fun generatesAProjectForOneLoader() {
        // One loader: a project per version (no loader projects), everything in src/main, the mod class is the entrypoint.
        val fabric = both.copy(projectName = "FabricOnly", versions = listOf("26.2"), loaders = listOf("fabric"))
        val dir = sampleDir("FabricOnly")
        ProjectGenerator.generate(dir, fabric, VersionLookup { null }.resolve(fabric), PluginRepository.Published("1.0.0"))

        for (path in listOf("gradle/common", "gradle/fabric", "gradle/neoforge", "src/fabric", "src/neoforge")) {
            assertFalse(path, dir.resolve(path).exists())
        }
        val build = dir.resolve("build.gradle").readText()
        assertTrue(build, build.contains("net.fabricmc.fabric-loom") && !build.contains("moddev"))
        val settings = dir.resolve("settings.gradle").readText()
        assertTrue(settings, settings.contains("maven { url = \"https://gahahawang.github.io/FastMultiversion/maven\" }"))
        assertTrue(settings, settings.contains("id 'cc.gahaha.fastmultiversion' version 'latest.release'"))
        assertTrue(settings, settings.contains("multiversion {\n    versions '26.2'\n}"))
        assertFalse(settings, settings.contains("loaders"))
        val script = dir.resolve("gradle/version/version.gradle").readText()
        assertTrue(script, script.startsWith("// The build script of every :<version> project;") && script.contains("fabric-loom"))
        val mod = dir.resolve("src/main/java/com/example/testmod/TestMod.java").readText()
        assertTrue(mod, mod.contains("public final class TestMod implements ModInitializer"))
        assertTrue(mod, mod.contains("LOGGER.info(\"Test Mod for Minecraft 26.2\");") && !mod.contains("Mc"))
        val modJson = dir.resolve("src/main/resources/fabric.mod.json").readText()
        assertTrue(modJson, modJson.contains("\"com.example.testmod.TestMod\""))
        assertEquals("classTweaker v1 official\n", dir.resolve("src/main/resources/testmod.classtweaker").readText())
        assertTrue(modJson, modJson.contains("\"accessWidener\": \"testmod.classtweaker\","))
        assertMixins(dir, "src/main/resources/fabric.mod.json", null)
        assertTrue(script, script.contains("    accessWidenerPath = multiversion.accessWidener\n"))
        assertFalse(dir.resolve("gradle.properties").readText().contains("mixin_version")) // no common projects
        assertNoPlaceholders(dir)

        val neoforge = both.copy(projectName = "NeoForgeOnly", versions = listOf("26.2", "26.3"), loaders = listOf("neoforge"))
        val neoDir = sampleDir("NeoForgeOnly")
        ProjectGenerator.generate(neoDir, neoforge, VersionLookup { null }.resolve(neoforge), PluginRepository.Published("1.0.0"))

        assertFalse(neoDir.resolve("src/neoforge").exists())
        val neoBuild = neoDir.resolve("build.gradle").readText()
        assertTrue(neoBuild, neoBuild.contains("net.neoforged.moddev") && !neoBuild.contains("fabric-loom"))
        assertTrue(neoDir.resolve("gradle/version/version.gradle").readText().contains("net.neoforged.moddev"))
        assertEquals("", neoDir.resolve("src/main/resources/META-INF/accesstransformer.cfg").readText())
        assertFalse(neoDir.resolve("src/main/resources/testmod.classtweaker").exists())
        assertMixins(neoDir, null, "src/main/resources/META-INF/neoforge.mods.toml")
        assertTrue(neoDir.resolve("gradle/version/version.gradle").readText().contains("    accessTransformers = multiversion.accessTransformers\n"))
        val neoMod = neoDir.resolve("src/main/java/com/example/testmod/TestMod.java").readText()
        assertTrue(neoMod, neoMod.contains("@Mod(TestMod.MOD_ID)") && neoMod.contains("public TestMod() {"))
        assertTrue(neoMod, neoMod.contains("import cc.gahaha.fastmultiversion.Mc;") && neoMod.contains("if (Mc.is(\"[26.3,)\"))"))
        assertNoPlaceholders(neoDir)
    }

    /** An empty mixin package, its config, and the config named in the metadata files given. */
    private fun assertMixins(dir: Path, fabricModJson: String?, modsToml: String?) {
        assertTrue(dir.resolve("src/main/java/com/example/testmod/mixin").isDirectory())
        val config = dir.resolve("src/main/resources/testmod.mixins.json").readText()
        assertTrue(config, config.contains("\"package\": \"com.example.testmod.mixin\",") && config.contains("\"compatibilityLevel\": \"JAVA_25\","))
        if (fabricModJson != null) {
            val json = dir.resolve(fabricModJson).readText()
            assertTrue(json, json.contains("    \"mixins\": [\n        \"testmod.mixins.json\"\n    ],\n"))
        }
        if (modsToml != null) {
            val toml = dir.resolve(modsToml).readText()
            assertTrue(toml, toml.contains("[[mixins]]\nconfig = \"testmod.mixins.json\"\n"))
        }
    }

    private fun assertNoPlaceholders(dir: Path) {
        Files.walk(dir).use { files ->
            files.filter { Files.isRegularFile(it) && !it.toString().endsWith(".jar") }.forEach { file ->
                assertFalse(file.toString(), file.readText().contains("{{"))
            }
        }
    }

    private fun pluginVersion(): String = System.getProperty("fastmultiversion.version", "0.0.0") + "-SNAPSHOT".takeUnless {
        System.getProperty("fastmultiversion.version", "").endsWith("-SNAPSHOT")
    }.orEmpty()

    /** In build/wizard-sample, so that a sample project can be built by hand after the tests. */
    private fun sampleDir(name: String): Path {
        val dir = Path.of(System.getProperty("fastmultiversion.sample") ?: Files.createTempDirectory("fmv-sample").toString(), name)
        if (dir.exists()) {
            dir.toFile().deleteRecursively()
        }
        return dir
    }

    private companion object {
        fun metadata(vararg versions: String) =
            "<metadata><versioning><versions>" + versions.joinToString("") { "<version>$it</version>" } + "</versions></versioning></metadata>"

        /** What the loaders' repositories answer. */
        val RESPONSES = mapOf(
            VersionLookup.FABRIC_LOOM to metadata("1.19.3", "1.19.4", "1.20.0-alpha.30", "1.20.0-alpha.31"),
            VersionLookup.MOD_DEV_GRADLE to metadata("2.0.147", "2.0.148", "2.1.0-beta.2"),
            VersionLookup.FABRIC_LOADER to """[{"version":"0.21.0-beta","stable":false},{"version":"0.20.9","stable":true}]""",
            VersionLookup.FABRIC_API to metadata("0.161.0+26.2", "0.161.9+26.2", "0.161.10+26.2", "0.170.0+26.3", "0.170.1+26.4"),
            VersionLookup.NEOFORGE to metadata("26.2.0.93", "26.2.0.101", "26.2.0.110-beta", "26.1.2.126", "26.3.0.1-beta"),
            VersionLookup.NEOFORM to metadata("26.1.2-1", "26.2-1", "26.2-2", "26.3-rc-1-2", "26.3-1"),
            VersionLookup.SPONGE_MIXIN to metadata("0.17.5+mixin.0.8.7", "0.17.10+mixin.0.8.7", "0.17.9+mixin.0.8.7"),
            VersionLookup.MIXIN_EXTRAS to metadata("0.5.4", "0.5.5", "0.6.0-beta.1"),
        )

        const val MANIFEST = """{"versions": [{"id": "26.3", "type": "release"}, {"id": "26.2", "type": "release"}]}"""
    }
}
