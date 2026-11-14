package cc.gahaha.fastmultiversion.intellij.wizard

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
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
    fun looksUpTheNewestVersions() {
        val responses = mapOf(
            VersionLookup.FABRIC_LOADER to """[{"version":"0.21.0-beta","stable":false},{"version":"0.20.9","stable":true}]""",
            VersionLookup.FABRIC_API to metadata("0.161.0+26.2", "0.161.9+26.2", "0.161.10+26.2", "0.170.0+26.3", "0.170.1+26.4"),
            VersionLookup.NEOFORGE to metadata("26.2.0.93", "26.2.0.101", "26.2.0.110-beta", "26.1.2.126", "26.3.0.1-beta"),
        )
        val settings = both.copy(versions = listOf("26.1.2", "26.2", "26.3"))
        val versions = VersionLookup { responses[it] }.resolve(settings)

        assertEquals("0.20.9", versions.fabricLoader)
        assertEquals("0.161.10+26.2", versions.fabricApi["26.2"])
        assertEquals("26.2.0.101", versions.neoforge["26.2"]) // releases before betas
        assertEquals("26.3.0.1-beta", versions.neoforge["26.3"]) // a beta if that is all there is
        assertEquals("26.1.2.126", versions.neoforge["26.1.2"])
        assertEquals("0.155.3+26.1.2", versions.fabricApi["26.1.2"]) // not listed: the default
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
    fun usesTheReleaseOfThisPluginOrMavenLocal() {
        assertEquals(PluginRepository.Published("1.0.0"), PluginRepository.of("1.0.0"))
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
        assertTrue(settings, settings.contains("loaders 'fabric', 'neoforge'"))
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
    fun generatesAProjectForOneLoader() {
        val fabric = both.copy(projectName = "FabricOnly", versions = listOf("26.2"), loaders = listOf("fabric"))
        val dir = sampleDir("FabricOnly")
        ProjectGenerator.generate(dir, fabric, VersionLookup { null }.resolve(fabric), PluginRepository.Published("1.0.0"))

        assertFalse(dir.resolve("gradle/common/common.gradle").exists()) // one loader owns src/main itself
        assertFalse(dir.resolve("gradle/neoforge").exists())
        val build = dir.resolve("build.gradle").readText()
        assertTrue(build, build.contains("net.fabricmc.fabric-loom") && !build.contains("moddev"))
        val settings = dir.resolve("settings.gradle").readText()
        assertTrue(settings, settings.contains("maven { url = \"https://gahahawang.github.io/FastMultiversion/maven\" }"))
        assertTrue(settings, settings.contains("id 'cc.gahaha.fastmultiversion' version '1.0.0'"))
        assertTrue(settings, settings.contains("loaders 'fabric'"))
        val mod = dir.resolve("src/main/java/com/example/testmod/TestMod.java").readText()
        assertTrue(mod, mod.contains("LOGGER.info(\"Test Mod for Minecraft 26.2\");") && !mod.contains("Mc.is("))
        assertNoPlaceholders(dir)
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

    private fun metadata(vararg versions: String) =
        "<metadata><versioning><versions>" + versions.joinToString("") { "<version>$it</version>" } + "</versions></versioning></metadata>"
}
