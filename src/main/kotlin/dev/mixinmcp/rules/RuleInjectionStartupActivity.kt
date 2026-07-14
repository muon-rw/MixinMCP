package dev.mixinmcp.rules

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import dev.mixinmcp.settings.MixinMcpSettings
import java.io.IOException
import java.net.JarURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

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
        val removedLegacy = removeLegacyCursorRules(projectRoot)

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
            destinationRoot = projectRoot.resolve(".claude"),
            overwrite = overwrite,
        )

        if (written.isNotEmpty()) {
            addToGitignore(projectRoot, written)
            LOG.info("MixinMCP: injected assistant files: ${written.joinToString()}")
            showRuleNotification(project, written, settings)
        }

        if (removedLegacy.isNotEmpty()) {
            LOG.info("MixinMCP: removed legacy Cursor rules: ${removedLegacy.joinToString()}")
        }

        if (written.isNotEmpty() || removedLegacy.isNotEmpty()) {
            refreshDirectoryRecursively(projectRoot.resolve(".cursor"))
            if (written.isNotEmpty()) {
                refreshDirectoryRecursively(projectRoot.resolve(".claude"))
            }
            refreshSingleFile(projectRoot.resolve(".gitignore"))
        }
    }

    /** Removes pre-skills rule files that older MixinMCP versions injected into `.cursor/rules/`. */
    private fun removeLegacyCursorRules(projectRoot: Path): List<String> {
        val rulesDir = projectRoot.resolve(".cursor").resolve("rules")
        val removed = mutableListOf<String>()
        for (name in LEGACY_CURSOR_RULE_FILES) {
            val path = rulesDir.resolve(name)
            if (!Files.isRegularFile(path)) continue
            try {
                Files.delete(path)
                removed += projectRoot.relativize(path).toString().replace('\\', '/')
            } catch (e: IOException) {
                LOG.warn("MixinMCP: failed to remove legacy rule '$name': ${e.message}")
            }
        }
        return removed
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
                    "<a href=\"https://github.com/muon-rw/MixinMCP#decompilation-cache\">Setup guide</a>",
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

    /**
     * Lists classpath entry names under [bundlePrefix]/.
     *
     * Uses a stable anchor resource URL — not `Class.protectionDomain.codeSource`, which often
     * points at `classes/kotlin/main` while `src/main/resources` lands in `classes/java/main`,
     * so `inject/` would be missing from that path.
     */
    private fun listBundledResourcePaths(bundlePrefix: String): List<String> {
        val classLoader = RuleInjectionStartupActivity::class.java.classLoader
        val anchorPath = injectAnchorPath(bundlePrefix)
        val url = classLoader.getResource(anchorPath) ?: run {
            LOG.warn("MixinMCP: inject anchor not on classpath: $anchorPath")
            return emptyList()
        }

        return try {
            when (url.protocol) {
                "jar" -> listInjectEntriesInJar(url, bundlePrefix)
                "file" -> listInjectEntriesOnDisk(url, bundlePrefix)
                else -> {
                    LOG.warn("MixinMCP: unsupported inject resource URL protocol: ${url.protocol} ($url)")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            LOG.warn("MixinMCP: failed to list bundled inject under '$bundlePrefix': ${e.message}")
            emptyList()
        }
    }

    private fun injectAnchorPath(bundlePrefix: String): String = when (bundlePrefix) {
        BUNDLE_CURSOR -> "$bundlePrefix/rules/minecraft-mod-project.mdc"
        BUNDLE_CLAUDE -> "$bundlePrefix/skills/mixin-writing/SKILL.md"
        else -> "$bundlePrefix/"
    }

    private fun listInjectEntriesInJar(anchorUrl: URL, bundlePrefix: String): List<String> {
        val connection = anchorUrl.openConnection() as JarURLConnection
        val searchPrefix = "$bundlePrefix/"
        return connection.jarFile.use { jar ->
            jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(searchPrefix) }
                .map { it.name }
                .sorted()
                .toList()
        }
    }

    /**
     * [anchorUrl] points at a file under the bundle (e.g. .../inject/cursor/rules/foo.mdc).
     * Classpath root is the directory that contains the `inject` segment (e.g. .../java/main).
     */
    private fun listInjectEntriesOnDisk(anchorUrl: URL, bundlePrefix: String): List<String> {
        val anchorFile = Path.of(anchorUrl.toURI())
        val bundleRoot = findInjectBundleRoot(anchorFile, bundlePrefix)
        val injectDir = bundleRoot.parent // .../inject
        val classpathRoot = injectDir.parent
        val searchPrefix = "$bundlePrefix/"
        if (!Files.isDirectory(bundleRoot)) return emptyList()
        return Files.walk(bundleRoot)
            .filter { Files.isRegularFile(it) }
            .map { classpathRoot.relativize(it).toString().replace('\\', '/') }
            .filter { it.startsWith(searchPrefix) || it == bundlePrefix }
            .sorted()
            .toList()
    }

    private fun findInjectBundleRoot(anchorFile: Path, bundlePrefix: String): Path {
        val tailDir = bundlePrefix.substringAfterLast('/')
        val injectSegment = bundlePrefix.substringBeforeLast('/') // "inject"
        var p: Path? = anchorFile
        while (p != null) {
            val parent = p.parent
            if (p.fileName.toString() == tailDir && parent?.fileName?.toString() == injectSegment) {
                return p
            }
            p = parent
        }
        throw IOException("could not resolve inject bundle root for $bundlePrefix from $anchorFile")
    }

    private fun refreshDirectoryRecursively(dir: Path) {
        if (!Files.isDirectory(dir)) return
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir) ?: return
        vf.refresh(/* asynchronous = */ true, /* recursive = */ true)
    }

    private fun refreshSingleFile(file: Path) {
        if (!Files.exists(file)) return
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: return
        vf.refresh(/* asynchronous = */ true, /* recursive = */ false)
    }

    companion object {
        private val LOG = Logger.getInstance(RuleInjectionStartupActivity::class.java)

        private const val GITIGNORE_MARKER = "# MixinMCP auto-injected rules"

        private const val BUNDLE_CURSOR = "inject/cursor"
        private const val BUNDLE_CLAUDE = "inject/claude"
    }
}

private val LEGACY_CURSOR_RULE_FILES = listOf("mixinmcp.mdc", "mixin-reference.mdc")

private val MC_BUILD_PLUGIN_PATTERNS = listOf(
    "fabric-loom",
    "net.fabricmc.loom",
    "net.neoforged.gradle",
    "net.neoforged.moddev",
    "net.minecraftforge.gradle",
    "dev.architectury",
    "org.quiltmc.loom",
)

internal fun hasGradlePlugin(root: Path): Boolean {
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

internal fun isMinecraftProject(root: Path): Boolean {
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
