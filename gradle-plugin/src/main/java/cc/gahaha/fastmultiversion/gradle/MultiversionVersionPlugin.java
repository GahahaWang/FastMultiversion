package cc.gahaha.fastmultiversion.gradle;

import cc.gahaha.fastmultiversion.versioning.McVersion;
import java.io.File;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;

/**
 * Applied to each version project {@code :<version>}. It
 * <ul>
 *   <li>compiles the shared {@code src/main} (and the project's own {@code versions/<version>/src/main}),</li>
 *   <li>adds {@code Mc}/{@code @Mc.Is} to the compile classpath and the javac plugin to the processor path,</li>
 *   <li>runs javac with {@code -Xplugin:"FastMultiversion target=<version> apidb=..."} in a forked compiler
 *       with the exports the plugin needs,</li>
 *   <li>records this version's API ({@code generateVersionApi}) for the root project's database,</li>
 *   <li>keeps only this version's lines of access wideners, class tweakers and access transformers
 *       ({@link VersionedFiles}).</li>
 * </ul>
 * During IntelliJ's Gradle sync only the active version gets the shared sources, because IntelliJ puts a
 * source directory in one module only; the IDE then shows the code as the active version sees it. With
 * loaders and {@code common.gradle}, {@code src/main} goes to the active version's common project, which
 * compiles it against Minecraft without a loader, and the loader projects depend on it, so that no loader
 * sees another loader's code.
 */
public abstract class MultiversionVersionPlugin implements Plugin<Project> {
    public static final String API_PART_ELEMENTS = "multiversionApiPartElements";
    static final String API_PART_USAGE = "cc.gahaha.fastmultiversion.api-part";

    @Override
    public void apply(Project project) {
        MultiversionVersionExtension version = project.getExtensions().getByType(MultiversionVersionExtension.class);
        MultiversionLayout layout = (MultiversionLayout) project.getExtensions().getExtraProperties().get(MultiversionLayout.KEY);
        project.getPluginManager().apply(JavaPlugin.class);
        Project root = project.getRootProject();
        EmbeddedLibraries.Libraries libraries = EmbeddedLibraries.extract(project.getGradle().getGradleUserHomeDir());

        SourceSet main = project.getExtensions().getByType(SourceSetContainer.class).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        String loader = version.getLoader();
        boolean ide = layout.ideSync();
        // Which project owns src/main in the IDE: the active version's common project if there is one,
        // otherwise the active version's (only) loader project, otherwise the active version's project.
        boolean ownsMain = version.isCommon() || loader == null || (!layout.common() && version.isActiveLoader());
        if (!ide || (version.isActive() && ownsMain)) {
            main.getJava().srcDir(root.file("src/main/java"));
            main.getResources().srcDir(root.file("src/main/resources"));
        }
        if (loader != null && !version.isCommon() && (!ide || version.isActive())) {
            main.getJava().srcDir(root.file("src/" + loader + "/java"));
            main.getResources().srcDir(root.file("src/" + loader + "/resources"));
        }
        File scriptDir = project.getBuildFile().toPath().toAbsolutePath().normalize().getParent().toFile();
        if (ide && version.isActive() && !scriptDir.equals(root.getProjectDir().toPath().toAbsolutePath().normalize().toFile())) {
            // The IDE resolves a Gradle script against the module that contains the file. The shared build script
            // (gradle/<kind>/<kind>.gradle) belongs to the root project's module, which applies none of its plugins,
            // so the IDE could not resolve loom { }, minecraft, multiversion, ... As a resource directory of the active
            // version's project, it belongs to this project's module. Only for the IDE: builds do not ship it.
            main.getResources().srcDir(scriptDir);
        }
        if (ide && layout.common() && loader != null && !version.isCommon() && version.isActive()) {
            // In the IDE the loader projects see src/main through the common project, which has no loader:
            // NeoForge code sees the shared code but nothing of Fabric. Not transitive, so the common
            // project's libraries stay out too; builds compile src/main in every project and need none of this.
            ProjectDependency common = project.getDependencyFactory().create(
                    project.project(":" + version.getVersion() + ":" + MultiversionLayout.COMMON));
            common.setTransitive(false);
            project.getDependencies().add(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, common);
        }

        VersionedFiles versioned = new VersionedFiles(project, McVersion.parse(version.getVersion()), loader, version.isCommon());
        version.setVersionedFiles(versioned);
        versioned.apply();

        project.getDependencies().add(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, project.files(libraries.api()));
        project.getDependencies().add(JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME, project.files(libraries.processorPath()));

        TaskProvider<GenerateVersionApi> part = project.getTasks().register("generateVersionApi", GenerateVersionApi.class, task -> {
            task.setGroup("multiversion");
            task.setDescription("Records the Minecraft " + version.getVersion() + (loader == null ? "" : " " + loader)
                    + " API for the range check.");
            task.getMinecraftVersion().set(version.getVersion());
            // The game's and libraries' API only: outputs of the mod's own projects are not Minecraft API.
            task.getClasspath().from(project.getConfigurations().named(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME)
                    .map(classpath -> classpath.getIncoming()
                            .artifactView(view -> view.componentFilter(id -> !(id instanceof ProjectComponentIdentifier)))
                            .getFiles()));
            task.getOutputFile().set(project.getLayout().getBuildDirectory().file("multiversion/api-part.mcapi"));
        });
        project.getConfigurations().consumable(API_PART_ELEMENTS, configuration -> configuration.getAttributes()
                .attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, API_PART_USAGE)));
        project.getArtifacts().add(API_PART_ELEMENTS, part.flatMap(GenerateVersionApi::getOutputFile), a -> a.builtBy(part));

        Provider<RegularFile> database = root.getLayout().getBuildDirectory().file(MultiversionLayout.databasePath(loader));
        project.getTasks().named(JavaPlugin.COMPILE_JAVA_TASK_NAME, JavaCompile.class, task -> {
            task.getOptions().setFork(true);
            task.getOptions().getForkOptions().getJvmArgumentProviders().add(new JavacExports());
            MultiversionCompilerArguments arguments = project.getObjects().newInstance(MultiversionCompilerArguments.class);
            arguments.getTarget().set(version.getVersion());
            arguments.getApiDatabase().set(database);
            task.getOptions().getCompilerArgumentProviders().add(arguments);
            task.dependsOn(":" + MultiversionLayout.databaseTask(loader));
        });
    }
}
