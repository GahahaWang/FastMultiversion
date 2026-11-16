package cc.gahaha.fastmultiversion.intellij.update

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.updateSettings.impl.UpdateSettings

/**
 * Adds the plugin's own repository on GitHub Pages to IntelliJ IDEA's plugin repositories, so that the IDE offers
 * its updates: the release channel for a release, the dev channel (pre-releases too) for a pre-release, x.y.z-dev.n.
 * Removes the other channel's, so that switching channels by installing the other kind sticks. Nothing for a
 * build from source (a snapshot).
 */
class PluginRepositoryRegistrar : ProjectActivity {
    override suspend fun execute(project: Project) {
        val version = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: return
        val repository = repositoryFor(version) ?: return
        val hosts = UpdateSettings.getInstance().storedPluginHosts
        hosts.removeAll(CHANNELS - repository)
        if (repository !in hosts) {
            hosts.add(repository)
        }
    }

    companion object {
        const val PLUGIN_ID = "cc.gahaha.fastmultiversion"
        const val RELEASES = "https://gahahawang.github.io/FastMultiversion/updatePlugins.xml"
        const val DEV = "https://gahahawang.github.io/FastMultiversion/updatePlugins-dev.xml"
        private val CHANNELS = setOf(RELEASES, DEV)

        /** The repository that has the updates of [version]; `null` for a build from source. */
        fun repositoryFor(version: String): String? = when {
            version.endsWith("-SNAPSHOT") -> null
            "-dev." in version -> DEV
            else -> RELEASES
        }
    }
}
