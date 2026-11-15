package cc.gahaha.fastmultiversion.intellij;

import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings;
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode;
import com.intellij.openapi.externalSystem.task.TaskCallback;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.externalSystem.util.task.TaskExecutionSpec;
import com.intellij.openapi.project.Project;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.util.GradleConstants;

/**
 * Generates the API database in the background with the Gradle task {@value #TASK}, then reloads the setup,
 * so that code of other versions is resolved and checked again: after each Gradle sync (a sync runs no tasks,
 * so the database would otherwise only change with a build), and when the database is missing. Gradle skips
 * the work when nothing changed.
 */
@Service(Service.Level.PROJECT)
public final class ApiDatabaseUpdater {
    static final String TASK = "generateApiDatabase";
    private static final String NOTIFICATIONS = "FastMultiversion";

    private final Project project;
    private boolean running;
    private @Nullable String pending;

    public ApiDatabaseUpdater(Project project) {
        this.project = project;
    }

    public static ApiDatabaseUpdater getInstance(Project project) {
        return project.getService(ApiDatabaseUpdater.class);
    }

    /** Generates the database of the Gradle project in {@code projectPath}; after the running generation, if any. */
    public void update(String projectPath) {
        synchronized (this) {
            if (running) {
                pending = projectPath;
                return;
            }
            running = true;
        }
        ApplicationManager.getApplication().invokeLater(() -> run(projectPath), project.getDisposed());
    }

    /** What the Gradle run executes: {@value #TASK} in {@code projectPath}. */
    static ExternalSystemTaskExecutionSettings settings(String projectPath) {
        ExternalSystemTaskExecutionSettings settings = new ExternalSystemTaskExecutionSettings();
        settings.setExternalSystemIdString(GradleConstants.SYSTEM_ID.getId());
        settings.setExternalProjectPath(projectPath);
        settings.setTaskNames(List.of(TASK));
        return settings;
    }

    private void run(String projectPath) {
        TaskExecutionSpec spec = TaskExecutionSpec.Companion.create()
                .withProject(project)
                .withSystemId(GradleConstants.SYSTEM_ID)
                .withExecutorId(DefaultRunExecutor.EXECUTOR_ID)
                .withSettings(settings(projectPath))
                .withProgressExecutionMode(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
                .withActivateToolWindowBeforeRun(false)
                .withActivateToolWindowOnFailure(false)
                .withCallback(new TaskCallback() {
                    @Override
                    public void onSuccess() {
                        finished(true);
                    }

                    @Override
                    public void onFailure() {
                        finished(false);
                    }
                })
                .build();
        ExternalSystemUtil.runTask(spec);
    }

    private void finished(boolean success) {
        String next;
        synchronized (this) {
            next = pending;
            pending = null;
            running = next != null;
        }
        if (project.isDisposed()) {
            return;
        }
        if (!success) {
            NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATIONS)
                    .createNotification("FastMultiversion", "The Gradle task " + TASK + " failed, so code of other versions is "
                            + "checked with the previous API database, if any. See the Run tool window.", NotificationType.WARNING)
                    .notify(project);
        }
        // Reads a database of several megabytes: not on the UI thread.
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (!project.isDisposed()) {
                MultiversionProject.getInstance(project).reload();
            }
        });
        if (next != null) {
            ApplicationManager.getApplication().invokeLater(() -> run(next), project.getDisposed());
        }
    }
}
