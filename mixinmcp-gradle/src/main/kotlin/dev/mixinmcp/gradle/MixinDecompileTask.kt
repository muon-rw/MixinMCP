package dev.mixinmcp.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
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
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

/**
 * Decompiles dependency JARs without -sources.jar into ~/.cache/mixinmcp/decompiled/.
 * See DESIGN.md Section 11.11.
 *
 * Each Gradle (sub)project writes its own manifest at <projectDir>/.gradle/mixinmcp/manifest.json,
 * while decompiled output is shared in a global content-addressed store. This allows multiloader
 * builds and entirely separate projects to share decompilation work without interfering with
 * each other's cache entries.
 *
 * Vineflower spawns N decompiler threads internally (default = CPU count). Each
 * thread builds an SSA variable graph per class that can consume hundreds of MB.
 * On a 12-core machine with a large JAR, this means 12 threads x ~800MB = ~10GB.
 *
 * To stay within a reasonable heap budget, this task limits Vineflower's thread
 * count (default 2). Override with --threads. If you still hit OOM, increase the
 * Gradle daemon heap in gradle.properties:
 *   org.gradle.jvmargs=-Xmx4g
 *
 * Configuration cache: artifactCollection is set at configuration time by the
 * plugin. No Task.project access at execution time.
 */
abstract class MixinDecompileTask : DefaultTask() {

    @get:Input
    @get:Option(option = "threads", description = "Vineflower decompiler threads (default 2). Lower = less memory.")
    var threads: Int = 2

    @get:Input
    @get:Option(option = "force", description = "Skip OOM pre-flight confirmation; proceed with decompilation even when heap may be insufficient.")
    var force: Boolean = false

    /**
     * Set by MixinDecompilePlugin at configuration time. Lenient artifact collection
     * so that transform failures (e.g. missing mapping data) are captured as failures
     * rather than crashing the entire resolution.
     */
    @get:Internal
    var artifactCollection: ArtifactCollection? = null

    /** Set by MixinDecompilePlugin at configuration time. */
    @get:Internal
    var projectDir: File? = null

    /** Set of "group:module:version" strings for modules that have published sources. */
    @get:Internal
    var modulesWithSourcesProvider: Provider<Set<String>>? = null

    private val globalCacheRoot: Path
        get() = Paths.get(System.getProperty("user.home"), ".cache", "mixinmcp", "decompiled")

    companion object {
        const val UNRESOLVED_MARKER_FILE = "unresolved.txt"
    }

    private val projectManifestRoot: Path
        get() = projectDir?.toPath()?.resolve(".gradle")?.resolve("mixinmcp")
            ?: globalCacheRoot

