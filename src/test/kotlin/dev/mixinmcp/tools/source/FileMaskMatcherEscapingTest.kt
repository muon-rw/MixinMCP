package dev.mixinmcp.tools.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileMaskMatcherEscapingTest {

    @Test
    fun braceGlobIsLiteral() {
        assertTrue(buildFileMaskMatcher("{Foo,Bar}*.java")("x/{Foo,Bar}Impl.java"))
        assertFalse(buildFileMaskMatcher("{Foo,Bar}*.java")("x/Foo.java"))
    }

    @Test
    fun backslashesMatchForwardSlashPaths() {
        assertTrue(buildFileMaskMatcher("net\\minecraft*")("net/minecraft/world/Level.java"))
        assertTrue(buildFileMaskMatcher("net\\minecraft")("net/minecraft/world/Level.java"))
        assertTrue(buildFileMaskMatcher("com\\intellij*")("com/intellij/openapi/Foo.java"))
    }

    @Test
    fun regexMetacharactersAreLiteral() {
        assertTrue(buildFileMaskMatcher("Outer\$Inner*")("a/Outer\$Inner.java"))
        assertTrue(buildFileMaskMatcher("Foo(1)*.java")("a/Foo(1).java"))
        assertFalse(buildFileMaskMatcher("Foo(1)*.java")("a/Foo1.java"))
        assertTrue(buildFileMaskMatcher("[a]*.java")("x/[a]b.java"))
        assertFalse(buildFileMaskMatcher("[a]*.java")("x/ab.java"))
    }

    @Test
    fun questionMarkMatchesExactlyOneCharacter() {
        assertTrue(buildFileMaskMatcher("Fo?.java")("Foo.java"))
        assertFalse(buildFileMaskMatcher("Fo?.java")("Fooo.java"))
    }

    @Test
    fun globToRegexQuotesLiteralChunks() {
        assertEquals("\\Qa.b\\E.*\\Q.java\\E", globToRegex("a.b*.java"))
        assertEquals(".*", globToRegex("*"))
        assertEquals("\\Qx\\E.", globToRegex("x?"))
    }
}
