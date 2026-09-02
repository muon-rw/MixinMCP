package dev.mixinmcp.tools.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceWindowTest {

    private fun resolve(
        lineCount: Int,
        lineNumber: Int = 1,
        linesBefore: Int = 30,
        linesAfter: Int = 70,
        startLine: Int? = null,
        endLine: Int? = null,
    ): SourceWindow = resolveSourceWindow("Foo.java", lineCount, lineNumber, linesBefore, linesAfter, startLine, endLine)

    private fun lines(
        lineCount: Int,
        lineNumber: Int = 1,
        linesBefore: Int = 30,
        linesAfter: Int = 70,
        startLine: Int? = null,
        endLine: Int? = null,
    ): SourceWindow.Lines =
        resolve(lineCount, lineNumber, linesBefore, linesAfter, startLine, endLine) as SourceWindow.Lines

    private fun invalid(lineCount: Int, startLine: Int? = null, endLine: Int? = null): SourceWindow.Invalid =
        resolve(lineCount, startLine = startLine, endLine = endLine) as SourceWindow.Invalid

    @Test
    fun defaultWindowShowsFirstSeventyOneLines() {
        assertEquals(SourceWindow.Lines(1, 71), lines(500))
    }

    @Test
    fun windowSurroundsLineNumber() {
        assertEquals(SourceWindow.Lines(390, 490), lines(600, lineNumber = 420))
    }

    @Test
    fun windowClampsToFileBounds() {
        assertEquals(SourceWindow.Lines(60, 100), lines(100, lineNumber = 90))
    }

    @Test
    fun zeroContextShowsOnlyTheAnchorLine() {
        assertEquals(SourceWindow.Lines(420, 420), lines(600, lineNumber = 420, linesBefore = 0, linesAfter = 0))
    }

    @Test
    fun negativeContextIsTreatedAsZero() {
        assertEquals(SourceWindow.Lines(50, 50), lines(100, lineNumber = 50, linesBefore = -5, linesAfter = -5))
    }

    @Test
    fun outOfRangeLineNumberClampsToEndAndExplains() {
        val window: SourceWindow.Lines = lines(71, lineNumber = 500)
        assertEquals(41, window.start)
        assertEquals(71, window.end)
        assertTrue(window.note!!.contains("requested line 500 is out of range; Foo.java has 71 lines; showing lines 41-71"))
    }

    @Test
    fun explicitRangeIsInclusiveAndUnannotated() {
        val window: SourceWindow.Lines = lines(600, startLine = 420, endLine = 465)
        assertEquals(SourceWindow.Lines(420, 465), window)
        assertNull(window.note)
    }

    @Test
    fun explicitRangeOverridesWindowParameters() {
        assertEquals(
            SourceWindow.Lines(96, 125),
            lines(600, lineNumber = 300, linesBefore = 5, linesAfter = 5, startLine = 96, endLine = 125),
        )
    }

    @Test
    fun startLineAloneReadsToEndOfFile() {
        assertEquals(SourceWindow.Lines(150, 200), lines(200, startLine = 150))
    }

    @Test
    fun endLineAloneReadsFromLineOne() {
        assertEquals(SourceWindow.Lines(1, 20), lines(200, endLine = 20))
    }

    @Test
    fun endLinePastEndOfFileClampsAndExplains() {
        val window: SourceWindow.Lines = lines(100, startLine = 90, endLine = 150)
        assertEquals(90, window.start)
        assertEquals(100, window.end)
        assertTrue(window.note!!.contains("requested lines 90-150 run past the end of Foo.java, which has 100 lines; showing lines 90-100"))
    }

    @Test
    fun startLinePastEndOfFileIsInvalid() {
        assertEquals(
            "startLine 150 is past the end of Foo.java, which has 100 lines.",
            invalid(100, startLine = 150, endLine = 160).message,
        )
    }

    @Test
    fun startLineAlonePastEndOfFileIsInvalid() {
        assertEquals(
            "startLine 150 is past the end of Foo.java, which has 100 lines.",
            invalid(100, startLine = 150).message,
        )
    }

    @Test
    fun hugeLinesAfterClampsToEndOfFileWithoutOverflow() {
        assertEquals(SourceWindow.Lines(390, 600), lines(600, lineNumber = 420, linesAfter = Int.MAX_VALUE))
    }

    @Test
    fun startLineAfterEndLineIsInvalid() {
        assertEquals("startLine (50) is after endLine (40).", invalid(100, startLine = 50, endLine = 40).message)
    }

    @Test
    fun startLineBelowOneIsInvalid() {
        assertEquals("startLine must be at least 1 (got 0).", invalid(100, startLine = 0, endLine = 10).message)
    }

    @Test
    fun endLineBelowOneIsInvalid() {
        assertEquals("endLine must be at least 1 (got 0).", invalid(100, endLine = 0).message)
    }
}
