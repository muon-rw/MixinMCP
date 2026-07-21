package dev.mixinmcp.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.java.decompiler.api.Decompiler
import org.jetbrains.java.decompiler.main.decompiler.DirectoryResultSaver
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.zip.ZipFile
import java.util.concurrent.TimeUnit

/**
 * Decompiles dependency JARs without -sources.jar into ~/.cache/mixinmcp/decompiled/.
 * Published `-sources.jar` artifacts are extracted into the same cache so the IDE can
 * index them even when the Gradle model uses a transformed classes JAR without linked sources.
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
@DisableCachingByDefault(because = "writes to a machine-global content-addressed cache with its own incrementality")
abstract class MixinDecompileTask : DefaultTask() {

    @get:Input
    @get:Option(option = "threads", description = "Vineflower decompiler threads (default 2). Lower = less memory.")
    var threads: Int = 2

    @get:Input
    @get:Option(option = "force", description = "Skip OOM pre-flight confirmation; proceed with decompilation even when heap may be insufficient.")
    var force: Boolean = false

    /**
     * Set by MixinDecompilePlugin at configuration time. Lenient artifact collections
     * (one per classpath configuration) so that transform failures (e.g. missing
     * mapping data) are captured as failures rather than crashing the entire resolution.
     * Resolves compileClasspath to cover all compile-visible scopes (implementation,
     * api, compileOnly). runtimeOnly deps are excluded by design.
     */
    @get:Internal
    var artifactCollections: List<ArtifactCollection> = emptyList()

    /** Buildscript classpath of this project and its ancestors; entries tag as classpathKind=buildscript. */
    @get:Internal
    var buildscriptArtifactCollections: List<ArtifactCollection> = emptyList()

    /** Set by MixinDecompilePlugin at configuration time. */
    @get:Internal
    var projectDir: File? = null

    /** "group:module:version" -> Gradle-resolved `-sources.jar` file. */
    @get:Internal
    var publishedSourcesJarsProvider: Provider<Map<String, File>>? = null

    /** Same map for the buildscript classpath. */
    @get:Internal
    var buildscriptPublishedSourcesJarsProvider: Provider<Map<String, File>>? = null

    /** Set by MixinDecompilePlugin at configuration time; used to purge corrupt cached downloads. */
    @get:Internal
    var gradleUserHome: File? = null

    private val globalCacheRoot: Path
        get() = Paths.get(System.getProperty("user.home"), ".cache", "mixinmcp", "decompiled")

    private val ownPluginVersion: String?
        get() = javaClass.`package`?.implementationVersion

    companion object {
        const val UNRESOLVED_MARKER_FILE = "unresolved.txt"
    }

    private val projectManifestRoot: Path
        get() = projectDir?.toPath()?.resolve(".gradle")?.resolve("mixinmcp")
            ?: globalCacheRoot

    @TaskAction
    fun decompile() {
        if (artifactCollections.isEmpty() && buildscriptArtifactCollections.isEmpty()) {
            logger.lifecycle("No compileClasspath or buildscript classpath configuration found, skipping decompilation")
            return
        }

        evictStaleCacheEntries()

        val hashMemo = DecompilationManifest.loadHashMemo(projectManifestRoot)

        val compileArtifacts = artifactCollections
            .flatMap { it.resolvedArtifacts.get() }
            .distinctBy { it.file.absolutePath }
        val compilePaths: Set<String> = compileArtifacts.map { it.file.absolutePath }.toSet()
        // Compile wins on overlap: a jar on both classpaths keeps its module-dependency identity.
        val buildscriptArtifacts = buildscriptArtifactCollections
            .flatMap { it.resolvedArtifacts.get() }
            .distinctBy { it.file.absolutePath }
            .filter { it.file.absolutePath !in compilePaths }
        val buildscriptPaths: Set<String> = buildscriptArtifacts.map { it.file.absolutePath }.toSet()
        val resolvedArtifacts = compileArtifacts + buildscriptArtifacts
        fun classpathKindOf(jarFile: File): String =
            if (jarFile.absolutePath in buildscriptPaths) "buildscript" else "compile"
        val resolutionFailures = (artifactCollections + buildscriptArtifactCollections).flatMap { it.failures }
        val publishedSourcesJars = (publishedSourcesJarsProvider?.get() ?: emptyMap()) +
            (buildscriptPublishedSourcesJarsProvider?.get() ?: emptyMap())

        val withoutSources = resolvedArtifacts
            .filter { it.variant.owner is ModuleComponentIdentifier }
            .filter { artifact ->
                val owner = artifact.variant.owner as ModuleComponentIdentifier
                val coordinate = "${owner.group}:${owner.module}:${owner.version}"
                coordinate !in publishedSourcesJars
            }
            .filter { it.file.extension.equals("jar", ignoreCase = true) }
            .filter { !isJdkJar(it.file) }
            .sortedBy { it.file.length() }

        // Toolchain Minecraft jars (group net.minecraft, e.g. Loom's minecraft-merged) are never
        // mirrored: once genSources output exists the IDE attaches it directly, and the manifest
        // prune below drops any earlier decompiled entry. Without it they stay in withoutSources
        // and are decompiled as the genSources-free fallback.
        val withPublishedSources = resolvedArtifacts
            .filter { it.variant.owner is ModuleComponentIdentifier }
            .filter { it.file.extension.equals("jar", ignoreCase = true) }
            .filter { !isJdkJar(it.file) }
            .filter { artifact ->
                val owner = artifact.variant.owner as ModuleComponentIdentifier
                owner.group != "net.minecraft" &&
                    "${owner.group}:${owner.module}:${owner.version}" in publishedSourcesJars
            }
            .sortedBy { it.file.length() }

        var manifest = DecompilationManifest().load(projectManifestRoot)
        val currentJarHashes = mutableSetOf<String>()
        var decompiled = 0
        var cached = 0
        var skipped = 0
        var failed = 0
        var corruptPurged = 0
        var publishedMirrored = 0
        var publishedCached = 0
        var publishedFailed = 0
        val total = withoutSources.size
        val publishedTotal = withPublishedSources.size

        logger.lifecycle(
            "MixinMCP: $total JAR(s) to decompile, $publishedTotal published source JAR(s) to mirror " +
                "(${buildscriptPaths.size} buildscript-classpath jar(s) considered), threads=$threads",
        )

        for ((index, artifact) in withoutSources.withIndex()) {
            val jarFile = artifact.file
            val jarPath = jarFile.absolutePath
            val owner = artifact.variant.owner as ModuleComponentIdentifier
            val libraryName = "${owner.group}:${owner.module}:${owner.version}"
            val progress = "[${index + 1}/$total]"

            // Raw cached jars are validated even when artifact.file is a healthy transform output:
            // streaming remap transforms can "succeed" on a cleanly-truncated download and emit a
            // valid but classless jar, which caches as a successful transform.
            val rawPurged: Boolean = purgeCachedVersionDirIfCorrupt(owner)
            if (rawPurged || !isValidZip(jarFile)) {
                logCorruptDownload(progress, libraryName, rawPurged)
                corruptPurged++
                continue
            }

            val jarSize = jarFile.length()
            val jarModified = jarFile.lastModified()
            val hash = DecompilationManifest.computeArtifactHashMemoized(jarFile, hashMemo)
            currentJarHashes.add(hash)

            val sizeMb = jarSize / 1024 / 1024
            val sizeKb = jarSize / 1024
            val sizeStr = if (sizeMb > 0) "${sizeMb}MB" else "${sizeKb}KB"

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
                        classpathKind = classpathKindOf(jarFile),
                    )
                    manifest = DecompilationManifest(manifest.entries + (hash to entry))
                }
                touchDirectory(cacheDir.toPath())
                logger.lifecycle("$progress Already cached: $libraryName ($sizeStr) Skipping...")
                cached++
                continue
            }

            // Per-jar OOM skip: skip large jars that may exceed available heap
            val jarSizeMb = jarFile.length() / 1024 / 1024
            val maxHeapMb = Runtime.getRuntime().maxMemory() / 1024 / 1024
            val estimatedNeedMb = (threads * 800L) + 500L

            if (jarSizeMb >= 15 && maxHeapMb < estimatedNeedMb && !force) {
                logger.warn("$progress Skipping $libraryName ($sizeStr) — may exceed ${maxHeapMb}MB heap. " +
                    "Run with --force or set org.gradle.jvmargs=-Xmx${estimatedNeedMb + 512}m")
                skipped++
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
                    classpathKind = classpathKindOf(jarFile),
                )
                manifest = DecompilationManifest(manifest.entries + (hash to entry))
                manifest.save(projectManifestRoot, ownPluginVersion)
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

        for ((index, artifact) in withPublishedSources.withIndex()) {
            val jarFile = artifact.file
            val jarPath = jarFile.absolutePath
            val owner = artifact.variant.owner as ModuleComponentIdentifier
            val libraryName = "${owner.group}:${owner.module}:${owner.version}"
            val sourcesJar = publishedSourcesJars[libraryName]!!
            val progress = "[published ${index + 1}/$publishedTotal]"

            val rawPurged: Boolean = purgeCachedVersionDirIfCorrupt(owner)
            if (rawPurged || !isValidZip(jarFile) || !isValidZip(sourcesJar)) {
                logCorruptDownload(progress, libraryName, rawPurged)
                corruptPurged++
                continue
            }

            val jarSize = jarFile.length()
            val jarModified = jarFile.lastModified()
            val hash = DecompilationManifest.computeArtifactHashMemoized(jarFile, hashMemo)
            currentJarHashes.add(hash)

            val cacheDir = globalCacheRoot.resolve(hash).toFile()

            if (cacheDir.isDirectory && cacheDir.list()?.isNotEmpty() == true) {
                if (hash !in manifest.entries) {
                    val entry = CacheEntry(
                        libraryName = libraryName,
                        classesJarPath = jarPath,
                        jarSize = jarSize,
                        jarModified = jarModified,
                        cachePath = cacheDir.absolutePath + File.separator,
                        decompilerVersion = "published-sources",
                        createdAt = System.currentTimeMillis(),
                        classpathKind = classpathKindOf(jarFile),
                    )
                    manifest = DecompilationManifest(manifest.entries + (hash to entry))
                }
                touchDirectory(cacheDir.toPath())
                logger.lifecycle("$progress Already cached: $libraryName (published sources)")
                publishedCached++
                continue
            }

            Files.createDirectories(cacheDir.toPath())
            logger.lifecycle("$progress Mirroring published sources: $libraryName")

            try {
                extractPublishedSourcesJar(sourcesJar, cacheDir)
                val entry = CacheEntry(
                    libraryName = libraryName,
                    classesJarPath = jarPath,
                    jarSize = jarSize,
                    jarModified = jarModified,
                    cachePath = cacheDir.absolutePath + File.separator,
                    decompilerVersion = "published-sources",
                    createdAt = System.currentTimeMillis(),
                    classpathKind = classpathKindOf(jarFile),
                )
                manifest = DecompilationManifest(manifest.entries + (hash to entry))
                manifest.save(projectManifestRoot, ownPluginVersion)
                publishedMirrored++
                logger.lifecycle("$progress Done! $libraryName")
            } catch (e: Exception) {
                logger.warn("$progress Failed mirroring sources for $libraryName — ${e.message}")
                deleteRecursively(cacheDir)
                publishedFailed++
            }
        }

        manifest = DecompilationManifest(manifest.entries.filterKeys { it in currentJarHashes })
        manifest.save(projectManifestRoot, ownPluginVersion)
        DecompilationManifest.saveHashMemo(
            projectManifestRoot,
            hashMemo.filterValues { it in currentJarHashes },
        )

        logger.lifecycle(
            "MixinMCP complete: decompile — $decompiled new, $cached cached, $skipped skipped (heap), $failed failed (of $total); " +
                "published sources — $publishedMirrored mirrored, $publishedCached cached, $publishedFailed failed (of $publishedTotal)" +
                if (corruptPurged > 0) "; $corruptPurged corrupt download(s) purged, re-sync to re-download" else "",
        )

        if (skipped > 0) {
            logger.warn("")
            logger.warn("MixinMCP: $skipped JAR(s) skipped due to memory constraints.")
            logger.warn("To decompile them, run: ./gradlew genDependencySources --force")
            logger.warn("Or increase heap: org.gradle.jvmargs=-Xmx${(threads * 800L) + 1012}m in gradle.properties")
            logger.warn("")
        }

        val unresolvedMarker = projectManifestRoot.resolve(UNRESOLVED_MARKER_FILE)
        if (resolutionFailures.isNotEmpty()) {
            logger.warn("")
            logger.warn("MixinMCP: ${resolutionFailures.size} artifact(s) could not be resolved:")
            for (failure in resolutionFailures.take(5)) {
                logger.warn("MixinMCP:   ${describeFailure(failure)}")
            }
            if (resolutionFailures.size > 5) {
                logger.warn("MixinMCP:   ... and ${resolutionFailures.size - 5} more")
            }
            val purged: List<String> = purgeCorruptCachedArtifacts(resolutionFailures)
            if (purged.isNotEmpty()) {
                logger.warn(
                    "MixinMCP: ${purged.size} of these were corrupt or truncated downloads; purged from the " +
                        "Gradle cache, re-sync to re-download: ${purged.joinToString(limit = 6)}",
                )
            }
            if (purged.size < resolutionFailures.size) {
                logger.warn("MixinMCP: Other failures are often mapping data missing during a first sync.")
            }
            logger.warn("MixinMCP: Run './gradlew genDependencySources' manually after a successful Gradle sync to decompile them.")
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

    private fun isValidZip(file: File): Boolean =
        try {
            ZipFile(file).use { true }
        } catch (_: Exception) {
            false
        }

    private fun logCorruptDownload(progress: String, libraryName: String, purged: Boolean) {
        if (purged) {
            logger.warn("$progress Corrupt or truncated download purged from the Gradle cache: $libraryName. Re-sync to re-download.")
        } else {
            logger.warn("$progress Corrupt jar skipped (not found in the Gradle artifact cache): $libraryName")
        }
    }

    private fun purgeCachedVersionDirIfCorrupt(owner: ModuleComponentIdentifier): Boolean =
        purgeCachedVersionDirIfCorrupt(owner.group, owner.module, owner.version)

    /**
     * Some repositories (cursemaven) serve chunked responses without checksums, so Gradle caches
     * truncated downloads as if complete and never retries them. Deleting the version dir makes
     * the next resolution re-fetch.
     */
    private fun purgeCachedVersionDirIfCorrupt(group: String, module: String, version: String): Boolean {
        val home: File = gradleUserHome ?: return false
        val versionDir: File = home.resolve("caches/modules-2/files-2.1/$group/$module/$version")
        if (!versionDir.isDirectory) return false
        val jars: List<File> = versionDir.walkTopDown().filter { it.extension.equals("jar", ignoreCase = true) }.toList()
        if (jars.isEmpty() || jars.all { isValidZip(it) }) return false
        deleteRecursively(versionDir)
        return true
    }

    private val failureGavPattern = Regex("""\(([\w.-]+):([\w.-]+):([^):\s]+)\)""")

    private fun purgeCorruptCachedArtifacts(failures: List<Throwable>): List<String> {
        val purged = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (failure in failures) {
            var t: Throwable? = failure
            var match: MatchResult? = null
            while (t != null && match == null) {
                match = t.message?.let { failureGavPattern.find(it) }
                val next: Throwable? = t.cause
                t = if (next === t) null else next
            }
            val (group, module, version) = match?.destructured ?: continue
            val gav = "$group:$module:$version"
            if (!seen.add(gav)) continue
            if (purgeCachedVersionDirIfCorrupt(group, module, version)) {
                purged.add(gav)
            }
        }
        return purged
    }

    private fun describeFailure(t: Throwable): String {
        var root: Throwable = t
        while (root.cause != null && root.cause !== root) {
            root = root.cause!!
        }
        val top: String = (t.message ?: t.toString()).lineSequence().first()
        if (root === t) return top
        return "$top <- ${root.javaClass.simpleName}: ${root.message ?: ""}".trim()
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

    /**
     * Unzips a `-sources.jar` into [destDir] (defensive against zip-slip).
     */
    private fun extractPublishedSourcesJar(sourcesJar: File, destDir: File) {
        val destRoot = destDir.toPath().normalize()
        ZipFile(sourcesJar).use { zip ->
            for (entry in zip.entries().asSequence()) {
                if (entry.isDirectory) continue
                val target = destRoot.resolve(entry.name).normalize()
                if (!target.startsWith(destRoot)) continue
                Files.createDirectories(target.parent)
                zip.getInputStream(entry).use { input ->
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
