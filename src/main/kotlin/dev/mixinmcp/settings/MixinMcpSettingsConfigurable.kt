package dev.mixinmcp.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected

class MixinMcpSettingsConfigurable(private val project: Project) : BoundConfigurable("MixinMCP") {

    private val settings get() = MixinMcpSettings.getInstance(project)

    override fun createPanel() = panel {
        group("Cursor & Claude Project Files") {
            lateinit var masterCheckbox: com.intellij.ui.dsl.builder.Cell<javax.swing.JCheckBox>
            row {
                masterCheckbox = checkBox(
                    "Automatically add Cursor (.cursor) and Claude (.claude) skills and rules to Minecraft projects",
                )
                    .bindSelected(settings::autoInjectCursorRules)
            }
            row {
                checkBox("Overwrite existing files on project open")
                    .bindSelected(settings::overwriteExistingRules)
                    .enabledIf(masterCheckbox.selected)
            }
        }
        group("Gradle Plugin") {
            row {
                checkBox("Warn when the MixinMCP Gradle plugin is missing or outdated")
                    .bindSelected(settings::warnMissingGradlePlugin)
            }
        }
        group("Buildscript Classpath") {
            row {
                checkBox("Index the Gradle buildscript classpath (plugins, buildSrc, Gradle API)")
                    .bindSelected(settings::indexBuildscriptClasspath)
            }
            row {
                comment(
                    "Makes Gradle plugin classes (Loom, ModDevGradle, etc.) searchable by every MixinMCP " +
                        "tool and visible in goto-class. Costs a one-time index of roughly one extra " +
                        "Minecraft jar. When off, exact-FQCN lookup still works through a non-indexed " +
                        "fallback; broad search and text grep do not cover buildscript classes.",
                )
            }
        }
        group("Non-Minecraft JVM Projects") {
            row {
                checkBox("Also inject the MixinMCP tool skill into non-Minecraft JVM projects")
                    .bindSelected(MixinMcpAppSettings.getInstance()::injectToolsSkillIntoJvmProjects)
            }
            row {
                comment(
                    "Applies to every JVM project you open, so assistants prefer MixinMCP for classpath and " +
                        "dependency search. Minecraft-specific skills and references are not included.",
                )
            }
        }
    }
}
