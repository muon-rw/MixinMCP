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
 * Project-level service that reads the decompilation cache manifest
 * and exposes cached roots. The cache is populated by the Gradle plugin;
 * the IDE is a read-only consumer.
 *
 * See DESIGN.md Section 11 and 11.11.5.
 */
@Service(Service.Level.PROJECT)
class DecompilationCacheService {

    private var manifest: DecompilationManifest = DecompilationManifest()

    private val cacheRoot: Path
        get() = Paths.get(System.getProperty("user.home"), ".cache", "mixinmcp", "decompiled")

    /**
     * Returns the list of currently-valid cache entries with their VirtualFile root dirs.
     * Consumed by AdditionalLibraryRootsProvider (Prompt 2).
     */
    /**
     * Ensure the VFS knows about the entire cache directory tree. Must be
     * called outside of read lock (e.g. from startup activity) before
     * getAdditionalProjectLibraries is invoked under read lock.
     */
    fun refreshVfs() {
        val root = cacheRoot.toFile()
        if (!root.isDirectory) return

        val rootVf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)
        if (rootVf != null) {
            rootVf.refresh(false, true)
            LOG.info("MixinMCP: VFS recursive refresh completed for $cacheRoot")
        }
    }

    fun getCachedRoots(): List<CachedLibraryInfo> {
        manifest = manifest.load(cacheRoot)
        val result = mutableListOf<CachedLibraryInfo>()
        var invalidJar = 0
        var missingCache = 0
        var noVirtualFile = 0

        for ((hash, entry) in manifest.entries) {
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
            "$noVirtualFile no VirtualFile (of ${manifest.entries.size} total)")
        return result
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
