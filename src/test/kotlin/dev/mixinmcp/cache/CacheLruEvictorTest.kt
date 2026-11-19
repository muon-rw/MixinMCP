package dev.mixinmcp.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

class CacheLruEvictorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val now = 1_800_000_000_000L

    private fun daysAgo(days: Long): Long = now - TimeUnit.DAYS.toMillis(days)

    @Test
    fun touchIfStaleUpdatesOldTimestampAndSkipsRecentOne() {
        val file: Path = tempFolder.newFile("sample.idx").toPath()
        Files.setLastModifiedTime(file, FileTime.fromMillis(now - TimeUnit.HOURS.toMillis(2)))

        CacheLruEvictor.touchIfStale(file, nowMs = now)
        assertEquals(now, Files.getLastModifiedTime(file).toMillis())

        CacheLruEvictor.touchIfStale(file, nowMs = now + TimeUnit.MINUTES.toMillis(10))
        assertEquals(now, Files.getLastModifiedTime(file).toMillis())
    }

    @Test
    fun evictsLeastRecentlyUsedUntilUnprotectedBytesFit() {
        val cacheRoot: Path = tempFolder.newFolder("decompiled").toPath()
        val protectedOld = createCacheDir(cacheRoot, "hash-protected", sizeBytes = 1000, mtimeMs = daysAgo(60))
        val oldest = createCacheDir(cacheRoot, "hash-oldest", sizeBytes = 1000, mtimeMs = daysAgo(10))
        val middle = createCacheDir(cacheRoot, "hash-middle", sizeBytes = 1000, mtimeMs = daysAgo(5))
        val newest = createCacheDir(cacheRoot, "hash-newest", sizeBytes = 1000, mtimeMs = daysAgo(1))

        val result = CacheLruEvictor.evictDecompiledCache(
            cacheRoot = cacheRoot,
            protectedDirNames = setOf("hash-protected"),
            maxUnprotectedBytes = 2500,
            nowMs = now,
        )

        assertEquals(1, result.deletedCount)
        assertTrue(Files.isDirectory(protectedOld))
        assertFalse(Files.exists(oldest))
        assertTrue(Files.isDirectory(middle))
        assertTrue(Files.isDirectory(newest))
    }

    @Test
    fun protectedEntriesOverBudgetDoNotEvictRecentlyUsedOnes() {
        val cacheRoot: Path = tempFolder.newFolder("decompiled-big-open").toPath()
        val openProject = createCacheDir(cacheRoot, "hash-open", sizeBytes = 10_000, mtimeMs = daysAgo(1))
        val closedYesterday = createCacheDir(cacheRoot, "hash-closed", sizeBytes = 500, mtimeMs = daysAgo(1))

        val result = CacheLruEvictor.evictDecompiledCache(
            cacheRoot = cacheRoot,
            protectedDirNames = setOf("hash-open"),
            maxUnprotectedBytes = 2000,
            nowMs = now,
        )

        assertEquals(0, result.deletedCount)
        assertTrue(Files.isDirectory(openProject))
        assertTrue(Files.isDirectory(closedYesterday))
    }

    @Test
    fun evictsExpiredDecompiledDirsEvenWhenUnderSizeBudget() {
        val cacheRoot: Path = tempFolder.newFolder("decompiled-age").toPath()
        val expired = createCacheDir(cacheRoot, "hash-expired", sizeBytes = 500, mtimeMs = daysAgo(35))
        val fresh = createCacheDir(cacheRoot, "hash-fresh", sizeBytes = 500, mtimeMs = daysAgo(2))

        val result = CacheLruEvictor.evictDecompiledCache(
            cacheRoot = cacheRoot,
            protectedDirNames = emptySet(),
            maxUnprotectedBytes = 10_000,
            nowMs = now,
        )

        assertEquals(1, result.deletedCount)
        assertFalse(Files.exists(expired))
        assertTrue(Files.isDirectory(fresh))
    }

    @Test
    fun neverEvictsDirectoriesModifiedWithinTheIdleGrace() {
        val cacheRoot: Path = tempFolder.newFolder("decompiled-writing").toPath()
        val beingWritten = createCacheDir(cacheRoot, "hash-writing", sizeBytes = 5000, mtimeMs = now - TimeUnit.MINUTES.toMillis(5))

        val result = CacheLruEvictor.evictDecompiledCache(
            cacheRoot = cacheRoot,
            protectedDirNames = emptySet(),
            maxUnprotectedBytes = 100,
            nowMs = now,
        )

        assertEquals(0, result.deletedCount)
        assertTrue(Files.isDirectory(beingWritten))
    }

    @Test
    fun evictionNeverLeavesAPartiallyDeletedCacheDirectory() {
        val cacheRoot: Path = tempFolder.newFolder("decompiled-locked").toPath()
        val dir = createCacheDir(cacheRoot, "hash-locked", sizeBytes = 100, mtimeMs = daysAgo(40))
        Files.write(dir.resolve("Other.java"), ByteArray(100))
        Files.setLastModifiedTime(dir, FileTime.fromMillis(daysAgo(40)))

        FileInputStream(dir.resolve("Sample.java").toFile()).use {
            CacheLruEvictor.evictDecompiledCache(cacheRoot, emptySet(), maxUnprotectedBytes = 1L shl 30, nowMs = now)
        }

        // Windows refuses to rename a directory with an open file inside; elsewhere the rename succeeds.
        if (Files.exists(dir)) {
            assertTrue(Files.exists(dir.resolve("Sample.java")))
            assertTrue(Files.exists(dir.resolve("Other.java")))
        }
        CacheLruEvictor.evictDecompiledCache(cacheRoot, emptySet(), maxUnprotectedBytes = 1L shl 30, nowMs = now)
        Files.list(cacheRoot).use { assertEquals(0L, it.count()) }
    }

    @Test
    fun leftoverTombstonesAreDeletedOnTheNextRun() {
        val cacheRoot: Path = tempFolder.newFolder("decompiled-tombstone").toPath()
        val tombstone = createCacheDir(cacheRoot, "hash-x.evicting-123", sizeBytes = 100, mtimeMs = now)

        CacheLruEvictor.evictDecompiledCache(cacheRoot, emptySet(), maxUnprotectedBytes = 1L shl 30, nowMs = now)

        assertFalse(Files.exists(tombstone))
    }

    @Test
    fun memoizedSizesAreUsedAndPrunedForMissingDirectories() {
        val cacheRoot: Path = tempFolder.newFolder("decompiled-memo").toPath()
        createCacheDir(cacheRoot, "hash-a", sizeBytes = 100, mtimeMs = daysAgo(3))
        // A memoized size this large proves the memo, not a walk, decided the budget.
        val sizes: MutableMap<String, Long> = mutableMapOf("hash-a" to 1_000_000L, "hash-gone" to 5L)

        val result = CacheLruEvictor.evictDecompiledCache(cacheRoot, emptySet(), maxUnprotectedBytes = 10_000, nowMs = now, knownSizes = sizes)

        assertEquals(1, result.deletedCount)
        assertEquals(1_000_000L, result.freedBytes)
        assertEquals(emptyMap<String, Long>(), sizes)

        val memoFile: Path = tempFolder.root.toPath().resolve("sizes.properties")
        CacheLruEvictor.saveSizeMemo(memoFile, mapOf("hash-b" to 42L))
        assertEquals(mapOf("hash-b" to 42L), CacheLruEvictor.loadSizeMemo(memoFile))
    }

    @Test
    fun evictsOldestShardsCleansStaleTmpFilesAndKeepsProtectedShards() {
        val shardDir: Path = tempFolder.newFolder("trigrams").toPath()
        val staleTmp = createSizedFile(shardDir.resolve("jar-a.idx.tmp.1"), 500, now - TimeUnit.HOURS.toMillis(2))
        val freshTmp = createSizedFile(shardDir.resolve("jar-b.idx.tmp.2"), 500, now - TimeUnit.MINUTES.toMillis(5))
        val protectedShard = createSizedFile(shardDir.resolve("dir-keep.idx"), 5000, daysAgo(50))
        val oldShard = createSizedFile(shardDir.resolve("jar-old.idx"), 1000, daysAgo(8))
        val newShard = createSizedFile(shardDir.resolve("jar-new.idx"), 1000, daysAgo(1))

        val result = CacheLruEvictor.evictTrigramShards(
            shardDir = shardDir,
            protectedShardNames = setOf("dir-keep.idx"),
            maxUnprotectedBytes = 1500,
            nowMs = now,
        )

        assertEquals(1, result.deletedCount)
        assertFalse(Files.exists(staleTmp))
        assertTrue(Files.exists(freshTmp))
        assertTrue(Files.exists(protectedShard))
        assertFalse(Files.exists(oldShard))
        assertTrue(Files.exists(newShard))
    }

    private fun createCacheDir(root: Path, name: String, sizeBytes: Int, mtimeMs: Long): Path {
        val dir: Path = root.resolve(name)
        Files.createDirectories(dir)
        Files.write(dir.resolve("Sample.java"), ByteArray(sizeBytes))
        Files.setLastModifiedTime(dir, FileTime.fromMillis(mtimeMs))
        return dir
    }

    private fun createSizedFile(path: Path, sizeBytes: Int, mtimeMs: Long): Path {
        Files.write(path, ByteArray(sizeBytes))
        Files.setLastModifiedTime(path, FileTime.fromMillis(mtimeMs))
        return path
    }
}
