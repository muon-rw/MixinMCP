package dev.mixinmcp.tools.semantic

import dev.mixinmcp.resolve.BytecodeAnalyzer.InvokeSite
import org.junit.Assert.assertEquals
import org.junit.Test

class OrdinalTagFormatTest {

    private fun format(sites: List<InvokeSite>, vararg qualifiers: String): String =
        CallHierarchyExpander.formatOrdinalTag(CallHierarchyExpander.OrdinalTag(sites, qualifiers.toList()))

    @Test
    fun bytecodeOrdinalsListEachCallWithItsLine() {
        assertEquals(
            "[x2: ordinal 0 line 1234, ordinal 1 line 1250]",
            format(listOf(InvokeSite(0, 1234), InvokeSite(1, 1250))),
        )
    }

    @Test
    fun qualifiersFollowTheCountAndMissingLinesAreOmitted() {
        assertEquals(
            "[x2, INVOKE owner a.Sub, source order: ordinal 0, ordinal 1 line 7]",
            format(listOf(InvokeSite(0, null), InvokeSite(1, 7)), "INVOKE owner a.Sub", "source order"),
        )
    }

    @Test
    fun longRunsAreCapped() {
        val sites: List<InvokeSite> = (0 until 12).map { InvokeSite(it, it + 1) }
        assertEquals(
            "[x12: " + (0 until 10).joinToString(", ") { "ordinal $it line ${it + 1}" } + ", +2 more]",
            format(sites),
        )
    }
}
