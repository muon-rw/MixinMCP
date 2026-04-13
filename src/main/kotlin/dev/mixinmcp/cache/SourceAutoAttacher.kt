package dev.mixinmcp.cache

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.Alarm
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * After Gradle sync (and on project open), attaches MDG merged game jars as Library **SOURCES** roots
 * so `mixin_search_in_deps` can grep vanilla / Forge / NeoForge `.java` entries shipped inside the merged
 * artifact (or falls back to the universal `-sources.jar` in the Gradle cache when recompilation is off).
 */
object SourceAutoAttacher {

    data class Report(
        val runAtMillis: Long,
        val reason: String,
        val attached: List<String>,
        val warnings: List<String>,
        val hadMdgMergedCandidates: Boolean,
    )

    private val LOG = Logger.getInstance(SourceAutoAttacher::class.java)
    private val reportsByBasePath = ConcurrentHashMap<String, Report>()
    private val alarms = ConcurrentHashMap<Project, Alarm>()

    private const val DEBOUNCE_MS = 1500

    fun getLastReport(project: Project): Report? =
        project.basePath?.let { reportsByBasePath[it] }

    /**
     * Debounced schedule on the EDT. Safe to call from any thread.
     */
    fun schedule(project: Project, reason: String) {
        if (project.isDisposed) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val alarm = alarms.computeIfAbsent(project) { p ->
                Disposer.register(p) {
                    alarms.remove(p)?.cancelAllRequests()
                }
                Alarm(Alarm.ThreadToUse.SWING_THREAD, p)
            }
            alarm.cancelAllRequests()
            alarm.addRequest({ performAttach(project, reason) }, DEBOUNCE_MS)
        }
    }

    private fun performAttach(project: Project, reason: String) {
        if (project.isDisposed) return

        val candidates: List<MergedRootCandidate> = ReadAction.compute<List<MergedRootCandidate>, Throwable> {
            collectMergedRootCandidates(project)
        }

        if (candidates.isEmpty()) {
            storeReport(
                project,
                Report(
                    runAtMillis = System.currentTimeMillis(),
                    reason = reason,
                    attached = emptyList(),
                    warnings = emptyList(),
                    hadMdgMergedCandidates = false,
                ),
            )
            return
        }

        val resolved = resolveSourceUrls(candidates)
        val attached = mutableListOf<String>()
        val warnings = resolved.warnings.toMutableList()

        ApplicationManager.getApplication().runWriteAction {
            for (op in resolved.operations) {
                if (project.isDisposed) break
                try {
                    val model = op.library.modifiableModel
                    try {
                        model.addRoot(op.sourceUrl, OrderRootType.SOURCES)
                        model.commit()
                    } catch (t: Throwable) {
                        try {
                            model.dispose()
                        } catch (_: Throwable) {
                        }
                        throw t
                    }
                    val line = "${op.libraryName} <- ${op.sourceUrl}"
                    attached.add(line)
                    LOG.info("MixinMCP: auto-attached MDG sources for library '${op.libraryName}' ($line)")
                } catch (e: Exception) {
                    val msg = "Failed to attach sources for library '${op.libraryName}': ${e.message}"
                    LOG.warn("MixinMCP: $msg", e)
                    warnings.add(msg)
                }
            }
        }

        storeReport(
            project,
            Report(
                runAtMillis = System.currentTimeMillis(),
                reason = reason,
                attached = attached,
                warnings = warnings,
                hadMdgMergedCandidates = true,
            ),
        )
    }

    private fun storeReport(project: Project, report: Report) {
        project.basePath?.let { reportsByBasePath[it] = report }
    }

    private data class MergedRootCandidate(
        val library: Library,
        val libraryName: String,
        val classRoot: VirtualFile,
        val classUrl: String,
        val mergedJarPath: String,
        val jarContainsJava: Boolean,
    )

    private data class ResolvedAttach(
        val operations: List<AttachOp>,
        val warnings: List<String>,
    )

    private data class AttachOp(
        val library: Library,
        val libraryName: String,
        val sourceUrl: String,
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
            val existingSource = library.getUrls(OrderRootType.SOURCES)
                .map { normalizeLibraryRootUrl(it) }
                .toMutableSet()

            for (classRoot in classRoots) {
                val path: String = classRoot.path.replace('\\', '/')
                if (!isMdgMergedJarForAttach(path)) continue
                val classUrl: String = classRoot.url
                if (existingSource.contains(normalizeLibraryRootUrl(classUrl))) continue

                val hasJava: Boolean = jarContainsJavaSources(classRoot, maxVisited = 5000)
                out.add(
                    MergedRootCandidate(
                        library = library,
                        libraryName = libName,
                        classRoot = classRoot,
                        classUrl = classUrl,
                        mergedJarPath = path,
                        jarContainsJava = hasJava,
                    ),
                )
                existingSource.add(normalizeLibraryRootUrl(classUrl))
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
                val gradleJar: Path? = findForgelikeSourcesJarInGradleCache(c.mergedJarPath)
                if (gradleJar == null) {
                    val msg =
                        "Merged jar has no .java entries (disableRecompilation?) and no Gradle cache *-sources.jar " +
                            "was found for: ${c.mergedJarPath}"
                    LOG.warn("MixinMCP: $msg")
                    warnings.add(msg)
                    null
                } else {
                    if (!Files.isRegularFile(gradleJar)) {
                        val msg = "Gradle cache sources JAR path is not a file: $gradleJar"
                        LOG.warn("MixinMCP: $msg")
                        warnings.add(msg)
                        null
                    } else {
                        VfsUtil.getUrlForLibraryRoot(gradleJar)
                    }
                }
            }

            if (sourceUrl == null) continue
            val key = c.library to normalizeLibraryRootUrl(sourceUrl)
            if (!seen.add(key)) continue

            operations.add(
                AttachOp(
                    library = c.library,
                    libraryName = c.libraryName,
                    sourceUrl = sourceUrl,
                ),
            )
        }

        return ResolvedAttach(operations, warnings)
    }

    private fun isMdgMergedJarForAttach(path: String): Boolean {
        val p: String = path.replace('\\', '/').lowercase()
        if (!p.contains("moddev/artifacts")) return false
        // Jar root paths are often "...-merged.jar!/" — do not use endsWith("merged.jar") or we never match.
        if (!p.contains("-merged.jar")) return false
        val isForge: Boolean = p.contains("forge") && !p.contains("neoforge")
        val isNeo: Boolean = p.contains("neoforge")
        return isForge || isNeo
    }

    private fun normalizeLibraryRootUrl(url: String): String =
        url.trimEnd('/').lowercase()

    private fun jarContainsJavaSources(root: VirtualFile, maxVisited: Int): Boolean {
        var visited: Int = 0
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

    private fun gradleUserHomeDir(): Path {
        val env: String? = System.getenv("GRADLE_USER_HOME")
        if (!env.isNullOrBlank()) {
            return Path.of(env)
        }
        return Path.of(System.getProperty("user.home"), ".gradle")
    }

    /**
     * Strips jar-root suffix (`!/`) so [Path] parsing and filename regex work on the on-disk `.jar` path.
     */
    private fun mergedJarFileNameOnDisk(mergedJarPath: String): String {
        var p: String = mergedJarPath.replace('\\', '/')
        val bang: Int = p.indexOf("!/")
        if (bang >= 0) {
            p = p.substring(0, bang)
        }
        return Path.of(p).fileName.toString()
    }

    private fun findForgelikeSourcesJarInGradleCache(mergedJarPath: String): Path? {
        val fileName: String = mergedJarFileNameOnDisk(mergedJarPath)
        val files21: Path = gradleUserHomeDir().resolve("caches/modules-2/files-2.1")

        val forgeVer: String? = Regex("""(?i)^forge-(.+)-merged\.jar$""").matchEntire(fileName)?.groupValues?.get(1)
        if (forgeVer != null) {
            val dir: Path = files21.resolve("net.minecraftforge").resolve("forge").resolve(forgeVer)
            return findAnySourcesJarUnderVersionDir(dir)
        }

        val neoVer: String? = Regex("""(?i)^neoforge-(.+)-merged\.jar$""").matchEntire(fileName)?.groupValues?.get(1)
        if (neoVer != null) {
            val dir: Path = files21.resolve("net.neoforged").resolve("neoforge").resolve(neoVer)
            return findAnySourcesJarUnderVersionDir(dir)
        }

        return null
    }

    private fun findAnySourcesJarUnderVersionDir(versionDir: Path): Path? {
        if (!Files.isDirectory(versionDir)) return null
        return try {
            Files.list(versionDir).use { hashStream ->
                for (hashDir in hashStream.filter { Files.isDirectory(it) }) {
                    Files.list(hashDir).use { fileStream ->
                        for (f in fileStream) {
                            val n: String = f.fileName.toString().lowercase()
                            if (n.endsWith("-sources.jar")) return f
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
