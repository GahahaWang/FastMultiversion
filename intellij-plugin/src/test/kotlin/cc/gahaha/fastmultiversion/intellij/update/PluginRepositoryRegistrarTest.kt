package cc.gahaha.fastmultiversion.intellij.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginRepositoryRegistrarTest {
    @Test
    fun usesTheChannelOfTheVersionExceptForBuildsFromSource() {
        assertEquals("https://gahahawang.github.io/FastMultiversion/updatePlugins.xml", PluginRepositoryRegistrar.repositoryFor("1.0.0"))
        assertEquals("https://gahahawang.github.io/FastMultiversion/updatePlugins-dev.xml", PluginRepositoryRegistrar.repositoryFor("1.1.0-dev.3"))
        assertNull(PluginRepositoryRegistrar.repositoryFor("1.0.1-SNAPSHOT"))
    }
}
