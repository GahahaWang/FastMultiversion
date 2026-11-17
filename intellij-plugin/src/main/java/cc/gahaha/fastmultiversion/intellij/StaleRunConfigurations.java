package cc.gahaha.fastmultiversion.intellij;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ModuleBasedConfiguration;
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.util.GradleConstants;

/**
 * Run configurations of what a Gradle sync removed: Loom and ModDevGradle make one per version project and loader,
 * but never remove one, so those of a version or loader the mod no longer has, or of a layout it had before, would
 * stay. They cannot run any more, so that the list only has what the projects have now. Gone are
 * <ul>
 *   <li>configurations of a module the project no longer has (ModDevGradle's), and</li>
 *   <li>Gradle configurations that only run the game ({@code runClient}, {@code runServer}, ...) in projects that are
 *       not version projects (Loom's), e.g. {@code :26.3:fabric:runClient} once the mod has no loader projects.</li>
 * </ul>
 */
final class StaleRunConfigurations {
    /** The tasks that run the game, of Loom and ModDevGradle. */
    private static final Pattern RUN_TASK = Pattern.compile("run(Client|Server|Data|Datagen|ClientData|ServerData|GameTestServer)");

    private StaleRunConfigurations() {
    }

    /**
     * Removes the stale run configurations; returns their names. {@code versionProjects} are the Gradle paths of the
     * version projects ({@code :26.3}, {@code :26.3:fabric}, ...); {@code null} if not known, then only those of
     * modules that are gone.
     */
    static List<String> remove(Project project, @Nullable Set<String> versionProjects) {
        RunManager runManager = RunManager.getInstance(project);
        ModuleManager modules = ModuleManager.getInstance(project);
        List<RunnerAndConfigurationSettings> stale = new ArrayList<>();
        for (RunnerAndConfigurationSettings settings : runManager.getAllSettings()) {
            if (settings.getConfiguration() instanceof ModuleBasedConfiguration<?, ?> configuration) {
                String module = configuration.getConfigurationModule().getModuleName();
                if (!module.isEmpty() && modules.findModuleByName(module) == null) {
                    stale.add(settings);
                }
            } else if (versionProjects != null && settings.getConfiguration() instanceof ExternalSystemRunConfiguration gradle
                    && GradleConstants.SYSTEM_ID.getId().equals(gradle.getSettings().getExternalSystemIdString())
                    && runsGoneProjects(gradle.getSettings().getTaskNames(), versionProjects)) {
                stale.add(settings);
            }
        }
        stale.forEach(runManager::removeConfiguration);
        return stale.stream().map(RunnerAndConfigurationSettings::getName).toList();
    }

    /** Whether every task runs the game in a project that is no version project ({@code :runClient}: the root project). */
    private static boolean runsGoneProjects(List<String> tasks, Set<String> versionProjects) {
        if (tasks.isEmpty()) {
            return false;
        }
        for (String task : tasks) {
            int colon = task.lastIndexOf(':');
            if (colon < 0) {
                return false; // a task name without a project: run where the configuration says, which may be a version project
            }
            String projectPath = colon == 0 ? ":" : (task.startsWith(":") ? "" : ":") + task.substring(0, colon);
            if (!RUN_TASK.matcher(task.substring(colon + 1)).matches() || versionProjects.contains(projectPath)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The Gradle paths of the version projects, from what the Gradle plugin writes to
     * {@code build/multiversion/project.properties}; {@code null} if it is not there.
     */
    static @Nullable Set<String> versionProjects(Path projectDir) {
        Path info = projectDir.resolve("build/multiversion/project.properties");
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(info, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            return null;
        }
        List<String> loaders = split(properties.getProperty("loaders", ""));
        boolean common = Boolean.parseBoolean(properties.getProperty("common", "false"));
        Set<String> paths = new HashSet<>();
        for (String version : split(properties.getProperty("versions", ""))) {
            paths.add(":" + version);
            loaders.forEach(loader -> paths.add(":" + version + ":" + loader));
            if (common) {
                paths.add(":" + version + ":common");
            }
        }
        return paths;
    }

    private static List<String> split(String list) {
        return Arrays.stream(list.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
