package dev.mixinmcp.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected

class MixinMcpSettingsConfigurable(private val project: Project) : BoundConfigurable("MixinMCP") {

    private val settings get() = MixinMcpSettings.getInstance(project)

    override fun createPanel() = panel {
        group("Cursor Rules") {
            lateinit var masterCheckbox: com.intellij.ui.dsl.builder.Cell<javax.swing.JCheckBox>
            row {
                masterCheckbox = checkBox("Automatically add Cursor rules to Minecraft projects")
                    .bindSelected(settings::autoInjectCursorRules)
            }
            row {
                checkBox("Overwrite existing rules on project open")
                    .bindSelected(settings::overwriteExistingRules)
                    .enabledIf(masterCheckbox.selected)
            }
        }
        group("Gradle Plugin") {
            row {
                checkBox("Warn when MixinMCP Gradle plugin is not detected")
                    .bindSelected(settings::warnMissingGradlePlugin)
            }
        }
    }
}
