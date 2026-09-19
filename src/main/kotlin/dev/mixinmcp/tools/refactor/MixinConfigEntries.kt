package dev.mixinmcp.tools.refactor

internal const val MIXIN_ANNOTATION: String = "org.spongepowered.asm.mixin.Mixin"

private val MIXIN_ARRAY_KEY = Regex("\"(mixins|client|server)\"\\s*:\\s*$")
private val STRING_ELEMENTS = Regex("(\\s*\"[^\"\\\\]*\"\\s*,)*\\s*")

/** [start, end) widened to the surrounding quotes when it covers only a JSON string's content; null when not a string. */
internal fun quotedRange(text: CharSequence, start: Int, end: Int): Pair<Int, Int>? {
    var from: Int = start
    var to: Int = end
    if (from > 0 && from < text.length && text[from] != '"' && text[from - 1] == '"') from--
    if (to > from && to < text.length && text[to - 1] != '"' && text[to] == '"') to++
    return if (to - from >= 2 && text[from] == '"' && text[to - 1] == '"') from to to else null
}

/** True when the quoted string at [start, end) is an element of a mixin config's mixins, client, or server array. */
internal fun isMixinConfigEntry(text: CharSequence, start: Int, end: Int): Boolean {
    val open: Int = text.lastIndexOf('[', start)
    if (open < 0 || !STRING_ELEMENTS.matches(text.subSequence(open + 1, start))) return false
    val next: Int = skipWhitespace(text, end)
    if (next >= text.length || text[next] !in ",]") return false
    return MIXIN_ARRAY_KEY.containsMatchIn(text.subSequence(maxOf(0, open - 64), open))
}

/**
 * The half-open range to delete so the array element at [start, end) disappears with its separating comma,
 * taking its whole line when it sits on a line of its own.
 */
internal fun entryRemovalRange(text: CharSequence, start: Int, end: Int): Pair<Int, Int> {
    val next: Int = skipWhitespace(text, end)
    if (text[next] == ',') {
        var to: Int = next + 1
        while (to < text.length && text[to] in " \t") to++
        val lineStart: Int = text.lastIndexOf('\n', start - 1) + 1
        val aloneOnLine: Boolean = (lineStart until start).all { text[it] in " \t" } &&
            (to == text.length || text[to] == '\r' || text[to] == '\n')
        if (!aloneOnLine) return start to to
        if (to < text.length && text[to] == '\r') to++
        if (to < text.length && text[to] == '\n') to++
        return lineStart to to
    }
    var previous: Int = start - 1
    while (previous >= 0 && text[previous].isWhitespace()) previous--
    return if (text[previous] == ',') previous to end else previous + 1 to next
}

private fun skipWhitespace(text: CharSequence, from: Int): Int {
    var index: Int = from
    while (index < text.length && text[index].isWhitespace()) index++
    return index
}
