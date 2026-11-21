package cc.gahaha.fastmultiversion.intellij.wizard

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
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.CheckBoxList
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.util.ui.JBUI
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.nio.file.Path
import javax.swing.Icon

/** File → New → Project → FastMultiversion: a Minecraft mod for several Minecraft versions and loaders. */
class FastMultiversionWizard : GeneratorNewProjectWizard {
    override val id: String = "cc.gahaha.fastmultiversion"
    override val name: String = "FastMultiversion"
    override val icon: Icon = IconLoader.getIcon("/icons/fastmultiversion.svg", FastMultiversionWizard::class.java)
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
    private val versionChoice = VersionChoice()
    private val versionList = CheckBoxList<String>()
    private val snapshotsProperty = propertyGraph.property(false)
    private val fabricProperty = propertyGraph.property(true)
    private val neoforgeProperty = propertyGraph.property(true)
    private val bothLoadersProperty = propertyGraph.lazyProperty { fabricProperty.get() && neoforgeProperty.get() }
    private val mainLoaderProperty = propertyGraph.property("fabric")
    private val licenseProperty = propertyGraph.property(License.ALL_RIGHTS_RESERVED)
    private val descriptionProperty = propertyGraph.property("")
    private val authorsProperty = propertyGraph.property("")
    private val websiteProperty = propertyGraph.property("")
    private val repositoryProperty = propertyGraph.property("")
    private val issueTrackerProperty = propertyGraph.property("")

    init {
        baseData?.let { base ->
            modIdProperty.dependsOn(base.nameProperty, deleteWhenModified = true) { ModSettings.suggestModId(base.name) }
            modNameProperty.dependsOn(base.nameProperty, deleteWhenModified = true) { base.name }
        }
        groupProperty.dependsOn(modIdProperty, deleteWhenModified = true) { "com.example." + modIdProperty.get() }
        bothLoadersProperty.dependsOn(fabricProperty) { fabricProperty.get() && neoforgeProperty.get() }
        bothLoadersProperty.dependsOn(neoforgeProperty) { fabricProperty.get() && neoforgeProperty.get() }
        versionList.setCheckBoxListListener { index, ticked -> versionList.getItemAt(index)?.let { versionChoice.tick(it, ticked) } }
        snapshotsProperty.afterChange { snapshots ->
            versionChoice.snapshots = snapshots
            showVersions()
        }
        showVersions()
    }

    /** Fills the list with the versions [versionChoice] shows, ticked as chosen. */
    private fun showVersions() {
        versionList.clear()
        for (version in versionChoice.shown()) {
            versionList.addItem(version.id, version.id, versionChoice.isTicked(version.id))
        }
    }

    /** Reads Mojang's version list in the background, then shows it; [status] gets what the list is. */
    private fun loadVersions(status: (String) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val versions = MinecraftVersions.fetch(CACHE, Fetcher(::readText))
            ApplicationManager.getApplication().invokeLater({
                if (versions != null && versions.isNotEmpty()) {
                    versionChoice.loaded(versions)
                    showVersions()
                    status("Minecraft 26.1 and newer, from Mojang's version list")
                } else {
                    status("Mojang's version list could not be read: the versions this plugin knows")
                }
            }, ModalityState.any())
        }
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
            val list = cell(JBScrollPane(versionList))
                .applyToComponent { preferredSize = JBUI.size(280, 150) }
                .comment("Loading the versions from Mojang…")
                .validationOnApply {
                    val chosen = versionChoice.chosen()
                    if (chosen.isEmpty()) error("Choose at least one Minecraft version")
                    else ModSettings.parseVersions(chosen.joinToString(", ")).exceptionOrNull()?.let { error(it.message ?: "") }
                }
            loadVersions { status -> list.comment?.text = status }
        }.layout(RowLayout.LABEL_ALIGNED)
        builder.row("") {
            checkBox("Show snapshots").bindSelected(snapshotsProperty)
                .comment("Snapshots, pre-releases and release candidates")
        }
        builder.row("Loaders:") {
            checkBox("Fabric").bindSelected(fabricProperty)
            checkBox("NeoForge").bindSelected(neoforgeProperty)
                .validationOnApply { if (!fabricProperty.get() && !neoforgeProperty.get()) error("Choose at least one loader") else null }
        }
        builder.row("Main loader:") {
            segmentedButton(ModSettings.LOADERS) { text = LOADER_NAMES.getValue(it) }.bind(mainLoaderProperty)
        }.visibleIf(bothLoadersProperty)
            .rowComment("The shared code compiles against Minecraft as its Gradle plugin sets it up, and the mod's access changes "
                + "are one file in its format (a class tweaker or an access transformer), converted for the other loader.")
        builder.row("License:") {
            comboBox(License.entries).bindItem(licenseProperty) // shown by their display names
        }
        builder.collapsibleGroup("Optional Settings") {
            row("Description:") { textField().bindText(descriptionProperty).columns(COLUMNS_LARGE) }
            row("Authors:") { textField().bindText(authorsProperty).columns(COLUMNS_LARGE).comment("Separated by commas") }
            row("Website:") { textField().bindText(websiteProperty).columns(COLUMNS_LARGE) }
            row("Repository:") { textField().bindText(repositoryProperty).columns(COLUMNS_LARGE) }
            row("Issue tracker:") { textField().bindText(issueTrackerProperty).columns(COLUMNS_LARGE) }
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
            versions = ModSettings.parseVersions(versionChoice.chosen().joinToString(", ")).getOrThrow(),
            loaders = ModSettings.LOADERS.filter { (it == "fabric" && fabricProperty.get()) || (it == "neoforge" && neoforgeProperty.get()) },
            mainLoader = mainLoaderProperty.get(),
            license = licenseProperty.get(),
            description = descriptionProperty.get(),
            authors = ModSettings.parseAuthors(authorsProperty.get()),
            website = websiteProperty.get(),
            repository = repositoryProperty.get(),
            issueTracker = issueTrackerProperty.get(),
        )
        val pluginVersion = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "0.0.0-SNAPSHOT"
        val lookUp = ThrowableComputable<DependencyVersions, RuntimeException> { VersionLookup(CACHE, Fetcher(::readText)).resolve(settings) }
        val versions = if (ApplicationManager.getApplication().isDispatchThread) {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(lookUp, "Looking up Fabric and NeoForge versions", false, project)
        } else {
            lookUp.compute()
        }
        ProjectGenerator.generate(dir, settings, versions, PluginRepository.of(pluginVersion))
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)
        project.service<GradleLinking>().linkAndSync(dir)
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
        val LOADER_NAMES = mapOf("fabric" to "Fabric", "neoforge" to "NeoForge")

        fun readText(url: String): String? = try {
            HttpRequests.request(url).userAgent(USER_AGENT).connectTimeout(5000).readTimeout(15000).readString()
        } catch (e: IOException) {
            null
        }

        /** What the wizard read online (versions of Minecraft, the loaders and their Gradle plugins), for creating projects offline. */
        val CACHE: ResponseCache by lazy { FileResponseCache(Path.of(PathManager.getSystemPath(), "fastmultiversion", "downloads")) }
    }
}
