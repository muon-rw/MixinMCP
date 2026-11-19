package dev.mixinmcp.tools.source

/**
 * Extracts sound literal pre-filters and trigram index queries from a Java regex.
 *
 * Every approximation only widens the candidate set (never causes false negatives):
 * unsupported constructs, optional subexpressions, and overly wide alternations degrade
 * to wildcards rather than dropping matches.
 */
internal object RegexLiteralPlanner {

    private const val MAX_BRANCHES: Int = 16
    private const val MIN_TRIGRAM_LEN: Int = 3

    sealed interface TrigramQuery {
        data object All : TrigramQuery
        /** Union (OR) of branches; each branch is an intersection (AND) of identifier words >= 3 chars. */
        data class Clauses(val branches: List<List<String>>) : TrigramQuery
    }

    sealed interface LiteralPrefilter {
        fun matches(content: CharSequence): Boolean

        /** Single longest required literal for fast indexOf jumping inside a file, if one exists. */
        val jumpLiteral: String?
        val ignoreCase: Boolean

        data object None : LiteralPrefilter {
            override fun matches(content: CharSequence): Boolean = true
            override val jumpLiteral: String? = null
            override val ignoreCase: Boolean = false
        }

        data class RequiredAll(
            val literals: List<String>,
            override val ignoreCase: Boolean,
        ) : LiteralPrefilter {
            override val jumpLiteral: String = literals.maxBy { it.length }

            override fun matches(content: CharSequence): Boolean {
                for (lit in literals) {
                    if (content.indexOf(lit, ignoreCase = ignoreCase) < 0) return false
                }
                return true
            }
        }

        data class RequiredAnyBranch(
            val branches: List<List<String>>,
            override val ignoreCase: Boolean,
        ) : LiteralPrefilter {
            override val jumpLiteral: String? = null

            override fun matches(content: CharSequence): Boolean {
                outer@ for (branch in branches) {
                    for (lit in branch) {
                        if (content.indexOf(lit, ignoreCase = ignoreCase) < 0) continue@outer
                    }
                    return true
                }
                return false
            }
        }
    }

    data class Plan(
        val trigramQuery: TrigramQuery,
        val prefilter: LiteralPrefilter,
    )

    fun plan(regex: String, caseSensitive: Boolean): Plan {
        val parser = Parser(regex)
        val expr: Fragment = parser.parseAlternation()
        // In comments mode Java ignores whitespace and `#` comments, which we would otherwise require as literals.
        if (parser.sawCommentsFlag) return Plan(TrigramQuery.All, LiteralPrefilter.None)
        val ignoreCase: Boolean = !caseSensitive || parser.sawCaseFlag
        val trigramQuery: TrigramQuery = toTrigramQuery(expr)
        val prefilter: LiteralPrefilter = toPrefilter(expr, ignoreCase)
        return Plan(trigramQuery, prefilter)
    }

    private fun toTrigramQuery(expr: Fragment): TrigramQuery {
        if (expr.branches.isEmpty()) return TrigramQuery.All
        val result: MutableList<List<String>> = ArrayList(expr.branches.size)
        for (branch in expr.branches) {
            val words: List<String> = branch.allLiterals()
                .flatMap { extractIdentifierWords(it) }
                .distinct()
            if (words.isEmpty()) return TrigramQuery.All
            result.add(words)
        }
        return TrigramQuery.Clauses(result.distinct())
    }

    private fun toPrefilter(expr: Fragment, ignoreCase: Boolean): LiteralPrefilter {
        if (expr.branches.isEmpty()) return LiteralPrefilter.None
        val cleanedBranches: MutableList<List<String>> = ArrayList(expr.branches.size)
        for (branch in expr.branches) {
            val lits: List<String> = branch.allLiterals()
                .filter { it.length >= 2 }
                .distinct()
                .sortedByDescending { it.length }
            if (lits.isEmpty()) return LiteralPrefilter.None
            cleanedBranches.add(lits)
        }
        val distinct: List<List<String>> = cleanedBranches.distinct()
        return if (distinct.size == 1) {
            LiteralPrefilter.RequiredAll(distinct.single(), ignoreCase)
        } else {
            LiteralPrefilter.RequiredAnyBranch(distinct, ignoreCase)
        }
    }

    private fun extractIdentifierWords(literal: String): List<String> {
        val out: MutableList<String> = mutableListOf()
        var start = -1
        for (i in literal.indices) {
            if (Character.isJavaIdentifierPart(literal[i])) {
                if (start < 0) start = i
            } else if (start >= 0) {
                if (i - start >= MIN_TRIGRAM_LEN) out.add(literal.substring(start, i))
                start = -1
            }
        }
        if (start >= 0 && literal.length - start >= MIN_TRIGRAM_LEN) {
            out.add(literal.substring(start))
        }
        return out
    }

