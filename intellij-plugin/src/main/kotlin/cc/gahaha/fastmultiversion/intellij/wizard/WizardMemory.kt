package cc.gahaha.fastmultiversion.intellij.wizard

import com.intellij.ide.util.PropertiesComponent

/**
 * What the wizard keeps of the last project it created, as the defaults of the next: the package prefix and the
 * authors. Kept by the IDE, for all projects.
 */
object WizardMemory {
    private const val PACKAGE_PREFIX = "cc.gahaha.fastmultiversion.wizard.packagePrefix"
    private const val AUTHORS = "cc.gahaha.fastmultiversion.wizard.authors"

    /** The package prefix of the last project, `com.example` before the first. */
    val packagePrefix: String
        get() = PropertiesComponent.getInstance().getValue(PACKAGE_PREFIX, ModSettings.DEFAULT_PACKAGE_PREFIX)

    /** The authors of the last project that had any, as the wizard shows them: `Alex, Steve`. */
    val authors: String
        get() = PropertiesComponent.getInstance().getValue(AUTHORS, "")

    /** Keeps the package prefix and the authors of [settings]; a project without authors keeps the earlier ones. */
    fun remember(settings: ModSettings) {
        val properties = PropertiesComponent.getInstance()
        ModSettings.packagePrefix(settings.group, settings.modId)?.let { properties.setValue(PACKAGE_PREFIX, it) }
        if (settings.authors.isNotEmpty()) {
            properties.setValue(AUTHORS, settings.authors.joinToString(", "))
        }
    }
}
