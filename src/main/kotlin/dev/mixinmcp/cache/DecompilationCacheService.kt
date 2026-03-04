package dev.mixinmcp.cache

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
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

    private val globalCacheRoot: Path
        get() = Paths.get(System.getProperty("user.home"), ".cache", "mixinmcp", "decompiled")

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
        var invalidJar = 0
        var missingCache = 0
        var noVirtualFile = 0

        for ((hash, entry) in mergedEntries) {
            val jarFile = File(entry.classesJarPath)
            if (!entry.isValid(jarFile)) {
                if (invalidJar == 0) {
                    LOG.info("MixinMCP: first invalid JAR: ${entry.classesJarPath} " +
                        "(exists=${jarFile.exists()}, size=${jarFile.length()}/${entry.jarSize}, " +
                        "modified=${jarFile.lastModified()}/${entry.jarModified})")
                }
                invalidJar++
                continue
            }

            val cachePath = Paths.get(entry.cachePath)
            if (!Files.exists(cachePath) || !Files.isDirectory(cachePath)) {
                missingCache++
                continue
            }

            val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(cachePath.toFile())
            if (virtualFile != null) {
                result.add(CachedLibraryInfo(entry.libraryName, hash, virtualFile))
            } else {
                noVirtualFile++
            }
        }

        LOG.info("MixinMCP: getCachedRoots() → ${result.size} valid, " +
            "$invalidJar invalid JAR, $missingCache missing cache dir, " +
            "$noVirtualFile no VirtualFile (of ${mergedEntries.size} total)")
        return result
    }

    /**
     * Discover per-project manifests from the root project and immediate subdirectories,
     * then merge all entries. Falls back to the legacy global manifest if no per-project
     * manifests are found.
     */
    private fun loadMergedManifestEntries(): Map<String, CacheEntry> {
        val projectDir = project.basePath?.let { Paths.get(it) } ?: return emptyMap()
        val allEntries = mutableMapOf<String, CacheEntry>()
        var manifestCount = 0

        fun tryLoadManifest(dir: Path) {
            val manifestDir = dir.resolve(".gradle").resolve("mixinmcp")
            if (Files.exists(manifestDir.resolve("manifest.json"))) {
                val manifest = DecompilationManifest().load(manifestDir)
                allEntries.putAll(manifest.entries)
                manifestCount++
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

        if (manifestCount > 0) {
            LOG.info("MixinMCP: loaded $manifestCount per-project manifest(s) " +
                "with ${allEntries.size} unique entries")
            return allEntries
        }

        LOG.info("MixinMCP: no per-project manifests found, falling back to global manifest")
        val globalManifest = DecompilationManifest().load(globalCacheRoot)
        return globalManifest.entries
    }

    data class CachedLibraryInfo(
        val libraryName: String,
        val artifactHash: String,
        val root: com.intellij.openapi.vfs.VirtualFile,
    )

    companion object {
        private val LOG = Logger.getInstance(DecompilationCacheService::class.java)

        fun getInstance(project: Project): DecompilationCacheService =
            project.service()
    }
}
