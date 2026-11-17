package cc.gahaha.fastmultiversion.intellij;

import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.execution.application.ApplicationConfigurationType;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType;

/** After a sync, the run configurations of what the sync removed go. */
public class StaleRunConfigurationsTest extends LightJavaCodeInsightFixtureTestCase {
    public void testRemovesRunConfigurationsOfWhatIsGone() {
        RunManager runManager = RunManager.getInstance(getProject());
        ConfigurationFactory application = ApplicationConfigurationType.getInstance().getConfigurationFactories()[0];
        // ModDevGradle's, of a module: one of this layout, one of a layout the mod had before.
        RunnerAndConfigurationSettings current = runManager.createConfiguration("NeoForge Client 26.3", application);
        ((ApplicationConfiguration) current.getConfiguration()).setModule(getModule());
        RunnerAndConfigurationSettings oldModule = runManager.createConfiguration("NeoForge Client (:neoforge)", application);
        ((ApplicationConfiguration) oldModule.getConfiguration()).getConfigurationModule().setModuleName("EntityCount.neoforge.main");
        List<RunnerAndConfigurationSettings> all = List.of(current, oldModule, runManager.createConfiguration("Tool", application),
                // Loom's, of Gradle tasks: of a version project, of the root project, and of a version the mod dropped.
                gradle(runManager, "Fabric Client 26.3", ":26.3:fabric:runClient"),
                gradle(runManager, "Minecraft Client", ":runClient"),
                gradle(runManager, "Fabric Server 26.1", ":26.1:fabric:runServer"),
                // Other Gradle tasks stay, and so do run tasks without a project.
                gradle(runManager, "EntityCount [build]", ":build"),
                gradle(runManager, "runClient", "runClient"));
        all.forEach(runManager::addConfiguration);
        try {
            assertEquals(Set.of("NeoForge Client (:neoforge)", "Minecraft Client", "Fabric Server 26.1"),
                    Set.copyOf(StaleRunConfigurations.remove(getProject(), Set.of(":26.3:fabric", ":26.3:neoforge", ":26.3:common"))));
            assertEquals(Set.of("NeoForge Client 26.3", "Tool", "Fabric Client 26.3", "EntityCount [build]", "runClient"), names(runManager));

            // Unless the version projects are known, only those of gone modules go.
            runManager.addConfiguration(gradle(runManager, "Minecraft Client", ":runClient"));
            assertTrue(StaleRunConfigurations.remove(getProject(), null).isEmpty());
        } finally {
            runManager.getAllSettings().forEach(runManager::removeConfiguration); // the light project outlives the test
        }
    }

    public void testKnowsTheVersionProjectsByWhatTheGradlePluginWrites() throws Exception {
        Path dir = Files.createTempDirectory("fmv-runs");
        assertNull(StaleRunConfigurations.versionProjects(dir));
        Files.createDirectories(dir.resolve("build/multiversion"));
        Files.writeString(dir.resolve("build/multiversion/project.properties"), "versions=26.1.2,26.3\nloaders=fabric,neoforge\ncommon=true\n");
        assertEquals(Set.of(":26.1.2", ":26.1.2:fabric", ":26.1.2:neoforge", ":26.1.2:common", ":26.3", ":26.3:fabric", ":26.3:neoforge",
                ":26.3:common"), StaleRunConfigurations.versionProjects(dir));
        Files.writeString(dir.resolve("build/multiversion/project.properties"), "versions=26.3\n");
        assertEquals(Set.of(":26.3"), StaleRunConfigurations.versionProjects(dir));
    }

    private static RunnerAndConfigurationSettings gradle(RunManager runManager, String name, String task) {
        RunnerAndConfigurationSettings settings = runManager.createConfiguration(name, GradleExternalTaskConfigurationType.getInstance().getFactory());
        ((ExternalSystemRunConfiguration) settings.getConfiguration()).getSettings().setTaskNames(List.of(task));
        return settings;
    }

    private static Set<String> names(RunManager runManager) {
        return runManager.getAllSettings().stream().map(RunnerAndConfigurationSettings::getName).collect(Collectors.toSet());
    }
}
