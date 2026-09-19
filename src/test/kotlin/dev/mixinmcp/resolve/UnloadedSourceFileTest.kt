package dev.mixinmcp.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UnloadedSourceFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun source(relative: String): File =
        File(folder.root, relative).apply { parentFile.mkdirs(); writeText("class X {}") }

    private fun expectedPath(file: File): String = file.path.replace('\\', '/')

    @Test
    fun fileTheVfsHasNotSeenIsReported() {
        val file: File = source("a/b/Lion.java")
        assertEquals(expectedPath(file), FqcnResolver.unloadedSourceFile(listOf(folder.root.path), "a.b.Lion") { null })
    }

    @Test
    fun nestedClassMapsToItsOuterFile() {
        val file: File = source("a/b/Lion.kt")
        assertEquals(expectedPath(file), FqcnResolver.unloadedSourceFile(listOf(folder.root.path), "a.b.Lion\$Pride") { null })
    }

    @Test
    fun fileNewerOnDiskThanInTheVfsIsReported() {
        val file: File = source("a/b/Lion.java")
        assertEquals(
            expectedPath(file),
            FqcnResolver.unloadedSourceFile(listOf(folder.root.path), "a.b.Lion") { it.lastModified() - 1000 },
        )
    }

    @Test
    fun fileInSyncWithTheVfsIsNotReported() {
        source("a/b/Lion.java")
        assertNull(FqcnResolver.unloadedSourceFile(listOf(folder.root.path), "a.b.Lion") { it.lastModified() })
    }

    @Test
    fun missingFileIsNotReported() {
        assertNull(FqcnResolver.unloadedSourceFile(listOf(folder.root.path), "a.b.Lion") { null })
    }
}
