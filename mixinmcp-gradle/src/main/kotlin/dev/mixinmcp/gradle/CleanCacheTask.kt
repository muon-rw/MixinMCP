package dev.mixinmcp.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Deletes the MixinMCP decompilation cache.
 *
 * Default: deletes the project manifest and the global cache entries it references.
 * --global: deletes the entire global cache directory (~/.cache/mixinmcp/decompiled/).
 */
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
        val manifest = DecompilationManifest().load(manifestPath)

        var deletedEntries = 0
        for ((hash, entry) in manifest.entries) {
            val cacheDir = globalCacheRoot.resolve(hash).toFile()
            if (cacheDir.isDirectory) {
                deleteRecursively(cacheDir)
                deletedEntries++
                logger.lifecycle("Deleted cache entry: ${entry.libraryName} ($hash)")
            }
        }

        // Delete the project manifest and unresolved marker
        val manifestFile = manifestPath.resolve("manifest.json").toFile()
        val unresolvedFile = manifestPath.resolve(MixinDecompileTask.UNRESOLVED_MARKER_FILE).toFile()
        if (manifestFile.exists()) {
            manifestFile.delete()
            logger.lifecycle("Deleted project manifest: ${manifestFile.path}")
        }
        if (unresolvedFile.exists()) {
            unresolvedFile.delete()
        }

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

        // Also delete the project manifest
        val manifestFile = projectManifestRoot.resolve("manifest.json").toFile()
        val unresolvedFile = projectManifestRoot.resolve(MixinDecompileTask.UNRESOLVED_MARKER_FILE).toFile()
        if (manifestFile.exists()) {
            manifestFile.delete()
            logger.lifecycle("Deleted project manifest: ${manifestFile.path}")
        }
        if (unresolvedFile.exists()) {
            unresolvedFile.delete()
        }

        logger.lifecycle("Run ./gradlew genDependencySources to re-decompile.")
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }
}
