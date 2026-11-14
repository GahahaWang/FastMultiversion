package cc.gahaha.fastmultiversion.apidb.generator;

import static cc.gahaha.fastmultiversion.apidb.ApiClass.MemberKind.METHOD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.gahaha.fastmultiversion.apidb.ApiDatabase;
import cc.gahaha.fastmultiversion.versioning.McVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Builds the database from the Minecraft jars that Fabric Loom has cached; skipped if they are missing. */
class RealMinecraftApiDbTest {
    private static final List<String> VERSIONS = List.of("26.1", "26.1.2", "26.2", "26.3");

    @TempDir
    Path temp;

    @Test
    void knowsRealApiChanges() throws Exception {
        List<VersionJars> inputs = new ArrayList<>();
        for (String version : VERSIONS) {
            Path jar = Path.of(System.getProperty("user.home"), ".gradle", "caches", "fabric-loom", version, "minecraft-merged.jar");
            assumeTrue(Files.isRegularFile(jar), "Minecraft " + version + " is not in the Loom cache");
            inputs.add(new VersionJars(McVersion.parse(version), List.of(jar)));
        }
        long start = System.nanoTime();
        ApiDatabase db = ApiDbGenerator.generate(inputs);
        long generated = System.nanoTime();
        Path file = temp.resolve("api.mcapi");
        db.write(file);
        ApiDatabase read = ApiDatabase.read(file);
        long loaded = System.nanoTime();
        System.out.printf("generated %d classes in %d ms; file %,d bytes; read back in %d ms%n", db.classes().size(),
                (generated - start) / 1_000_000, Files.size(file), (loaded - generated) / 1_000_000);

        // 26.3 moved GpuTexture into a new package.
        assertEquals("[26.3,)", read.range(read.classVersions("com/mojang/renderpearl/api/textures/GpuTexture")));
        assertEquals("[26.1,26.3)", read.range(read.classVersions("com/mojang/blaze3d/textures/GpuTexture")));
        assertEquals("[26.1,26.3)", read.range(read.resolvable(METHOD, "com/mojang/blaze3d/pipeline/RenderTarget",
                "getColorTexture", "()Lcom/mojang/blaze3d/textures/GpuTexture;")));

        // hurtAndBreak keeps its descriptor, but its Consumer<Item> became Consumer<ItemStack> in 26.3.
        String stack = "net/minecraft/world/item/ItemStack";
        String hurt = "(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V";
        int v261 = read.indexOf(McVersion.parse("26.1"));
        int v263 = read.indexOf(McVersion.parse("26.3"));
        assertEquals("[26.1,)", read.range(read.resolvable(METHOD, stack, "hurtAndBreak", hurt)));
        assertEquals("[26.1,26.3)", read.range(read.compatible(METHOD, stack, "hurtAndBreak", hurt, v261)));
        assertEquals("[26.3,)", read.range(read.compatible(METHOD, stack, "hurtAndBreak", hurt, v263)));

        // Stable API, including members inherited from the JDK.
        assertEquals(read.allVersions(), read.compatible(METHOD, "net/minecraft/resources/Identifier", "fromNamespaceAndPath",
                "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/Identifier;", v261));
        assertEquals(read.allVersions(), read.resolvable(METHOD, stack, "hashCode", "()I"));
        assertTrue(Files.size(file) < 20_000_000);
    }
}
