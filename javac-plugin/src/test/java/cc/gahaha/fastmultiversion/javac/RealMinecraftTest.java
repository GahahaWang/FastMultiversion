package cc.gahaha.fastmultiversion.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Compiles one mod source against the real Minecraft 26.1 and 26.3 jars that Fabric Loom has cached.
 * 26.3 moved {@code GpuTexture} from {@code com.mojang.blaze3d.textures} to the new package
 * {@code com.mojang.renderpearl.api.textures}, which does not exist at all in 26.1.
 * Skipped when the jars are not cached.
 */
class RealMinecraftTest {
    @TempDir
    Path temp;

    private static Path minecraft(String version) {
        return Path.of(System.getProperty("user.home"), ".gradle", "caches", "fabric-loom", version, "minecraft-merged.jar");
    }

    private static final String TEXTURES = """
            package mod;

            import cc.gahaha.fastmultiversion.IfMc;
            import cc.gahaha.fastmultiversion.Mc;
            import com.mojang.blaze3d.pipeline.RenderTarget;
            import com.mojang.blaze3d.textures.GpuTexture;
            import com.mojang.renderpearl.api.textures.*;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;

            public class Textures {
                public static Object colorTexture(RenderTarget target) {
                    if (Mc.is("[26.3,)")) {
                        com.mojang.renderpearl.api.textures.GpuTexture texture = target.getColorTexture();
                        GpuTextureView view = target.getColorTextureView();
                        return texture != null ? texture : view;
                    } else {
                        GpuTexture texture = target.getColorTexture();
                        return texture;
                    }
                }

                @IfMc("[26.1,26.3)")
                public static String describe(GpuTexture texture) { return "blaze3d"; }

                @IfMc("[26.3,)")
                public static String describe(com.mojang.renderpearl.api.textures.GpuTexture texture) { return "renderpearl"; }

                public static ItemStack diamonds() { return new ItemStack(Items.DIAMOND, 3); }

                public static String which() { return Mc.is("[26.3,)") ? "26.3+" : "older"; }
            }
            """;

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "26.1 | com/mojang/blaze3d/textures/GpuTexture | com/mojang/renderpearl | older",
            "26.3 | com/mojang/renderpearl/api/textures/GpuTexture | com/mojang/blaze3d/textures/GpuTexture | 26.3+"})
    void compilesAndLinksAgainstEachRealVersion(String version, String uses, String mustNotUse, String which) throws Exception {
        Path game = minecraft(version);
        assumeTrue(Files.isRegularFile(game), "Minecraft " + version + " is not in the Loom cache");

        var result = TestCompiler.target(temp, version).classpath(game).source(TEXTURES).compile().assertSuccess();
        assertTrue(result.mentions("mod.Textures", uses));
        assertFalse(result.mentions("mod.Textures", mustNotUse));
        assertFalse(result.mentions("mod.Textures", "cc/gahaha/fastmultiversion"));
        assertEquals(which, result.call("mod.Textures", "which", game));

        // Resolving every method signature against the real jar fails if any type is from another version.
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {result.classes().toUri().toURL(), game.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Method[] methods = Class.forName("mod.Textures", false, loader).getDeclaredMethods();
            assertEquals(1, Arrays.stream(methods).filter(m -> m.getName().equals("describe")).count());
        }
    }
}
