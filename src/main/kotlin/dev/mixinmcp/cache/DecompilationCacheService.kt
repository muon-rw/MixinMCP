package dev.mixinmcp.cache

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.java.decompiler.api.Decompiler
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Project-level service that decompiles library JARs without sources
 * and manages the file cache on disk.
 *
 * See DESIGN.md Section 11.
 */
@Service(Service.Level.PROJECT)
class DecompilationCacheService {

    private var manifest: DecompilationManifest = DecompilationManifest()

    private val cacheRoot: Path
        get() = Paths.get(System.getProperty("user.home"), ".cache", "mixinmcp", "decompiled")

    /**
     * Refresh the decompilation cache: enumerate libraries needing decompilation,
     * decompile cache misses via Vineflower, update manifest, delete orphans.
     *
     * Called from a background task. All file I/O and Vineflower calls happen
     * on the calling thread (no EDT). ReadAction is needed only for library enumeration.
     */
    fun refreshCache(project: Project) {
        val librariesToProcess = ReadAction.compute<List<LibraryJarInfo>, Throwable> {
            enumerateLibrariesNeedingDecompilation(project)
        } ?: return

        manifest = manifest.load(cacheRoot)
        val newEntries = manifest.entries.toMutableMap()

        for ((libraryName, classesJarPaths) in librariesToProcess) {
            for (jarPath in classesJarPaths) {
                val jarFile = File(jarPath)
                if (!jarFile.exists()) continue

                val jarSize = jarFile.length()
                val jarModified = jarFile.lastModified()
                val hash = DecompilationManifest.computeArtifactHash(jarPath, jarSize, jarModified)

                val existingEntry = newEntries[hash]
                if (existingEntry != null && existingEntry.isValid(jarFile)) {
                    continue
                }

                val cacheDir = cacheRoot.resolve(hash).toFile()
                Files.createDirectories(cacheDir.toPath())

                try {
                    decompileWithVineflower(jarFile, cacheDir)
                    val entry = CacheEntry(
                        libraryName = libraryName,
                        classesJarPath = jarPath,
                        jarSize = jarSize,
                        jarModified = jarModified,
                        cachePath = cacheDir.absolutePath,
                        decompilerVersion = "vineflower-1.11.2",
                        createdAt = System.currentTimeMillis(),
                    )
                    newEntries[hash] = entry
                    manifest = DecompilationManifest(entries = newEntries)
                    manifest.save(cacheRoot)
                } catch (e: Exception) {
                    // Log and continue with other JARs
                    if (Files.exists(cacheDir.toPath())) {
                        cacheDir.deleteRecursively()
                    }
                }
            }
        }

        // Delete orphaned cache entries (JAR removed or hash changed)
        val currentHashes = librariesToProcess.flatMap { it.classesJarPaths }
            .mapNotNull { path ->
                val f = File(path)
                if (f.exists()) DecompilationManifest.computeArtifactHash(path, f.length(), f.lastModified())
                else null
            }
            .toSet()

        val toRemove = newEntries.keys.filter { it !in currentHashes }
        for (hash in toRemove) {
            val entry = newEntries.remove(hash) ?: continue
            val cachePath = Paths.get(entry.cachePath)
            if (Files.exists(cachePath)) {
                try {
                    Files.walk(cachePath)
                        .sorted(Comparator.reverseOrder())
                        .forEach { Files.deleteIfExists(it) }
                } catch (_: Exception) { }
            }
        }

        manifest = DecompilationManifest(entries = newEntries)
        manifest.save(cacheRoot)
    }

    /**
     * Returns the list of currently-valid cache entries with their VirtualFile root dirs.
     * Consumed by AdditionalLibraryRootsProvider (Prompt 2).
     */
    fun getCachedRoots(): List<CachedLibraryInfo> {
        manifest = manifest.load(cacheRoot)
        val result = mutableListOf<CachedLibraryInfo>()

        for ((hash, entry) in manifest.entries) {
            val jarFile = File(entry.classesJarPath)
            if (!entry.isValid(jarFile)) continue

            val cachePath = Paths.get(entry.cachePath)
            if (!Files.exists(cachePath) || !Files.isDirectory(cachePath)) continue

            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(cachePath.toFile())
            if (virtualFile != null) {
                result.add(CachedLibraryInfo(entry.libraryName, hash, virtualFile))
            }
        }

        return result
    }

    private fun enumerateLibrariesNeedingDecompilation(project: Project): List<LibraryJarInfo> {
        val result = mutableMapOf<String, MutableSet<String>>()

        for (module in ModuleManager.getInstance(project).modules) {
            val orderEntries = ModuleRootManager.getInstance(module).orderEntries

            for (entry in orderEntries) {
                if (entry is JdkOrderEntry) continue
                if (entry !is LibraryOrderEntry) continue

                val library = entry.library ?: continue
                val sources = library.getFiles(OrderRootType.SOURCES).toList()
                val classes = library.getFiles(OrderRootType.CLASSES).toList()

                if (sources.isNotEmpty() || classes.isEmpty()) continue

                val libraryName = library.name ?: "unknown"
                val jarPaths = classes.mapNotNull { vf ->
                    val path = vf.path
                    if (path.endsWith(".jar", ignoreCase = true)) path else null
                }

                if (jarPaths.isNotEmpty()) {
                    result.getOrPut(libraryName) { mutableSetOf() }.addAll(jarPaths)
                }
            }
        }

        return result.map { (name, paths) -> LibraryJarInfo(name, paths.toList()) }
    }

    private fun decompileWithVineflower(jarFile: File, cacheDir: File) {
        val decompiler = Decompiler.builder()
            .inputs(jarFile)
            .output(DirectoryResultSaver(cacheDir))
            .option(IFernflowerPreferences.REMOVE_SYNTHETIC, "0")
            .logger(IFernflowerLogger.NO_OP)
            .build()
        decompiler.decompile()
    }

    data class LibraryJarInfo(val libraryName: String, val classesJarPaths: List<String>)

    data class CachedLibraryInfo(
        val libraryName: String,
        val artifactHash: String,
        val root: com.intellij.openapi.vfs.VirtualFile,
    )

    companion object {
        fun getInstance(project: Project): DecompilationCacheService =
            project.service()
    }
}
