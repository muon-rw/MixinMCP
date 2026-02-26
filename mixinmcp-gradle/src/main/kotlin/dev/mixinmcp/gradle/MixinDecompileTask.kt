package dev.mixinmcp.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.jetbrains.java.decompiler.api.Decompiler
import org.jetbrains.java.decompiler.main.decompiler.DirectoryResultSaver
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Decompiles dependency JARs without -sources.jar into ~/.cache/mixinmcp/decompiled/.
 * See DESIGN.md Section 11.11.
 *
 * Vineflower spawns N decompiler threads internally (default = CPU count). Each
 * thread builds an SSA variable graph per class that can consume hundreds of MB.
 * On a 12-core machine with a large JAR, this means 12 threads x ~800MB = ~10GB.
 *
 * To stay within a reasonable heap budget, this task limits Vineflower's thread
 * count (default 2). Override with --threads. If you still hit OOM, increase the
 * Gradle daemon heap in gradle.properties:
 *   org.gradle.jvmargs=-Xmx4g
 */
abstract class MixinDecompileTask : DefaultTask() {

    @get:Input
    @get:Option(option = "threads", description = "Vineflower decompiler threads (default 2). Lower = less memory.")
    var threads: Int = 2

    private val cacheRoot: Path
        get() = Paths.get(System.getProperty("user.home"), ".cache", "mixinmcp", "decompiled")

    @TaskAction
    fun decompile() {
        val runtimeClasspath = project.configurations.findByName("runtimeClasspath") ?: run {
            logger.lifecycle("No runtimeClasspath configuration found, skipping decompilation")
            return
        }

        val resolvedArtifacts = runtimeClasspath.resolvedConfiguration.resolvedArtifacts
        val artifactsByModule = resolvedArtifacts.groupBy {
            "${it.moduleVersion.id.group}:${it.moduleVersion.id.name}:${it.moduleVersion.id.version}"
        }

        val withoutSources = artifactsByModule
            .filter { (_, artifacts) ->
                artifacts.none { it.classifier == "sources" }
            }
            .flatMap { (_, artifacts) ->
                artifacts.filter { it.extension == "jar" }
            }
            .filter { artifact ->
                !isJdkJar(artifact.file)
            }
            .sortedBy { it.file.length() }

        var manifest = DecompilationManifest().load(cacheRoot)
        val currentJarHashes = mutableSetOf<String>()
        var decompiled = 0
        var cached = 0
        var failed = 0
        val total = withoutSources.size

        logger.lifecycle("MixinMCP: ${total} JARs without sources, threads=$threads")

        for ((index, artifact) in withoutSources.withIndex()) {
            val jarFile = artifact.file
            val jarPath = jarFile.absolutePath
            val jarSize = jarFile.length()
            val jarModified = jarFile.lastModified()
            val hash = DecompilationManifest.computeArtifactHash(jarPath, jarSize, jarModified)
            currentJarHashes.add(hash)

            val libraryName = "${artifact.moduleVersion.id.group}:${artifact.moduleVersion.id.name}:${artifact.moduleVersion.id.version}"
            val sizeMb = jarSize / 1024 / 1024
            val sizeKb = jarSize / 1024
            val sizeStr = if (sizeMb > 0) "${sizeMb}MB" else "${sizeKb}KB"
            val progress = "[${index + 1}/$total]"

            val existingEntry = manifest.entries[hash]
            if (existingEntry != null && existingEntry.isValid(jarFile)) {
                logger.lifecycle("$progress Already cached: $libraryName ($sizeStr) Skipping...")
                cached++
                continue
            }

            val cacheDir = cacheRoot.resolve(hash).toFile()
            Files.createDirectories(cacheDir.toPath())

            logger.lifecycle("$progress Decompiling: $libraryName ($sizeStr)")

            try {
                val decompiler = Decompiler.builder()
                    .inputs(jarFile)
                    .output(DirectoryResultSaver(cacheDir))
                    .option(IFernflowerPreferences.THREADS, threads.toString())
                    .option(IFernflowerPreferences.REMOVE_SYNTHETIC, "0")
                    .logger(IFernflowerLogger.NO_OP)
                    .build()
                decompiler.decompile()

                val entry = CacheEntry(
                    libraryName = libraryName,
                    classesJarPath = jarPath,
                    jarSize = jarSize,
                    jarModified = jarModified,
                    cachePath = cacheDir.absolutePath + File.separator,
                    decompilerVersion = "vineflower-1.11.2",
                    createdAt = System.currentTimeMillis(),
                )
                manifest = DecompilationManifest(manifest.entries + (hash to entry))
                manifest.save(cacheRoot)
                decompiled++
                logger.lifecycle("$progress Done! $libraryName")
            } catch (e: OutOfMemoryError) {
                logger.error("$progress OOM decompiling $libraryName ($sizeStr) — skipping. " +
                    "Try: --threads=1 or org.gradle.jvmargs=-Xmx6g in gradle.properties")
                deleteRecursively(cacheDir)
                failed++
                System.gc()
            } catch (e: Exception) {
                logger.warn("$progress Failed: $libraryName — ${e.message}")
                deleteRecursively(cacheDir)
                failed++
            }

            System.gc()
        }

        val orphanedHashes = manifest.entries.keys - currentJarHashes
        if (orphanedHashes.isNotEmpty()) {
            val newEntries = manifest.entries.toMutableMap()
            for (hash in orphanedHashes) {
                newEntries.remove(hash)
                val orphanDir = cacheRoot.resolve(hash)
                if (Files.exists(orphanDir)) {
                    deleteRecursively(orphanDir.toFile())
                    logger.lifecycle("Removed orphaned cache: $hash")
                }
            }
            manifest = DecompilationManifest(newEntries)
            manifest.save(cacheRoot)
        }

        logger.lifecycle("MixinMCP decompilation complete: " +
            "$decompiled decompiled, $cached cached, $failed failed (of $total)")
    }

    private fun isJdkJar(jarFile: File): Boolean {
        val path = jarFile.absolutePath
        val javaHome = System.getProperty("java.home", "")
        return path.startsWith(javaHome) || path.contains("jrt-fs")
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }
}
