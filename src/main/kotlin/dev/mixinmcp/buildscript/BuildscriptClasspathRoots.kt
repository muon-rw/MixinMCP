package dev.mixinmcp.buildscript

import com.intellij.openapi.application.readAction
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import dev.mixinmcp.settings.MixinMcpSettings
import dev.mixinmcp.tools.GradleCachePaths
import dev.mixinmcp.tools.source.BUILDSCRIPT_LABEL_PREFIX
import org.jetbrains.plugins.gradle.model.data.BuildScriptClasspathData
import org.jetbrains.plugins.gradle.service.GradleBuildClasspathManager
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

internal data class BuildscriptEntry(
    val pairKey: String,
    val classesRoot: VirtualFile?,
    val sourcesRoot: VirtualFile?,
    /** Every containing build uses the Kotlin DSL, whose script classpath the Kotlin plugin already indexes. */
    val kotlinDslOnly: Boolean,
)

/**
 * Enumerates the Gradle buildscript classpath (plugins applied via plugins{} or buildscript{},
 * their transitives, buildSrc runtime, Gradle distribution and generated gradle-api jars) from
 * [GradleBuildClasspathManager]. Sources resolve in three tiers: sources jars already in the
 * flat classpath (paired by name within a cache version dir), per-dependency paths recorded in
 * [BuildScriptClasspathData], then a sibling -sources.jar probe in the Gradle module cache.
 *
 * Lives in the optional Gradle module; always-loaded code reaches it only through
 * [dev.mixinmcp.tools.source.LabeledSyntheticRootsProvider] on the EP-registered provider.
 */
internal object BuildscriptClasspathRoots {

    /**
     * Only [BuildscriptClasspathSnapshot] calls this, on a background coroutine. The manager calls
     * stay outside any read action: each may [GradleBuildClasspathManager.reload], which resolves
     * every classpath path through a synchronous VFS refresh.
     */
    suspend fun collectEntries(project: Project): List<BuildscriptEntry> {
        if (!MixinMcpSettings.getInstance(project).indexBuildscriptClasspath) return emptyList()

        val manager = GradleBuildClasspathManager.getInstance(project)
        // Prime the lazily-loaded classpath map: getModuleClasspathEntries never triggers
        // the initial load itself, so the first query of a session would see an empty map.
        manager.getAllClasspathEntries()
        val modulePaths: List<Pair<String, String>> = readAction {
            ModuleManager.getInstance(project).modules.mapNotNull { module ->
                val modulePath: String = ExternalSystemApiUtil.getExternalProjectPath(module) ?: return@mapNotNull null
                val buildRoot: String = ExternalSystemApiUtil.getExternalRootProjectPath(module) ?: return@mapNotNull null
                modulePath to buildRoot
            }
        }
        val rootsByBuild = LinkedHashMap<String, LinkedHashSet<VirtualFile>>()
        for ((modulePath, buildRoot) in modulePaths) {
            val entries: List<VirtualFile> = manager.getModuleClasspathEntries(modulePath)
            if (entries.isEmpty()) continue
            rootsByBuild.getOrPut(buildRoot) { LinkedHashSet() }.addAll(entries)
        }
        if (rootsByBuild.isEmpty()) return emptyList()

        val moduleLibraryJarPaths: Set<String> = readAction { collectModuleLibraryJarPaths(project) }
        val ktsBuilds: Set<String> = rootsByBuild.keys.filterTo(mutableSetOf()) { isKotlinDslBuild(it) }

        val byKey = LinkedHashMap<String, MutableEntry>()
        for ((buildRoot, roots) in rootsByBuild) {
            val kts: Boolean = buildRoot in ktsBuilds
            for (root in roots) {
                // Directory roots are buildSrc / build-logic compiler output, already indexed
                // as module content of the imported buildSrc modules.
                if (root.isDirectory && !root.url.contains("!/")) continue
                val name: String = jarDisplayName(root)
                val diskPath: String = root.path.substringBefore("!/")
                val entry: MutableEntry = byKey.getOrPut(pairScopeKey(diskPath, name)) { MutableEntry() }
                if (isSourcesJarName(name)) {
                    entry.sources = entry.sources ?: root
                } else {
                    entry.classes = entry.classes ?: root
                }
                entry.kotlinDslOnly = entry.kotlinDslOnly && kts
            }
        }

        val hasDistLibJar: Boolean = byKey.values.any { e ->
            e.classes?.let { isGradleDistLibPath(it.path) } == true
        }
        val recordedSources: Map<String, String> = readAction { sourcesByClassesPath(project, rootsByBuild.keys) }

        return byKey.entries.mapNotNull { (key, e) ->
            val classesPath: String? = e.classes?.path?.substringBefore("!/")
            if (classesPath != null && normalizeDiskPath(classesPath) in moduleLibraryJarPaths) return@mapNotNull null
            // The generated gradle-api fat jar duplicates the dist lib jars; keep the
            // per-artifact dist jars for cleaner labels when both are present.
            if (hasDistLibJar && e.classes?.let { isGeneratedGradleApiFatJar(it.path) } == true) return@mapNotNull null
            if (e.classes == null && e.sources == null) return@mapNotNull null
            val sources: VirtualFile? = e.sources
                ?: classesPath?.let { resolveSourcesRoot(it, recordedSources) }
            BuildscriptEntry(key, e.classes, sources, e.kotlinDslOnly)
        }
    }

