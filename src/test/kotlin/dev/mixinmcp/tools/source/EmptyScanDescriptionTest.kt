package dev.mixinmcp.tools.source

import org.junit.Assert.assertEquals
import org.junit.Test

class EmptyScanDescriptionTest {

    @Test
    fun zeroScannedWithMaskSaysRegexNeverTestedAndExplainsMaskModes() {
        assertEquals(
            "fileMask \"Validators\" matched no files in the searched roots; the regex was never tested." +
                " Without wildcards, fileMask matches as a case-insensitive path substring; with wildcards, as a glob.",
            describeEmptyScan(0, "Validators", null),
        )
    }

    @Test
    fun zeroScannedWithPathPrefixOnlyOmitsMaskModeExplanation() {
        assertEquals(
            "pathPrefix \"net/minecraft\" matched no files in the searched roots; the regex was never tested.",
            describeEmptyScan(0, null, "net/minecraft"),
        )
    }

    @Test
    fun zeroScannedWithBothFiltersNamesBoth() {
        assertEquals(
            "fileMask \"Validators\" under pathPrefix \"net/minecraft\" matched no files in the searched roots; " +
                "the regex was never tested." +
                " Without wildcards, fileMask matches as a case-insensitive path substring; with wildcards, as a glob.",
            describeEmptyScan(0, "Validators", "net/minecraft"),
        )
    }

    @Test
    fun zeroScannedWithoutFiltersSaysRootsAreEmpty() {
        assertEquals("The searched roots contain no source files.", describeEmptyScan(0, null, null))
    }

    @Test
    fun scannedWithMaskUsesSingularAndPlural() {
        assertEquals(
            "fileMask \"Validators\" matched 1 file; the regex matched no lines in it.",
            describeEmptyScan(1, "Validators", null),
        )
        assertEquals(
            "fileMask \"Validators\" matched 3 files; the regex matched no lines in them.",
            describeEmptyScan(3, "Validators", null),
        )
    }

    @Test
    fun scannedWithoutFiltersReportsFileCount() {
        assertEquals("The regex matched no lines in the 1 file scanned.", describeEmptyScan(1, null, null))
        assertEquals("The regex matched no lines in the 5 files scanned.", describeEmptyScan(5, null, null))
    }

    @Test
    fun blankAndStarMasksAreTreatedAsNoMask() {
        assertEquals("The searched roots contain no source files.", describeEmptyScan(0, "*", null))
        assertEquals("The searched roots contain no source files.", describeEmptyScan(0, "   ", null))
        assertEquals("The regex matched no lines in the 2 files scanned.", describeEmptyScan(2, "*", null))
        assertEquals(
            "pathPrefix \"a/b\" matched no files in the searched roots; the regex was never tested.",
            describeEmptyScan(0, " * ", "a/b"),
        )
    }
}
