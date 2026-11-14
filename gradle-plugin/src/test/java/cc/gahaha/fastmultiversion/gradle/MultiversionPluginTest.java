package cc.gahaha.fastmultiversion.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.gahaha.fastmultiversion.apidb.ApiDatabase;
import cc.gahaha.fastmultiversion.javac.TestCompiler;
import cc.gahaha.fastmultiversion.versioning.McVersion;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;

/**
 * Builds small multi-version mods with Gradle TestKit. Project directories live under build/testkit and are
 * not deleted afterwards, since Windows keeps files of the TestKit daemon locked.
 */
class MultiversionPluginTest {
    private static final Path TESTKIT = Path.of("build", "testkit").toAbsolutePath();

    private static Path newProject(String name) throws Exception {
        Files.createDirectories(TESTKIT);
        return Files.createTempDirectory(TESTKIT, name);
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static GradleRunner gradle(Path project, String... arguments) {
        List<String> args = new ArrayList<>(List.of(arguments));
        args.add("--stacktrace");
        return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath().withArguments(args);
    }

    /** A project for "26.1" and "26.2", with a made-up Minecraft jar per version in libs/. */
    private static Path syntheticProject(String name) throws Exception {
        Path project = newProject(name);
        Path libs = project.resolve("libs");
        Files.createDirectories(libs);
        TestCompiler.jar(libs, "mc-26.1", """
                package mc;
                public class Registry { public static String id(String ns, String path) { return ns + ":" + path; } }
                """, """
                package mc;
                public class OldThing { public String name() { return "old-thing"; } }
                """);
        TestCompiler.jar(libs, "mc-26.2", """
                package mc;
                public class Registry { public static String of(String ns, String path) { return ns + "/" + path; } }
                """, """
                package mc;
                public class NewThing { public String name() { return "new-thing"; } }
                """);
        write(project.resolve("settings.gradle"), """
                plugins { id 'cc.gahaha.fastmultiversion' }
                rootProject.name = 'testmod'
                multiversion {
                    versions '26.2', '26.1'
                }
                """);
        write(project.resolve("version.gradle"), """
                dependencies {
                    compileOnly files(rootProject.file("libs/mc-${multiversion.version}.jar"))
                }
                println "SHARED ${multiversion.version} ${sourceSets.main.java.srcDirs.contains(rootProject.file('src/main/java'))}"
                """);
        write(project.resolve("src/main/java/mod/Uses.java"), """
                package mod;

                import cc.gahaha.fastmultiversion.Mc;
                import mc.*;

                public class Uses {
                    public static String run() {
                        if (Mc.is("[26.1,26.2)")) {
                            return new OldThing().name() + " " + Registry.id("a", "b");
                        }
                        return new NewThing().name() + " " + Registry.of("a", "b");
                    }
                }
                """);
        return project;
    }

    @Test
    void compilesTheSharedSourcesForEveryVersion() throws Exception {
        Path project = syntheticProject("synthetic");
        BuildResult result = gradle(project, "compileAllVersions").build();

        assertTrue(result.getOutput().contains("multiversion: API database for Minecraft [26.1, 26.2]"), result.getOutput());
        assertEquals("old-thing a:b", run(project, "26.1", project.resolve("libs/mc-26.1.jar")));
        assertEquals("new-thing a/b", run(project, "26.2", project.resolve("libs/mc-26.2.jar")));
        assertEquals("versions=26.1,26.2\nactive=26.2\napidb=build/multiversion/api.mcapi\n",
                Files.readString(project.resolve("build/multiversion/project.properties")));
    }

    @Test
    void reportsApiThatSomeVersionsLack() throws Exception {
        Path project = syntheticProject("unguarded");
        write(project.resolve("src/main/java/mod/Bad.java"), """
                package mod;
                public class Bad {
                    static String id() { return mc.Registry.of("a", "b"); }
                }
                """);
        BuildResult result = gradle(project, ":26.2:compileJava").buildAndFail();

        assertTrue(result.getOutput().contains("[multiversion] 'Registry.of(String, String)' only exists in [26.2,), "
                + "but this code runs in [26.1,)"), result.getOutput());
    }

    @Test
    void ideSyncGivesTheSharedSourcesToTheActiveVersionOnly() throws Exception {
        Path project = syntheticProject("sync");

        String normal = gradle(project, "help").build().getOutput();
        assertTrue(normal.contains("SHARED 26.1 true") && normal.contains("SHARED 26.2 true"), normal);

        String sync = gradle(project, "help", "-Didea.sync.active=true").build().getOutput();
        assertTrue(sync.contains("SHARED 26.1 false") && sync.contains("SHARED 26.2 true"), sync);

        String other = gradle(project, "help", "-Didea.sync.active=true", "-Pmultiversion.active=26.1").build().getOutput();
        assertTrue(other.contains("SHARED 26.1 true") && other.contains("SHARED 26.2 false"), other);
        assertTrue(Files.readString(project.resolve("build/multiversion/project.properties")).contains("active=26.1"));
    }

    @Test
    void buildsEachLoaderForEachVersion() throws Exception {
        Path project = syntheticProject("loaders");
        Path libs = project.resolve("libs");
        TestCompiler.jar(libs, "fabric-api", "package fab; public class Hooks { public static String name() { return \"fabric\"; } }");
        TestCompiler.jar(libs, "neoforge-api", "package neo; public class Events { public static String name() { return \"neoforge\"; } }");
        write(project.resolve("settings.gradle"), """
                plugins { id 'cc.gahaha.fastmultiversion' }
                rootProject.name = 'loadermod'
                multiversion {
                    versions '26.1', '26.2'
                    loaders 'fabric', 'neoforge'
                }
                """);
        for (String loader : List.of("fabric", "neoforge")) {
            // The same group and name in every version, as real mods have: cc.example:fabric.
            write(project.resolve(loader + ".gradle"), """
                    group = 'cc.example'
                    version = "1.0-${multiversion.version}"
                    dependencies {
                        compileOnly files(rootProject.file("libs/mc-${multiversion.version}.jar"), rootProject.file("libs/%s-api.jar"))
                    }
                    def dirs = sourceSets.main.java.srcDirs
                    println "SOURCES ${multiversion.version} ${multiversion.loader} main=${dirs.contains(rootProject.file('src/main/java'))} own=${dirs.contains(rootProject.file('src/%s/java'))}"
                    """.formatted(loader, loader));
        }
        write(project.resolve("common.gradle"), """
                dependencies {
                    compileOnly files(rootProject.file("libs/mc-${multiversion.version}.jar"))
                }
                println "SOURCES ${multiversion.version} common main=${sourceSets.main.java.srcDirs.contains(rootProject.file('src/main/java'))}"
                """);
        write(project.resolve("src/fabric/java/mod/fabric/Entry.java"), """
                package mod.fabric;
                public class Entry { public static String run() { return fab.Hooks.name() + " " + mod.Uses.run(); } }
                """);
        write(project.resolve("src/neoforge/java/mod/neoforge/Entry.java"), """
                package mod.neoforge;
                public class Entry { public static String run() { return neo.Events.name() + " " + mod.Uses.run(); } }
                """);

        gradle(project, "compileAllVersions").build();
        assertEquals("fabric old-thing a:b", runEntry(project, "26.1", "fabric", libs.resolve("mc-26.1.jar"), libs.resolve("fabric-api.jar")));
        assertEquals("neoforge new-thing a/b", runEntry(project, "26.2", "neoforge", libs.resolve("mc-26.2.jar"), libs.resolve("neoforge-api.jar")));
        for (String kind : List.of("common", "fabric", "neoforge")) {
            ApiDatabase db = ApiDatabase.read(project.resolve("build/multiversion/" + kind + "/api.mcapi"));
            assertEquals(List.of(McVersion.parse("26.1"), McVersion.parse("26.2")), db.versions(), kind);
        }
        assertTrue(Files.isRegularFile(project.resolve("versions/26.1/common/build/classes/java/main/mod/Uses.class")),
                "the common projects compile src/main without a loader");
        assertTrue(Files.readString(project.resolve("build/multiversion/project.properties"))
                .contains("loaders=fabric,neoforge\nactiveLoader=fabric\ncommon=true\napidb=build/multiversion/common/api.mcapi"));

        // In the IDE: src/main only in the active version's common project, src/<loader> in its loader project.
        String sync = gradle(project, "help", "-Didea.sync.active=true").build().getOutput();
        for (String line : List.of("SOURCES 26.2 common main=true", "SOURCES 26.2 fabric main=false own=true",
                "SOURCES 26.2 neoforge main=false own=true", "SOURCES 26.1 common main=false",
                "SOURCES 26.1 fabric main=false own=false", "SOURCES 26.1 neoforge main=false own=false")) {
            assertTrue(sync.contains(line), line + "\n" + sync);
        }

        // The IDE's neoforge module sees src/main through the common project...
        gradle(project, ":26.2:neoforge:compileJava", "-Didea.sync.active=true").build();
        // ...but nothing of Fabric.
        write(project.resolve("src/neoforge/java/mod/neoforge/Leak.java"), """
                package mod.neoforge;
                public class Leak { static String run() { return mod.fabric.Entry.run(); } }
                """);
        String leak = gradle(project, ":26.2:neoforge:compileJava", "-Didea.sync.active=true").buildAndFail().getOutput();
        assertTrue(leak.contains("package mod.fabric does not exist"), leak);
        Files.delete(project.resolve("src/neoforge/java/mod/neoforge/Leak.java"));
    }

    @Test
    void linksScriptsInTheirOwnDirectoriesToTheActiveVersionInTheIde() throws Exception {
        Path project = syntheticProject("scriptdirs");
        write(project.resolve("settings.gradle"), """
                plugins { id 'cc.gahaha.fastmultiversion' }
                multiversion {
                    versions '26.1', '26.2'
                    loaders 'fabric', 'neoforge'
                }
                """);
        for (String kind : List.of("common", "fabric", "neoforge")) {
            write(project.resolve("gradle/" + kind + "/" + kind + ".gradle"), """
                    dependencies {
                        compileOnly files(rootProject.file("libs/mc-${multiversion.version}.jar"))
                    }
                    println "SCRIPT ${multiversion.version} %s linked=${sourceSets.main.resources.srcDirs.contains(rootProject.file('gradle/%s'))}"
                    """.formatted(kind, kind));
        }

        // Builds find the scripts there and never ship them.
        String build = gradle(project, "compileAllVersions").build().getOutput();
        assertTrue(build.contains("SCRIPT 26.2 fabric linked=false"), build);
        assertFalse(build.contains("linked=true"), build);

        // In the IDE, each script's directory belongs to the active version's project of its kind.
        String sync = gradle(project, "help", "-Didea.sync.active=true").build().getOutput();
        for (String line : List.of("SCRIPT 26.2 common linked=true", "SCRIPT 26.2 fabric linked=true",
                "SCRIPT 26.2 neoforge linked=true", "SCRIPT 26.1 common linked=false",
                "SCRIPT 26.1 fabric linked=false", "SCRIPT 26.1 neoforge linked=false")) {
            assertTrue(sync.contains(line), line + "\n" + sync);
        }
    }

    @Test
    void severalLoadersNeedACommonProject() throws Exception {
        Path project = syntheticProject("nocommon");
        write(project.resolve("settings.gradle"), """
                plugins { id 'cc.gahaha.fastmultiversion' }
                multiversion {
                    versions '26.1', '26.2'
                    loaders 'fabric', 'neoforge'
                }
                """);
        write(project.resolve("fabric.gradle"), "");
        write(project.resolve("neoforge.gradle"), "");
        String output = gradle(project, "help").buildAndFail().getOutput();
        assertTrue(output.contains("with several loaders, add common.gradle"), output);
    }

    private static String runEntry(Path project, String version, String loader, Path... runtime) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(project.resolve("versions/" + version + "/" + loader + "/build/classes/java/main").toUri().toURL());
        for (Path jar : runtime) {
            urls.add(jar.toUri().toURL());
        }
        try (URLClassLoader loaderClassLoader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            return (String) Class.forName("mod." + loader + ".Entry", true, loaderClassLoader).getMethod("run").invoke(null);
        }
    }

