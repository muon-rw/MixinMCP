package dev.mixinmcp.tools.source

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.util.regex.Pattern

class DepSearchCollectorTest : LightJavaCodeInsightFixtureTestCase() {

    fun testGetPathForMaskReturnsRootRelativeForwardSlashPath() {
        val vf: VirtualFile = myFixture
            .addFileToProject("deps1/com/example/FooValidators.java", "class FooValidators {}")
            .virtualFile
        val root: VirtualFile = myFixture.findFileInTempDir("deps1")
        assertEquals("com/example/FooValidators.java", getPathForMask(root, vf))
    }

    fun testGetPathForMaskFallsBackToFileNameOutsideRoot() {
        val vf: VirtualFile = myFixture.addFileToProject("other/Bar.java", "class Bar {}").virtualFile
        myFixture.addFileToProject("deps2/x/X.java", "class X {}")
        val root: VirtualFile = myFixture.findFileInTempDir("deps2")
        assertEquals("Bar.java", getPathForMask(root, vf))
    }

    fun testScannedFilesCountsEveryMaskMatchingFileWhenRegexMisses() {
        myFixture.addFileToProject("deps3/com/example/FooValidators.java", "int a;")
        myFixture.addFileToProject("deps3/com/example/BarValidators.java", "int b;")
        myFixture.addFileToProject("deps3/com/example/Other.java", "int c;")
        val (hits, scanned) = scan(myFixture.findFileInTempDir("deps3"), "zzz_nomatch", "Validators")
        assertTrue(hits.isEmpty())
        assertEquals(2, scanned)
    }

    fun testScannedFilesStaysZeroWhenMaskMatchesNoFiles() {
        myFixture.addFileToProject("deps4/com/example/FooValidators.java", "int a;")
        val (hits, scanned) = scan(myFixture.findFileInTempDir("deps4"), "int", "Nonexistent")
        assertTrue(hits.isEmpty())
        assertEquals(0, scanned)
    }

    fun testPathPrefixAndSkipPathFilterBeforeCounting() {
        myFixture.addFileToProject("deps5/com/example/FooValidators.java", "int a;")
        myFixture.addFileToProject("deps5/org/other/BarValidators.java", "int b;")
        myFixture.addFileToProject("deps5/com/skipped/BazValidators.java", "int c;")
        val (hits, scanned) = scan(
            myFixture.findFileInTempDir("deps5"),
            "zzz_nomatch",
            "Validators",
            pathPrefix = "com/",
            skipPath = { it.startsWith("com/skipped/") },
        )
        assertTrue(hits.isEmpty())
        assertEquals(1, scanned)
    }

    fun testMatchingRegexRecordsHitsAndCountsFile() {
        myFixture.addFileToProject("deps6/com/example/FooValidators.java", "int fooField;")
        val (hits, scanned) = scan(myFixture.findFileInTempDir("deps6"), "fooField", "Validators")
        assertEquals(1, hits.size)
        assertEquals("com/example/FooValidators.java", hits.single().filePath)
        assertEquals(1, scanned)
    }

    private fun scan(
        root: VirtualFile,
        regex: String,
        mask: String?,
        pathPrefix: String? = null,
        skipPath: (String) -> Boolean = { false },
    ): Pair<List<DepSearchHit>, Int> {
        val hits: MutableList<DepSearchHit> = mutableListOf()
        val scanned = IntArray(1)
        collectRegexHits(
            root,
            root,
            Pattern.compile(regex),
            buildFileMaskMatcher(mask),
            hits,
            100,
            System.currentTimeMillis(),
            60_000,
            pathPrefix = pathPrefix,
            skipPath = skipPath,
            scannedFiles = scanned,
        )
        return hits to scanned[0]
    }
}
