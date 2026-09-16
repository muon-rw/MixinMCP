package dev.mixinmcp.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Deletes the MixinMCP decompilation cache.
 *
 * Default: deletes the project manifest and the global cache entries it references.
 * --global: deletes the entire global cache directory (~/.cache/mixinmcp/decompiled/).
 */
@DisableCachingByDefault(because = "deletes external cache state; there is nothing to cache")
abstract class CleanCacheTask : DefaultTask() {

    @get:Input
    @get:Option(option = "global", description = "Delete the entire global cache, not just this project's entries.")
    var global: Boolean = false

    /** Set by MixinDecompilePlugin at configuration time. */
    @get:Internal
    var projectDir: File? = null

    private val globalCacheRoot: Path
        get() = Paths.get(System.getProperty("user.home"), ".cache", "mixinmcp", "decompiled")

    private val projectManifestRoot: Path
        get() = projectDir?.toPath()?.resolve(".gradle")?.resolve("mixinmcp")
            ?: globalCacheRoot

    @TaskAction
    fun clean() {
        if (global) {
            cleanGlobal()
        } else {
            cleanProject()
        }
    }

    private fun cleanProject() {
        val manifestPath = projectManifestRoot
        val entries = DecompilationManifest().load(manifestPath).entries +
            DecompilationManifest().load(manifestPath, DecompilationManifest.ADHOC_MANIFEST_FILE).entries

        var deletedEntries = 0
        for ((hash, entry) in entries) {
            val cacheDir = globalCacheRoot.resolve(hash).toFile()
            if (cacheDir.isDirectory) {
                deleteRecursively(cacheDir)
                deletedEntries++
                logger.lifecycle("Deleted cache entry: ${entry.libraryName} ($hash)")
            }
        }

        deleteManifests(manifestPath)

        logger.lifecycle("MixinMCP: cleaned $deletedEntries cache entries for this project.")
        logger.lifecycle("Run ./gradlew genDependencySources to re-decompile.")
    }

    private fun cleanGlobal() {
        val cacheRoot = globalCacheRoot.toFile()
        if (cacheRoot.isDirectory) {
            val count = cacheRoot.listFiles()?.count { it.isDirectory } ?: 0
            deleteRecursively(cacheRoot)
            logger.lifecycle("MixinMCP: deleted entire global cache ($count entries) at ${cacheRoot.path}")
        } else {
            logger.lifecycle("MixinMCP: no global cache found at ${cacheRoot.path}")
        }

        deleteManifests(projectManifestRoot)

        logger.lifecycle("Run ./gradlew genDependencySources to re-decompile.")
    }

    private fun deleteManifests(manifestRoot: Path) {
        for (name in listOf(DecompilationManifest.MANIFEST_FILE, DecompilationManifest.ADHOC_MANIFEST_FILE)) {
            val file = manifestRoot.resolve(name).toFile()
            if (file.exists()) {
                file.delete()
                logger.lifecycle("Deleted project manifest: ${file.path}")
            }
        }
        manifestRoot.resolve(MixinDecompileTask.UNRESOLVED_MARKER_FILE).toFile().delete()
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }
}