    @Test
    void buildsAgainstRealMinecraftJars() throws Exception {
        String jars26_1 = String.join("', '", realClasspath("26.1"));
        String jars26_3 = String.join("', '", realClasspath("26.3"));
        Path project = newProject("real");
        write(project.resolve("settings.gradle"), """
                plugins { id 'cc.gahaha.fastmultiversion' }
                rootProject.name = 'realmod'
                multiversion {
                    versions '26.1', '26.3'
                }
                """);
        write(project.resolve("version.gradle"), """
                def jars = ['26.1': ['%s'], '26.3': ['%s']]
                dependencies {
                    compileOnly files(jars[multiversion.version])
                }
                """.formatted(jars26_1, jars26_3));
        write(project.resolve("src/main/java/mod/Textures.java"), """
                package mod;

                import cc.gahaha.fastmultiversion.IfMc;
                import cc.gahaha.fastmultiversion.Mc;
                import com.mojang.blaze3d.pipeline.RenderTarget;
                import com.mojang.blaze3d.textures.GpuTexture;
                import com.mojang.renderpearl.api.textures.*;

                public class Textures {
                    public static Object colorTexture(RenderTarget target) {
                        if (Mc.is("[26.3,)")) {
                            GpuTextureView view = target.getColorTextureView();
                            return view;
                        } else {
                            GpuTexture texture = target.getColorTexture();
                            return texture;
                        }
                    }

                    @IfMc("[26.1,26.3)")
                    public static String describe(GpuTexture texture) { return "blaze3d"; }
                }
                """);
        gradle(project, "compileAllVersions").build();

        write(project.resolve("src/main/java/mod/Careless.java"), """
                package mod;
                import com.mojang.blaze3d.pipeline.RenderTarget;
                public class Careless {
                    public static Object colorTexture(RenderTarget target) { return target.getColorTexture(); }
                }
                """);
        String output = gradle(project, ":26.3:compileJava").buildAndFail().getOutput();
        assertTrue(output.contains("[multiversion] 'RenderTarget.getColorTexture()' only exists in [26.3,), "
                + "but this code runs in [26.1,)"), output);
    }

