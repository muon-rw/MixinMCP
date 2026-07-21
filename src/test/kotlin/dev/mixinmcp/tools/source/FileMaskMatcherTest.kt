package dev.mixinmcp.tools.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileMaskMatcherTest {

    @Test
    fun nullBlankAndStarMatchEverything() {
        assertTrue(buildFileMaskMatcher(null)("com/x/Validators.java"))
        assertTrue(buildFileMaskMatcher("")("com/x/Validators.java"))
        assertTrue(buildFileMaskMatcher("   ")("com/x/Validators.java"))
        assertTrue(buildFileMaskMatcher("*")("com/x/Validators.java"))
    }

    @Test
    fun bareMaskMatchesAsCaseInsensitiveSubstring() {
        assertTrue(buildFileMaskMatcher("validators")("com/x/Validators.java"))
        assertTrue(buildFileMaskMatcher("LivingEntity.java")("net/minecraft/world/entity/LivingEntity.java"))
        assertTrue(buildFileMaskMatcher("minecraft/world")("net/minecraft/world/entity/LivingEntity.java"))
        assertFalse(buildFileMaskMatcher("Validators")("com/x/Other.java"))
    }

    @Test
    fun substringModeTreatsDotLiterally() {
        assertFalse(buildFileMaskMatcher("Foo.java")("com/x/FooXjava"))
        assertTrue(buildFileMaskMatcher("Foo.java")("com/x/Foo.java"))
    }

    @Test
    fun globStarAndQuestionMarkMatch() {
        assertTrue(buildFileMaskMatcher("*.java")("a/b/C.java"))
        assertTrue(buildFileMaskMatcher("com/*/Validators.java")("com/example/Validators.java"))
        assertTrue(buildFileMaskMatcher("Foo?java")("com/x/Foo.java"))
        assertFalse(buildFileMaskMatcher("Foo?java")("com/x/Foojava"))
    }

    @Test
    fun globEscapesDots() {
        assertFalse(buildFileMaskMatcher("*.java")("a/b/Cjava"))
        assertFalse(buildFileMaskMatcher("Foo.jav?")("com/x/FooXjava"))
    }

    @Test
    fun globsAreCaseInsensitive() {
        assertTrue(buildFileMaskMatcher("*.JAVA")("a/b/C.java"))
        assertTrue(buildFileMaskMatcher("VALIDATORS*")("com/x/validators/Impl.java"))
    }
}
