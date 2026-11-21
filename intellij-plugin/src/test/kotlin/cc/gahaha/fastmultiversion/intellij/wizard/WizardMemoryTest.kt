package cc.gahaha.fastmultiversion.intellij.wizard

import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The wizard's defaults from the last project it created, kept by the IDE. */
class WizardMemoryTest : BasePlatformTestCase() {
    private val mod = ModSettings("TestMod", "testmod", "Test Mod", "io.github.alex.testmod", listOf("26.1.2"), listOf("fabric"))

    override fun setUp() {
        super.setUp()
        forget()
    }

    override fun tearDown() {
        try {
            forget()
        } finally {
            super.tearDown()
        }
    }

    private fun forget() {
        PropertiesComponent.getInstance().unsetValue("cc.gahaha.fastmultiversion.wizard.packagePrefix")
        PropertiesComponent.getInstance().unsetValue("cc.gahaha.fastmultiversion.wizard.authors")
    }

    fun testRemembersThePackagePrefixAndTheAuthors() {
        assertEquals("com.example", WizardMemory.packagePrefix)
        assertEquals("", WizardMemory.authors)

        WizardMemory.remember(mod.copy(authors = listOf("Alex", "Steve")))
        assertEquals("io.github.alex", WizardMemory.packagePrefix)
        assertEquals("Alex, Steve", WizardMemory.authors)

        // A project without authors keeps the earlier ones; one whose package is only the mod id keeps the prefix.
        WizardMemory.remember(mod.copy(group = "testmod"))
        assertEquals("io.github.alex", WizardMemory.packagePrefix)
        assertEquals("Alex, Steve", WizardMemory.authors)

        WizardMemory.remember(mod.copy(group = "net.steve.testmod", authors = listOf("Steve")))
        assertEquals("net.steve", WizardMemory.packagePrefix)
        assertEquals("Steve", WizardMemory.authors)
    }
}
