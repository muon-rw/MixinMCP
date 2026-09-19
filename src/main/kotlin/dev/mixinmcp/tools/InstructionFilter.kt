package dev.mixinmcp.tools

/**
 * The instructions of a Textifier method dump whose text matches [pattern], each with its source
 * line and, when the same target occurs more than once in that method, its Mixin ordinal: the index
 * among instructions with the same operand (INVOKE owner, name, and descriptor; the full text for other
 * opcodes) in bytecode order. An INVOKEDYNAMIC matches on its bootstrap arguments too and prints the
 * method handle it binds, such as a lambda body. Returns null when nothing matches.
 */
internal fun filterInstructions(textified: String, pattern: Regex): String? {
    val instructions: List<TextifiedInstruction> = parseTextified(textified)
    val totals: Map<Pair<Int, String>, Int> = instructions.groupingBy { it.method to it.key }.eachCount()
    val seen = HashMap<Pair<Int, String>, Int>()
    val matched: List<Triple<TextifiedInstruction, Int, Int>> = instructions.mapNotNull { insn ->
        val id: Pair<Int, String> = insn.method to insn.key
        val ordinal: Int = seen.getOrDefault(id, 0)
        seen[id] = ordinal + 1
        if (pattern.containsMatchIn(insn.searchText)) Triple(insn, ordinal, totals.getValue(id)) else null
    }
    if (matched.isEmpty()) return null
    val methodCount: Int = instructions.maxOfOrNull { it.method }?.plus(1) ?: 0
    return buildString {
        var lastMethod: Int = -1
        for ((insn, ordinal, total) in matched) {
            if (methodCount > 1 && insn.method != lastMethod) {
                appendLine("  -- ${insn.descriptor}")
                lastMethod = insn.method
            }
            val where: String = insn.sourceLine?.let { "line $it: " } ?: ""
            val ordinalNote: String = if (total > 1) "  [ordinal $ordinal of $total]" else ""
            appendLine("  $where${insn.text}$ordinalNote")
        }
    }
}

internal fun countInstructions(textified: String): Int = parseTextified(textified).size

private class TextifiedInstruction(
    val method: Int,
    val descriptor: String,
    val sourceLine: String?,
    val text: String,
    val key: String,
    val searchText: String,
)

private const val INSTRUCTION_INDENT: Int = 4
private val NON_INSTRUCTION_PREFIXES: List<String> = listOf("//", "@", "]", "LOCALVARIABLE ", "MAXSTACK", "MAXLOCALS")

private fun parseTextified(textified: String): List<TextifiedInstruction> {
    val out = mutableListOf<TextifiedInstruction>()
    val lines: List<String> = textified.lines()
    var method: Int = -1
    var descriptor = ""
    var sourceLine: String? = null
    var index = 0
    while (index < lines.size) {
        val raw: String = lines[index++]
        val trimmed: String = raw.trim()
        if (trimmed.startsWith("// descriptor:")) {
            method++
            descriptor = trimmed.removePrefix("// descriptor:").trim()
            sourceLine = null
            continue
        }
        if (indentOf(raw) != INSTRUCTION_INDENT || trimmed.isEmpty()) continue
        if (trimmed.startsWith("LINENUMBER ")) {
            sourceLine = trimmed.split(' ').getOrNull(1)
            continue
        }
        if (NON_INSTRUCTION_PREFIXES.any { trimmed.startsWith(it) }) continue
        val opcode: String = trimmed.substringBefore(' ')
        if (opcode == "INVOKEDYNAMIC" && trimmed.endsWith("[")) {
            val bootstrap = mutableListOf<String>()
            while (index < lines.size && !(indentOf(lines[index]) == INSTRUCTION_INDENT && lines[index].trim() == "]")) {
                bootstrap.add(lines[index++].trim())
            }
            index++
            val text: String = describeInvokeDynamic(trimmed, bootstrap)
            val searchText: String = (listOf(trimmed) + bootstrap).joinToString(" ")
            out.add(TextifiedInstruction(maxOf(method, 0), descriptor, sourceLine, text, text, searchText))
            continue
        }
        val key: String = if (opcode.startsWith("INVOKE") && opcode != "INVOKEDYNAMIC") {
            trimmed.substringAfter(' ').removeSuffix(" (itf)")
        } else {
            trimmed
        }
        out.add(TextifiedInstruction(maxOf(method, 0), descriptor, sourceLine, trimmed, key, trimmed))
    }
    return out
}

private fun indentOf(line: String): Int = line.length - line.trimStart().length

/** The call site plus the method handles among its bootstrap arguments, or the plain arguments when none is a handle. */
private fun describeInvokeDynamic(head: String, bootstrap: List<String>): String {
    val call: String = head.removeSuffix("[").trimEnd()
    val arguments: List<String> = bootstrap.dropWhile { it != "// arguments:" }.drop(1)
    val handles: List<String> = arguments.zipWithNext()
        .filter { (previous, _) -> previous.startsWith("// handle kind") }
        .map { (_, handle) -> handle.removeSuffix(",").trim() }
    val values: List<String> = arguments.filterNot { it.startsWith("//") }.map { it.removeSuffix(",").trim() }
    return when {
        handles.isNotEmpty() -> "$call  -> ${handles.joinToString(", ")}"
        values.isNotEmpty() -> "$call  [${values.joinToString(", ")}]"
        else -> call
    }
}
