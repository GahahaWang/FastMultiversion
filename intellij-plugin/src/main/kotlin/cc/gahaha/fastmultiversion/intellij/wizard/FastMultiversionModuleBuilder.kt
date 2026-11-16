package cc.gahaha.fastmultiversion.intellij.wizard

import com.intellij.ide.wizard.GeneratorNewProjectWizardBuilderAdapter

/**
 * Lists the [wizard][FastMultiversionWizard] under Generators in File → New → Project, as a module builder
 * (the generator extension point would list it with New Project and Empty Project instead).
 */
class FastMultiversionModuleBuilder : GeneratorNewProjectWizardBuilderAdapter(FastMultiversionWizard())
