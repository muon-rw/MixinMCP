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

    @Test
    fun zeroScannedWithIndexExplainsZeroTrigramCandidates() {
        assertEquals(
            "No file in the searched roots contains the pattern's literals (the trigram index found no candidates).",
            describeEmptyScan(0, null, null, usedIndex = true),
        )
        assertEquals(
            "No file matching pathPrefix \"net/minecraft/\" contains the pattern's literals (the trigram index found no candidates).",
            describeEmptyScan(0, null, "net/minecraft/", usedIndex = true),
        )
    }

    @Test
    fun indexCandidatesRemovedByTheFilterPointAtTheFilter() {
        assertEquals(
            "3 files contain the pattern's literals, but none match pathPrefix \"net/minecraft/entity/\", " +
                "so the regex was never tested. Check the filter.",
            describeEmptyScan(0, null, "net/minecraft/entity/", usedIndex = true, indexCandidatesFilteredOut = 3),
        )
    }

    @Test
    fun timeoutDiagnosticsReportOnlyActiveFallbacks() {
        assertEquals(null, describeTimeoutDiagnostics(usesRe2j = true, patternHasTrigrams = true, indexBuildsPending = 0))
        val re2Only = describeTimeoutDiagnostics(usesRe2j = false, patternHasTrigrams = true, indexBuildsPending = 0)!!
        assertEquals(true, re2Only.contains("RE2J"))
        assertEquals(false, re2Only.contains("trigram index"))
        val noTrigrams = describeTimeoutDiagnostics(usesRe2j = true, patternHasTrigrams = false, indexBuildsPending = 0)!!
        assertEquals(false, noTrigrams.contains("RE2J"))
        assertEquals(true, noTrigrams.contains("no 3+ character identifier literals"))
        val building = describeTimeoutDiagnostics(usesRe2j = true, patternHasTrigrams = true, indexBuildsPending = 2)!!
        assertEquals(true, building.contains("2 roots is still being built"))
        assertEquals(false, building.contains("no 3+ character identifier literals"))
        val both = describeTimeoutDiagnostics(usesRe2j = false, patternHasTrigrams = false, indexBuildsPending = 0)!!
        assertEquals(true, both.contains("RE2J") && both.contains("trigram index"))
    }

    @Test
    fun unfinishedRootsAreListedRegardlessOfPosition() {
        assertEquals(null, describeUnfinishedRoots(3, 3, emptyList()))
        assertEquals(
            "1 of 3 roots were fully searched; not fully searched: Library sources: a, Library sources: c.",
            describeUnfinishedRoots(1, 3, listOf("Library sources: a", "Library sources: c")),
        )
    }
}
