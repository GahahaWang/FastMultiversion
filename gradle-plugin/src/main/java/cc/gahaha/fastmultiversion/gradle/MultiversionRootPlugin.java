package cc.gahaha.fastmultiversion.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.DependencyScopeConfiguration;
import org.gradle.api.artifacts.ResolvableConfiguration;
import org.gradle.api.attributes.Usage;
import org.gradle.api.provider.Provider;

/**
 * Applied to the root project. It merges every version's API into the database the range check uses
 * (task {@code generateApiDatabase}; with loaders, one database and task per loader, e.g.
 * {@code generateApiDatabaseFabric}), writes {@value #INFO_PATH} for the IDE plugin, and adds
 * {@code compileAllVersions} and {@code buildAllVersions}.
 */
public abstract class MultiversionRootPlugin implements Plugin<Project> {
    /** Versions, active version and database location, as properties; read by the IDE plugin. */
    public static final String INFO_PATH = "multiversion/project.properties";

    @Override
    public void apply(Project root) {
        MultiversionLayout layout = (MultiversionLayout) root.getExtensions().getExtraProperties().get(MultiversionLayout.KEY);

        List<String> databaseTasks = new ArrayList<>();
        List<String> loaders = layout.loaders().isEmpty() ? java.util.Collections.singletonList(null) : layout.projectKinds();
        for (String loader : loaders) {
            registerDatabase(root, layout, loader);
            databaseTasks.add(MultiversionLayout.databaseTask(loader));
        }
        if (!layout.loaders().isEmpty()) {
            root.getTasks().register(MultiversionLayout.databaseTask(null), task -> {
                task.setGroup("multiversion");
                task.setDescription("Builds the API databases of all Minecraft versions and loaders.");
                task.dependsOn(databaseTasks);
            });
        }

        List<String> paths = layout.targets().stream().map(MultiversionLayout.Target::path).toList();
        root.getTasks().register("compileAllVersions", task -> {
            task.setGroup("multiversion");
            task.setDescription("Compiles the mod for every Minecraft version (and the shared code without a loader).");
            task.dependsOn(paths.stream().map(p -> p + ":compileJava").toList());
        });
        List<String> modPaths = layout.targets().stream().filter(t -> !t.isCommon()).map(MultiversionLayout.Target::path).toList();
        root.getTasks().register("buildAllVersions", task -> {
            task.setGroup("multiversion");
            task.setDescription("Builds the mod for every Minecraft version.");
            task.dependsOn(modPaths.stream().map(p -> p + ":build").toList());
        });

        writeInfo(root, layout);
    }

    private static void registerDatabase(Project root, MultiversionLayout layout, String loader) {
        // One configuration per version project: the projects of one loader usually share group and name
        // (e.g. cc.example:fabric), so in a single configuration Gradle would treat them as versions of one
        // module and keep only the newest.
        List<Provider<ResolvableConfiguration>> partFiles = new ArrayList<>();
        for (MultiversionLayout.Target target : layout.targets(loader)) {
            String id = "multiversionApiPart" + target.path().replaceAll("[^A-Za-z0-9]+", "_");
            NamedDomainObjectProvider<DependencyScopeConfiguration> part = root.getConfigurations().dependencyScope(id);
            root.getDependencies().add(part.getName(), root.getDependencyFactory().create(root.project(target.path())));
            partFiles.add(root.getConfigurations().resolvable(id + "Files", c -> {
                c.extendsFrom(part.get());
                c.getAttributes().attribute(Usage.USAGE_ATTRIBUTE,
                        root.getObjects().named(Usage.class, MultiversionVersionPlugin.API_PART_USAGE));
            }));
        }
        root.getTasks().register(MultiversionLayout.databaseTask(loader), MergeApiDatabase.class, task -> {
            task.setGroup("multiversion");
            task.setDescription("Builds the API database of all Minecraft versions" + (loader == null ? "" : " for " + loader)
                    + " for the range check.");
            partFiles.forEach(files -> task.getParts().from(files));
            task.getOutputFile().set(root.getLayout().getBuildDirectory().file(MultiversionLayout.databasePath(loader)));
        });
    }

    /** Written while configuring, because the IDE reads it after a Gradle sync, which runs no tasks. */
    private static void writeInfo(Project root, MultiversionLayout layout) {
        Path buildDir = root.getLayout().getBuildDirectory().get().getAsFile().toPath();
        Path info = buildDir.resolve(INFO_PATH);
        // The IDE checks src/main against the database of the projects that own it there.
        Path database = buildDir.resolve(MultiversionLayout.databasePath(
                layout.common() ? MultiversionLayout.COMMON : layout.activeLoader()));
        StringBuilder text = new StringBuilder()
                .append("versions=").append(String.join(",", layout.names())).append('\n')
                .append("active=").append(layout.active()).append('\n');
        if (!layout.loaders().isEmpty()) {
            text.append("loaders=").append(String.join(",", layout.loaders())).append('\n')
                    .append("activeLoader=").append(layout.activeLoader()).append('\n')
                    .append("common=").append(layout.common()).append('\n');
        }
        text.append("apidb=").append(root.getProjectDir().toPath().relativize(database).toString().replace('\\', '/')).append('\n');
        try {
            if (!Files.isRegularFile(info) || !Files.readString(info, StandardCharsets.UTF_8).contentEquals(text)) {
                Files.createDirectories(info.getParent());
                Files.writeString(info, text, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + info, e);
        }
    }
}