    fun labelForRoot(root: VirtualFile): String = labelForJar(jarDisplayName(root), root.path)

    private class MutableEntry {
        var classes: VirtualFile? = null
        var sources: VirtualFile? = null
        var kotlinDslOnly: Boolean = true
    }

    /**
     * Per-dependency sources paths recorded by Gradle sync, keyed by normalized classes-jar
     * path. Unlike the flat classpath these keep the classes/sources association explicit.
     */
    private fun sourcesByClassesPath(project: Project, buildRoots: Set<String>): Map<String, String> {
        val dataManager = ProjectDataManager.getInstance() ?: return emptyMap()
        val out = HashMap<String, String>()
        for (rootPath in buildRoots) {
            val structure = dataManager.getExternalProjectData(project, GradleConstants.SYSTEM_ID, rootPath)
                ?.externalProjectStructure ?: continue
            for (node in ExternalSystemApiUtil.findAllRecursively(structure, BuildScriptClasspathData.KEY)) {
                for (entry in node.data.classpathEntries) {
                    val sources: String = entry.sourcesFile.firstOrNull() ?: continue
                    for (classes in entry.classesFile) {
                        out.putIfAbsent(normalizeDiskPath(classes), sources)
                    }
                }
            }
        }
        return out
    }

    private fun resolveSourcesRoot(classesDiskPath: String, recordedSources: Map<String, String>): VirtualFile? {
        val recorded: Path? = recordedSources[normalizeDiskPath(classesDiskPath)]?.let { Path.of(it) }
        val jar: Path? = recorded?.takeIf { Files.isRegularFile(it) }
            ?: modulesCacheVersionDir(classesDiskPath)?.let { GradleCachePaths.findNewestSourcesJarUnderVersionDir(it) }
        if (jar == null) return null
        return VirtualFileManager.getInstance().findFileByUrl(VfsUtil.getUrlForLibraryRoot(jar))
    }

    private fun collectModuleLibraryJarPaths(project: Project): Set<String> {
        val out = mutableSetOf<String>()
        for (module in ModuleManager.getInstance(project).modules) {
            for (entry in ModuleRootManager.getInstance(module).orderEntries) {
                if (entry !is LibraryOrderEntry) continue
                val lib = entry.library ?: continue
                for (root in lib.getFiles(OrderRootType.CLASSES)) {
                    out.add(normalizeDiskPath(root.path.substringBefore("!/")))
                }
            }
        }
        return out
    }

    private fun isKotlinDslBuild(buildRoot: String): Boolean =
        File(buildRoot, "settings.gradle.kts").isFile || File(buildRoot, "build.gradle.kts").isFile
}

internal fun jarDisplayName(root: VirtualFile): String =
    root.url.substringBefore("!/").trimEnd('/').substringAfterLast('/')

internal fun pairKey(jarFileName: String): String =
    jarFileName.removeSuffix(".jar").removeSuffix("-sources")

/**
 * Pairing scope: the cache version dir when the jar is in the Gradle module cache (classes and
 * sources live in sibling hash dirs there), else the jar's parent dir. Keeps identically named
 * jars of unrelated artifacts from merging into one entry.
 */
internal fun pairScopeKey(jarDiskPath: String, jarFileName: String): String {
    val scope: String = modulesCacheVersionDir(jarDiskPath)?.toString()
        ?: Path.of(jarDiskPath).parent?.toString().orEmpty()
    return normalizeDiskPath(scope) + "::" + pairKey(jarFileName)
}

internal fun isSourcesJarName(jarFileName: String): Boolean =
    jarFileName.endsWith("-sources.jar")

internal fun isGradleDistLibPath(path: String): Boolean =
    path.replace('\\', '/').contains("/wrapper/dists/")

internal fun isGeneratedGradleApiFatJar(path: String): Boolean =
    path.replace('\\', '/').contains("/generated-gradle-jars/")

internal fun isGradleDistributionPath(path: String): Boolean =
    isGradleDistLibPath(path) || isGeneratedGradleApiFatJar(path)

internal fun labelForJar(jarFileName: String, path: String): String {
    val name: String = pairKey(jarFileName)
    return if (isGradleDistributionPath(path)) {
        "$BUILDSCRIPT_LABEL_PREFIX (Gradle distribution): $name"
    } else {
        "$BUILDSCRIPT_LABEL_PREFIX: $name"
    }
}

internal fun normalizeDiskPath(path: String): String =
    path.replace('\\', '/').trimEnd('/').lowercase()

/**
 * Version directory for a jar in the Gradle module cache
 * (modules-2/files-2.1/group/artifact/version/hash/x.jar); null for jars elsewhere.
 * Sibling hash dirs under it may hold a -sources.jar the flat classpath lacks.
 */
internal fun modulesCacheVersionDir(classesJarDiskPath: String): Path? {
    if (!classesJarDiskPath.replace('\\', '/').contains("/modules-2/files-2.1/")) return null
    return Path.of(classesJarDiskPath).parent?.parent
}
