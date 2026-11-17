package cc.gahaha.fastmultiversion.gradle;

import cc.gahaha.fastmultiversion.versioning.McVersion;
import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.initialization.ProjectDescriptor;
import org.gradle.api.initialization.Settings;

/**
 * {@code plugins { id 'cc.gahaha.fastmultiversion' }} in {@code settings.gradle}.
 *
 * <p>For each version in the {@code multiversion} block it adds a project {@code :<version>} in
 * {@code versions/<version>}, which runs the shared {@code version.gradle} (where the mod sets up Loom or
 * another loader plugin for that version) and compiles the shared sources in {@code src/} for that version
 * ({@link MultiversionVersionPlugin}). With {@code loaders}, each version instead gets a project per loader,
 * {@code :<version>:<loader>} in {@code versions/<version>/<loader>}, running {@code <loader>.gradle}.
 * The root project gets the API database and aggregate tasks ({@link MultiversionRootPlugin}).
 */
public abstract class MultiversionSettingsPlugin implements Plugin<Settings> {
    public static final String ACTIVE_PROPERTY = "multiversion.active";

    @Override
    public void apply(Settings settings) {
        MultiversionSettingsExtension extension = settings.getExtensions().create("multiversion", MultiversionSettingsExtension.class);

        settings.getGradle().settingsEvaluated(evaluated -> configure(evaluated, extension));
    }

    private static void configure(Settings settings, MultiversionSettingsExtension extension) {
        if (extension.getVersions().isEmpty()) {
            throw new GradleException("multiversion: list the Minecraft versions in settings.gradle, e.g. "
                    + "multiversion { versions '26.1', '26.2' }");
        }
        List<McVersion> versions = extension.getVersions().stream().map(McVersion::parse).sorted().toList();
        for (int i = 1; i < versions.size(); i++) {
            if (versions.get(i).equals(versions.get(i - 1))) {
                throw new GradleException("multiversion: Minecraft " + versions.get(i) + " is listed twice");
            }
        }
        McVersion active = McVersion.parse(settings.getProviders().gradleProperty(ACTIVE_PROPERTY)
                .orElse(extension.getActive())
                .getOrElse(versions.getLast().toString()));
        if (!versions.contains(active)) {
            throw new GradleException("multiversion: the active version " + active + " is not one of " + versions);
        }

        List<String> loaders = extension.getLoaders();
        if (new LinkedHashSet<>(loaders).size() != loaders.size()) {
            throw new GradleException("multiversion: a loader is listed twice in " + loaders);
        }
        for (String loader : loaders) {
            if (!loader.matches("[a-z][a-z0-9_-]*")) {
                throw new GradleException("multiversion: loader names are lower-case identifiers, not '" + loader + "'");
            }
            if (loader.equals(MultiversionLayout.COMMON)) {
                throw new GradleException("multiversion: '" + MultiversionLayout.COMMON + "' is not a loader; the common "
                        + "projects come from common.gradle");
            }
        }
        // With common.gradle, each version also gets a project that owns src/main in the IDE and compiles it
        // against Minecraft without a loader, so that no loader's code sees another loader's.
        File rootDir = settings.getRootDir();
        boolean common = !loaders.isEmpty() && new File(rootDir, script(rootDir, MultiversionLayout.COMMON)).isFile();
        if (loaders.size() > 1 && !common) {
            throw new GradleException("multiversion: with several loaders, add common.gradle (in gradle/common/ or the "
                    + "root directory), the build script of the "
                    + "per-version common projects (Minecraft without a loader, plus libraries the shared code uses). "
                    + "In the IDE src/main belongs to them, so each loader sees the shared code but not the other loaders.");
        }
        boolean ideSync = Boolean.parseBoolean(settings.getProviders().systemProperty("idea.sync.active").getOrElse("false"));
        MultiversionLayout layout = new MultiversionLayout(versions, loaders, common, active, ideSync);

        Map<String, MultiversionLayout.Target> targets = new HashMap<>();
        for (MultiversionLayout.Target target : layout.targets()) {
            String script = target.loader() != null ? script(rootDir, target.loader())
                    : extension.getVersionScript().getOrElse(script(rootDir, "version"));
            if (!new File(rootDir, script).isFile()) {
                throw new GradleException("multiversion: " + script + " is missing; it is the build script of "
                        + target.path() + " (set up Minecraft and the loader there)");
            }
            settings.include(target.path());
            File versionDir = new File(settings.getRootDir(), "versions/" + target.version());
            versionDir.mkdirs();
            settings.project(":" + target.version()).setProjectDir(versionDir);
            ProjectDescriptor project = settings.project(target.path());
            if (target.loader() != null) {
                File dir = new File(versionDir, target.loader());
                dir.mkdirs();
                project.setProjectDir(dir);
            }
            project.setBuildFileName((target.loader() == null ? "../../" : "../../../") + script);
            targets.put(target.path(), target);
        }

        // Compared by path: Gradle may hand out different wrapper objects for the same project.
        settings.getGradle().beforeProject(project -> {
            if (project.getPath().equals(":")) {
                project.getExtensions().getExtraProperties().set(MultiversionLayout.KEY, layout);
                project.getPluginManager().apply(MultiversionRootPlugin.class);
                return;
            }
            MultiversionLayout.Target target = targets.get(project.getPath());
            if (target != null) {
                project.getExtensions().add(MultiversionVersionExtension.class, "multiversion", new MultiversionVersionExtension(
                        target.version(), target.loader(), target.isCommon(), target.version().equals(active), layout.versions()));
                project.getExtensions().getExtraProperties().set(MultiversionLayout.KEY, layout);
                project.getPluginManager().apply(MultiversionVersionPlugin.class);
            }
        });
    }

    /**
     * The build script of the projects of one kind ({@code fabric}, {@code common}, {@code version}, ...), relative
     * to the root directory: {@code gradle/<kind>/<kind>.gradle} if there is one, else {@code <kind>.gradle}. In its
     * own directory, the IDE can link the script to the active version's project of that kind (see
     * {@link MultiversionVersionPlugin}) and so knows the plugins it applies; in the root directory it cannot.
     */
    static String script(File rootDir, String kind) {
        String own = "gradle/" + kind + "/" + kind + ".gradle";
        return new File(rootDir, own).isFile() ? own : kind + ".gradle";
    }
}
