package cc.gahaha.fastmultiversion.intellij.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginRepositoryRegistrarTest {
    @Test
    fun usesTheReleasesRepositoryExceptForBuildsFromSource() {
        assertEquals("https://gahahawang.github.io/FastMultiversion/updatePlugins.xml", PluginRepositoryRegistrar.repositoryFor("0.3.0"))
        assertNull(PluginRepositoryRegistrar.repositoryFor("0.4.0-SNAPSHOT"))
    }
}
