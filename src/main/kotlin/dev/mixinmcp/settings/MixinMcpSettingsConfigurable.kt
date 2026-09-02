package dev.mixinmcp.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import dev.mixinmcp.tools.source.LabeledSyntheticRootsProvider

class MixinMcpSettingsConfigurable(private val project: Project) : BoundConfigurable("MixinMCP") {

    private val settings get() = MixinMcpSettings.getInstance(project)

    override fun createPanel() = panel {
        group("Claude Code Plugin") {
            row {
                checkBox("Warn when the MixinMCP Claude Code plugin is missing or outdated")
                    .bindSelected(MixinMcpAppSettings.getInstance()::warnMissingClaudePlugin)
            }
            row {
                comment(
                    "Agent skills ship as a Claude Code plugin instead of files injected into the project. " +
                        "Install in Claude Code: /plugin marketplace add muon-rw/MixinMCP, then " +
                        "/plugin install mixinmcp@mixinmcp.",
                )
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
    }

    override fun apply() {
        val indexBuildscriptBefore: Boolean = settings.indexBuildscriptClasspath
        super.apply()
        if (settings.indexBuildscriptClasspath != indexBuildscriptBefore) {
            AdditionalLibraryRootsProvider.EP_NAME.extensionList
                .filterIsInstance<LabeledSyntheticRootsProvider>()
                .forEach { it.indexingSettingChanged(project) }
        }
    }
}
