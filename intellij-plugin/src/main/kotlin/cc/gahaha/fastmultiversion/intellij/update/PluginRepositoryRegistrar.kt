package cc.gahaha.fastmultiversion.intellij.update

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.updateSettings.impl.UpdateSettings

/**
 * Adds the plugin's own repository on GitHub Pages to IntelliJ IDEA's plugin repositories, so that the IDE offers
 * its updates. Nothing for a build from source (a snapshot).
 */
class PluginRepositoryRegistrar : ProjectActivity {
    override suspend fun execute(project: Project) {
        val version = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: return
        val repository = repositoryFor(version) ?: return
        val hosts = UpdateSettings.getInstance().storedPluginHosts
        if (repository !in hosts) {
            hosts.add(repository)
        }
    }

    companion object {
        const val PLUGIN_ID = "cc.gahaha.fastmultiversion"
        const val RELEASES = "https://gahahawang.github.io/FastMultiversion/updatePlugins.xml"

        /** The repository that has the updates of [version]; `null` for a build from source. */
        fun repositoryFor(version: String): String? = if (version.endsWith("-SNAPSHOT")) null else RELEASES
    }
}
