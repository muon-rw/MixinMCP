package dev.mixinmcp.startup

import com.intellij.ide.plugins.PluginDetailsService
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.mixinmcp.cache.DecompilationCacheService
import dev.mixinmcp.cache.compareGradlePluginVersions
import dev.mixinmcp.cache.isGradlePluginVersionAtLeast
import dev.mixinmcp.settings.MixinMcpAppSettings
import dev.mixinmcp.settings.MixinMcpSettings
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

class StartupChecksActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = MixinMcpSettings.getInstance(project)
        val basePath = project.basePath ?: return
        val projectRoot = Path.of(basePath)

        // The whole body is blocking filesystem IO (project-type probes, legacy-file cleanup); keep it
        // off the Default pool. Notifications and PropertiesComponent are thread-safe.
        withContext(Dispatchers.IO) {
            cleanupInjectedFiles(projectRoot, project)

            if (!isMinecraftProject(projectRoot)) return@withContext

            warnMissingClaudePlugin(project)

            if (settings.warnMissingGradlePlugin) {
                if (!hasGradlePlugin(projectRoot)) {
                    showGradlePluginWarning(project, settings)
                } else {
                    val installed: String? = listOfNotNull(
                        DecompilationCacheService.getInstance(project).installedGradlePluginVersion(),
                        declaredGradlePluginVersion(projectRoot),
                    ).maxWithOrNull(::compareGradlePluginVersions)
                    if (!isGradlePluginVersionAtLeast(installed, DecompilationCacheService.REQUIRED_GRADLE_PLUGIN_VERSION)) {
                        showGradlePluginOutdatedWarning(project, settings, installed)
                    }
                }
            }
        }
    }

    /**
     * Older MixinMCP versions injected rule and skill files into `.cursor` and `.claude` and gitignored
     * them; skills now ship as a Claude Code plugin. One migration pass per project moves the files this
     * plugin can attribute to itself into a backup under the IDE system directory and strips their
     * `.gitignore` entries. Attribution requires the injection manifest, a SKILL.md version stamp, or an
     * exact known path under our `.gitignore` marker (pre-stamp injections); anything else is left alone.
     */
    private fun cleanupInjectedFiles(projectRoot: Path, project: Project) {
        val props = PropertiesComponent.getInstance(project)
        if (props.getBoolean(CLEANUP_DONE_KEY, false)) return

        val projectRootReal = try {
            projectRoot.toRealPath()
        } catch (e: IOException) {
            LOG.warn("MixinMCP: cannot resolve project root, skipping legacy cleanup: ${e.message}")
            return
        }

        val manifest = loadManifest(project).filter(::isSafeManifestEntry).toSet()
        val stampedSkillDirs = KNOWN_SKILL_DIRS.filterTo(mutableSetOf()) {
            readSkillStamp(projectRoot.resolve(it).resolve(SKILL_FILE_NAME)) != null
        }
        val gitignoreOwned = gitignoreMarkerEntries(projectRoot)
        val backupRoot = Path.of(PathManager.getSystemPath(), "mixinmcp", "removed-skills", project.locationHash)

        val removed = mutableListOf<String>()
        var failures = 0
        for (rel in (KNOWN_INJECTED_PATHS + manifest).distinct()) {
            if (!isSafeManifestEntry(rel)) continue
            if (rel !in manifest && !isConfirmedOurs(rel, stampedSkillDirs, gitignoreOwned)) continue
            val path = resolveInsideProject(projectRoot, projectRootReal, rel) ?: continue
            if (!Files.isRegularFile(path)) continue
            try {
                val backup = backupRoot.resolve(rel)
                Files.createDirectories(backup.parent)
                Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING)
                removed += rel
            } catch (e: IOException) {
                failures++
                LOG.warn("MixinMCP: failed to remove injected file '$rel': ${e.message}")
            }
        }

        if (removed.isNotEmpty()) {
            for (rel in PRUNABLE_INJECTION_DIRS) {
                deleteDirIfEmpty(projectRoot.resolve(rel), projectRootReal)
            }
            cleanGitignore(projectRoot, projectRootReal, ourLines = KNOWN_INJECTED_PATHS + manifest)
            LOG.info("MixinMCP: moved legacy injected files to '$backupRoot': ${removed.joinToString()}")
            notifyLegacyCleanup(project, removed, backupRoot)
            refreshCleanedRoots(projectRoot)
        }

        if (failures == 0) {
            if (manifest.isNotEmpty()) props.unsetValue(MANIFEST_KEY)
            props.setValue(CLEANUP_DONE_KEY, true)
        }
    }

    private fun isConfirmedOurs(rel: String, stampedSkillDirs: Set<String>, gitignoreOwned: Set<String>): Boolean =
        rel in gitignoreOwned || stampedSkillDirs.any { rel.startsWith("$it/") }

    /** Exact [KNOWN_INJECTED_PATHS] lines of a `.gitignore` that carries our marker; other lines are never claimed. */
    private fun gitignoreMarkerEntries(projectRoot: Path): Set<String> {
        val gitignore = projectRoot.resolve(".gitignore")
        if (!Files.isRegularFile(gitignore)) return emptySet()
        return try {
            val lines = Files.readString(gitignore).lines().map { it.trim() }
            if (GITIGNORE_MARKER !in lines) emptySet()
            else lines.filterTo(mutableSetOf()) { it in KNOWN_INJECTED_PATHS }
        } catch (e: IOException) {
            LOG.warn("MixinMCP: failed to read .gitignore: ${e.message}")
            emptySet()
        }
    }

    /**
     * Lexical then real-path containment, so neither `..`/separator tricks in a poisoned manifest nor a
     * symlinked ancestor (e.g. `.claude` linked to a dotfiles repo) can direct deletion outside the project.
     */
    private fun resolveInsideProject(projectRoot: Path, projectRootReal: Path, rel: String): Path? {
        val path = try {
            projectRoot.resolve(rel).normalize()
        } catch (_: InvalidPathException) {
            return null
        }
        if (!path.startsWith(projectRoot)) return null
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        return try {
            if (path.toRealPath().startsWith(projectRootReal)) path else null
        } catch (_: IOException) {
            null
        }
    }

    private fun deleteDirIfEmpty(dir: Path, projectRootReal: Path) {
        try {
            val attrs = Files.readAttributes(dir, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            // isOther catches Windows junctions, which report isDirectory under NOFOLLOW yet delete like links.
            if (!attrs.isDirectory || attrs.isOther) return
            if (!dir.toRealPath().startsWith(projectRootReal)) return
            Files.delete(dir)
        } catch (_: NoSuchFileException) {
        } catch (_: DirectoryNotEmptyException) {
        } catch (e: IOException) {
            LOG.warn("MixinMCP: failed to prune '$dir': ${e.message}")
        }
    }

    /** Drops ignore entries for injected files that no longer exist, and the marker comment once none remain. */
    private fun cleanGitignore(projectRoot: Path, projectRootReal: Path, ourLines: Collection<String>) {
        val gitignore = projectRoot.resolve(".gitignore")
        if (!Files.isRegularFile(gitignore)) return
        try {
            val content = Files.readString(gitignore)
            val ours = ourLines.toSet()
            val lines = content.lines()
            var kept = lines.filter { line ->
                val entry = line.trim()
                if (entry !in ours) return@filter true
                val path = resolveInsideProject(projectRoot, projectRootReal, entry)
                path != null && Files.isRegularFile(path)
            }
            if (kept.none { it.trim() in ours }) {
                kept = kept.filter { it.trim() != GITIGNORE_MARKER }
            }
            if (kept.size == lines.size) return
            val newline = if ("\r\n" in content) "\r\n" else "\n"
            Files.writeString(gitignore, kept.joinToString(newline).trimEnd('\r', '\n') + newline)
        } catch (e: IOException) {
            LOG.warn("MixinMCP: failed to clean .gitignore: ${e.message}")
        }
    }

    private fun notifyLegacyCleanup(project: Project, removed: List<String>, backupRoot: Path) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("MixinMCP")
            .createNotification(
                "MixinMCP",
                "Agent skills now ship via the MixinMCP Claude Code plugin, so ${removed.size} previously " +
                    "injected file(s) were removed from this project: ${removed.joinToString(", ")}. " +
                    "Copies were saved to <code>$backupRoot</code>.",
                NotificationType.INFORMATION,
            )
            .notify(project)
    }

    private fun refreshCleanedRoots(projectRoot: Path) {
        for (name in listOf(".cursor", ".claude")) {
            val dir = projectRoot.resolve(name)
            if (Files.isDirectory(dir)) refreshDirectoryRecursively(dir) else refreshNearestExistingAncestor(dir)
        }
        refreshSingleFile(projectRoot.resolve(".gitignore"))
    }

    private fun refreshNearestExistingAncestor(path: Path) {
        val existing = generateSequence(path.parent) { it.parent }.firstOrNull { Files.isDirectory(it) } ?: return
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(existing) ?: return
        vf.refresh(/* asynchronous = */ true, /* recursive = */ false)
    }

    /**
     * Presence check only: Claude Code owns install health and auto-updates its plugins. The version
     * comparison rides along because the cache layout is `cache/<marketplace>/<plugin>/<version>/`,
     * so versions are already in hand after the presence scan. Community-marketplace installs may use
     * a commit SHA as the version directory; non-semver names are excluded from the comparison.
     */
    private fun warnMissingClaudePlugin(project: Project) {
        if (!MixinMcpAppSettings.getInstance().warnMissingClaudePlugin) return
        val claudeHome = Path.of(System.getProperty("user.home")).resolve(".claude")
        if (!Files.isDirectory(claudeHome)) return
        val ideVersion = pluginVersion() ?: return
        val installed = installedClaudePluginVersions(claudeHome)
        val newest = installed.filter { SEMVER_LIKE.matches(it) }.maxWithOrNull(::compareGradlePluginVersions)
        val (gateValue, message) = when {
            installed.isEmpty() -> "missing@$ideVersion" to
                ("Claude Code is installed, but the MixinMCP Claude Code plugin is not. It ships the agent " +
                    "skills that teach Claude when and how to use the mixin_* tools. In Claude Code, run " +
                    "<code>/plugin marketplace add muon-rw/MixinMCP</code>, then " +
                    "<code>/plugin install mixinmcp@mixinmcp</code>.")
            newest != null && !isGradlePluginVersionAtLeast(newest, ideVersion) -> "$newest<$ideVersion" to
                ("The MixinMCP Claude Code plugin is outdated (installed $newest, expected $ideVersion). " +
                    "Claude Code usually auto-updates plugins; to update now, run " +
                    "<code>/plugin update mixinmcp</code> in Claude Code.")
            else -> return
        }
        val props = PropertiesComponent.getInstance()
        if (props.getValue(CLAUDE_PLUGIN_WARNED_KEY) == gateValue) return
        props.setValue(CLAUDE_PLUGIN_WARNED_KEY, gateValue)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("MixinMCP")
            .createNotification("MixinMCP", message, NotificationType.WARNING)
            .addAction(object : com.intellij.notification.NotificationAction("Don't warn again") {
                override fun actionPerformed(
                    e: com.intellij.openapi.actionSystem.AnActionEvent,
                    notification: com.intellij.notification.Notification,
                ) {
                    MixinMcpAppSettings.getInstance().warnMissingClaudePlugin = false
                    notification.expire()
                }
            })
            .notify(project)
    }

    /** Installed versions across marketplaces, from Claude Code's plugin cache layout `cache/<marketplace>/<plugin>/<version>/`. */
    private fun installedClaudePluginVersions(claudeHome: Path): List<String> {
        val cache = claudeHome.resolve("plugins").resolve("cache")
        if (!Files.isDirectory(cache)) return emptyList()
        val versions = mutableListOf<String>()
        try {
            Files.newDirectoryStream(cache).use { marketplaces ->
                for (marketplace in marketplaces) {
                    val pluginDir = marketplace.resolve(CLAUDE_PLUGIN_NAME)
                    if (!Files.isDirectory(pluginDir)) continue
                    Files.newDirectoryStream(pluginDir).use { versionDirs ->
                        for (versionDir in versionDirs) {
                            if (Files.isDirectory(versionDir)) versions += versionDir.fileName.toString()
                        }
                    }
                }
            }
        } catch (e: IOException) {
            LOG.warn("MixinMCP: failed to scan the Claude Code plugin cache: ${e.message}")
        } catch (e: DirectoryIteratorException) {
            LOG.warn("MixinMCP: failed to scan the Claude Code plugin cache: ${e.cause?.message}")
        }
        return versions
    }

    private fun showGradlePluginWarning(project: Project, settings: MixinMcpSettings) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("MixinMCP")
            .createNotification(
                "MixinMCP",
                "Gradle plugin not detected — dependencies without published sources won't be searchable. " +
                    "Add <code>id(\"dev.mixinmcp.decompile\")</code> to your build.gradle.kts plugins block " +
                    "and run <code>./gradlew genDependencySources</code>. " +
                    "<a href=\"https://github.com/muon-rw/MixinMCP#decompilation-cache-details\">Setup guide</a>",
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

    private fun showGradlePluginOutdatedWarning(project: Project, settings: MixinMcpSettings, installed: String?) {
        val required: String = DecompilationCacheService.REQUIRED_GRADLE_PLUGIN_VERSION
        val props = PropertiesComponent.getInstance(project)
        val comboKey = "${installed ?: "unknown"}<$required"
        if (props.getValue(OUTDATED_GRADLE_PLUGIN_NOTIFIED_KEY) == comboKey) return
        props.setValue(OUTDATED_GRADLE_PLUGIN_NOTIFIED_KEY, comboKey)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("MixinMCP")
            .createNotification(
                "MixinMCP",
                "The MixinMCP Gradle plugin is outdated (detected ${installed ?: "a pre-$required version"}); " +
                    "$required or newer is needed for full dependency coverage. Bump the " +
                    "<code>dev.mixinmcp.decompile</code> version and run <code>./gradlew genDependencySources</code>.",
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

    private fun readSkillStamp(skill: Path): String? {
        if (!Files.isRegularFile(skill)) return null
        return try {
            Files.readString(skill).lineSequence()
                .lastOrNull { it.startsWith(STAMP_PREFIX) }
                ?.removePrefix(STAMP_PREFIX)
                ?.removeSuffix(STAMP_SUFFIX)
                ?.trim()
        } catch (_: IOException) {
            null
        }
    }

    private fun isSafeManifestEntry(entry: String): Boolean =
        (entry.startsWith(".cursor/") || entry.startsWith(".claude/")) &&
            "\\" !in entry && ".." !in entry.split('/')

    private fun loadManifest(project: Project): List<String> =
        PropertiesComponent.getInstance(project).getValue(MANIFEST_KEY)
            ?.lineSequence()?.filter { it.isNotBlank() }?.toList()
            ?: emptyList()

    // PluginManagerCore.getPlugin, PluginManager.findEnabledPlugin, and PluginManager.getPluginByClass
    // are all @ApiStatus.Internal as of 2026.2; their javadoc points plugins here instead.
    private fun pluginVersion(): String? =
        PluginDetailsService.getInstance().findDetails(PluginId.getId(PLUGIN_ID))?.version

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
        private val LOG = Logger.getInstance(StartupChecksActivity::class.java)

        private const val GITIGNORE_MARKER = "# MixinMCP auto-injected rules"

        private const val PLUGIN_ID = "dev.mixinmcp"
        private const val CLAUDE_PLUGIN_NAME = "mixinmcp"
        private const val SKILL_FILE_NAME = "SKILL.md"
        private const val STAMP_PREFIX = "<!-- mixinmcp-skill-version:"
        private const val STAMP_SUFFIX = "-->"
        private const val OUTDATED_GRADLE_PLUGIN_NOTIFIED_KEY = "mixinmcp.outdatedGradlePluginNotified"
        private const val CLAUDE_PLUGIN_WARNED_KEY = "mixinmcp.claudePluginWarned"
        private const val MANIFEST_KEY = "mixinmcp.injectedFileManifest"
        private const val CLEANUP_DONE_KEY = "mixinmcp.legacyCleanupDone"
    }
}

private val SEMVER_LIKE = Regex("""\d+(\.\d+)+([.-].*)?""")

private val KNOWN_INJECTED_PATHS = listOf(
    ".cursor/rules/minecraft-mod-project.mdc",
    ".cursor/rules/mixinmcp.mdc",
    ".cursor/rules/mixin-reference.mdc",
    ".cursor/skills/mixinmcp-tools/SKILL.md",
    ".cursor/skills/mixinmcp-tools/references/toolchains.md",
    ".cursor/skills/mixin-writing/SKILL.md",
    ".cursor/skills/mixin-writing/references/at-reference.md",
    ".cursor/skills/mixin-writing/references/expressions-language.md",
    ".claude/skills/mixinmcp-tools/SKILL.md",
    ".claude/skills/mixinmcp-tools/references/toolchains.md",
    ".claude/skills/mixin-writing/SKILL.md",
    ".claude/skills/mixin-writing/references/at-reference.md",
    ".claude/skills/mixin-writing/references/expressions-language.md",
)

private val KNOWN_SKILL_DIRS = listOf(
    ".cursor/skills/mixinmcp-tools",
    ".cursor/skills/mixin-writing",
    ".claude/skills/mixinmcp-tools",
    ".claude/skills/mixin-writing",
)

/** Child before parent, so each level empties before its parent is tried. */
private val PRUNABLE_INJECTION_DIRS = listOf(
    ".cursor/rules",
    ".cursor/skills/mixinmcp-tools/references",
    ".cursor/skills/mixinmcp-tools",
    ".cursor/skills/mixin-writing/references",
    ".cursor/skills/mixin-writing",
    ".cursor/skills",
    ".cursor",
    ".claude/skills/mixinmcp-tools/references",
    ".claude/skills/mixinmcp-tools",
    ".claude/skills/mixin-writing/references",
    ".claude/skills/mixin-writing",
    ".claude/skills",
    ".claude",
)

private val MC_BUILD_PLUGIN_PATTERNS = listOf(
    "fabric-loom",
    "net.fabricmc.loom",
    "net.neoforged.gradle",
    "net.neoforged.moddev",
    "net.minecraftforge.gradle",
    "dev.architectury",
    "org.quiltmc.loom",
    "org.relativitymc.neo-loom",
)

internal fun hasGradlePlugin(root: Path): Boolean {
    if (gradlePluginAppliedIn(root)) return true
    return immediateChildDirs(root).any { gradlePluginAppliedIn(it) }
}

private fun gradlePluginAppliedIn(dir: Path): Boolean {
    if (Files.exists(dir.resolve(".gradle/mixinmcp/manifest.json"))) return true
    return buildFileContainsPlugin(dir.resolve("build.gradle")) ||
        buildFileContainsPlugin(dir.resolve("build.gradle.kts"))
}

private fun buildFileContainsPlugin(file: Path): Boolean {
    if (!Files.exists(file)) return false
    return try {
        "dev.mixinmcp.decompile" in Files.readString(file)
    } catch (_: IOException) {
        false
    }
}

private fun immediateChildDirs(root: Path): List<Path> {
    return try {
        Files.list(root).use { children ->
            children.filter { Files.isDirectory(it) }
                .filter { !it.fileName.toString().startsWith(".") }
                .toList()
        }
    } catch (_: IOException) {
        emptyList()
    } catch (_: UncheckedIOException) {
        emptyList()
    }
}

/**
 * Version declared next to the plugin id in a root or immediate-subproject build file.
 * Catches a just-bumped version before any sync has stamped a manifest; misses
 * version-catalog declarations, which the manifest stamp covers after the next
 * genDependencySources run.
 */
internal fun declaredGradlePluginVersion(root: Path): String? {
    declaredDecompileVersionIn(root)?.let { return it }
    for (child in immediateChildDirs(root)) {
        declaredDecompileVersionIn(child)?.let { return it }
    }
    return null
}

private fun declaredDecompileVersionIn(dir: Path): String? {
    for (name in listOf("build.gradle", "build.gradle.kts")) {
        val file = dir.resolve(name)
        if (!Files.exists(file)) continue
        val version: String? = try {
            parseDeclaredDecompileVersion(Files.readString(file))
        } catch (_: IOException) {
            null
        }
        if (version != null) return version
    }
    return null
}

internal fun parseDeclaredDecompileVersion(buildFileContent: String): String? =
    Regex("""dev\.mixinmcp\.decompile['"]\s*\)?\s*version\s*\(?\s*['"]([^'"]+)['"]""")
        .find(buildFileContent)?.groupValues?.get(1)

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

    // Scan build files for Minecraft-related plugin IDs; multiloader roots often keep
    // loader plugins only in subproject build files, so also check immediate children.
    return hasMcPluginInBuildFile(root.resolve("build.gradle")) ||
        hasMcPluginInBuildFile(root.resolve("build.gradle.kts")) ||
        hasMcPluginInChildBuildFiles(root)
}

private val CHILD_BUILD_FILE_NAMES =
    listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")

private fun hasMcPluginInChildBuildFiles(root: Path): Boolean {
    return try {
        Files.list(root).use { children ->
            children.filter { Files.isDirectory(it) }
                .anyMatch { child -> CHILD_BUILD_FILE_NAMES.any { hasMcPluginInBuildFile(child.resolve(it)) } }
        }
    } catch (_: IOException) {
        false
    } catch (_: UncheckedIOException) {
        false
    }
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
