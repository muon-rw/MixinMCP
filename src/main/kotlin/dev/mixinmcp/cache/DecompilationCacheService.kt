package dev.mixinmcp.cache

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Project-level service that reads per-project decompilation manifests
 * and exposes cached roots. The cache is populated by the Gradle plugin;
 * the IDE is a read-only consumer.
 *
 * In multiloader builds each subproject writes its own manifest at
 * <subprojectDir>/.gradle/mixinmcp/manifest.json. This service merges
 * all discovered manifests into a single set of roots.
 *
 * Falls back to the legacy global manifest at ~/.cache/mixinmcp/decompiled/
 * for backward compatibility with older Gradle plugin versions.
 *
 * See DESIGN.md Section 11 and 11.11.5.
 */
@Service(Service.Level.PROJECT)
class DecompilationCacheService(private val project: Project) {

    /**
     * Ensure the VFS knows about the entire cache directory tree. Must be
     * called outside of read lock (e.g. from startup activity) before
     * getAdditionalProjectLibraries is invoked under read lock.
     */
    fun refreshVfs() {
        val root = globalCacheRoot.toFile()
        if (!root.isDirectory) return

        val rootVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)
        if (rootVf != null) {
            rootVf.refresh(false, true)
            LOG.info("MixinMCP: VFS recursive refresh completed for $globalCacheRoot")
        }
    }

    fun getCachedRoots(): List<CachedLibraryInfo> {
        val mergedEntries = loadMergedManifestEntries()
        val result = mutableListOf<CachedLibraryInfo>()
        var missingCache = 0
        var noVirtualFile = 0

        for ((hash, entry) in mergedEntries) {
            val cachePath = Paths.get(entry.cachePath)
            if (!Files.exists(cachePath) || !Files.isDirectory(cachePath)) {
                missingCache++
                continue
            }

            val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(cachePath.toFile())
            if (virtualFile != null) {
                result.add(
                    CachedLibraryInfo(entry.libraryName, hash, entry.classesJarPath, virtualFile, entry.classpathKind),
                )
            } else {
                noVirtualFile++
            }
        }

        buildscriptRootNamesByPath = result
            .filter { it.classpathKind == "buildscript" }
            .associate { it.root.path to it.libraryName }
        lastScanStats = CacheScanStats(
            valid = result.size,
            missingCacheDir = missingCache,
            noVirtualFile = noVirtualFile,
            totalManifestEntries = mergedEntries.size,
        )

        LOG.info("MixinMCP: getCachedRoots() → ${result.size} valid, " +
            "$missingCache missing cache dir, " +
            "$noVirtualFile no VirtualFile (of ${mergedEntries.size} total)")
        return result
    }

    /**
     * Snapshot from the last getCachedRoots pass, same lifecycle as
     * buildscriptRootNamesByPath; lets hint builders distinguish an
     * unpopulated cache from one that is on disk but not yet in the VFS.
     */
    @Volatile
    var lastScanStats: CacheScanStats = CacheScanStats(0, 0, 0, 0)
        private set

    data class CacheScanStats(
        val valid: Int,
        val missingCacheDir: Int,
        val noVirtualFile: Int,
        val totalManifestEntries: Int,
    )

    /**
     * Snapshot from the last getCachedRoots pass. labelFor runs once per root per
     * enumeration and must not re-read every manifest from disk each time.
     */
    @Volatile
    private var buildscriptRootNamesByPath: Map<String, String> = emptyMap()

    fun buildscriptCacheLibraryName(root: VirtualFile): String? = buildscriptRootNamesByPath[root.path]

    /**
     * Discover per-project manifests from the root project and immediate subdirectories,
     * then merge all entries. Falls back to the legacy global manifest if no per-project
     * manifests are found.
     */
    private fun loadMergedManifestEntries(): Map<String, CacheEntry> {
        val allEntries = mutableMapOf<String, CacheEntry>()
        var manifestCount = 0
        forEachManifest { manifest ->
            allEntries.putAll(manifest.entries)
            manifestCount++
        }

        if (manifestCount > 0) {
            LOG.info("MixinMCP: loaded $manifestCount per-project manifest(s) " +
                "with ${allEntries.size} unique entries")
            return allEntries
        }

        LOG.info("MixinMCP: no per-project manifests found, falling back to global manifest")
        val globalManifest = DecompilationManifest().load(globalCacheRoot)
        return globalManifest.entries
    }

    /**
     * Newest Gradle-plugin version stamped across the project's manifests; null when no
     * manifest carries one (plugin absent, or every manifest predates version stamping).
     */
    fun installedGradlePluginVersion(): String? {
        var newest: String? = null
        forEachManifest { manifest ->
            val v: String = manifest.pluginVersion ?: return@forEachManifest
            if (newest == null || compareGradlePluginVersions(v, newest!!) > 0) newest = v
        }
        return newest
    }

    private fun forEachManifest(consume: (DecompilationManifest) -> Unit) {
        val projectDir = project.basePath?.let { Paths.get(it) } ?: return

        fun tryLoadManifest(dir: Path) {
            val manifestDir = dir.resolve(".gradle").resolve("mixinmcp")
            if (Files.exists(manifestDir.resolve("manifest.json"))) {
                consume(DecompilationManifest().load(manifestDir))
            }
        }

        tryLoadManifest(projectDir)
        try {
            Files.list(projectDir).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .filter { !it.fileName.toString().startsWith(".") }
                    .forEach { tryLoadManifest(it) }
            }
        } catch (_: Exception) {}
    }

    data class CachedLibraryInfo(
        val libraryName: String,
        val artifactHash: String,
        val classesJarPath: String,
        val root: VirtualFile,
        val classpathKind: String = "compile",
    )

    companion object {
        private val LOG = Logger.getInstance(DecompilationCacheService::class.java)

        /** Oldest Gradle plugin whose manifests cover everything this IDE plugin surfaces. */
        const val REQUIRED_GRADLE_PLUGIN_VERSION: String = "1.3.0"

        val globalCacheRoot: Path
            get() = Paths.get(System.getProperty("user.home"), ".cache", "mixinmcp", "decompiled")

        fun isDecompiledCachePath(path: String): Boolean {
            val norm: String = path.replace('\\', '/').trimEnd('/')
            val root: String = globalCacheRoot.toString().replace('\\', '/').trimEnd('/')
            return norm == root || norm.startsWith("$root/")
        }

        fun normalizeJarDiskPath(path: String): String {
            var p: String = path.replace('\\', '/')
            val bang: Int = p.indexOf('!')
            if (bang >= 0) p = p.substring(0, bang)
            return p.trimEnd('/').lowercase()
        }

        fun getInstance(project: Project): DecompilationCacheService =
            project.service()
    }
}

/** Numeric per dot segment, non-digit suffixes ignored; missing segments count as zero. */
internal fun compareGradlePluginVersions(a: String, b: String): Int {
    fun segments(v: String): List<Int> = v.split('.').map { seg ->
        seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }
    val sa: List<Int> = segments(a)
    val sb: List<Int> = segments(b)
    for (i in 0 until maxOf(sa.size, sb.size)) {
        val cmp: Int = (sa.getOrElse(i) { 0 }).compareTo(sb.getOrElse(i) { 0 })
        if (cmp != 0) return cmp
    }
    return 0
}

internal fun isGradlePluginVersionAtLeast(installed: String?, required: String): Boolean =
    installed != null && compareGradlePluginVersions(installed, required) >= 0
