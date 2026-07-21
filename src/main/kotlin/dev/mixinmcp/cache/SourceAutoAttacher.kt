package dev.mixinmcp.cache

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import dev.mixinmcp.tools.GradleCachePaths
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * After Gradle sync (and on project open), attaches missing Library **SOURCES** roots:
 * MDG merged game jars (vanilla, Forge, or NeoForge; falling back to the sibling `*-sources.jar`
 * next to the merged jar, then the universal `-sources.jar` in the Gradle cache), and the
 * IntelliJ Platform sources jar on IPGP plugin projects, so editor navigation and PSI resolve
 * real source. Candidate collection and file probing run on the service scope; only the final
 * root mutation takes an EDT write action.
 */
@Service(Service.Level.PROJECT)
class SourceAutoAttacher(
    private val project: Project,
    private val scope: CoroutineScope,
) {

    data class Report(
        val runAtMillis: Long,
        val reason: String,
        val attached: List<String>,
        val warnings: List<String>,
        // Intentionally false on Loom-style projects: their game jars attach via the maven
        // -sources.jar convention, not this attacher.
        val hadMdgMergedCandidates: Boolean,
    )

    @Volatile
    var lastReport: Report? = null
        private set

    private var pendingAttach: Job? = null

    private var platformSourcesWatch: Job? = null

    /**
     * Debounced; safe to call from any thread. The pending run is cancelled with the
     * service scope on project close.
     */
    fun schedule(reason: String) {
        if (project.isDisposed) return
        synchronized(this) {
            pendingAttach?.cancel()
            pendingAttach = scope.launch(CoroutineName("SourceAutoAttacher.debouncedAttach")) {
                delay(DEBOUNCE)
                performAttach(reason)
            }
        }
    }

    private suspend fun performAttach(reason: String) {
        if (project.isDisposed) return

        val (candidates, platformCandidates) = readAction {
            collectMergedRootCandidates(project) to collectIjPlatformCandidates(project)
        }

        if (candidates.isEmpty() && platformCandidates == null) {
            lastReport = Report(
                runAtMillis = System.currentTimeMillis(),
                reason = reason,
                attached = emptyList(),
                warnings = emptyList(),
                hadMdgMergedCandidates = false,
            )
            return
        }

        // Both probe the filesystem (Files.isRegularFile / Files.list); keep that blocking IO off the
        // service scope's Dispatchers.Default pool thread.
        val (resolved, platformResolved) = withContext(Dispatchers.IO) {
            resolveSourceUrls(candidates) to resolveIjPlatformOps(platformCandidates)
        }
        val operations: List<AttachOp> = resolved.operations + platformResolved.operations
        val attached = mutableListOf<String>()
        val warnings = (resolved.warnings + platformResolved.warnings).toMutableList()

        edtWriteAction {
            // Merged into one rootsChanged event; per-library commits would otherwise each trigger a rescan.
            ProjectRootManagerEx.getInstanceEx(project).mergeRootsChangesDuring {
                for (op in operations) {
                    if (project.isDisposed) break
                    try {
                        val model = op.library.modifiableModel
                        try {
                            for (url in op.addUrls) {
                                model.addRoot(url, OrderRootType.SOURCES)
                            }
                            model.commit()
                        } catch (t: Throwable) {
                            try {
                                model.dispose()
                            } catch (_: Throwable) {
                            }
                            throw t
                        }
                        for (url in op.addUrls) {
                            val line = "${op.libraryName} <- $url"
                            attached.add(line)
                            LOG.info("MixinMCP: auto-attached sources for library '${op.libraryName}' ($line)")
                        }
                    } catch (e: Exception) {
                        val msg = "Failed to update sources for library '${op.libraryName}': ${e.message}"
                        LOG.warn("MixinMCP: $msg", e)
                        warnings.add(msg)
                    }
                }
            }
        }

        lastReport = Report(
            runAtMillis = System.currentTimeMillis(),
            reason = reason,
            attached = attached,
            warnings = warnings,
            hadMdgMergedCandidates = candidates.isNotEmpty(),
        )

        if (platformResolved.platformSourcesPending) awaitPlatformSources(reason)
    }

    /**
     * A platform version bump leaves the new sources jar undownloaded, and it is large enough that the
     * download usually outlives the sync that triggered this attach. Without this the jar sits in the
     * Gradle cache unattached until the next sync or an IDE restart, and dependency search silently
     * returns nothing for platform classes. Poll until it lands, then attach it.
     */
    private fun awaitPlatformSources(reason: String) {
        synchronized(this) {
            if (platformSourcesWatch?.isActive == true) return
            platformSourcesWatch = scope.launch(CoroutineName("SourceAutoAttacher.awaitPlatformSources")) {
                for (wait in PLATFORM_SOURCES_RETRIES) {
                    delay(wait)
                    if (project.isDisposed) return@launch
                    val candidates = readAction { collectIjPlatformCandidates(project) }
                    // resolveIjPlatformOps probes the Gradle cache (Files.list / getLastModifiedTime).
                    val ready: Boolean = candidates
                        ?.let { withContext(Dispatchers.IO) { resolveIjPlatformOps(it) }.operations.isNotEmpty() } == true
                    if (ready) {
                        LOG.info("MixinMCP: platform sources appeared; re-running attach")
                        performAttach("$reason + platform sources download")
                        return@launch
                    }
                }
                LOG.info("MixinMCP: platform sources did not appear; giving up until the next sync")
            }
        }
    }

    private data class MergedRootCandidate(
        val library: Library,
        val libraryName: String,
        val classRoot: VirtualFile,
        val classUrl: String,
        val mergedJarPath: String,
        val jarContainsJava: Boolean,
        val existingSourceUrls: Set<String>,
    )

    private data class ResolvedAttach(
        val operations: List<AttachOp>,
        val warnings: List<String>,
        /** The platform sources jar is simply not downloaded yet, so a later run may succeed. */
        val platformSourcesPending: Boolean = false,
    )

    private data class AttachOp(
        val library: Library,
        val libraryName: String,
        val addUrls: List<String>,
    )

    /**
     * Gradle/MDG often exposes game jars on module order entries; those [Library] instances are not
     * always enumerated by [LibraryTablesRegistrar.getLibraryTable] alone.
     */
    private fun collectDistinctLibraries(project: Project): List<Library> {
        val seen: MutableSet<Library> = Collections.newSetFromMap(IdentityHashMap())
        for (library in LibraryTablesRegistrar.getInstance().getLibraryTable(project).libraries) {
            seen.add(library)
        }
        for (module in ModuleManager.getInstance(project).modules) {
            for (entry in ModuleRootManager.getInstance(module).orderEntries) {
                if (entry is LibraryOrderEntry) {
                    val library: Library = entry.library ?: continue
                    seen.add(library)
                }
            }
        }
        return seen.toList()
    }

    private fun collectMergedRootCandidates(project: Project): List<MergedRootCandidate> {
        val out = mutableListOf<MergedRootCandidate>()
        for (library in collectDistinctLibraries(project)) {
            val libName: String = library.name ?: "(unnamed)"
            val classRoots: Array<VirtualFile> = library.getFiles(OrderRootType.CLASSES)
            if (classRoots.isEmpty()) continue
            val existingSourceUrls: Set<String> = library.getUrls(OrderRootType.SOURCES)
                .map { normalizeLibraryRootUrl(it) }
                .toSet()
            val pendingClassUrls = mutableSetOf<String>()

            for (classRoot in classRoots) {
                val path: String = classRoot.path.replace('\\', '/')
                if (!isMdgMergedJarForAttach(path)) continue
                val classUrl: String = classRoot.url
                val normalizedClassUrl: String = normalizeLibraryRootUrl(classUrl)
                if (existingSourceUrls.contains(normalizedClassUrl)) continue
                if (!pendingClassUrls.add(normalizedClassUrl)) continue

                val hasJava: Boolean = jarContainsJavaSources(classRoot, maxVisited = 5000)
                out.add(
                    MergedRootCandidate(
                        library = library,
                        libraryName = libName,
                        classRoot = classRoot,
                        classUrl = classUrl,
                        mergedJarPath = path,
                        jarContainsJava = hasJava,
                        existingSourceUrls = existingSourceUrls,
                    ),
                )
            }
        }
        return out
    }

    private fun resolveSourceUrls(candidates: List<MergedRootCandidate>): ResolvedAttach {
        val operations = mutableListOf<AttachOp>()
        val warnings = mutableListOf<String>()
        val seen = mutableSetOf<Pair<Library, String>>()

        for (c in candidates) {
            val sourceUrl: String? = if (c.jarContainsJava) {
                c.classUrl
            } else {
                val sourcesJar: Path? = findSiblingSourcesJar(c.mergedJarPath)
                    ?: findForgelikeSourcesJarInGradleCache(c.mergedJarPath)?.takeIf { Files.isRegularFile(it) }
                if (sourcesJar == null) {
                    val msg =
                        "Merged jar has no .java entries (disableRecompilation?), no sibling *-sources.jar " +
                            "next to it, and no Gradle cache *-sources.jar was found for: ${c.mergedJarPath}"
                    LOG.warn("MixinMCP: $msg")
                    warnings.add(msg)
                    null
                } else {
                    VfsUtil.getUrlForLibraryRoot(sourcesJar)
                }
            }

            if (sourceUrl == null) continue
            val normalizedSourceUrl: String = normalizeLibraryRootUrl(sourceUrl)
            if (c.existingSourceUrls.contains(normalizedSourceUrl)) continue
            val key = c.library to normalizedSourceUrl
            if (!seen.add(key)) continue

            operations.add(
                AttachOp(
                    library = c.library,
                    libraryName = c.libraryName,
                    addUrls = listOf(sourceUrl),
                ),
            )
        }

        return ResolvedAttach(operations, warnings)
    }

    private data class IjPlatformTarget(
        val library: Library,
        val libraryName: String,
    )

    private data class IjPlatformCandidates(
        val distCoordinates: Set<Pair<String, String>>,
        val targets: List<IjPlatformTarget>,
    )

    /**
     * IPGP resolves the IntelliJ Platform from installer dists that have no sources variant, so
     * Gradle sync never attaches platform sources, and DevKit's manual download attaches one
     * library at a time and is wiped by the next re-import. Re-attach the DevKit-downloaded
     * sources jar from the Gradle cache to the dist and bundled plugin/module libraries.
     * Mostly for MixinMCP's own development.
     */
    private fun collectIjPlatformCandidates(project: Project): IjPlatformCandidates? {
        val distCoordinates = mutableSetOf<Pair<String, String>>()
        val targets = mutableListOf<IjPlatformTarget>()
        for (library in collectDistinctLibraries(project)) {
            val name: String = library.name ?: continue
            val coordinate: Pair<String, String>? = parseIjPlatformDistCoordinate(name)
            if (coordinate == null && !isIjPlatformBundledLibrary(name)) continue
            if (coordinate != null) distCoordinates.add(coordinate)
            // Decompiled-cache stubs (attached by older MixinMCP builds) count as absent so they
            // never block attaching real platform sources.
            val sourceUrls: Array<String> = library.getUrls(OrderRootType.SOURCES)
            if (sourceUrls.all { DecompilationCacheService.isDecompiledCachePath(VfsUtilCore.urlToPath(it)) }) {
                targets.add(IjPlatformTarget(library, name))
            }
        }
        if (distCoordinates.isEmpty() || targets.isEmpty()) return null
        return IjPlatformCandidates(distCoordinates, targets)
    }

    private fun resolveIjPlatformOps(candidates: IjPlatformCandidates?): ResolvedAttach {
        if (candidates == null) return ResolvedAttach(emptyList(), emptyList())
        val coordinate: Pair<String, String>? = candidates.distCoordinates.singleOrNull()
        if (coordinate == null) {
            val msg = "Multiple IntelliJ Platform dists on the classpath " +
                "(${candidates.distCoordinates.joinToString { "${it.first}:${it.second}" }}); " +
                "skipping platform sources attach."
            LOG.info("MixinMCP: $msg")
            return ResolvedAttach(emptyList(), listOf(msg))
        }
        val (artifactId, version) = coordinate
        val sourcesJar: Path? = findIjPlatformSourcesJar(artifactId, version)
        if (sourcesJar == null) {
            val msg = "IntelliJ Platform sources for $artifactId:$version are not in the " +
                "Gradle cache yet. If no download is in flight, run DevKit's 'Download IntelliJ " +
                "Platform sources' editor action once; MixinMCP re-attaches the jar after every sync."
            LOG.info("MixinMCP: $msg")
            return ResolvedAttach(emptyList(), listOf(msg), platformSourcesPending = true)
        }
        val url: String = VfsUtil.getUrlForLibraryRoot(sourcesJar)
        val operations: List<AttachOp> = candidates.targets.map {
            AttachOp(library = it.library, libraryName = it.libraryName, addUrls = listOf(url))
        }
        return ResolvedAttach(operations, emptyList())
    }

    // DevKit downloads platform sources as com.jetbrains.intellij.idea:<artifact>:<version>:sources,
    // where <artifact> is `idea` for 2025.3+ unified dists and ideaIC/ideaIU before; probe all.
    private fun findIjPlatformSourcesJar(artifactId: String, version: String): Path? {
        val group: Path = gradleUserHomeDir().resolve("caches/modules-2/files-2.1/com.jetbrains.intellij.idea")
        for (candidate in linkedSetOf(artifactId, "idea", "ideaIC", "ideaIU")) {
            val jar: Path? = findNewestSourcesJarUnderVersionDir(group.resolve(candidate).resolve(version))
            if (jar != null) return jar
        }
        return null
    }

    private fun normalizeLibraryRootUrl(url: String): String =
        url.trimEnd('/').lowercase()

    private fun jarContainsJavaSources(root: VirtualFile, maxVisited: Int): Boolean {
        var visited = 0
        fun visit(vf: VirtualFile): Boolean {
            if (visited >= maxVisited) return false
            visited++
            if (!vf.isDirectory) {
                return vf.name.endsWith(".java", ignoreCase = true)
            }
            for (child in vf.children) {
                if (visit(child)) return true
            }
            return false
        }
        return visit(root)
    }

    private fun gradleUserHomeDir(): Path = GradleCachePaths.gradleUserHomeDir()

    /**
     * Strips jar-root suffix (`!/`) so [Path] parsing and filename regex work on the on-disk `.jar` path.
     */
    private fun mergedJarPathOnDisk(mergedJarPath: String): Path {
        var p: String = mergedJarPath.replace('\\', '/')
        val bang: Int = p.indexOf("!/")
        if (bang >= 0) {
            p = p.substring(0, bang)
        }
        return Path.of(p)
    }

    private fun findSiblingSourcesJar(mergedJarPath: String): Path? {
        val onDisk: Path = mergedJarPathOnDisk(mergedJarPath)
        val siblingName: String = siblingSourcesJarName(onDisk.fileName.toString()) ?: return null
        return onDisk.resolveSibling(siblingName).takeIf { Files.isRegularFile(it) }
    }

    private fun findForgelikeSourcesJarInGradleCache(mergedJarPath: String): Path? {
        val fileName: String = mergedJarPathOnDisk(mergedJarPath).fileName.toString()
        val files21: Path = gradleUserHomeDir().resolve("caches/modules-2/files-2.1")

        val forgeVer: String? = Regex("""(?i)^forge-(.+)-merged\.jar$""").matchEntire(fileName)?.groupValues?.get(1)
        if (forgeVer != null) {
            val dir: Path = files21.resolve("net.minecraftforge").resolve("forge").resolve(forgeVer)
            return findNewestSourcesJarUnderVersionDir(dir)
        }

        val neoVer: String? = Regex("""(?i)^neoforge-(.+)-merged\.jar$""").matchEntire(fileName)?.groupValues?.get(1)
        if (neoVer != null) {
            val dir: Path = files21.resolve("net.neoforged").resolve("neoforge").resolve(neoVer)
            return findNewestSourcesJarUnderVersionDir(dir)
        }

        return null
    }

    private fun findNewestSourcesJarUnderVersionDir(versionDir: Path): Path? =
        GradleCachePaths.findNewestSourcesJarUnderVersionDir(versionDir)

    companion object {
        private val LOG = Logger.getInstance(SourceAutoAttacher::class.java)
        private val DEBOUNCE = 1500.milliseconds

        // Cumulative ~18 minutes, which covers a cold multi-hundred-megabyte sources download.
        private val PLATFORM_SOURCES_RETRIES = listOf(
            15.seconds, 30.seconds, 60.seconds, 2.minutes, 5.minutes, 10.minutes,
        )
        private val IJ_DIST_ARTIFACT_IDS = setOf("idea", "ideaIC", "ideaIU")

        fun getInstance(project: Project): SourceAutoAttacher = project.service()

        fun getLastReport(project: Project): Report? = getInstance(project).lastReport

        // MDG's "-merged" suffix marks its classes+sources artifact for every mode (vanilla, Forge, NeoForge).
        // Jar root paths are often "...-merged.jar!/"; do not use endsWith("merged.jar") or we never match.
        internal fun isMdgMergedJarForAttach(path: String): Boolean {
            val p: String = path.replace('\\', '/').lowercase()
            return p.contains("moddev/artifacts") && p.contains("-merged.jar")
        }

        internal fun siblingSourcesJarName(mergedJarFileName: String): String? {
            val match: MatchResult = Regex("(?i)-merged\\.jar$").find(mergedJarFileName) ?: return null
            return mergedJarFileName.substring(0, match.range.first) + "-sources.jar"
        }

        /**
         * Matches IPGP dist library names in installer form (`Gradle: idea:idea:aarch64:2026.1.4`,
         * `Gradle: idea:ideaIC:2024.3`) and Maven form (`Gradle: com.jetbrains.intellij.idea:ideaIC:2023.2.7`).
         * Returns (artifactId, version).
         */
        internal fun parseIjPlatformDistCoordinate(libraryName: String): Pair<String, String>? {
            val parts: List<String> = libraryName.removePrefix("Gradle: ").split(':')
            if (parts.size < 3) return null
            if (parts[0] != "idea" && parts[0] != "com.jetbrains.intellij.idea") return null
            if (parts[1] !in IJ_DIST_ARTIFACT_IDS) return null
            val version: String = parts.last()
            if (version.isEmpty() || !version.first().isDigit()) return null
            return parts[1] to version
        }

        internal fun isIjPlatformBundledLibrary(libraryName: String): Boolean {
            val stripped: String = libraryName.removePrefix("Gradle: ")
            return stripped.startsWith("bundledPlugin:") || stripped.startsWith("bundledModule:")
        }
    }
}
