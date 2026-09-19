package dev.mixinmcp.tools.refactor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixinConfigEntriesTest {

    private val config: String = listOf(
        "{",
        "  \"package\": \"a.mixin\",",
        "  \"plugin\": \"a.Plugin\",",
        "  \"mixins\": [",
        "    \"AMixin\",",
        "    \"BMixin\",",
        "    \"CMixin\"",
        "  ],",
        "  \"client\": [\"DMixin\", \"EMixin\"],",
        "  \"server\": [",
        "    \"FMixin\"",
        "  ]",
        "}",
    ).joinToString("\n")

    private fun rangeOf(text: String, entry: String): Pair<Int, Int> {
        val start: Int = text.indexOf("\"$entry\"")
        return start to start + entry.length + 2
    }

    private fun remove(text: String, entry: String): String {
        val (start: Int, end: Int) = rangeOf(text, entry)
        assertTrue(entry, isMixinConfigEntry(text, start, end))
        val (from: Int, to: Int) = entryRemovalRange(text, start, end)
        return text.removeRange(from, to)
    }

    @Test
    fun middleEntryTakesItsLine() {
        assertTrue(remove(config, "BMixin").contains("    \"AMixin\",\n    \"CMixin\"\n  ],"))
    }

    @Test
    fun lastEntryTakesThePrecedingComma() {
        assertTrue(remove(config, "CMixin").contains("    \"BMixin\"\n  ],"))
    }

    @Test
    fun inlineEntriesKeepTheirSpacing() {
        assertTrue(remove(config, "DMixin").contains("\"client\": [\"EMixin\"],"))
        assertTrue(remove(config, "EMixin").contains("\"client\": [\"DMixin\"],"))
    }

    @Test
    fun onlyEntryLeavesAnEmptyArray() {
        assertTrue(remove(config, "FMixin").contains("\"server\": []"))
    }

    @Test
    fun crlfLineGoesWithTheEntry() {
        val crlf: String = config.replace("\n", "\r\n")
        assertTrue(remove(crlf, "AMixin").contains("\"mixins\": [\r\n    \"BMixin\",\r\n"))
    }

    @Test
    fun stringsOutsideMixinArraysAreNotEntries() {
        val (start: Int, end: Int) = rangeOf(config, "a.Plugin")
        assertFalse(isMixinConfigEntry(config, start, end))
        val entrypoints = "{\"entrypoints\": {\"main\": [\"a.Mod\"]}}"
        val (entryStart: Int, entryEnd: Int) = rangeOf(entrypoints, "a.Mod")
        assertFalse(isMixinConfigEntry(entrypoints, entryStart, entryEnd))
    }

    @Test
    fun contentRangeWidensToTheQuotes() {
        val (start: Int, end: Int) = rangeOf(config, "BMixin")
        assertEquals(start to end, quotedRange(config, start + 1, end - 1))
        assertEquals(start to end, quotedRange(config, start, end))
        assertNull(quotedRange("{\"a\": 1}", 6, 7))
    }
}
