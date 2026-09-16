package dev.mixinmcp.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GradleRootResolutionTest {

    @Test
    fun backslashPathMatchesLinkedRoot() {
        assertEquals("C:/p/mod", resolveGradleRoot(normalizeDiskPath("C:\\p\\mod\\"), listOf("C:/p/mod"), "C:/p/mod"))
    }

    @Test
    fun subdirectoryResolvesToDeepestContainingRoot() {
        assertEquals(
            "C:/p/mod/common",
            resolveGradleRoot("C:/p/mod/common/src", listOf("C:/p/mod", "C:/p/mod/common"), "C:/p/mod"),
        )
    }

    @Test
    fun projectDirWithOneNestedRootResolvesToIt() {
        assertEquals("C:/p/mod/gradle-root", resolveGradleRoot("C:/p/mod", listOf("C:/p/mod/gradle-root"), "C:/p/mod"))
    }

    @Test
    fun projectDirWithSeveralNestedRootsIsAmbiguous() {
        assertNull(resolveGradleRoot("C:/p/mod", listOf("C:/p/mod/a", "C:/p/mod/b"), "C:/p/mod"))
    }

    @Test
    fun unrelatedPathIsRejected() {
        assertNull(resolveGradleRoot("D:/other", listOf("C:/p/mod"), "C:/p/mod"))
        assertNull(resolveGradleRoot("C:/p/mod", emptyList(), "C:/p/mod"))
    }

    @Test
    fun normalizeCollapsesDotSegmentsAndSeparators() {
        assertEquals("C:/p/mod", normalizeDiskPath("C:\\p\\x\\..\\mod\\"))
        assertEquals("C:/p/mod", normalizeDiskPath("  C:/p/./mod  "))
    }
}
