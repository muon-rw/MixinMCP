package dev.mixinmcp.rules

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import dev.mixinmcp.settings.MixinMcpSettings
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class RuleInjectionStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = MixinMcpSettings.getInstance(project)
        val basePath = project.basePath ?: return
        val projectRoot = Path.of(basePath)

        if (!isMinecraftProject(projectRoot)) {
            LOG.info("MixinMCP: project '${project.name}' is not a Minecraft mod project, skipping")
            return
        }

        if (settings.autoInjectCursorRules) {
            injectCursorRules(projectRoot, settings, project)
        }

        if (settings.warnMissingGradlePlugin && !hasGradlePlugin(projectRoot)) {
            showGradlePluginWarning(project, settings)
        }
    }

    private fun injectCursorRules(projectRoot: Path, settings: MixinMcpSettings, project: Project) {
        val rulesDir = projectRoot.resolve(".cursor").resolve("rules")
        val written = mutableListOf<String>()

        for (ruleName in RULE_FILES) {
            val target = rulesDir.resolve(ruleName)
            if (!settings.overwriteExistingRules && Files.exists(target)) {
                continue
            }

            val content = loadBundledRule(ruleName)
            if (content == null) {
                LOG.warn("MixinMCP: bundled rule '$ruleName' not found in plugin resources")
                continue
            }

            try {
                Files.createDirectories(target.parent)
                Files.writeString(
                    target, content,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                )
                written.add(ruleName)
            } catch (e: IOException) {
                LOG.warn("MixinMCP: failed to write rule '$ruleName': ${e.message}")
            }
        }

        if (written.isNotEmpty()) {
            addToGitignore(projectRoot, written)
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rulesDir)
            LOG.info("MixinMCP: injected cursor rules: ${written.joinToString()}")
            showRuleNotification(project, written, settings)
        }
    }

    private fun showRuleNotification(project: Project, written: List<String>, settings: MixinMcpSettings) {
        val fileList = written.joinToString(", ") { it }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("MixinMCP")
            .createNotification(
                "MixinMCP",
                "Injected Cursor rules: $fileList",
                NotificationType.INFORMATION,
            )
            .addAction(object : com.intellij.notification.NotificationAction("Don't do this again") {
                override fun actionPerformed(
                    e: com.intellij.openapi.actionSystem.AnActionEvent,
                    notification: com.intellij.notification.Notification,
                ) {
                    settings.autoInjectCursorRules = false
                    notification.expire()
                }
            })
            .notify(project)
    }

    private fun showGradlePluginWarning(project: Project, settings: MixinMcpSettings) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("MixinMCP")
            .createNotification(
                "MixinMCP",
                "Gradle plugin not detected — dependencies without published sources won't be searchable. " +
                    "Add <code>id(\"dev.mixinmcp.decompile\")</code> to your build.gradle.kts plugins block " +
                    "and run <code>./gradlew genDependencySources</code>. " +
                    "<a href=\"https://github.com/muon-rpc/MixinMCP#decompilation-cache\">Setup guide</a>",
                NotificationType.WARNING,
            )
            .addAction(object : com.intellij.notification.NotificationAction("Don't warn again") {
                override fun actionPerformed(
                    e: com.intellij.openapi.actionSystem.AnActionEvent,
                    notification: com.intellij.notification.Notification,
                ) {
                    settings.warnMissingGradlePlugin = false
                    notification.expire()
                }
            })
            .notify(project)
    }

    private fun addToGitignore(projectRoot: Path, written: List<String>) {
        val gitignore = projectRoot.resolve(".gitignore")
        if (!Files.exists(gitignore)) return

        try {
            val content = Files.readString(gitignore)
            val missing = written.filter { ".cursor/rules/$it" !in content }
            if (missing.isEmpty()) return

            val block = buildString {
                if (!content.endsWith("\n")) append("\n")
                if (GITIGNORE_MARKER !in content) append("\n$GITIGNORE_MARKER\n")
                for (name in missing) {
                    appendLine(".cursor/rules/$name")
                }
            }

            Files.writeString(gitignore, block, StandardOpenOption.APPEND)
        } catch (e: IOException) {
            LOG.warn("MixinMCP: failed to update .gitignore: ${e.message}")
        }
    }

    companion object {
        private val LOG = Logger.getInstance(RuleInjectionStartupActivity::class.java)

        private const val GITIGNORE_MARKER = "# MixinMCP auto-injected rules"

        private val RULE_FILES = listOf("mixinmcp.mdc", "mixin-reference.mdc")

        private val MC_BUILD_PLUGIN_PATTERNS = listOf(
            "fabric-loom",
            "net.fabricmc.loom",
            "net.neoforged.gradle",
            "net.neoforged.moddev",
            "net.minecraftforge.gradle",
            "dev.architectury",
            "org.quiltmc.loom",
        )

        fun hasGradlePlugin(root: Path): Boolean {
            if (Files.exists(root.resolve(".gradle/mixinmcp/manifest.json"))) return true

            fun buildFileContainsPlugin(file: Path): Boolean {
                if (!Files.exists(file)) return false
                return try {
                    "dev.mixinmcp.decompile" in Files.readString(file)
                } catch (_: IOException) {
                    false
                }
            }

            return buildFileContainsPlugin(root.resolve("build.gradle")) ||
                buildFileContainsPlugin(root.resolve("build.gradle.kts"))
        }

        fun isMinecraftProject(root: Path): Boolean {
            // Fabric
            if (Files.exists(root.resolve("fabric.mod.json")) ||
                Files.exists(root.resolve("src/main/resources/fabric.mod.json"))
            ) return true

            // Forge / NeoForge
            if (Files.exists(root.resolve("src/main/resources/META-INF/mods.toml")) ||
                Files.exists(root.resolve("src/main/resources/META-INF/neoforge.mods.toml"))
            ) return true

            // MixinMCP Gradle plugin already configured
            if (Files.exists(root.resolve(".gradle/mixinmcp/manifest.json"))) return true

            // Scan build files for Minecraft-related plugin IDs
            return hasMcPluginInBuildFile(root.resolve("build.gradle")) ||
                hasMcPluginInBuildFile(root.resolve("build.gradle.kts"))
        }

        private fun hasMcPluginInBuildFile(buildFile: Path): Boolean {
            if (!Files.exists(buildFile)) return false
            return try {
                val content = Files.readString(buildFile)
                MC_BUILD_PLUGIN_PATTERNS.any { it in content }
            } catch (_: IOException) {
                false
            }
        }

        private fun loadBundledRule(name: String): String? {
            val stream = RuleInjectionStartupActivity::class.java
                .getResourceAsStream("/cursor-rules/$name")
                ?: return null
            return stream.bufferedReader().use { it.readText() }
        }
    }
}
