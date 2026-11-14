package cc.gahaha.fastmultiversion.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.gahaha.fastmultiversion.apidb.generator.ApiDbGenerator;
import cc.gahaha.fastmultiversion.apidb.generator.VersionJars;
import cc.gahaha.fastmultiversion.versioning.McVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The range check against the real Minecraft 26.1 and 26.3 jars from the Loom cache; skipped without them. */
class RealMinecraftRangeTest {
    @TempDir
    static Path temp;

    private static Path database;

    private static final Path GRADLE_CACHES = Path.of(System.getProperty("user.home"), ".gradle", "caches");

    private static Path minecraft(String version) {
        return GRADLE_CACHES.resolve(Path.of("fabric-loom", version, "minecraft-merged.jar"));
    }

    /**
     * The Minecraft jar plus DataFixerUpper, which Minecraft's signatures need (26.1 uses DFU 9, 26.3 uses
     * DFU 10). The DFU version comes from the version's library list in the Loom cache.
     */
    private static Path[] classpath(String version) throws Exception {
        String info = Files.readString(GRADLE_CACHES.resolve(Path.of("fabric-loom", version, "mojang_minecraft_info.json")));
        Matcher dfu = Pattern.compile("\"com\\.mojang:datafixerupper:([^\"]+)\"").matcher(info);
        assumeTrue(dfu.find(), "no DataFixerUpper in the library list of " + version);
        Path dir = GRADLE_CACHES.resolve(Path.of("modules-2", "files-2.1", "com.mojang", "datafixerupper", dfu.group(1)));
        String jarName = "datafixerupper-" + dfu.group(1) + ".jar";
        assumeTrue(Files.isDirectory(dir), "DataFixerUpper " + dfu.group(1) + " is not in the Gradle cache");
        try (Stream<Path> files = Files.walk(dir)) {
            Path jar = files.filter(p -> p.getFileName().toString().equals(jarName)).findFirst().orElse(null);
            assumeTrue(jar != null, "DataFixerUpper " + dfu.group(1) + " is not in the Gradle cache");
            return new Path[] {minecraft(version), jar};
        }
    }

    @BeforeAll
    static void buildDatabase() throws Exception {
        assumeTrue(Files.isRegularFile(minecraft("26.1")) && Files.isRegularFile(minecraft("26.3")),
                "Minecraft 26.1 and 26.3 are not in the Loom cache");
        database = temp.resolve("api.mcapi");
        ApiDbGenerator.generate(List.of(
                new VersionJars(McVersion.parse("26.1"), List.of(classpath("26.1"))),
                new VersionJars(McVersion.parse("26.3"), List.of(classpath("26.3"))))).write(database);
    }

    private static final String GUARDED = """
            package mod;

            import cc.gahaha.fastmultiversion.IfMc;
            import cc.gahaha.fastmultiversion.Mc;
            import com.mojang.blaze3d.pipeline.RenderTarget;
            import com.mojang.blaze3d.textures.GpuTexture;
            import com.mojang.renderpearl.api.textures.*;
            import net.minecraft.resources.Identifier;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;

            public class Guarded {
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

                @IfMc("[26.3,)")
                public static String describe(com.mojang.renderpearl.api.textures.GpuTexture texture) { return "renderpearl"; }

                public static void damage(ItemStack stack, ServerLevel level, ServerPlayer player) {
                    if (Mc.is("[26.3,)")) {
                        stack.hurtAndBreak(1, level, player, broken -> {});
                    } else {
                        stack.hurtAndBreak(1, level, player, broken -> {});
                    }
                }

                public static ItemStack diamonds() { return new ItemStack(Items.DIAMOND, 3); }

                public static Identifier id() { return Identifier.fromNamespaceAndPath("mod", "thing"); }
            }
            """;

    @ParameterizedTest
    @ValueSource(strings = {"26.1", "26.3"})
    void acceptsCorrectlyGuardedRealCode(String version) throws Exception {
        TestCompiler.target(temp, version).classpath(classpath(version)).apiDatabase(database)
                .source(GUARDED).compile().assertSuccess();
    }

    private static final String UNGUARDED = """
            package mod;

            import com.mojang.blaze3d.pipeline.RenderTarget;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.item.ItemStack;

            public class Unguarded {
                public static Object colorTexture(RenderTarget target) {
                    return target.getColorTexture();
                }

                public static void damage(ItemStack stack, ServerLevel level, ServerPlayer player) {
                    stack.hurtAndBreak(1, level, player, broken -> {});
                }
            }
            """;

    @ParameterizedTest
    @ValueSource(strings = {"26.1", "26.3"})
    void reportsRealApiChangesInEitherBuild(String version) throws Exception {
        String errors = TestCompiler.target(temp, version).classpath(classpath(version)).apiDatabase(database)
                .source(UNGUARDED).compile().assertFailure().errors();

        String usable = version.equals("26.1") ? "[26.1,26.3)" : "[26.3,)";
        assertEquals(2, errors.lines().filter(l -> l.startsWith("line ")).count(), errors);
        assertTrue(errors.contains("line 10: [multiversion] 'RenderTarget.getColorTexture()' only exists in " + usable
                + ", but this code runs in [26.1,)"), errors);
        assertTrue(errors.contains("line 14: [multiversion] 'ItemStack.hurtAndBreak(int, ServerLevel, ServerPlayer, Consumer<"
                + (version.equals("26.1") ? "Item" : "ItemStack") + ">)' exists in every version this code runs in, "
                + "but only has this form"), errors);
    }
}