    /**
     * One conjunction branch of a regex fragment.
     *
     * When [exact] is true, the branch matches the single literal [prefix] with no wildcards,
     * so concatenation with a neighbor can merge literals directly (e.g. `get` + `(Health|MaxHealth)`
     * becomes `getHealth` | `getMaxHealth`). When [exact] is false, [prefix] is the required
     * leading literal, [suffix] is the required trailing literal, and [middle] holds any required
     * interior literals separated by wildcards.
     */
    private data class Branch(
        val exact: Boolean,
        val prefix: String,
        val middle: List<String>,
        val suffix: String,
    ) {
        fun allLiterals(): List<String> = buildList {
            if (prefix.isNotEmpty()) add(prefix)
            addAll(middle)
            if (!exact && suffix.isNotEmpty()) add(suffix)
        }

        companion object {
            val EMPTY_EXACT = Branch(exact = true, prefix = "", middle = emptyList(), suffix = "")
            val WILDCARD = Branch(exact = false, prefix = "", middle = emptyList(), suffix = "")

            fun literal(text: String): Branch = Branch(exact = true, prefix = text, middle = emptyList(), suffix = "")
        }
    }

    private data class Fragment(val branches: List<Branch>) {
        companion object {
            val EMPTY = Fragment(listOf(Branch.EMPTY_EXACT))
            val ANY = Fragment(listOf(Branch.WILDCARD))

            fun literal(text: String): Fragment =
                if (text.isEmpty()) EMPTY else Fragment(listOf(Branch.literal(text)))
        }
    }

    private fun concat(a: Fragment, b: Fragment): Fragment {
        if (a === Fragment.EMPTY) return b
        if (b === Fragment.EMPTY) return a
        if (a.branches.size * b.branches.size > MAX_BRANCHES) {
            val aCollapsed: Fragment = collapseToWildcardIfMulti(a)
            val bCollapsed: Fragment = collapseToWildcardIfMulti(b)
            if (aCollapsed.branches.size * bCollapsed.branches.size > MAX_BRANCHES) return Fragment.ANY
            return concatExact(aCollapsed, bCollapsed)
        }
        return concatExact(a, b)
    }

    private fun collapseToWildcardIfMulti(f: Fragment): Fragment =
        if (f.branches.size > 1) Fragment.ANY else f

    private fun concatExact(a: Fragment, b: Fragment): Fragment {
        val out: MutableList<Branch> = ArrayList(a.branches.size * b.branches.size)
        for (left in a.branches) {
            for (right in b.branches) {
                out.add(concatBranches(left, right))
            }
        }
        return Fragment(out)
    }

    private fun concatBranches(left: Branch, right: Branch): Branch = when {
        left.exact && right.exact ->
            Branch(exact = true, prefix = left.prefix + right.prefix, middle = emptyList(), suffix = "")
        left.exact ->
            Branch(exact = false, prefix = left.prefix + right.prefix, middle = right.middle, suffix = right.suffix)
        right.exact ->
            Branch(exact = false, prefix = left.prefix, middle = left.middle, suffix = left.suffix + right.prefix)
        else -> {
            val joined: String = left.suffix + right.prefix
            val mid: List<String> = buildList(left.middle.size + right.middle.size + 1) {
                addAll(left.middle)
                if (joined.isNotEmpty()) add(joined)
                addAll(right.middle)
            }
            Branch(exact = false, prefix = left.prefix, middle = mid, suffix = right.suffix)
        }
    }

    private fun alternate(parts: List<Fragment>): Fragment {
        if (parts.size == 1) return parts.single()
        val total: Int = parts.sumOf { it.branches.size }
        if (total > MAX_BRANCHES) return Fragment.ANY
        val out: MutableList<Branch> = ArrayList(total)
        for (p in parts) {
            for (b in p.branches) {
                if (b.allLiterals().isEmpty()) return Fragment.ANY
                out.add(b)
            }
        }
        return Fragment(out)
    }

