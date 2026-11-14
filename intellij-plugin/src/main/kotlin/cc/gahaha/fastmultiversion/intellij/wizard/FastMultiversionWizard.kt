package cc.gahaha.fastmultiversion.intellij.wizard

import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.GitNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.NewProjectWizardChainStep.Companion.nextStep
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.nio.file.Path
import javax.swing.Icon
import org.jetbrains.plugins.gradle.service.project.open.linkAndRefreshGradleProject

/** File → New → Project → FastMultiversion: a Minecraft mod for several Minecraft versions and loaders. */
class FastMultiversionWizard : GeneratorNewProjectWizard {
    override val id: String = "cc.gahaha.fastmultiversion"
    override val name: String = "FastMultiversion"
    override val icon: Icon = AllIcons.Nodes.Module
    override val description: String =
        "A Minecraft mod built for several Minecraft versions and loaders from one source tree, with FastMultiversion."

    override fun createStep(context: WizardContext): NewProjectWizardStep =
        RootNewProjectWizardStep(context)
            .nextStep(::NewProjectWizardBaseStep)
            .nextStep(::GitNewProjectWizardStep)
            .nextStep(::FastMultiversionStep)
}

/** The mod: its id, name and package, the Minecraft versions and the loaders. */
class FastMultiversionStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {
    private val modIdProperty = propertyGraph.lazyProperty { ModSettings.suggestModId(baseData?.name ?: "") }
    private val modNameProperty = propertyGraph.lazyProperty { baseData?.name ?: "My Mod" }
    private val groupProperty = propertyGraph.lazyProperty { "com.example." + modIdProperty.get() }
    private val versionsProperty = propertyGraph.property(VersionLookup.DEFAULT_MINECRAFT_VERSIONS.joinToString(", "))
    private val fabricProperty = propertyGraph.property(true)
    private val neoforgeProperty = propertyGraph.property(true)

    init {
        baseData?.let { base ->
            modIdProperty.dependsOn(base.nameProperty, deleteWhenModified = true) { ModSettings.suggestModId(base.name) }
            modNameProperty.dependsOn(base.nameProperty, deleteWhenModified = true) { base.name }
        }
        groupProperty.dependsOn(modIdProperty, deleteWhenModified = true) { "com.example." + modIdProperty.get() }
    }

    override fun setupUI(builder: Panel) {
        builder.row("Mod ID:") {
            textField().bindText(modIdProperty).columns(COLUMNS_MEDIUM)
                .validationOnInput { field -> ModSettings.checkModId(field.text)?.let { error(it) } }
                .validationOnApply { field -> ModSettings.checkModId(field.text)?.let { error(it) } }
        }
        builder.row("Mod name:") {
            textField().bindText(modNameProperty).columns(COLUMNS_MEDIUM)
                .validationOnApply { field -> if (field.text.isBlank()) error("Enter the mod's name") else null }
        }
        builder.row("Package:") {
            textField().bindText(groupProperty).columns(COLUMNS_MEDIUM)
                .validationOnInput { field -> ModSettings.checkGroup(field.text)?.let { error(it) } }
                .validationOnApply { field -> ModSettings.checkGroup(field.text)?.let { error(it) } }
        }
        builder.row("Minecraft versions:") {
            textField().bindText(versionsProperty).columns(COLUMNS_LARGE)
                .comment("Comma-separated; Minecraft 26.1 and newer")
                .validationOnApply { field -> ModSettings.parseVersions(field.text).exceptionOrNull()?.let { error(it.message ?: "") } }
        }
        builder.row("Loaders:") {
            checkBox("Fabric").bindSelected(fabricProperty)
            checkBox("NeoForge").bindSelected(neoforgeProperty)
                .validationOnApply { if (!fabricProperty.get() && !neoforgeProperty.get()) error("Choose at least one loader") else null }
        }
    }

    override fun setupProject(project: Project) {
        val base = baseData ?: return
        val dir = Path.of(base.path, base.name)
        val settings = ModSettings(
            projectName = base.name,
            modId = modIdProperty.get(),
            modName = modNameProperty.get().trim(),
            group = groupProperty.get(),
            versions = ModSettings.parseVersions(versionsProperty.get()).getOrThrow(),
            loaders = ModSettings.LOADERS.filter { (it == "fabric" && fabricProperty.get()) || (it == "neoforge" && neoforgeProperty.get()) },
        )
        val pluginVersion = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "0.0.0-SNAPSHOT"
        val lookUp = ThrowableComputable<DependencyVersions, RuntimeException> { VersionLookup(Fetcher(::readText)).resolve(settings) }
        val versions = if (ApplicationManager.getApplication().isDispatchThread) {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(lookUp, "Looking up Fabric and NeoForge versions", false, project)
        } else {
            lookUp.compute()
        }
        ProjectGenerator.generate(dir, settings, versions, PluginRepository.of(pluginVersion))
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)
        linkAndRefreshGradleProject(dir.toString(), project)
        warnAboutGaps(project, settings, versions)
    }

    private fun warnAboutGaps(project: Project, settings: ModSettings, versions: DependencyVersions) {
        val missing = versions.missing(settings)
        if (missing.isNotEmpty()) {
            NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATIONS)
                .createNotification("FastMultiversion project", "Not found, set them in gradle.properties: " + missing.joinToString(", ") + ".", NotificationType.WARNING)
                .notify(project)
        }
    }

    private companion object {
        const val PLUGIN_ID = "cc.gahaha.fastmultiversion"
        const val NOTIFICATIONS = "FastMultiversion"
        const val USER_AGENT = "FastMultiversion IntelliJ plugin"

        fun readText(url: String): String? = try {
            HttpRequests.request(url).userAgent(USER_AGENT).connectTimeout(5000).readTimeout(15000).readString()
        } catch (e: IOException) {
            null
        }
    }
}
