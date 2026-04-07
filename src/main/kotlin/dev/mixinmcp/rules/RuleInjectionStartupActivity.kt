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
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

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
            injectAssistantFiles(projectRoot, settings, project)
        }

        if (settings.warnMissingGradlePlugin && !hasGradlePlugin(projectRoot)) {
            showGradlePluginWarning(project, settings)
        }
    }

    private fun injectAssistantFiles(projectRoot: Path, settings: MixinMcpSettings, project: Project) {
        val overwrite = settings.overwriteExistingRules
        val written = mutableListOf<String>()

        written += copyBundledTree(
            projectRoot = projectRoot,
            bundlePrefix = BUNDLE_CURSOR,
            destinationRoot = projectRoot.resolve(".cursor"),
            overwrite = overwrite,
        )
        written += copyBundledTree(
            projectRoot = projectRoot,
            bundlePrefix = BUNDLE_CLAUDE,
            destinationRoot = projectRoot.resolve("claude"),
            overwrite = overwrite,
        )

        if (written.isEmpty()) return

        addToGitignore(projectRoot, written)

        val refreshDirs = listOf(projectRoot.resolve(".cursor"), projectRoot.resolve("claude"))
        for (dir in refreshDirs) {
            if (Files.isDirectory(dir)) {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)
            }
        }

        LOG.info("MixinMCP: injected assistant files: ${written.joinToString()}")
        showRuleNotification(project, written, settings)
    }

    private fun showRuleNotification(project: Project, written: List<String>, settings: MixinMcpSettings) {
        val fileList = written.joinToString(", ")
        NotificationGroupManager.getInstance()
            .getNotificationGroup("MixinMCP")
            .createNotification(
                "MixinMCP",
                "Injected Cursor + Claude project files: $fileList",
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
            val alreadyIgnored = gitCheckIgnore(projectRoot, written)
            val content = Files.readString(gitignore)
            val existingLines = content.lineSequence().map { it.trim() }.toSet()
            val missing = written.filter { rel ->
                rel !in alreadyIgnored && rel !in existingLines
            }
            if (missing.isEmpty()) return

            val block = buildString {
                if (!content.endsWith("\n")) append("\n")
                if (GITIGNORE_MARKER !in content) append("\n$GITIGNORE_MARKER\n")
                for (path in missing) {
                    appendLine(path)
                }
            }

            Files.writeString(gitignore, block, StandardOpenOption.APPEND)
        } catch (e: IOException) {
            LOG.warn("MixinMCP: failed to update .gitignore: ${e.message}")
        }
    }

    /**
     * Returns the subset of [paths] that git already considers ignored.
     * Falls back to an empty set if git is not available or the command fails.
     */
    private fun gitCheckIgnore(projectRoot: Path, paths: List<String>): Set<String> {
        return try {
            val process = ProcessBuilder("git", "check-ignore", *paths.toTypedArray())
                .directory(projectRoot.toFile())
                .redirectErrorStream(true)
                .start()
            val exited = process.waitFor(5, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                return emptySet()
            }
            process.inputStream.bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
        } catch (e: Exception) {
            LOG.info("MixinMCP: git check-ignore unavailable, skipping gitignore update: ${e.message}")
            emptySet()
        }
    }

    /**
     * Copies everything under classpath [bundlePrefix] (e.g. `inject/cursor`) into [destinationRoot],
     * preserving subdirectories. Returns project-relative POSIX paths for files written.
     */
    private fun copyBundledTree(
        projectRoot: Path,
        bundlePrefix: String,
        destinationRoot: Path,
        overwrite: Boolean,
    ): List<String> {
        val entries = listBundledResourcePaths(bundlePrefix)
        if (entries.isEmpty()) {
            LOG.info("MixinMCP: no bundled resources under '$bundlePrefix'")
            return emptyList()
        }

        val classLoader = RuleInjectionStartupActivity::class.java.classLoader
        val prefix = "$bundlePrefix/"
        val written = mutableListOf<String>()

        for (entry in entries) {
            if (!entry.startsWith(prefix)) continue
            val relativeInside = entry.removePrefix(prefix)
            val target = destinationRoot.resolve(relativeInside)
            if (!overwrite && Files.exists(target)) continue

            val stream = classLoader.getResourceAsStream(entry) ?: run {
                LOG.warn("MixinMCP: missing stream for bundled resource '$entry'")
                continue
            }

            try {
                Files.createDirectories(target.parent)
                stream.use { input ->
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                }
                written += projectRoot.relativize(target).toString().replace('\\', '/')
            } catch (e: IOException) {
                LOG.warn("MixinMCP: failed to write '$target': ${e.message}")
            }
        }

        return written
    }

    private fun listBundledResourcePaths(bundlePrefix: String): List<String> {
        val normalized = bundlePrefix.trim().trim('/')
        val searchPrefix = "$normalized/"

        val codeSource = RuleInjectionStartupActivity::class.java.protectionDomain.codeSource
        val location = codeSource?.location ?: run {
            LOG.warn("MixinMCP: no code source location for bundled inject resources")
            return emptyList()
        }

        val rootPath = try {
            Path.of(location.toURI())
        } catch (e: Exception) {
            LOG.warn("MixinMCP: invalid code source URI: ${e.message}")
            return emptyList()
        }

        return when {
            rootPath.isRegularFile() && rootPath.extension.equals("jar", ignoreCase = true) ->
                ZipFile(rootPath.toFile()).use { jar ->
                    jar.entries().asSequence()
                        .filter { !it.isDirectory && it.name.startsWith(searchPrefix) }
                        .map { it.name }
                        .sorted()
                        .toList()
                }
            Files.isDirectory(rootPath) -> {
                val base = rootPath.resolve(normalized)
                if (!Files.isDirectory(base)) {
                    emptyList()
                } else {
                    Files.walk(base)
                        .filter { Files.isRegularFile(it) }
                        .map { rootPath.relativize(it).toString().replace('\\', '/') }
                        .filter { it.startsWith(normalized) }
                        .sorted()
                        .toList()
                }
            }
            else -> emptyList()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(RuleInjectionStartupActivity::class.java)

        private const val GITIGNORE_MARKER = "# MixinMCP auto-injected rules"

        private const val BUNDLE_CURSOR = "inject/cursor"
        private const val BUNDLE_CLAUDE = "inject/claude"

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
    }
}
