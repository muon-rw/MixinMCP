package dev.mixinmcp.mcsources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Duration

class McSourcesCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun publishesHitsTouchesAndDeletesEntries() {
        val cache = McSourcesCache(tempFolder.root.toPath())
        val key = "1.21.1-vanilla"
        assertNull(cache.getIfPresent(key))

        val tmp = cache.createTempJarPath(key)
        Files.writeString(tmp, "fake-jar-bytes")
        val published = cache.publishTempJar(key, tmp)

        assertTrue(Files.isRegularFile(published))
        assertFalse(Files.exists(tmp))

        val hit = cache.getIfPresent(key, nowMs = 2_000_000L)
        assertNotNull(hit)
        assertEquals(2_000_000L, Files.getLastModifiedTime(hit!!).toMillis())

        val entries = cache.listEntries()
        assertEquals(1, entries.size)
        assertEquals(key, entries[0].key)

        val deleted = cache.deleteEntry(key)
        assertEquals(1, deleted.deletedJars)
        assertTrue(deleted.freedBytes > 0L)
        assertNull(cache.getIfPresent(key))
    }

    @Test
    fun evictsOnlyEntriesOlderThanTtl() {
        val cache = McSourcesCache(tempFolder.root.toPath())
        val now = 100L * Duration.ofDays(1).toMillis()
        val oldTime = now - Duration.ofDays(31).toMillis()
        val freshTime = now - Duration.ofDays(5).toMillis()

        val oldJar = cache.publishTempJar("1.20.1-vanilla", cache.createTempJarPath("1.20.1-vanilla").also {
            Files.writeString(it, "old")
        })
        Files.setLastModifiedTime(oldJar, FileTime.fromMillis(oldTime))

        val freshJar = cache.publishTempJar("1.21.1-vanilla", cache.createTempJarPath("1.21.1-vanilla").also {
            Files.writeString(it, "fresh")
        })
        Files.setLastModifiedTime(freshJar, FileTime.fromMillis(freshTime))

        val evicted = cache.evictStale(nowMs = now)
        assertEquals(listOf("1.20.1-vanilla"), evicted)
        assertFalse(Files.exists(oldJar))
        assertTrue(Files.exists(freshJar))
    }

    @Test
    fun cleanWorkPreservesJarsAndCleanAllRemovesEverything() {
        val cache = McSourcesCache(tempFolder.root.toPath())
        val jar = cache.publishTempJar("1.21.1-vanilla", cache.createTempJarPath("1.21.1-vanilla").also {
            Files.writeString(it, "jar-data")
        })
        val workFile = cache.nfrtHomeDir.resolve("work/temp.bin")
        Files.createDirectories(workFile.parent)
        Files.write(workFile, ByteArray(4096))

        val workCleaned = cache.cleanWorkCache()
        assertEquals(0, workCleaned.deletedJars)
        assertEquals(4096L, workCleaned.freedBytes)
        assertFalse(Files.exists(cache.nfrtHomeDir))
        assertTrue(Files.isRegularFile(jar))

        val allCleaned = cache.cleanAll()
        assertEquals(1, allCleaned.deletedJars)
        assertFalse(Files.exists(jar))
    }

    @Test
    fun evictsWorkCacheOnlyAfterItsTtl() {
        val cache = McSourcesCache(tempFolder.root.toPath())
        val now = 100L * Duration.ofDays(1).toMillis()
        val workFile = cache.nfrtHomeDir.resolve("work/temp.bin")
        Files.createDirectories(workFile.parent)
        Files.write(workFile, ByteArray(2048))

        cache.markWorkCacheUsed(nowMs = now - Duration.ofDays(3).toMillis())
        assertEquals(0L, cache.evictStaleWorkCache(nowMs = now))
        assertTrue(Files.exists(workFile))

        cache.markWorkCacheUsed(nowMs = now - Duration.ofDays(8).toMillis())
        assertEquals(2048L, cache.evictStaleWorkCache(nowMs = now))
        assertFalse(Files.exists(cache.nfrtHomeDir))
    }

    @Test
    fun deletesOnlyOldOrphanTempJars() {
        val cache = McSourcesCache(tempFolder.root.toPath())
        val now = 100L * Duration.ofDays(1).toMillis()
        val orphan = cache.createTempJarPath("1.20.1-vanilla")
        Files.setLastModifiedTime(orphan, FileTime.fromMillis(now - Duration.ofHours(2).toMillis()))
        val live = cache.createTempJarPath("1.21.1-vanilla")
        Files.setLastModifiedTime(live, FileTime.fromMillis(now - Duration.ofMinutes(5).toMillis()))

        assertEquals(1, cache.deleteOrphanTempJars(nowMs = now))
        assertFalse(Files.exists(orphan))
        assertTrue(Files.exists(live))

        cache.cleanAll()
        assertFalse(Files.exists(live))
    }

    @Test
    fun sizesAndDeletesTreesWithoutThrowing() {
        val cache = McSourcesCache(tempFolder.root.toPath())
        assertEquals(0L, McSourcesCache.directorySizeBytes(cache.nfrtHomeDir))
        McSourcesCache.deleteRecursively(cache.nfrtHomeDir)

        val nested = cache.nfrtHomeDir.resolve("a/b/c.bin")
        Files.createDirectories(nested.parent)
        Files.write(nested, ByteArray(100))
        assertEquals(100L, McSourcesCache.directorySizeBytes(cache.nfrtHomeDir))
        McSourcesCache.deleteRecursively(cache.nfrtHomeDir)
        assertFalse(Files.exists(cache.nfrtHomeDir))
    }

    @Test
    fun acceptsEveryCharacterRequestValidationAllows() {
        val req = McSourceRequest.validated("1.20.1", "forge", "47.3.0+build.1")
        McSourcesCache.validateKey(req.cacheKey)
    }

    @Test
    fun rejectsUnsafeCacheKeys() {
        assertThrows(IllegalArgumentException::class.java) { McSourcesCache.validateKey("../escape") }
        assertThrows(IllegalArgumentException::class.java) { McSourcesCache.validateKey("a/b") }
        assertThrows(IllegalArgumentException::class.java) { McSourcesCache.validateKey("") }
    }
}
