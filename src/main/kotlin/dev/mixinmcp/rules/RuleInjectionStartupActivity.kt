package dev.mixinmcp.rules

import com.intellij.ide.plugins.PluginDetailsService
import com.intellij.ide.util.PropertiesComponent
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

        // The whole body is blocking filesystem IO (project-type probes, bundled-file copies) plus a git
        // subprocess; keep it off the Default pool. Notifications and PropertiesComponent are thread-safe.
        withContext(Dispatchers.IO) {
            if (!isMinecraftProject(projectRoot)) {
                if (MixinMcpAppSettings.getInstance().injectToolsSkillIntoJvmProjects && isJvmProject(projectRoot)) {
                    injectToolsSkillOnly(projectRoot, project)
                } else {
                    LOG.info("MixinMCP: project '${project.name}' is not a Minecraft mod project, skipping")
                }
                return@withContext
            }

            if (settings.autoInjectCursorRules) {
                injectAssistantFiles(projectRoot, settings, project)
            } else {
                notifyStaleSkillsOnce(project, projectRoot)
            }

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

    // The injected files are gitignored, version-stamped, plugin-managed artifacts, so this path always
    // overwrites; that keeps the stamp current on upgrade and avoids coupling to the Minecraft-only settings.
    private fun injectToolsSkillOnly(projectRoot: Path, project: Project) {
        val removedLegacy = cleanupRemovedManifestEntries(projectRoot, project)

        val written = mutableListOf<String>()
        written += copyBundledTree(
            projectRoot = projectRoot,
            bundlePrefix = BUNDLE_CURSOR,
            destinationRoot = projectRoot.resolve(".cursor"),
            overwrite = true,
            pathFilter = ::isToolsSkillPath,
        )
        written += copyBundledTree(
            projectRoot = projectRoot,
            bundlePrefix = BUNDLE_CLAUDE,
            destinationRoot = projectRoot.resolve(".claude"),
            overwrite = true,
            pathFilter = ::isToolsSkillPath,
        )

        if (written.isNotEmpty()) {
            addToGitignore(projectRoot, written)
            recordInjectedFiles(project, written)
            LOG.info("MixinMCP: injected tool skill into JVM project: ${written.joinToString()}")
            val props = PropertiesComponent.getInstance(project)
            if (!props.getBoolean(TOOLS_SKILL_NOTIFIED_KEY, false)) {
                props.setValue(TOOLS_SKILL_NOTIFIED_KEY, true)
                showToolsSkillNotification(project, written)
            }
        }

        if (written.isNotEmpty() || removedLegacy.isNotEmpty()) {
            refreshDirectoryRecursively(projectRoot.resolve(".cursor"))
            refreshDirectoryRecursively(projectRoot.resolve(".claude"))
            refreshSingleFile(projectRoot.resolve(".gitignore"))
        }
    }

    private fun isToolsSkillPath(relativeInside: String): Boolean =
        relativeInside.startsWith("skills/mixinmcp-tools/") &&
            relativeInside != "skills/mixinmcp-tools/references/toolchains.md"

    private fun showToolsSkillNotification(project: Project, written: List<String>) {
        val fileList = written.joinToString(", ")
        NotificationGroupManager.getInstance()
            .getNotificationGroup("MixinMCP")
            .createNotification(
                "MixinMCP",
                "Injected the MixinMCP tool skill into this JVM project: $fileList",
                NotificationType.INFORMATION,
            )
            .addAction(object : com.intellij.notification.NotificationAction("Don't do this again") {
                override fun actionPerformed(
                    e: com.intellij.openapi.actionSystem.AnActionEvent,
                    notification: com.intellij.notification.Notification,
                ) {
                    MixinMcpAppSettings.getInstance().injectToolsSkillIntoJvmProjects = false
                    notification.expire()
                }
            })
            .notify(project)
    }

    private fun injectAssistantFiles(projectRoot: Path, settings: MixinMcpSettings, project: Project) {
        val overwrite = settings.overwriteExistingRules
        val removedLegacy = removeLegacyCursorRules(projectRoot) + cleanupRemovedManifestEntries(projectRoot, project)

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
            recordInjectedFiles(project, written)
            LOG.info("MixinMCP: injected assistant files: ${written.joinToString()}")
            showRuleNotification(project, written, settings)
        }

        if (removedLegacy.isNotEmpty()) {
            LOG.info("MixinMCP: removed legacy or stale injected files: ${removedLegacy.joinToString()}")
        }

        if (!overwrite) {
            notifyStaleSkillsOnce(project, projectRoot)
        }

        if (written.isNotEmpty() || removedLegacy.isNotEmpty()) {
            refreshDirectoryRecursively(projectRoot.resolve(".cursor"))
            if (written.isNotEmpty() || removedLegacy.any { it.startsWith(".claude/") }) {
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
        pathFilter: (String) -> Boolean = { true },
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
            if (!pathFilter(relativeInside)) continue
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
                if (target.fileName.toString() == SKILL_FILE_NAME) {
                    stampSkillVersion(target)
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

    private fun stampSkillVersion(skill: Path) {
        val version = pluginVersion() ?: return
        try {
            val separator = if (Files.readString(skill).endsWith("\n")) "" else "\n"
            Files.writeString(skill, "$separator$STAMP_PREFIX $version $STAMP_SUFFIX\n", StandardOpenOption.APPEND)
        } catch (e: IOException) {
            LOG.warn("MixinMCP: failed to stamp '$skill': ${e.message}")
        }
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

    private fun notifyStaleSkillsOnce(project: Project, projectRoot: Path) {
        val version = pluginVersion() ?: return
        val props = PropertiesComponent.getInstance(project)
        if (props.getValue(STALE_SKILL_NOTIFIED_KEY) == version) return
        val stale = findStaleStampedSkills(projectRoot, version)
        if (stale.isEmpty()) return
        props.setValue(STALE_SKILL_NOTIFIED_KEY, version)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("MixinMCP")
            .createNotification(
                "MixinMCP",
                "Injected skill files were written by an older MixinMCP version: ${stale.joinToString(", ")}. " +
                    "Enable auto-injection in Settings | Tools | MixinMCP to refresh them.",
                NotificationType.INFORMATION,
            )
            .notify(project)
    }

    private fun findStaleStampedSkills(projectRoot: Path, version: String): List<String> {
        val stale = mutableListOf<String>()
        for (skillsDir in listOf(projectRoot.resolve(".claude/skills"), projectRoot.resolve(".cursor/skills"))) {
            if (!Files.isDirectory(skillsDir)) continue
            try {
                Files.list(skillsDir).use { dirs ->
                    dirs.map { it.resolve(SKILL_FILE_NAME) }.forEach { skill ->
                        val stamp = readSkillStamp(skill) ?: return@forEach
                        if (stamp != version) {
                            stale += projectRoot.relativize(skill).toString().replace('\\', '/')
                        }
                    }
                }
            } catch (e: IOException) {
                LOG.warn("MixinMCP: failed to scan '$skillsDir': ${e.message}")
            }
        }
        return stale
    }

    /** Deletes previously injected files no longer present in the current bundle. Touches only manifest entries. */
    private fun cleanupRemovedManifestEntries(projectRoot: Path, project: Project): List<String> {
        val manifest = loadManifest(project)
        if (manifest.isEmpty()) return emptyList()
        val expected = expectedBundleTargets() ?: return emptyList()
        val originalSize = manifest.size
        val removed = mutableListOf<String>()
        for (entry in manifest.toList()) {
            if (entry in expected || !isSafeManifestEntry(entry)) continue
            val path = projectRoot.resolve(entry)
            try {
                if (Files.isRegularFile(path)) {
                    Files.delete(path)
                    removed += entry
                }
                manifest.remove(entry)
            } catch (e: IOException) {
                LOG.warn("MixinMCP: failed to remove stale injected file '$entry': ${e.message}")
            }
        }
        if (manifest.size != originalSize) {
            saveManifest(project, manifest)
        }
        return removed
    }

    private fun expectedBundleTargets(): Set<String>? {
        val cursor = listBundledResourcePaths(BUNDLE_CURSOR)
        val claude = listBundledResourcePaths(BUNDLE_CLAUDE)
        if (cursor.isEmpty() || claude.isEmpty()) return null
        val targets = mutableSetOf<String>()
        cursor.mapTo(targets) { ".cursor/" + it.removePrefix("$BUNDLE_CURSOR/") }
        claude.mapTo(targets) { ".claude/" + it.removePrefix("$BUNDLE_CLAUDE/") }
        return targets
    }

    private fun isSafeManifestEntry(entry: String): Boolean =
        (entry.startsWith(".cursor/") || entry.startsWith(".claude/")) && ".." !in entry.split('/')

    private fun recordInjectedFiles(project: Project, written: List<String>) {
        if (written.isEmpty()) return
        saveManifest(project, loadManifest(project) + written)
    }

    private fun loadManifest(project: Project): MutableList<String> =
        PropertiesComponent.getInstance(project).getValue(MANIFEST_KEY)
            ?.lineSequence()?.filter { it.isNotBlank() }?.toMutableList()
            ?: mutableListOf()

    private fun saveManifest(project: Project, entries: Collection<String>) {
        PropertiesComponent.getInstance(project)
            .setValue(MANIFEST_KEY, entries.distinct().sorted().joinToString("\n"))
    }

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
        private val LOG = Logger.getInstance(RuleInjectionStartupActivity::class.java)

        private const val GITIGNORE_MARKER = "# MixinMCP auto-injected rules"

        private const val BUNDLE_CURSOR = "inject/cursor"
        private const val BUNDLE_CLAUDE = "inject/claude"

        private const val PLUGIN_ID = "dev.mixinmcp"
        private const val SKILL_FILE_NAME = "SKILL.md"
        private const val STAMP_PREFIX = "<!-- mixinmcp-skill-version:"
        private const val STAMP_SUFFIX = "-->"
        private const val STALE_SKILL_NOTIFIED_KEY = "mixinmcp.staleSkillNotifiedVersion"
        private const val OUTDATED_GRADLE_PLUGIN_NOTIFIED_KEY = "mixinmcp.outdatedGradlePluginNotified"
        private const val TOOLS_SKILL_NOTIFIED_KEY = "mixinmcp.toolsSkillNotified"
        private const val MANIFEST_KEY = "mixinmcp.injectedFileManifest"
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

internal fun isJvmProject(root: Path): Boolean =
    Files.exists(root.resolve("build.gradle")) ||
        Files.exists(root.resolve("build.gradle.kts")) ||
        Files.exists(root.resolve("settings.gradle")) ||
        Files.exists(root.resolve("settings.gradle.kts")) ||
        Files.exists(root.resolve("pom.xml"))

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
