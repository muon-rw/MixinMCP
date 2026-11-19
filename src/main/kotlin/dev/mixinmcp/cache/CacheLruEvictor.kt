package dev.mixinmcp.cache

import com.intellij.openapi.diagnostic.Logger
import dev.mixinmcp.tools.source.TrigramShardIndex
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * LRU eviction for `~/.cache/mixinmcp/decompiled/` and `~/.cache/mixinmcp/trigrams/`, using each
 * entry's modification time as its last-used time. The Gradle plugin's own 30-day eviction reads the
 * same timestamp, so [touchIfStale] protects an entry from both.
 */
internal object CacheLruEvictor {

    val DEFAULT_MAX_UNUSED_AGE_MS: Long = TimeUnit.DAYS.toMillis(30)
    val TOUCH_THROTTLE_MS: Long = TimeUnit.HOURS.toMillis(1)

    // The Gradle plugin writes a cache directory in place, and another IDE may have just built a shard.
    val MIN_IDLE_BEFORE_EVICTION_MS: Long = TimeUnit.HOURS.toMillis(1)
    private val STALE_TMP_AGE_MS: Long = TimeUnit.HOURS.toMillis(1)

    // Evicted directories are renamed before deletion: the Gradle plugin treats any non-empty
    // cache directory as complete, so a half-deleted one would never be regenerated.
    private const val EVICTING_MARKER = ".evicting-"

    private val LOG = Logger.getInstance(CacheLruEvictor::class.java)

    private val lastTouchedMs = ConcurrentHashMap<Path, Long>()

    data class EvictionResult(val deletedCount: Int, val freedBytes: Long)

    fun touchIfStale(path: Path, nowMs: Long = System.currentTimeMillis(), throttleMs: Long = TOUCH_THROTTLE_MS) {
        val recent: Long? = lastTouchedMs[path]
        if (recent != null && nowMs - recent in 0 until throttleMs) return
        try {
            val mtime: Long = Files.getLastModifiedTime(path).toMillis()
            if (nowMs - mtime >= throttleMs) {
                Files.setLastModifiedTime(path, FileTime.fromMillis(nowMs))
                lastTouchedMs[path] = nowMs
            } else {
                lastTouchedMs[path] = mtime
            }
        } catch (_: Exception) {
            // A missing or locked file must never fail a search or a sync.
        }
    }

    /**
     * Deletes decompiled cache directories under [cacheRoot] unused for [maxAgeMs], then the least
     * recently used ones until those not in [protectedDirNames] fit in [maxUnprotectedBytes].
     * Protected directories don't count toward the budget, so a large set of open projects can't
     * push out everything else. [knownSizes] maps directory names to sizes from earlier runs and is
     * updated in place, so the caller can persist it.
     */
    fun evictDecompiledCache(
        cacheRoot: Path,
        protectedDirNames: Set<String>,
        maxUnprotectedBytes: Long,
        maxAgeMs: Long = DEFAULT_MAX_UNUSED_AGE_MS,
        nowMs: Long = System.currentTimeMillis(),
        knownSizes: MutableMap<String, Long> = HashMap(),
    ): EvictionResult {
        if (!Files.isDirectory(cacheRoot)) return EvictionResult(0, 0L)
        class Entry(val dir: Path, val name: String, val lastUsedMs: Long)

        val entries: MutableList<Entry> = mutableListOf()
        val existingNames: MutableSet<String> = HashSet()
        try {
            Files.list(cacheRoot).use { stream ->
                stream.filter { Files.isDirectory(it) }.forEach { dir ->
                    val name: String = dir.fileName?.toString() ?: return@forEach
                    if (EVICTING_MARKER in name) {
                        deleteRecursively(dir)
                        return@forEach
                    }
                    existingNames.add(name)
                    if (name in protectedDirNames) return@forEach
                    val mtime: Long = runCatching { Files.getLastModifiedTime(dir).toMillis() }.getOrDefault(0L)
                    entries.add(Entry(dir, name, mtime))
                }
            }
        } catch (_: Exception) {
            return EvictionResult(0, 0L)
        }
        knownSizes.keys.retainAll(existingNames)

        val idleCutoffMs: Long = nowMs - MIN_IDLE_BEFORE_EVICTION_MS
        fun sizeOf(entry: Entry): Long = knownSizes[entry.name] ?: directorySize(entry.dir).also {
            // A directory the Gradle plugin may still be writing would memoize a partial size.
            if (entry.lastUsedMs < idleCutoffMs) knownSizes[entry.name] = it
        }

        var unprotectedBytes: Long = entries.sumOf { sizeOf(it) }
        val ageCutoffMs: Long = nowMs - maxAgeMs
        var deleted = 0
        var freed = 0L
        for (entry in entries.sortedBy { it.lastUsedMs }) {
            if (entry.lastUsedMs >= idleCutoffMs) break
            if (entry.lastUsedMs >= ageCutoffMs && unprotectedBytes <= maxUnprotectedBytes) break
            val size: Long = sizeOf(entry)
            if (evictDirectory(entry.dir)) {
                knownSizes.remove(entry.name)
                unprotectedBytes -= size
                freed += size
                deleted++
            }
        }

        if (deleted > 0) {
            LOG.info("MixinMCP: LRU evicted $deleted decompiled cache dir(s), freed ${freed / (1024 * 1024)} MiB")
        }
        return EvictionResult(deleted, freed)
    }

