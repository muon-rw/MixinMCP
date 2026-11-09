package dev.mixinmcp.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArtifactHashMemoTest {

    private fun jar(name: String, bytes: Int): File =
        File.createTempFile(name, ".jar").apply {
            deleteOnExit()
            writeBytes(ByteArray(bytes))
        }

    private fun key(file: File, size: Long = file.length(), modified: Long = file.lastModified()): String =
        "${file.absolutePath}|$size|$modified"

    @Test
    fun byteIdenticalCopiesBothMapToTheirHash() {
        val pack: File = jar("iris-pack", 64)
        val maven: File = jar("iris-maven", 64)
        val hashes: Map<String, String> =
            DecompilationCacheService.currentArtifactHashes(mapOf(key(pack) to "abc", key(maven) to "abc"))

        assertEquals("abc", hashes[DecompilationCacheService.normalizeJarDiskPath(pack.absolutePath)])
        assertEquals("abc", hashes[DecompilationCacheService.normalizeJarDiskPath(maven.absolutePath)])
    }

    @Test
    fun changedOrDeletedJarsAreDropped() {
        val changed: File = jar("changed", 64)
        val deleted: File = jar("deleted", 64).also { it.delete() }
        val hashes: Map<String, String> = DecompilationCacheService.currentArtifactHashes(
            mapOf(key(changed, size = 63) to "old", key(deleted, size = 64, modified = 1) to "gone"),
        )
        assertTrue(hashes.isEmpty())
    }

    @Test
    fun malformedKeysAreIgnored() {
        assertTrue(DecompilationCacheService.currentArtifactHashes(mapOf("no-separators" to "x", "a|b|c" to "y")).isEmpty())
    }
}
