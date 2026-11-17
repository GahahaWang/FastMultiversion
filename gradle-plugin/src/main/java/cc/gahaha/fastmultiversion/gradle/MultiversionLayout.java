package cc.gahaha.fastmultiversion.gradle;

import cc.gahaha.fastmultiversion.versioning.McVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The settings' decisions, handed from the settings plugin to the project plugins.
 *
 * @param versions     the versions, ascending
 * @param loaders      the loaders; empty for one project per version
 * @param common       whether each version also has a {@value #COMMON} project for {@code src/main}
 * @param active       the version the IDE works with
 * @param ideSync      whether this build is IntelliJ's Gradle sync
 */
record MultiversionLayout(List<McVersion> versions, List<String> loaders, boolean common, McVersion active, boolean ideSync) {
    static final String KEY = "cc.gahaha.fastmultiversion.layout";
    /** The name of the per-version project that compiles {@code src/main} against Minecraft without a loader. */
    static final String COMMON = "common";

    /** One project that compiles the mod: a version, and a loader (or {@value #COMMON}) if there are loaders. */
    record Target(McVersion version, String loader) {
        String path() {
            return ":" + version + (loader == null ? "" : ":" + loader);
        }

        boolean isCommon() {
            return COMMON.equals(loader);
        }
    }

    List<String> names() {
        return versions.stream().map(McVersion::toString).toList();
    }

    /** The loaders, preceded by {@value #COMMON} if there are common projects: each gets its own API database. */
    List<String> projectKinds() {
        List<String> kinds = new ArrayList<>();
        if (common) {
            kinds.add(COMMON);
        }
        kinds.addAll(loaders);
        return kinds;
    }

    List<Target> targets() {
        List<Target> targets = new ArrayList<>();
        for (McVersion version : versions) {
            if (loaders.isEmpty()) {
                targets.add(new Target(version, null));
            } else {
                projectKinds().forEach(kind -> targets.add(new Target(version, kind)));
            }
        }
        return targets;
    }

    List<Target> targets(String loader) {
        return targets().stream().filter(t -> Objects.equals(t.loader(), loader)).toList();
    }

    /** Where the API database of {@code loader} (or the only one, for {@code null}) goes, under the root build directory. */
    static String databasePath(String loader) {
        return loader == null ? "multiversion/api.mcapi" : "multiversion/" + loader + "/api.mcapi";
    }

    /** The task that builds the API database of {@code loader}. */
    static String databaseTask(String loader) {
        return loader == null ? "generateApiDatabase"
                : "generateApiDatabase" + Character.toUpperCase(loader.charAt(0)) + loader.substring(1);
    }
}
