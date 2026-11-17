package cc.gahaha.fastmultiversion.intellij;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener;
import com.intellij.openapi.project.Project;
import java.nio.file.Path;
import org.jetbrains.annotations.Nullable;

/**
 * After a successful sync of a FastMultiversion project (its modules and libraries are then up to date in the IDE),
 * updates the API database, which the sync itself does not (see {@link ApiDatabaseUpdater}), and removes the run
 * configurations of modules the sync removed ({@link StaleRunConfigurations}).
 */
public final class GradleSyncListener implements ProjectDataImportListener {
    private final Project project;

    public GradleSyncListener(Project project) {
        this.project = project;
    }

    @Override
    public void onImportFinished(@Nullable String projectPath) {
        String path = projectPath != null ? projectPath : project.getBasePath();
        if (path != null && MultiversionProject.isMultiversion(Path.of(path))) {
            ApiDatabaseUpdater.getInstance(project).update(path);
            ApplicationManager.getApplication().invokeLater(
                    () -> StaleRunConfigurations.remove(project, StaleRunConfigurations.versionProjects(Path.of(path))), project.getDisposed());
        }
    }
}
