package cc.gahaha.fastmultiversion.intellij.update

import com.intellij.ide.plugins.cl.PluginAwareClassLoader

/**
 * The plugin's version, the one in its plugin.xml: x.y.z, x.y.z-dev.n or, built from source, x.y.z-SNAPSHOT. From
 * the class loader the IDE loads the plugin with; `null` where the plugin's classes are not loaded as a plugin.
 */
object PluginVersion {
    val current: String?
        get() = (PluginVersion::class.java.classLoader as? PluginAwareClassLoader)?.pluginDescriptor?.version
}