    @TaskAction
    fun decompile() {
        val collection = artifactCollection ?: run {
            logger.lifecycle("No runtimeClasspath/compileClasspath configuration found, skipping decompilation")
            return
        }

        evictStaleCacheEntries()

        val resolvedArtifacts = collection.resolvedArtifacts.get()
        val resolutionFailures = collection.failures
        val modulesWithSources = modulesWithSourcesProvider?.get() ?: emptySet()

        val withoutSources = resolvedArtifacts
            .filter { it.variant.owner is ModuleComponentIdentifier }
            .filter { artifact ->
                val owner = artifact.variant.owner as ModuleComponentIdentifier
                val coordinate = "${owner.group}:${owner.module}:${owner.version}"
                coordinate !in modulesWithSources
            }
            .filter { it.file.extension.equals("jar", ignoreCase = true) }
            .filter { !isJdkJar(it.file) }
            .sortedBy { it.file.length() }

        val skippedWithSources = resolvedArtifacts
            .filter { it.variant.owner is ModuleComponentIdentifier }
            .count { artifact ->
                val owner = artifact.variant.owner as ModuleComponentIdentifier
                "${owner.group}:${owner.module}:${owner.version}" in modulesWithSources
            }

        // Pre-flight memory check: warn if large uncached JARs may OOM the daemon
        val uncachedJars = withoutSources.filter { artifact ->
            val hash = DecompilationManifest.computeArtifactHash(
                artifact.file.absolutePath, artifact.file.length(), artifact.file.lastModified()
            )
            val cacheDir = globalCacheRoot.resolve(hash).toFile()
            !(cacheDir.isDirectory && cacheDir.list()?.isNotEmpty() == true)
        }

        if (uncachedJars.isNotEmpty()) {
            val largestArtifact = uncachedJars.maxByOrNull { it.file.length() }!!
            val largestOwner = largestArtifact.variant.owner as ModuleComponentIdentifier
            val largestLibraryName = "${largestOwner.group}:${largestOwner.module}:${largestOwner.version}"
            val largestUncachedMb = largestArtifact.file.length() / 1024 / 1024
            val maxHeapMb = Runtime.getRuntime().maxMemory() / 1024 / 1024
            // Heuristic: each Vineflower thread needs ~800MB for large JARs,
            // plus ~500MB baseline for the Gradle daemon itself.
            val estimatedNeedMb = (threads * 800L) + 500L

            if (largestUncachedMb >= 15 && maxHeapMb < estimatedNeedMb && !force) {
                val message = "Only ${maxHeapMb}MB is allocated to the Gradle daemon, and $largestLibraryName (${largestUncachedMb}MB) without a sources jar may cause decompilation to fail."
                val recommendations = "Set org.gradle.jvmargs=-Xmx${estimatedNeedMb + 512}m in gradle.properties, or run with --threads=1. Use --force to skip this check."

                val console = System.console()
                if (console != null) {
                    console.writer().println()
                    console.writer().println("MixinMCP: $message")
                    console.writer().println("Recommendations: $recommendations")
                    console.writer().println()
                    console.writer().print("Would you like to proceed with decompilation? [Y/N] ")
                    console.writer().flush()
                    val response = console.readLine()?.trim()?.uppercase()
                    if (response != "Y" && response != "YES") {
                        throw GradleException("Decompilation aborted by user.")
                    }
                } else {
                    throw GradleException(
                        "$message $recommendations (Run interactively to confirm and proceed.)"
                    )
                }
            }
        }

        var manifest = DecompilationManifest().load(projectManifestRoot)
        val currentJarHashes = mutableSetOf<String>()
        var decompiled = 0
        var cached = 0
        var failed = 0
        val total = withoutSources.size

        logger.lifecycle("MixinMCP: ${total} JARs without sources ($skippedWithSources skipped — sources available), threads=$threads")

        for ((index, artifact) in withoutSources.withIndex()) {
            val jarFile = artifact.file
            val jarPath = jarFile.absolutePath
            val jarSize = jarFile.length()
            val jarModified = jarFile.lastModified()
            val hash = DecompilationManifest.computeArtifactHash(jarPath, jarSize, jarModified)
            currentJarHashes.add(hash)

            val owner = artifact.variant.owner as ModuleComponentIdentifier
            val libraryName = "${owner.group}:${owner.module}:${owner.version}"
            val sizeMb = jarSize / 1024 / 1024
            val sizeKb = jarSize / 1024
            val sizeStr = if (sizeMb > 0) "${sizeMb}MB" else "${sizeKb}KB"
            val progress = "[${index + 1}/$total]"

            val cacheDir = globalCacheRoot.resolve(hash).toFile()

            if (cacheDir.isDirectory && cacheDir.list()?.isNotEmpty() == true) {
                if (hash !in manifest.entries) {
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
                }
                touchDirectory(cacheDir.toPath())
                logger.lifecycle("$progress Already cached: $libraryName ($sizeStr) Skipping...")
                cached++
                continue
            }

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
                manifest.save(projectManifestRoot)
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

        manifest = DecompilationManifest(manifest.entries.filterKeys { it in currentJarHashes })
        manifest.save(projectManifestRoot)

        logger.lifecycle("MixinMCP decompilation complete: " +
            "$decompiled decompiled, $cached cached, $failed failed (of $total)")

        val unresolvedMarker = projectManifestRoot.resolve(UNRESOLVED_MARKER_FILE)
        if (resolutionFailures.isNotEmpty()) {
            logger.warn("")
            logger.warn("MixinMCP: ${resolutionFailures.size} artifact(s) could not be resolved " +
                "(likely due to missing mapping data during first sync).")
            logger.warn("MixinMCP: Run './gradlew mixinDecompile' manually after a successful Gradle sync to decompile them.")
            logger.warn("")
            Files.createDirectories(projectManifestRoot)
            Files.writeString(unresolvedMarker, resolutionFailures.size.toString())
        } else {
            Files.deleteIfExists(unresolvedMarker)
        }
    }

    /**
     * Evict global cache entries whose directory hasn't been touched in 30+ days.
     * Runs at the start of each decompile — before this project's entries are touched —
     * so it only affects genuinely stale entries from any project.
     */
    private fun evictStaleCacheEntries() {
        if (!Files.exists(globalCacheRoot)) return
        val cutoffMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        var evicted = 0
        try {
            Files.list(globalCacheRoot).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .forEach { dir ->
                        try {
                            if (Files.getLastModifiedTime(dir).toMillis() < cutoffMs) {
                                deleteRecursively(dir.toFile())
                                evicted++
                            }
                        } catch (_: Exception) {}
                    }
            }
        } catch (_: Exception) {}
        if (evicted > 0) {
            logger.lifecycle("MixinMCP: evicted $evicted stale cache entries (>30 days untouched)")
        }
    }

    private fun touchDirectory(dir: Path) {
        try {
            Files.setLastModifiedTime(dir, FileTime.fromMillis(System.currentTimeMillis()))
        } catch (_: Exception) {}
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
