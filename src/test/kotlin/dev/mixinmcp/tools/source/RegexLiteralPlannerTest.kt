package dev.mixinmcp.tools.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegexLiteralPlannerTest {

    @Test
    fun extractsEscapedMethodCallLiteralAndTrigram() {
        val plan = RegexLiteralPlanner.plan("""addEffect\(""", caseSensitive = true)
        assertEquals(RegexLiteralPlanner.TrigramQuery.Clauses(listOf(listOf("addEffect"))), plan.trigramQuery)
        assertEquals(RegexLiteralPlanner.LiteralPrefilter.RequiredAll(listOf("addEffect("), ignoreCase = false), plan.prefilter)
    }

    @Test
    fun extractsAndClausesAcrossWildcards() {
        val plan = RegexLiteralPlanner.plan("""LivingEntity.*actuallyHurt\(""", caseSensitive = true)
        assertEquals(
            RegexLiteralPlanner.TrigramQuery.Clauses(listOf(listOf("LivingEntity", "actuallyHurt"))),
            plan.trigramQuery,
        )
        assertTrue(plan.prefilter.matches("class LivingEntity { void actuallyHurt() {} }"))
        assertFalse(plan.prefilter.matches("class LivingEntity { void tick() {} }"))
    }

    @Test
    fun extractsOrClausesForAlternationAndFactoredGroup() {
        val alt = RegexLiteralPlanner.plan("addEffect|removeEffect", caseSensitive = true)
        assertEquals(
            RegexLiteralPlanner.TrigramQuery.Clauses(listOf(listOf("addEffect"), listOf("removeEffect"))),
            alt.trigramQuery,
        )

        val factored = RegexLiteralPlanner.plan("get(Health|MaxHealth)", caseSensitive = true)
        assertEquals(
            RegexLiteralPlanner.TrigramQuery.Clauses(listOf(listOf("getHealth"), listOf("getMaxHealth"))),
            factored.trigramQuery,
        )
    }

    @Test
    fun handlesQuantifiersSoundly() {
        val optionalChar = RegexLiteralPlanner.plan("foo?bar", caseSensitive = true)
        assertEquals(
            RegexLiteralPlanner.TrigramQuery.Clauses(listOf(listOf("bar"))),
            optionalChar.trigramQuery,
        )
        assertTrue(optionalChar.prefilter.matches("fobar"))
        assertTrue(optionalChar.prefilter.matches("foobar"))

        val plusChar = RegexLiteralPlanner.plan("foo+bar", caseSensitive = true)
        assertTrue(plusChar.prefilter.matches("foobar"))
        assertTrue(plusChar.prefilter.matches("foooobar"))

        // Multi-branch repeat can start with one branch and end with another.
        val multiRepeat = RegexLiteralPlanner.plan("x(ab|cd)+y", caseSensitive = true)
        assertTrue(multiRepeat.prefilter.matches("xabcdy"))
        assertTrue(multiRepeat.prefilter.matches("xaby"))
        assertTrue(multiRepeat.prefilter.matches("xcdy"))
    }

    @Test
    fun handlesInlineCaseFlagAndCharClasses() {
        val inlineFlag = RegexLiteralPlanner.plan("(?i)computeIfAbsent\\(", caseSensitive = true)
        assertTrue(inlineFlag.prefilter.ignoreCase)
        assertTrue(inlineFlag.prefilter.matches("COMPUTEIFABSENT("))

        // Pipe inside character class must not split the alternation.
        val charClass = RegexLiteralPlanner.plan("[a|b]+Effect", caseSensitive = true)
        assertEquals(
            RegexLiteralPlanner.TrigramQuery.Clauses(listOf(listOf("Effect"))),
            charClass.trigramQuery,
        )
    }

    @Test
    fun degradesSafelyWhenBranchHasNoTrigramsOrExplodes() {
        assertEquals(RegexLiteralPlanner.TrigramQuery.All, RegexLiteralPlanner.plan("a|addEffect", true).trigramQuery)
        assertEquals(RegexLiteralPlanner.TrigramQuery.All, RegexLiteralPlanner.plan(".*", true).trigramQuery)
        assertEquals(
            RegexLiteralPlanner.TrigramQuery.All,
            RegexLiteralPlanner.plan("(aaa|bbb)(ccc|ddd)(eee|fff)(ggg|hhh)(iii|jjj)", true).trigramQuery,
        )
    }

    @Test
    fun quotedLiteralAndLookaroundAreSound() {
        val quoted = RegexLiteralPlanner.plan("""\Qnet.minecraft.Level\E""", caseSensitive = true)
        assertEquals(
            RegexLiteralPlanner.TrigramQuery.Clauses(listOf(listOf("net", "minecraft", "Level"))),
            quoted.trigramQuery,
        )
        assertTrue(quoted.prefilter.matches("net.minecraft.Level"))

        val lookaround = RegexLiteralPlanner.plan("""(?<!//)addEffect\(""", caseSensitive = true)
        assertEquals(
            RegexLiteralPlanner.TrigramQuery.Clauses(listOf(listOf("addEffect"))),
            lookaround.trigramQuery,
        )
    }

    @Test
    fun matchContentLinesMatchesJumpAndRegionPathsIdentically() {
        val content = "alpha\r\n  foo(1) + foo(2)\nbar\r^startOnly\n  endOnly$"
        val patterns = listOf(
            "foo\\(\\d+\\)" to true,
            "FOO\\(\\d+\\)" to false,
            "(?i)foo\\(\\d+\\)" to true,
            "^\\^startOnly$" to true,
            "alpha|bar" to true,
            "(?<!x)foo\\(\\d+\\)" to true,
        )
        for ((regex, caseSensitive) in patterns) {
            val compiled = CompiledRegex.compile(regex, caseSensitive)
            val plan = RegexLiteralPlanner.plan(regex, caseSensitive)
            val viaPlan = matchContentLines(
                content,
                "file:///T.java",
                "T.java",
                "root",
                compiled.newLineSession(),
                plan.prefilter,
                100,
            )
            val viaRegion = matchContentLines(
                content,
                "file:///T.java",
                "T.java",
                "root",
                compiled.newLineSession(),
                RegexLiteralPlanner.LiteralPrefilter.None,
                100,
            )
            assertEquals("Mismatch for regex '$regex' (caseSensitive=$caseSensitive)", viaRegion, viaPlan)
        }
    }

    @Test
    fun matchContentLinesCountsCrLfAndLfLineNumbersAccurately() {
        val content = "line1\r\nline2\nline3\rtargetHit\nline5"
        val compiled = CompiledRegex.compile("targetHit", caseSensitive = true)
        val plan = RegexLiteralPlanner.plan("targetHit", caseSensitive = true)
        val hits = matchContentLines(content, "file:///T.java", "T.java", "root", compiled.newLineSession(), plan.prefilter, 100)
        assertEquals(1, hits.size)
        assertEquals(4, hits.single().lineNum)
        assertEquals("||targetHit||", hits.single().highlighted)
    }

    @Test
    fun compiledRegexUsesRe2jByDefaultAndFallsBackForJavaOnlySyntax() {
        val standard = CompiledRegex.compile("addEffect\\(\\w+\\)", caseSensitive = true)
        assertTrue(standard.usesRe2j)

        val lookbehind = CompiledRegex.compile("(?<!//)addEffect\\(", caseSensitive = true)
        assertFalse(lookbehind.usesRe2j)
        val hit = lookbehind.newLineSession().matchAndHighlight("x.addEffect(e);", 0, 15)
        assertEquals("x.||addEffect(||e);", hit)

        val backref = CompiledRegex.compile("(\\w+)=\\1", caseSensitive = true)
        assertFalse(backref.usesRe2j)
        assertEquals("||foo=foo||", backref.newLineSession().matchAndHighlight("foo=foo", 0, 7))
    }

    @Test
    fun commentsModeDisablesLiteralPlanning() {
        val plan = RegexLiteralPlanner.plan("(?x) get Health # getter", caseSensitive = true)
        assertEquals(RegexLiteralPlanner.TrigramQuery.All, plan.trigramQuery)
        assertTrue(plan.prefilter.matches("float getHealth()"))
    }

    @Test
    fun multiCharacterEscapesNeverBecomeRequiredLiterals() {
        val content = "fooAbar and foo\u0001baz and fooéqux and (x)=x12"
        for (regex in listOf("""foo\0101bar""", """foo\cAbaz""", """foo\N{LATIN SMALL LETTER E WITH ACUTE}qux""", """\((\w)\)=\112""")) {
            val plan = RegexLiteralPlanner.plan(regex, caseSensitive = true)
            assertTrue("prefilter rejected a matching file for '$regex'", plan.prefilter.matches(content))
            assertTrue("pattern '$regex' should match", java.util.regex.Pattern.compile(regex).matcher(content).find())
        }
    }

    @Test
    fun javaOnlyReadingsNeverRunOnRe2j() {
        val cases: List<Pair<String, String>> = listOf(
            "[a-z&&[^aeiou]]ing" to "thing",
            "[a[bc]]x" to "cx",
            """(\w)\1""" to "aa",
            """foo\0101""" to "fooA",
            """a\vb""" to "a\u000Cb",
            "(?U)\\w+" to "éé",
        )
        for ((regex, line) in cases) {
            val compiled = CompiledRegex.compile(regex, caseSensitive = true)
            assertFalse("'$regex' must use java.util.regex", compiled.usesRe2j)
            assertTrue(
                "'$regex' should match '$line' as in Java",
                compiled.newLineSession().matchesLine(line, 0, line.length),
            )
        }
        assertTrue(CompiledRegex.compile("(?i)[]a-z]+(?s:x)", caseSensitive = true).usesRe2j)
    }

    @Test
    fun re2jHandlesBacktrackingPatternInLinearTime() {
        val evil = CompiledRegex.compile("(a+)+$", caseSensitive = true)
        assertTrue(evil.usesRe2j)
        val input = "a".repeat(32) + "!"
        assertEquals(null, evil.newLineSession().matchAndHighlight(input, 0, input.length))
    }
}
