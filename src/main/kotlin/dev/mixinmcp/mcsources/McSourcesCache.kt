package dev.mixinmcp.mcsources

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Duration

data class CachedSourceEntry(
    val key: String,
    val path: Path,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

data class CacheSummary(
    val entries: List<CachedSourceEntry>,
    val jarsTotalBytes: Long,
    val workCacheBytes: Long,
) {
    val totalBytes: Long get() = jarsTotalBytes + workCacheBytes
}

data class CleanupResult(
    val deletedJars: Int,
    val freedBytes: Long,
)

class McSourcesCache(val cacheRoot: Path = defaultCacheRoot) {

    val jarsDir: Path get() = cacheRoot.resolve("jars")
    val nfrtHomeDir: Path get() = cacheRoot.resolve("nfrt-home")

    // Kept outside nfrt-home so NFRT never sees a file it didn't create.
    private val workCacheLastUsedMarker: Path get() = cacheRoot.resolve("nfrt-home.last-used")

    fun jarPathForKey(key: String): Path {
        validateKey(key)
        return jarsDir.resolve("$key$JAR_SUFFIX")
    }

    fun getIfPresent(key: String, nowMs: Long = System.currentTimeMillis()): Path? {
        val path = jarPathForKey(key)
        if (!Files.isRegularFile(path)) return null
        val size = runCatching { Files.size(path) }.getOrDefault(0L)
        if (size <= 0L) return null
        runCatching { Files.setLastModifiedTime(path, FileTime.fromMillis(nowMs)) }
        return path
    }

    fun createTempJarPath(key: String): Path {
        validateKey(key)
        Files.createDirectories(jarsDir)
        return Files.createTempFile(jarsDir, "$key.", TEMP_SUFFIX)
    }

    fun publishTempJar(key: String, tempJar: Path): Path {
        val target = jarPathForKey(key)
        Files.createDirectories(jarsDir)
        try {
            Files.move(tempJar, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempJar, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    fun markWorkCacheUsed(nowMs: Long = System.currentTimeMillis()) {
        Files.createDirectories(cacheRoot)
        val marker = workCacheLastUsedMarker
        if (!Files.exists(marker)) Files.createFile(marker)
        Files.setLastModifiedTime(marker, FileTime.fromMillis(nowMs))
    }

    fun listEntries(): List<CachedSourceEntry> {
        if (!Files.isDirectory(jarsDir)) return emptyList()
        val result = mutableListOf<CachedSourceEntry>()
        Files.list(jarsDir).use { stream ->
            stream.forEach { path ->
                val name = path.fileName.toString()
                if (!name.endsWith(JAR_SUFFIX) || !Files.isRegularFile(path)) return@forEach
                val key = name.removeSuffix(JAR_SUFFIX)
                val size = runCatching { Files.size(path) }.getOrDefault(0L)
                val mtime = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)
                result.add(CachedSourceEntry(key, path, size, mtime))
            }
        }
        return result.sortedByDescending { it.lastModifiedMs }
    }

    fun summary(): CacheSummary {
        val entries = listEntries()
        val jarsTotal = entries.sumOf { it.sizeBytes }
        val workBytes = directorySizeBytes(nfrtHomeDir)
        return CacheSummary(entries, jarsTotal, workBytes)
    }

    fun evictStale(maxAgeMs: Long = DEFAULT_TTL_MS, nowMs: Long = System.currentTimeMillis()): List<String> {
        if (!Files.isDirectory(jarsDir)) return emptyList()
        val cutoff = nowMs - maxAgeMs
        val evicted = mutableListOf<String>()
        for (entry in listEntries()) {
            if (entry.lastModifiedMs < cutoff) {
                if (runCatching { Files.deleteIfExists(entry.path) }.getOrDefault(false)) {
                    evicted.add(entry.key)
                }
            }
        }
        return evicted
    }

    // A killed IDE leaves its temp jar behind. The age floor keeps live generations' temp jars safe.
    fun deleteOrphanTempJars(minAgeMs: Long = ORPHAN_TEMP_MIN_AGE_MS, nowMs: Long = System.currentTimeMillis()): Int {
        if (!Files.isDirectory(jarsDir)) return 0
        val cutoff = nowMs - minAgeMs
        var deleted = 0
        val temps = Files.list(jarsDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(TEMP_SUFFIX) }.toList()
        }
        for (temp in temps) {
            val mtime = runCatching { Files.getLastModifiedTime(temp).toMillis() }.getOrDefault(Long.MAX_VALUE)
            if (mtime <= cutoff && runCatching { Files.deleteIfExists(temp) }.getOrDefault(false)) deleted++
        }
        return deleted
    }

    /** Returns the bytes freed, or 0 when the work cache is absent or was used within [maxAgeMs]. */
    fun evictStaleWorkCache(maxAgeMs: Long = WORK_CACHE_TTL_MS, nowMs: Long = System.currentTimeMillis()): Long {
        if (!Files.exists(nfrtHomeDir)) return 0L
        // Work caches created before the marker existed fall back to the directory's own mtime.
        val lastUsedMs = runCatching { Files.getLastModifiedTime(workCacheLastUsedMarker).toMillis() }
            .recoverCatching { Files.getLastModifiedTime(nfrtHomeDir).toMillis() }
            .getOrDefault(nowMs)
        if (lastUsedMs >= nowMs - maxAgeMs) return 0L
        return cleanWorkCache().freedBytes
    }

    fun deleteEntry(key: String): CleanupResult {
        val path = jarPathForKey(key)
        if (!Files.isRegularFile(path)) return CleanupResult(deletedJars = 0, freedBytes = 0L)
        val size = runCatching { Files.size(path) }.getOrDefault(0L)
        Files.deleteIfExists(path)
        return CleanupResult(deletedJars = 1, freedBytes = size)
    }

    fun cleanWorkCache(): CleanupResult {
        runCatching { Files.deleteIfExists(workCacheLastUsedMarker) }
        if (!Files.exists(nfrtHomeDir)) return CleanupResult(deletedJars = 0, freedBytes = 0L)
        val bytes = directorySizeBytes(nfrtHomeDir)
        deleteRecursively(nfrtHomeDir)
        return CleanupResult(deletedJars = 0, freedBytes = bytes)
    }

    /** Only safe while no generation is running: in-progress temp jars are deleted too. */
    fun cleanAll(): CleanupResult {
        val entries = listEntries()
        var freed = 0L
        var deleted = 0
        for (entry in entries) {
            if (runCatching { Files.deleteIfExists(entry.path) }.getOrDefault(false)) {
                deleted++
                freed += entry.sizeBytes
            }
        }
        deleteOrphanTempJars(minAgeMs = 0L, nowMs = Long.MAX_VALUE)
        val work = cleanWorkCache()
        return CleanupResult(deletedJars = deleted, freedBytes = freed + work.freedBytes)
    }

    companion object {
        private const val JAR_SUFFIX = "-sources.jar"
        private const val TEMP_SUFFIX = ".tmp"

        // Request components share this so a value that passes request validation never fails as a key.
        val SAFE_NAME_PATTERN = Regex("""^[A-Za-z0-9._+-]+$""")

        val DEFAULT_TTL_MS: Long = Duration.ofDays(30).toMillis()
        val WORK_CACHE_TTL_MS: Long = Duration.ofDays(7).toMillis()

        // Well past NeoFormRuntimeRunner's 10-minute timeout, so no live generation owns the file.
        val ORPHAN_TEMP_MIN_AGE_MS: Long = Duration.ofHours(1).toMillis()

        val defaultCacheRoot: Path
            get() = Paths.get(System.getProperty("user.home"), ".cache", "mixinmcp", "mc-sources")

        fun validateKey(key: String) {
            require(key.isNotBlank() && SAFE_NAME_PATTERN.matches(key) && ".." !in key) {
                "Invalid cache key '$key'."
            }
        }

        // walkFileTree reports unreadable or vanished entries to visitFileFailed instead of throwing
        // mid-stream like Files.walk, which matters because a concurrent NFRT run churns nfrt-home.
        fun directorySizeBytes(root: Path): Long {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return 0L
            var total = 0L
            try {
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) total += attrs.size()
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                })
            } catch (_: IOException) {
                // Best-effort size reporting
            }
            return total
        }

        fun deleteRecursively(root: Path) {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
            try {
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        runCatching { Files.deleteIfExists(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        runCatching { Files.deleteIfExists(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        runCatching { Files.deleteIfExists(dir) }
                        return FileVisitResult.CONTINUE
                    }
                })
            } catch (_: IOException) {
                // Best-effort: anything still locked is left for the next cleanup.
            }
        }

        fun formatBytes(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024.0) return String.format(java.util.Locale.ROOT, "%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024.0) return String.format(java.util.Locale.ROOT, "%.1f MB", mb)
            val gb = mb / 1024.0
            return String.format(java.util.Locale.ROOT, "%.2f GB", gb)
        }
    }
}
