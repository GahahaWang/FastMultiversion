package cc.gahaha.fastmultiversion.gradle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.gradle.api.provider.Property;

/**
 * The {@code multiversion} block in {@code settings.gradle}:
 *
 * <pre>
 * multiversion {
 *     versions '26.1', '26.2', '26.3'
 *     active = '26.3'                    // optional; also -Pmultiversion.active=...; default: the newest
 *
 *     // Either one project per version, running gradle/version/version.gradle:
 *     versionScript = 'version.gradle'   // optional
 *
 *     // or one project per version and loader, e.g. :26.2:fabric, running gradle/fabric/fabric.gradle:
 *     loaders 'fabric', 'neoforge'
 *     activeLoader = 'fabric'            // optional; also -Pmultiversion.activeLoader=...; default: the first
 * }
 * </pre>
 *
 * <p>Each kind of project runs one build script, {@code gradle/<kind>/<kind>.gradle}, or {@code <kind>.gradle} in
 * the root directory. In its own directory the IDE resolves the script against the active version's project of
 * that kind, so it knows the plugins the script applies; in the root directory it does not.
 *
 * <p>With loaders, a {@code common.gradle} next to the loader scripts adds a project {@code :<version>:common}
 * per version that compiles {@code src/main} against Minecraft without a loader (it sets up Minecraft and the
 * libraries the shared code uses). In the IDE it owns {@code src/main} and the loader projects depend on it,
 * so that each loader's code sees the shared code but no other loader's. It is required with several loaders.
 */
public abstract class MultiversionSettingsExtension {
    private final List<String> versions = new ArrayList<>();
    private final List<String> loaders = new ArrayList<>();

    /** The Minecraft versions the mod is built for. */
    public void versions(String... versions) {
        this.versions.addAll(List.of(versions));
    }

    public List<String> getVersions() {
        return Collections.unmodifiableList(versions);
    }

    /**
     * The mod loaders, for a mod built for several loaders. Each version then gets one project per loader,
     * which runs {@code gradle/<loader>/<loader>.gradle} (or {@code <loader>.gradle}) and compiles {@code src/main}
     * plus {@code src/<loader>}.
     */
    public void loaders(String... loaders) {
        this.loaders.addAll(List.of(loaders));
    }

    public List<String> getLoaders() {
        return Collections.unmodifiableList(loaders);
    }

    /** The version the IDE works with; the others still build, but only it gets the shared sources in the IDE. */
    public abstract Property<String> getActive();

    /** The loader whose project gets {@code src/main} in the IDE. */
    public abstract Property<String> getActiveLoader();

    /**
     * The build script, relative to the root directory, that every version project runs (without loaders).
     * Default: {@code gradle/version/version.gradle}, or {@code version.gradle}.
     */
    public abstract Property<String> getVersionScript();
}
