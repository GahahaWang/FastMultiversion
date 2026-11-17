package cc.gahaha.fastmultiversion.gradle;

import cc.gahaha.fastmultiversion.versioning.McVersion;
import cc.gahaha.fastmultiversion.versioning.VersionRange;
import java.io.File;
import java.util.List;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;

/**
 * The {@code multiversion} object in {@code version.gradle} (or {@code <loader>.gradle}), describing the
 * project being configured:
 *
 * <pre>
 * dependencies {
 *     minecraft "net.minecraft:minecraft:${multiversion.version}"
 *     if (multiversion.is('[26.2,)')) { ... }
 * }
 * </pre>
 */
public class MultiversionVersionExtension {
    private final McVersion version;
    private final String loader;
    private final boolean common;
    private final boolean active;
    private final List<McVersion> versions;
    private VersionedFiles files;

    public MultiversionVersionExtension(McVersion version, String loader, boolean common, boolean active, List<McVersion> versions) {
        this.version = version;
        this.loader = loader;
        this.common = common;
        this.active = active;
        this.versions = List.copyOf(versions);
    }

    /** This project's Minecraft version, e.g. {@code "26.2"}. */
    public String getVersion() {
        return version.toString();
    }

    /** This project's loader, e.g. {@code "fabric"}; {@code "common"} for a common project; {@code null} without loaders. */
    public String getLoader() {
        return loader;
    }

    /** Whether this is a common project, run by {@code common.gradle}: {@code src/main} without a loader. */
    public boolean isCommon() {
        return common;
    }

    /** Whether this is the version the IDE works with. */
    public boolean isActive() {
        return active;
    }

    /** Every version the mod is built for, ascending. */
    public List<String> getVersions() {
        return versions.stream().map(McVersion::toString).toList();
    }

    /** Whether this project's version is in {@code range}, using the same syntax as {@code Mc.is}. */
    public boolean is(String range) {
        return VersionRange.parse(range).contains(version);
    }

    /**
     * This version's copy of an access widener, class tweaker or access transformer: without the lines whose
     * {@code # @Mc.Is("...")} comment excludes this version, but nothing merged into it or converted (see
     * {@link #getAccessWidener()}). A string is a path relative to the root directory (where {@code src/} is). For build
     * scripts that give Loom or ModDevGradle one file as it is:
     *
     * <pre>
     * loom {
     *     accessWidenerPath = multiversion.versioned('src/fabric/resources/mymod.classtweaker')
     * }
     * </pre>
     */
    public File versioned(Object path) {
        if (files == null) {
            throw new IllegalStateException("multiversion.versioned is not available before the version project is set up");
        }
        return files.versioned(files.resolve(path));
    }

    /**
     * The access widener made from all the access wideners, class tweakers and access transformers among this
     * project's resources, with this version's lines; what Loom's {@code accessWidenerPath} is by default:
     *
     * <pre>
     * loom {
     *     accessWidenerPath = multiversion.accessWidener
     * }
     * </pre>
     */
    public Provider<RegularFile> getAccessWidener() {
        return setUp().accessWidener();
    }

    /**
     * The access transformer made from them all, likewise; what ModDevGradle's access transformers are by default:
     *
     * <pre>
     * neoForge {
     *     accessTransformers = multiversion.accessTransformers
     * }
     * </pre>
     */
    public FileCollection getAccessTransformers() {
        return setUp().accessTransformers();
    }

    private VersionedFiles setUp() {
        if (files == null) {
            throw new IllegalStateException("multiversion's access files are not available before the version project is set up");
        }
        return files;
    }

    void setVersionedFiles(VersionedFiles files) {
        this.files = files;
    }

    @Override
    public String toString() {
        return getVersion();
    }
}
