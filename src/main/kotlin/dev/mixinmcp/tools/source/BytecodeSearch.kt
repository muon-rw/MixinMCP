package dev.mixinmcp.tools.source

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

private const val MAX_CLASS_BYTES: Long = 16L * 1024 * 1024
private const val MAX_NESTED_JAR_BYTES: Long = 256L * 1024 * 1024
private const val MAX_NESTED_DEPTH: Int = 3
private const val MAX_CONSTANT_CHARS: Int = 240

/** Groups for the `kinds` filter: what role a matching constant plays in its class. */
internal enum class MatchCategory(val key: String) {
    DECLARES("declares"),
    REFS("refs"),
    TYPES("types"),
    STRINGS("strings"),
    OTHER("other");

    companion object {
        fun fromKey(key: String): MatchCategory? = entries.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }
    }
}

internal data class ConstantMatch(val kind: String, val text: String) {
    val category: MatchCategory
        get() = when (kind) {
            "declares field", "declares method" -> MatchCategory.DECLARES
            "field ref", "method ref", "interface method ref" -> MatchCategory.REFS
            "class ref", "extends", "implements" -> MatchCategory.TYPES
            "string" -> MatchCategory.STRINGS
            else -> MatchCategory.OTHER
        }
}

internal data class ClassHit(val container: String, val className: String, val matches: List<ConstantMatch>)

internal class BytecodeScanStats {
    var classesScanned: Int = 0
    var nestedJarsScanned: Int = 0
    val unreadable: MutableList<String> = mutableListOf()
}

/**
 * Matches constant-pool strings against literal substrings or Java regexes. Literal needles also
 * match slash-normalized class names (`a.b.C` -> `a/b/C`) and compound `Owner.member` / `Owner#member` /
 * `Lowner;member` forms against synthesized member refs and `@At` target strings. ASCII tokens
 * prefilter raw class bytes before parsing.
 */
internal class ConstantMatcher(needles: List<String>, isRegex: Boolean) {
    private val patterns: List<Pattern>? = if (isRegex) needles.map { Pattern.compile(it) } else null
    private val literals: List<String> = if (isRegex) emptyList() else needles.flatMap(::expandDirectLiterals).distinct()
    private val compoundLiterals: List<String> = if (isRegex) emptyList() else needles.flatMap(::expandCompoundLiterals).distinct()
    val hasCompound: Boolean = if (isRegex) needles.any { '.' in it || '#' in it || ';' in it } else compoundLiterals.isNotEmpty()
    private val asciiTokenGroups: List<List<ByteArray>>? =
        if (!isRegex && needles.all { n -> n.all { it.code in 1..127 } }) {
            needles.map { n ->
                val tokens: List<String> = n.removePrefix("L").split(TOKEN_SPLIT_REGEX).filter { it.isNotEmpty() }
                    .distinct()
                    .sortedByDescending { it.length }
                (tokens.ifEmpty { listOf(n) }).map { it.toByteArray(StandardCharsets.US_ASCII) }
            }
        } else {
            null
        }

    fun matches(value: String): Boolean =
        patterns?.any { it.matcher(value).find() } ?: literals.any { value.contains(it) }

    fun matchesCompound(value: String): Boolean =
        patterns?.any { it.matcher(value).find() } ?: compoundLiterals.any { value.contains(it) }