    // ---- helpers

    /** Calls mod.Uses.run() from a version's compiled classes, with that version's Minecraft jar. */
    private static String run(Path project, String version, Path minecraft) throws Exception {
        Path classes = project.resolve("versions/" + version + "/build/classes/java/main");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL(), minecraft.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Method method = Class.forName("mod.Uses", true, loader).getMethod("run");
            return (String) method.invoke(null);
        }
    }

    /** Minecraft's merged jar and its DataFixerUpper from the Loom and Gradle caches; skips the test without them. */
    private static List<String> realClasspath(String version) throws Exception {
        Path caches = Path.of(System.getProperty("user.home"), ".gradle", "caches");
        Path loom = caches.resolve(Path.of("fabric-loom", version));
        assumeTrue(Files.isRegularFile(loom.resolve("minecraft-merged.jar")), "Minecraft " + version + " is not in the Loom cache");
        Matcher dfu = Pattern.compile("\"com\\.mojang:datafixerupper:([^\"]+)\"")
                .matcher(Files.readString(loom.resolve("mojang_minecraft_info.json")));
        assumeTrue(dfu.find(), "no DataFixerUpper in the library list of " + version);
        Path dir = caches.resolve(Path.of("modules-2", "files-2.1", "com.mojang", "datafixerupper", dfu.group(1)));
        assumeTrue(Files.isDirectory(dir), "DataFixerUpper " + dfu.group(1) + " is not in the Gradle cache");
        try (Stream<Path> files = Files.walk(dir)) {
            Path jar = files.filter(p -> p.getFileName().toString().equals("datafixerupper-" + dfu.group(1) + ".jar"))
                    .findFirst().orElse(null);
            assumeTrue(jar != null, "DataFixerUpper " + dfu.group(1) + " is not in the Gradle cache");
            return List.of(slashes(loom.resolve("minecraft-merged.jar")), slashes(jar));
        }
    }

    private static String slashes(Path path) {
        return path.toString().replace('\\', '/');
    }
}