    /**
     * Deletes stale `.tmp.*` files under [shardDir], then shards unused for [maxAgeMs], then the least
     * recently used shards until those not in [protectedShardNames] fit in [maxUnprotectedBytes].
     * Shards with a build in progress are never deleted.
     */
    fun evictTrigramShards(
        shardDir: Path,
        protectedShardNames: Set<String>,
        maxUnprotectedBytes: Long,
        maxAgeMs: Long = DEFAULT_MAX_UNUSED_AGE_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): EvictionResult {
        if (!Files.isDirectory(shardDir)) return EvictionResult(0, 0L)
        class ShardFile(val path: Path, val size: Long, val lastUsedMs: Long)

        val shards: MutableList<ShardFile> = mutableListOf()
        try {
            Files.list(shardDir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { file ->
                    val name: String = file.fileName?.toString() ?: return@forEach
                    val mtime: Long = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
                    if (".tmp." in name) {
                        if (nowMs - mtime >= STALE_TMP_AGE_MS) runCatching { Files.deleteIfExists(file) }
                        return@forEach
                    }
                    if (!name.endsWith(".idx") || name in protectedShardNames) return@forEach
                    if (TrigramShardIndex.isBuildScheduled(file)) return@forEach
                    shards.add(ShardFile(file, runCatching { Files.size(file) }.getOrDefault(0L), mtime))
                }
            }
        } catch (_: Exception) {
            return EvictionResult(0, 0L)
        }

        var unprotectedBytes: Long = shards.sumOf { it.size }
        val idleCutoffMs: Long = nowMs - MIN_IDLE_BEFORE_EVICTION_MS
        val ageCutoffMs: Long = nowMs - maxAgeMs
        var deleted = 0
        var freed = 0L
        for (shard in shards.sortedBy { it.lastUsedMs }) {
            if (shard.lastUsedMs >= idleCutoffMs) break
            if (shard.lastUsedMs >= ageCutoffMs && unprotectedBytes <= maxUnprotectedBytes) break
            if (runCatching { Files.deleteIfExists(shard.path) }.getOrDefault(false)) {
                TrigramShardIndex.invalidateCachedShard(shard.path)
                lastTouchedMs.remove(shard.path)
                unprotectedBytes -= shard.size
                freed += shard.size
                deleted++
            }
        }

        if (deleted > 0) {
            LOG.info("MixinMCP: LRU evicted $deleted trigram shard(s), freed ${freed / (1024 * 1024)} MiB")
        }
        return EvictionResult(deleted, freed)
    }

    fun loadSizeMemo(file: Path): MutableMap<String, Long> {
        val out: MutableMap<String, Long> = HashMap()
        try {
            if (!Files.isRegularFile(file)) return out
            val props = Properties()
            Files.newInputStream(file).use { props.load(it) }
            for (name in props.stringPropertyNames()) {
                props.getProperty(name).toLongOrNull()?.let { out[name] = it }
            }
        } catch (_: Exception) {
            out.clear()
        }
        return out
    }

    fun saveSizeMemo(file: Path, sizes: Map<String, Long>) {
        try {
            Files.createDirectories(file.parent)
            val props = Properties()
            for ((name, size) in sizes) props.setProperty(name, size.toString())
            val tmp: Path = file.resolveSibling("${file.fileName}.tmp.${ProcessHandle.current().pid()}")
            Files.newOutputStream(tmp).use { props.store(it, null) }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            // The memo only saves directory walks; losing it costs one walk per directory.
        }
    }

    private fun evictDirectory(dir: Path): Boolean {
        val tombstone: Path = dir.resolveSibling("${dir.fileName}$EVICTING_MARKER${System.nanoTime()}")
        try {
            // Fails as a whole (on Windows, when any file inside is open), leaving the directory intact.
            Files.move(dir, tombstone, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            return false
        }
        deleteRecursively(tombstone)
        return true
    }

    private fun directorySize(root: Path): Long {
        var total = 0L
        try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        total += attrs.size()
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                },
            )
        } catch (_: Exception) {
            // Concurrent modification; a partial size only skews the budget.
        }
        return total
    }

    // Best effort: keeps going past locked files, and the next run retries what's left.
    private fun deleteRecursively(root: Path) {
        try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        runCatching { Files.deleteIfExists(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        runCatching { Files.deleteIfExists(dir) }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (_: Exception) {
        }
    }
}