    fun mightMatch(classBytes: ByteArray): Boolean =
        asciiTokenGroups?.any { group -> group.all { token -> indexOf(classBytes, token) >= 0 } } ?: true

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty()) return 0
        val first: Byte = needle[0]
        val last: Int = haystack.size - needle.size
        var i = 0
        while (i <= last) {
            if (haystack[i] == first) {
                var j = 1
                while (j < needle.size && haystack[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }

    companion object {
        private val TOKEN_SPLIT_REGEX = Regex("[.#;/:()\\s]+")

        private fun expandDirectLiterals(n: String): List<String> {
            val out = LinkedHashSet<String>()
            out.add(n)
            out.add(n.replace('.', '/'))
            splitCompoundNeedle(n)?.let { (owner, rest) ->
                out.add("$owner;${rest.replace(" (", "(")}")
            }
            return out.toList()
        }

        private fun expandCompoundLiterals(n: String): List<String> {
            val (owner: String, rest: String) = splitCompoundNeedle(n) ?: return emptyList()
            val out = LinkedHashSet<String>()
            out.add("$owner.$rest")
            if ("(" in rest && " (" !in rest) {
                out.add("$owner.${rest.replaceFirst("(", " (")}")
            }
            return out.toList()
        }

        private fun splitCompoundNeedle(n: String): Pair<String, String>? {
            val headEnd: Int = n.indexOfAny(charArrayOf('(', ':')).let { if (it < 0) n.length else it }
            val sep: Int = n.substring(0, headEnd).lastIndexOfAny(charArrayOf('.', '#', ';'))
            if (sep <= 0 || sep >= headEnd - 1) return null
            val owner: String = n.substring(0, sep).removePrefix("L").replace('.', '/')
            val rest: String = n.substring(sep + 1)
            if (owner.isEmpty() || rest.isEmpty()) return null
            return owner to rest
        }
    }
}

/**
 * A class file's constant pool, read eagerly, plus its structure (superclass, interfaces,
 * fields, methods, and attribute offsets), parsed on first use. Indexes are constant-pool indexes.
 */
internal class ClassFileView private constructor(
    val bytes: ByteArray,
    val count: Int,
    private val tags: IntArray,
    private val offsets: IntArray,
    private val structureOffset: Int,
) {
    class Attribute(val nameIndex: Int, val offset: Int, val length: Int)

    class Member(val nameIndex: Int, val descriptorIndex: Int, val attributes: List<Attribute>)

    class Structure(
        val superIndex: Int,
        val interfaceIndexes: List<Int>,
        val fields: List<Member>,
        val methods: List<Member>,
        val attributes: List<Attribute>,
    )

    private val decoded = arrayOfNulls<String>(count)

    fun tag(i: Int): Int = if (i in 1 until count) tags[i] else 0

    fun offset(i: Int): Int = offsets[i]

    fun isUtf8(i: Int): Boolean = tag(i) == 1

    fun utf8(i: Int): String = decoded[i] ?: run {
        val length: Int = u2(bytes, offsets[i])
        DataInputStream(ByteArrayInputStream(bytes, offsets[i], length + 2)).readUTF().also { decoded[i] = it }
    }

    /** UTF-8 index of the name of the Class constant at [classIndex], or 0. */
    fun classNameIndex(classIndex: Int): Int =
        if (tag(classIndex) == 7) u2(bytes, offsets[classIndex]).takeIf(::isUtf8) ?: 0 else 0

    fun classNameAt(classIndex: Int): String? = classNameIndex(classIndex).takeIf { it != 0 }?.let(::utf8)

    val className: String? get() = classNameAt(u2(bytes, structureOffset + 2))

    /** Null when the bytes after the constant pool are malformed. */
    val structure: Structure? by lazy { runCatching { parseStructure() }.getOrNull() }

    private fun parseStructure(): Structure {
        var p: Int = structureOffset
        fun need(n: Int) = check(p + n <= bytes.size)
        fun readAttributes(): List<Attribute> {
            need(2)
            val n: Int = u2(bytes, p)
            p += 2
            return List(n) {
                need(6)
                val nameIndex: Int = u2(bytes, p)
                val length: Int = u4(bytes, p + 2)
                check(length >= 0)
                p += 6
                need(length)
                Attribute(nameIndex, p, length).also { p += length }
            }
        }
        fun readMembers(): List<Member> {
            need(2)
            val n: Int = u2(bytes, p)
            p += 2
            return List(n) {
                need(8)
                val nameIndex: Int = u2(bytes, p + 2)
                val descriptorIndex: Int = u2(bytes, p + 4)
                p += 6
                Member(nameIndex, descriptorIndex, readAttributes())
            }
        }
        need(8)
        val superIndex: Int = u2(bytes, p + 4)
        val interfaceCount: Int = u2(bytes, p + 6)
        p += 8
        need(2 * interfaceCount)
        val interfaces: List<Int> = List(interfaceCount) { u2(bytes, p + 2 * it) }
        p += 2 * interfaceCount
        val fields: List<Member> = readMembers()
        val methods: List<Member> = readMembers()
        return Structure(superIndex, interfaces, fields, methods, readAttributes())
    }

    companion object {
        fun parse(bytes: ByteArray): ClassFileView? {
            if (bytes.size < 10 || u4(bytes, 0) != 0xCAFEBABE.toInt()) return null
            val count: Int = u2(bytes, 8)
            val tags = IntArray(count)
            val offsets = IntArray(count)
            var pos = 10
            var index = 1
            while (index < count) {
                if (pos + 3 > bytes.size) return null
                val tag: Int = bytes[pos].toInt() and 0xFF
                tags[index] = tag
                offsets[index] = pos + 1
                pos += 1 + when (tag) {
                    1 -> 2 + u2(bytes, pos + 1)
                    3, 4 -> 4
                    5, 6 -> 8
                    7, 8, 16, 19, 20 -> 2
                    9, 10, 11, 12, 17, 18 -> 4
                    15 -> 3
                    else -> return null
                }
                index += if (tag == 5 || tag == 6) 2 else 1
            }
            if (pos + 8 > bytes.size) return null
            return ClassFileView(bytes, count, tags, offsets, pos)
        }
    }
}

/**
 * Parses [bytes] as a class file and reports what [matcher] accepts, by role: declared fields
 * and methods (name or descriptor matched), the superclass and interfaces, field and method refs
 * (owner, name, or descriptor matched), other class refs, string literals, and any other matching
 * UTF-8 constant (signatures, annotation values such as mixin targets). Returns null for bytes
 * that are not a class file, and a hit with no matches when nothing matched.
 */
internal fun scanClassConstants(bytes: ByteArray, container: String, matcher: ConstantMatcher): ClassHit? {
    val view: ClassFileView = ClassFileView.parse(bytes) ?: return null
    val className: String = view.className ?: return null
    val count: Int = view.count
    fun utf8(i: Int): String = view.utf8(i)
    fun isUtf8(i: Int): Boolean = view.isUtf8(i)

    val matched = BooleanArray(count)
    var anyMatched = false
    for (i in 1 until count) {
        if (isUtf8(i) && matcher.matches(utf8(i))) {
            matched[i] = true
            anyMatched = true
        }
    }
    if (!anyMatched && (!matcher.hasCompound || !matcher.mightMatch(bytes))) {
        return ClassHit(container, className, emptyList())
    }

    val reported = BooleanArray(count)
    val out = LinkedHashSet<ConstantMatch>()
    view.structure?.let { structure ->
        for ((members, kind, separator) in listOf(
            Triple(structure.fields, "declares field", ":"),
            Triple(structure.methods, "declares method", " "),
        )) {
            for (member in members) {
                if (!isUtf8(member.nameIndex) || !isUtf8(member.descriptorIndex)) continue
                val memberText = "${utf8(member.nameIndex)}$separator${utf8(member.descriptorIndex)}"
                val directMatch: Boolean = matched[member.nameIndex] || matched[member.descriptorIndex]
                if (!directMatch && !matcher.matchesCompound("$className.$memberText")) continue
                out.add(ConstantMatch(kind, memberText))
                reported[member.nameIndex] = true
                reported[member.descriptorIndex] = true
            }
        }
        for ((classIndex, kind) in listOf(structure.superIndex to "extends") + structure.interfaceIndexes.map { it to "implements" }) {
            val nameIndex: Int = view.classNameIndex(classIndex)
            if (nameIndex == 0 || !matched[nameIndex]) continue
            out.add(ConstantMatch(kind, utf8(nameIndex)))
            reported[nameIndex] = true
        }
    }
    val bytes: ByteArray = view.bytes
    for (i in 1 until count) {
        val kind: String = when (view.tag(i)) {
            9 -> "field ref"
            10 -> "method ref"
            11 -> "interface method ref"
            else -> continue
        }
        val ownerUtf8: Int = view.classNameIndex(u2(bytes, view.offset(i)))
        val nameAndType: Int = u2(bytes, view.offset(i) + 2)
        if (ownerUtf8 == 0 || view.tag(nameAndType) != 12) continue
        val nameUtf8: Int = u2(bytes, view.offset(nameAndType))
        val descUtf8: Int = u2(bytes, view.offset(nameAndType) + 2)
        if (!isUtf8(nameUtf8) || !isUtf8(descUtf8)) continue
        val separator: String = if (view.tag(i) == 9) ":" else " "
        val refText = "${utf8(ownerUtf8)}.${utf8(nameUtf8)}$separator${utf8(descUtf8)}"
        val directMatch: Boolean = matched[ownerUtf8] || matched[nameUtf8] || matched[descUtf8]
        if (!directMatch && !matcher.matchesCompound(refText)) continue
        out.add(ConstantMatch(kind, refText))
        reported[ownerUtf8] = true
        reported[nameUtf8] = true
        reported[descUtf8] = true
    }
    for (i in 1 until count) {
        if (view.tag(i) != 7 && view.tag(i) != 8) continue
        val target: Int = u2(bytes, view.offset(i))
        if (!isUtf8(target) || !matched[target] || reported[target]) continue
        out.add(
            if (view.tag(i) == 7) ConstantMatch("class ref", utf8(target))
            else ConstantMatch("string", "\"${utf8(target).take(MAX_CONSTANT_CHARS)}\""),
        )
        reported[target] = true
    }
    for (i in 1 until count) {
        if (matched[i] && !reported[i]) out.add(ConstantMatch("utf8", "\"${utf8(i).take(MAX_CONSTANT_CHARS)}\""))
    }
    return ClassHit(container, className, out.toList())
}

/**
 * Scans every class in [jar] with [matcher], recursing into nested jars like [walkJarClasses],
 * and passes each class with at least one match to [onHit].
 */
internal fun scanJarBytecode(
    jar: File,
    prefix: String?,
    matcher: ConstantMatcher,
    stats: BytecodeScanStats,
    shouldStop: () -> Boolean,
    onHit: (ClassHit) -> Unit,
) {
    walkJarClasses(jar, prefix, stats, shouldStop) { container, bytes ->
        if (!matcher.mightMatch(bytes)) return@walkJarClasses
        val hit: ClassHit? = try {
            scanClassConstants(bytes, container, matcher)
        } catch (_: Exception) {
            null
        }
        if (hit != null && hit.matches.isNotEmpty()) onHit(hit)
    }
}

/**
 * Passes the bytes of every class in [jar] to [visitClass] with its container label, recursing
 * into jars nested inside it (jar-in-jar) up to three levels. Nested containers are labeled
 * `outer.jar !/path/inner.jar`. Walking stops when [shouldStop] returns true.
 */
internal fun walkJarClasses(
    jar: File,
    prefix: String?,
    stats: BytecodeScanStats,
    shouldStop: () -> Boolean,
    visitClass: (container: String, bytes: ByteArray) -> Unit,
) {
    ZipFile(jar).use { zip ->
        for (entry in zip.entries().asSequence().sortedBy { it.name }) {
            if (shouldStop()) return
            if (entry.isDirectory) continue
            val keepGoing: Boolean = visitEntry(entry.name, entry.size, jar.name, 0, prefix, stats, shouldStop, visitClass) {
                zip.getInputStream(entry)
            }
            if (!keepGoing) return
        }
    }
}

/** Returns false when walking should stop. */
private fun visitEntry(
    name: String,
    size: Long,
    container: String,
    depth: Int,
    prefix: String?,
    stats: BytecodeScanStats,
    shouldStop: () -> Boolean,
    visitClass: (container: String, bytes: ByteArray) -> Unit,
    open: () -> InputStream,
): Boolean {
    when {
        name.endsWith(".class") -> {
            if (prefix != null && !name.startsWith(prefix, ignoreCase = true)) return true
            if (size > MAX_CLASS_BYTES) return true
            val bytes: ByteArray = open().use { readBounded(it, MAX_CLASS_BYTES) } ?: return true
            stats.classesScanned++
            visitClass(container, bytes)
        }
        name.endsWith(".jar", ignoreCase = true) && depth < MAX_NESTED_DEPTH -> {
            if (size > MAX_NESTED_JAR_BYTES) return true
            val nestedLabel = "$container !/$name"
            val bytes: ByteArray = open().use { readBounded(it, MAX_NESTED_JAR_BYTES) } ?: run {
                stats.unreadable.add("$nestedLabel (larger than ${MAX_NESTED_JAR_BYTES / 1024 / 1024} MB)")
                return true
            }
            stats.nestedJarsScanned++
            try {
                ZipInputStream(ByteArrayInputStream(bytes)).use { nested ->
                    while (true) {
                        if (shouldStop()) return false
                        val entry = nested.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val keepGoing: Boolean = visitEntry(
                            entry.name, entry.size, nestedLabel, depth + 1, prefix, stats, shouldStop, visitClass,
                        ) { NonClosingInputStream(nested) }
                        if (!keepGoing) return false
                    }
                }
            } catch (e: Exception) {
                stats.unreadable.add("$nestedLabel (${e.message})")
            }
        }
    }
    return true
}

/**
 * Reads [stream] fully, or returns null once it passes [limit] bytes. Entries inside a nested
 * jar often report size -1 (sizes kept in a data descriptor), so the size check before reading
 * cannot bound them.
 */
private fun readBounded(stream: InputStream, limit: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read: Int = stream.read(buffer)
        if (read < 0) return out.toByteArray()
        total += read
        if (total > limit) return null
        out.write(buffer, 0, read)
    }
}

/** Lets a nested entry be read with `use {}` without closing the enclosing ZipInputStream. */
private class NonClosingInputStream(private val delegate: InputStream) : InputStream() {
    override fun read(): Int = delegate.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    override fun close() {}
}

internal fun u2(bytes: ByteArray, at: Int): Int =
    ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

internal fun u4(bytes: ByteArray, at: Int): Int =
    (u2(bytes, at) shl 16) or u2(bytes, at + 2)