    /**
     * Makes a fragment repeatable 1+ times: its required literals stay required,
     * and any exact branch becomes non-exact (breaking right-edge literal merging
     * so `fo+bar` yields `["fo", "bar"]` rather than `"fobar"`).
     */
    private fun repeatOneOrMore(f: Fragment): Fragment {
        if (f.branches.size > 1) {
            // A multi-branch repeat like (ab|cd)+ can start with one branch and end with another,
            // so neither edge can merge with neighboring literals.
            return Fragment(
                f.branches.map { b ->
                    Branch(exact = false, prefix = "", middle = b.allLiterals(), suffix = "")
                },
            )
        }
        return Fragment(
            f.branches.map { b ->
                if (b.exact) {
                    Branch(exact = false, prefix = b.prefix, middle = emptyList(), suffix = b.prefix)
                } else {
                    Branch(exact = false, prefix = b.prefix, middle = b.middle, suffix = b.suffix)
                }
            },
        )
    }

    private class Parser(private val s: String) {
        private var pos: Int = 0
        var sawCaseFlag: Boolean = false
        var sawCommentsFlag: Boolean = false

        fun parseAlternation(): Fragment {
            val parts: MutableList<Fragment> = mutableListOf(parseConcat())
            while (pos < s.length && s[pos] == '|') {
                pos++
                parts.add(parseConcat())
            }
            return alternate(parts)
        }

        private fun parseConcat(): Fragment {
            var acc: Fragment = Fragment.EMPTY
            while (pos < s.length) {
                val c: Char = s[pos]
                if (c == ')' || c == '|') break
                val atom: Fragment = parseAtom()
                val quantified: Fragment = applyQuantifier(atom)
                acc = concat(acc, quantified)
            }
            return acc
        }

        private fun parseAtom(): Fragment {
            return when (val c: Char = s[pos]) {
                '(' -> parseGroup()
                '[' -> {
                    skipCharClass()
                    Fragment.ANY
                }
                '.' -> {
                    pos++
                    Fragment.ANY
                }
                '^', '$' -> {
                    pos++
                    Fragment.ANY
                }
                '\\' -> parseEscape()
                '*', '+', '?' -> {
                    // Stray quantifier; consume safely.
                    pos++
                    Fragment.ANY
                }
                else -> {
                    pos++
                    Fragment.literal(c.toString())
                }
            }
        }

        private fun parseGroup(): Fragment {
            pos++ // consume '('
            if (pos >= s.length) return Fragment.ANY
            if (s[pos] == '?') {
                pos++ // consume '?'
                if (pos >= s.length) return Fragment.ANY
                when {
                    // Non-capturing (?:...) or atomic (?>...)
                    s[pos] == ':' || s[pos] == '>' -> {
                        pos++
                        return finishGroup(parseAlternation())
                    }
                    // Lookahead (?=...) / (?!...)
                    s[pos] == '=' || s[pos] == '!' -> {
                        skipBalancedParens()
                        return Fragment.ANY
                    }
                    // Lookbehind (?<=...) / (?<!...) or named group (?<name>...)
                    s[pos] == '<' -> {
                        pos++
                        if (pos < s.length && (s[pos] == '=' || s[pos] == '!')) {
                            skipBalancedParens()
                            return Fragment.ANY
                        }
                        while (pos < s.length && s[pos] != '>') pos++
                        if (pos < s.length) pos++ // consume '>'
                        return finishGroup(parseAlternation())
                    }
                    // Inline flags (?i) or (?i:...) or (?-i:...)
                    else -> {
                        val flagStart: Int = pos
                        while (pos < s.length && s[pos] != ':' && s[pos] != ')') pos++
                        val flags: String = s.substring(flagStart, pos)
                        if ('i' in flags) sawCaseFlag = true
                        if ('x' in flags) sawCommentsFlag = true
                        if (pos < s.length && s[pos] == ':') {
                            pos++
                            return finishGroup(parseAlternation())
                        }
                        if (pos < s.length && s[pos] == ')') pos++
                        return Fragment.EMPTY
                    }
                }
            }
            return finishGroup(parseAlternation())
        }

        private fun finishGroup(inner: Fragment): Fragment {
            if (pos < s.length && s[pos] == ')') pos++
            return inner
        }

        private fun skipBalancedParens() {
            var depth = 1
            while (pos < s.length && depth > 0) {
                when (s[pos]) {
                    '\\' -> pos = (pos + 2).coerceAtMost(s.length)
                    '[' -> skipCharClass()
                    '(' -> {
                        depth++
                        pos++
                    }
                    ')' -> {
                        depth--
                        pos++
                    }
                    else -> pos++
                }
            }
        }

        private fun skipCharClass() {
            pos++ // consume '['
            if (pos < s.length && s[pos] == '^') pos++
            if (pos < s.length && s[pos] == ']') pos++
            var depth = 1
            while (pos < s.length && depth > 0) {
                when (s[pos]) {
                    '\\' -> pos = (pos + 2).coerceAtMost(s.length)
                    '[' -> {
                        depth++
                        pos++
                    }
                    ']' -> {
                        depth--
                        pos++
                    }
                    else -> pos++
                }
            }
        }

