package dev.mixinmcp.tools.source

import com.google.re2j.Pattern as Re2Pattern
import com.google.re2j.PatternSyntaxException as Re2SyntaxException
import java.util.regex.Matcher as JavaMatcher
import java.util.regex.Pattern as JavaPattern

/**
 * Wraps a validated Java regex with a linear-time RE2J engine whenever RE2J supports the syntax,
 * falling back transparently to [java.util.regex.Pattern] for Java-only constructs such as
 * lookarounds, backreferences, or possessive quantifiers.
 */
internal sealed interface CompiledRegex {
    val usesRe2j: Boolean

    fun newLineSession(): LineSession

    interface LineSession {
        /**
         * Matches the line at `[lineStart, lineEnd)` inside [content]. Returns the highlighted
         * line (`||match||`) if matched, or null if the line does not match.
         */
        fun matchAndHighlight(content: String, lineStart: Int, lineEnd: Int): String?

        /** True when the line at `[lineStart, lineEnd)` inside [content] matches the pattern. */
        fun matchesLine(content: String, lineStart: Int, lineEnd: Int): Boolean
    }

    companion object {
        private const val HIGHLIGHT_REPLACEMENT: String = "||\$0||"

        /**
         * Validates [regex] as a Java regex (throwing [java.util.regex.PatternSyntaxException] on
         * invalid syntax), then compiles with RE2J when supported.
         */
        fun compile(regex: String, caseSensitive: Boolean): CompiledRegex {
            val javaPattern: JavaPattern = JavaPattern.compile(
                regex,
                if (caseSensitive) 0 else JavaPattern.CASE_INSENSITIVE,
            )
            return fromJavaPattern(javaPattern)
        }

        fun fromJavaPattern(javaPattern: JavaPattern): CompiledRegex {
            val javaFlags: Int = javaPattern.flags()
            if ((javaFlags and JavaPattern.CASE_INSENSITIVE.inv()) != 0 || readsDifferentlyInRe2j(javaPattern.pattern())) {
                return JavaCompiledRegex(javaPattern)
            }
            val re2Flags: Int = if ((javaFlags and JavaPattern.CASE_INSENSITIVE) != 0) Re2Pattern.CASE_INSENSITIVE else 0
            val re2: Re2Pattern? = try {
                Re2Pattern.compile(javaPattern.pattern(), re2Flags)
            } catch (_: Re2SyntaxException) {
                null
            }
            return if (re2 != null) Re2CompiledRegex(re2) else JavaCompiledRegex(javaPattern)
        }

        private val RE2J_COMPATIBLE_INLINE_FLAGS: Set<Char> = setOf('i', 'm', 's', '-')

        /**
         * True for Java syntax that RE2J compiles without error but matches differently: class unions
         * and intersections (`[a[bc]]`, `[a-z&&[^e]]`), numeric escapes (backreferences or octal in Java),
         * `\v` (all vertical whitespace in Java, only VT in RE2J), and inline flags other than `i`, `m`,
         * `s` (`(?U)` means ungreedy in RE2J).
         */
        internal fun readsDifferentlyInRe2j(regex: String): Boolean {
            var i = 0
            var inClass = false
            while (i < regex.length) {
                val c: Char = regex[i]
                if (c == '\\') {
                    val next: Char = regex.getOrNull(i + 1) ?: return false
                    if (next == 'Q') {
                        val end: Int = regex.indexOf("\\E", i + 2)
                        if (end < 0) return false
                        i = end + 2
                        continue
                    }
                    if (next.isDigit() || next == 'v') return true
                    i += 2
                    continue
                }
                if (inClass) {
                    when {
                        c == '[' -> return true
                        c == '&' && regex.getOrNull(i + 1) == '&' -> return true
                        c == ']' -> inClass = false
                    }
                    i++
                    continue
                }
                when {
                    c == '[' -> {
                        inClass = true
                        i++
                        if (regex.getOrNull(i) == '^') i++
                        // A leading `]` is a literal in both engines.
                        if (regex.getOrNull(i) == ']') i++
                        continue
                    }
                    c == '(' && regex.getOrNull(i + 1) == '?' -> {
                        var j: Int = i + 2
                        while (j < regex.length && (regex[j].isLetter() || regex[j] == '-')) j++
                        val terminator: Char? = regex.getOrNull(j)
                        if (j > i + 2 && (terminator == ')' || terminator == ':') &&
                            regex.substring(i + 2, j).any { it !in RE2J_COMPATIBLE_INLINE_FLAGS }
                        ) {
                            return true
                        }
                    }
                }
                i++
            }
            return false
        }
    }

    private class Re2CompiledRegex(private val pattern: Re2Pattern) : CompiledRegex {
        override val usesRe2j: Boolean = true

        override fun newLineSession(): LineSession = object : LineSession {
            private val slice = LineSlice()

            override fun matchAndHighlight(content: String, lineStart: Int, lineEnd: Int): String? {
                slice.set(content, lineStart, lineEnd)
                val matcher = pattern.matcher(slice)
                if (!matcher.find()) return null
                // Re-run on the line substring for replaceAll so only the matched line allocates a String.
                return pattern.matcher(content.substring(lineStart, lineEnd)).replaceAll(HIGHLIGHT_REPLACEMENT)
            }

            override fun matchesLine(content: String, lineStart: Int, lineEnd: Int): Boolean {
                slice.set(content, lineStart, lineEnd)
                return pattern.matcher(slice).find()
            }
        }
    }

    private class JavaCompiledRegex(private val pattern: JavaPattern) : CompiledRegex {
        override val usesRe2j: Boolean = false

        override fun newLineSession(): LineSession = object : LineSession {
            private var boundContent: String? = null
            private var matcher: JavaMatcher? = null

            private fun regionMatcher(content: String, lineStart: Int, lineEnd: Int): JavaMatcher {
                val m: JavaMatcher = if (boundContent === content && matcher != null) {
                    matcher!!
                } else {
                    boundContent = content
                    pattern.matcher(content).also { matcher = it }
                }
                m.region(lineStart, lineEnd)
                return m
            }

            override fun matchAndHighlight(content: String, lineStart: Int, lineEnd: Int): String? {
                if (!regionMatcher(content, lineStart, lineEnd).find()) return null
                return pattern.matcher(content.substring(lineStart, lineEnd)).replaceAll(HIGHLIGHT_REPLACEMENT)
            }

            override fun matchesLine(content: String, lineStart: Int, lineEnd: Int): Boolean =
                regionMatcher(content, lineStart, lineEnd).find()
        }
    }

    /** Zero-copy reusable [CharSequence] slice over a single line of a file. */
    private class LineSlice : CharSequence {
        private var source: String = ""
        private var start: Int = 0
        override var length: Int = 0
            private set

        fun set(content: String, lineStart: Int, lineEnd: Int) {
            source = content
            start = lineStart
            length = lineEnd - lineStart
        }

        override fun get(index: Int): Char = source[start + index]

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            source.subSequence(start + startIndex, start + endIndex)

        override fun toString(): String = source.substring(start, start + length)
    }
}
