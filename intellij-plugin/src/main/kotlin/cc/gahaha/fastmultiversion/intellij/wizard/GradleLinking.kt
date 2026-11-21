package cc.gahaha.fastmultiversion.intellij.wizard

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.observation.launchTracked
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.plugins.gradle.service.project.open.linkAndSyncGradleProject

/**
 * Links the Gradle build of a new project to the project and syncs it, in the background. Tracked, so that
 * whatever waits for the project to be configured waits for the sync too.
 */
@Service(Service.Level.PROJECT)
class GradleLinking(private val project: Project, private val scope: CoroutineScope) {
    fun linkAndSync(dir: Path) {
        scope.launchTracked { linkAndSyncGradleProject(project, dir.toString()) }
    }
}
