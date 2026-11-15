package cc.gahaha.fastmultiversion.intellij;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.plugins.gradle.util.GradleConstants;
import org.junit.Test;

/** What runs after a Gradle sync, and for which projects. */
public class ApiDatabaseUpdaterTest {
    @Test
    public void runsGenerateApiDatabaseInTheSyncedProject() {
        ExternalSystemTaskExecutionSettings settings = ApiDatabaseUpdater.settings("C:/mods/EntityCount");
        assertEquals(List.of("generateApiDatabase"), settings.getTaskNames());
        assertEquals("C:/mods/EntityCount", settings.getExternalProjectPath());
        assertEquals(GradleConstants.SYSTEM_ID.getId(), settings.getExternalSystemIdString());
    }

    @Test
    public void knowsMultiversionProjectsByWhatTheGradlePluginWrites() throws IOException {
        Path dir = Files.createTempDirectory("fmv-sync");
        assertFalse(MultiversionProject.isMultiversion(dir));
        Files.createDirectories(dir.resolve("build/multiversion"));
        Files.writeString(dir.resolve("build/multiversion/project.properties"), "versions=26.1.2,26.2\n");
        assertTrue(MultiversionProject.isMultiversion(dir));
    }
}
