package dev.mixinmcp.tools.source

internal sealed interface SourceWindow {
    data class Lines(val start: Int, val end: Int, val note: String? = null) : SourceWindow
    data class Invalid(val message: String) : SourceWindow
}

internal fun resolveSourceWindow(
    fileName: String,
    lineCount: Int,
    lineNumber: Int,
    linesBefore: Int,
    linesAfter: Int,
    startLine: Int?,
    endLine: Int?,
): SourceWindow {
    if (startLine != null || endLine != null) {
        return resolveExplicitRange(fileName, lineCount, startLine, endLine)
    }
    val outOfRange: Boolean = lineNumber !in 1..lineCount
    val anchor: Int = lineNumber.coerceIn(1, lineCount)
    val start: Int = (anchor - linesBefore.coerceAtLeast(0)).coerceAtLeast(1)
    val end: Int = (anchor.toLong() + linesAfter.coerceAtLeast(0)).coerceAtMost(lineCount.toLong()).toInt()
    val note: String? = if (outOfRange) {
        "requested line $lineNumber is out of range; $fileName has $lineCount lines; showing lines $start-$end. " +
            "Line numbers may be stale; re-run mixin_search_in_deps."
    } else {
        null
    }
    return SourceWindow.Lines(start, end, note)
}

private fun resolveExplicitRange(fileName: String, lineCount: Int, startLine: Int?, endLine: Int?): SourceWindow {
    val start: Int = startLine ?: 1
    val end: Int = endLine ?: lineCount
    if (start < 1) return SourceWindow.Invalid("startLine must be at least 1 (got $start).")
    if (end < 1) return SourceWindow.Invalid("endLine must be at least 1 (got $end).")
    if (start > lineCount) {
        return SourceWindow.Invalid("startLine $start is past the end of $fileName, which has $lineCount lines.")
    }
    if (start > end) return SourceWindow.Invalid("startLine ($start) is after endLine ($end).")
    val clampedEnd: Int = end.coerceAtMost(lineCount)
    val note: String? = if (end > lineCount) {
        "requested lines $start-$end run past the end of $fileName, which has $lineCount lines; showing lines $start-$clampedEnd."
    } else {
        null
    }
    return SourceWindow.Lines(start, clampedEnd, note)
}
