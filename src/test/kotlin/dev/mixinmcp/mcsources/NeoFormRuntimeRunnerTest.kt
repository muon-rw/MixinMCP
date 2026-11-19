package dev.mixinmcp.mcsources

import org.junit.Assert.assertEquals
import org.junit.Test

class NeoFormRuntimeRunnerTest {

    @Test
    fun stripsAnsiAndControlChars() {
        val raw = "\u001B[31mError:\u001B[0m failed\u0007\nline 2\tok"
        assertEquals("Error: failed\nline 2\tok", NeoFormRuntimeRunner.stripAnsiAndControlChars(raw))
    }
}