        private fun parseEscape(): Fragment {
            pos++ // consume '\'
            if (pos >= s.length) return Fragment.ANY
            val c: Char = s[pos++]
            return when (c) {
                'Q' -> {
                    val end: Int = s.indexOf("\\E", pos)
                    val lit: String = if (end >= 0) {
                        val sub = s.substring(pos, end)
                        pos = end + 2
                        sub
                    } else {
                        val sub = s.substring(pos)
                        pos = s.length
                        sub
                    }
                    if ('\n' in lit || '\r' in lit) Fragment.ANY else Fragment.literal(lit)
                }
                't' -> Fragment.literal("\t")
                'n', 'r', 'f', 'a', 'e' -> Fragment.ANY
                'd', 'D', 'w', 'W', 's', 'S', 'h', 'H', 'v', 'V',
                'b', 'B', 'A', 'G', 'Z', 'z', 'R', 'X',
                -> Fragment.ANY
                'p', 'P' -> {
                    if (pos < s.length && s[pos] == '{') {
                        val close: Int = s.indexOf('}', pos)
                        pos = if (close >= 0) close + 1 else s.length
                    } else if (pos < s.length) {
                        pos++
                    }
                    Fragment.ANY
                }
                'k' -> {
                    if (pos < s.length && s[pos] == '<') {
                        val close: Int = s.indexOf('>', pos)
                        pos = if (close >= 0) close + 1 else s.length
                    }
                    Fragment.ANY
                }
                'x' -> parseHexEscape(2)
                'u' -> parseHexEscape(4)
                // Octal `\0nnn`, or a backreference whose group number can run several digits.
                in '0'..'9' -> {
                    while (pos < s.length && s[pos].isDigit()) pos++
                    Fragment.ANY
                }
                'c' -> {
                    if (pos < s.length) pos++
                    Fragment.ANY
                }
                'N' -> {
                    if (pos < s.length && s[pos] == '{') {
                        val close: Int = s.indexOf('}', pos)
                        pos = if (close >= 0) close + 1 else s.length
                    }
                    Fragment.ANY
                }
                else -> {
                    if (!c.isLetterOrDigit()) Fragment.literal(c.toString()) else Fragment.ANY
                }
            }
        }

        private fun parseHexEscape(digits: Int): Fragment {
            if (digits == 2 && pos < s.length && s[pos] == '{') {
                val close: Int = s.indexOf('}', pos)
                pos = if (close >= 0) close + 1 else s.length
                return Fragment.ANY
            }
            if (pos + digits > s.length) return Fragment.ANY
            val code: Int = s.substring(pos, pos + digits).toIntOrNull(16) ?: return Fragment.ANY
            pos += digits
            val ch: Char = code.toChar()
            return if (ch == '\n' || ch == '\r') Fragment.ANY else Fragment.literal(ch.toString())
        }

        private fun applyQuantifier(atom: Fragment): Fragment {
            if (pos >= s.length) return atom
            return when (s[pos]) {
                '*', '?' -> {
                    pos++
                    consumeQuantifierModifier()
                    Fragment.ANY
                }
                '+' -> {
                    pos++
                    consumeQuantifierModifier()
                    repeatOneOrMore(atom)
                }
                '{' -> parseBraceQuantifier(atom)
                else -> atom
            }
        }

        private fun parseBraceQuantifier(atom: Fragment): Fragment {
            val saved: Int = pos
            pos++ // consume '{'
            val minStart: Int = pos
            while (pos < s.length && s[pos].isDigit()) pos++
            if (pos == minStart || pos >= s.length) {
                pos = saved
                return atom
            }
            val min: Int = s.substring(minStart, pos).toIntOrNull() ?: run {
                pos = saved
                return atom
            }
            val exactCount: Boolean
            if (s[pos] == '}') {
                pos++
                exactCount = true
            } else if (s[pos] == ',') {
                pos++
                while (pos < s.length && s[pos].isDigit()) pos++
                if (pos >= s.length || s[pos] != '}') {
                    pos = saved
                    return atom
                }
                pos++
                exactCount = false
            } else {
                pos = saved
                return atom
            }
            consumeQuantifierModifier()
            return when {
                min == 0 -> Fragment.ANY
                exactCount && min == 1 -> atom
                else -> repeatOneOrMore(atom)
            }
        }

        private fun consumeQuantifierModifier() {
            if (pos < s.length && (s[pos] == '?' || s[pos] == '+')) pos++
        }
    }
}
