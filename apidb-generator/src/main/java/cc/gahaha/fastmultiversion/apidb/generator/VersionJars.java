package cc.gahaha.fastmultiversion.apidb.generator;

import cc.gahaha.fastmultiversion.versioning.McVersion;
import java.nio.file.Path;
import java.util.List;

/**
 * The jars that make up one Minecraft version's API: the Minecraft jar, and optionally its libraries and
 * the loader's API jars. If several jars contain the same class, the first one wins.
 */
public record VersionJars(McVersion version, List<Path> jars) {
    public VersionJars {
        if (jars.isEmpty()) {
            throw new IllegalArgumentException("no jars given for Minecraft " + version);
        }
        jars = List.copyOf(jars);
    }
}
