package dev.mixinmcp.tools.source

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue.FALSE
import com.intellij.mcpserver.annotations.McpToolHintValue.TRUE
import com.intellij.mcpserver.annotations.McpToolHints
import dev.mixinmcp.tools.requireProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

internal data class JarEntry(val name: String, val size: Long)

private const val MAX_JARS_PER_CALL: Int = 10
private const val BYTECODE_SCAN_PARALLELISM: Int = 4
private const val MAX_MATCHES_PER_CLASS: Int = 8
private const val MAX_GREP_ENTRY_BYTES: Long = 4L * 1024 * 1024
private const val MAX_GREP_LINE_CHARS: Int = 200

@Suppress("FunctionName") // @McpTool functions are snake_case by MCP convention
class JarEntriesToolset : McpToolset {

    override fun isExperimental(): Boolean = false

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription(
        "Lists or greps the entries inside jars: resources such as META-INF/mods.toml or neoforge.mods.toml, " +
            "fabric.mod.json, assets/<modid>/lang/*.json, models, textures, data/<modid>/recipes and loot tables, " +
            "*.mixins.json, accesstransformer.cfg, and (with includeClasses=true) .class files. jarPath: any jar on " +
            "disk, on the classpath or not (a mod sitting in a modpack folder), or a directory of jars such as a " +
            "pack's mods folder (every *.jar directly inside it); relative paths resolve against the project " +
            "directory and backslashes are fine. jar: instead of a path, a case-insensitive substring of a classpath " +
            "jar's file name or Maven coordinates (e.g. 'jade' or 'snownee:jade'); every matching classpath or " +
            "decompiled-cache jar is used, up to 10. This is the way to search runtime-only jars, which " +
            "mixin_search_in_deps cannot see. pathPrefix filters entries by prefix (e.g. data/minecraft/recipes/), " +
            "fileMask by case-insensitive substring or glob (e.g. '*.toml', 'lang', '*/en_us.json'). regexPattern: " +
            "instead of listing entries, grep the text entries that pass those filters and print each matching " +
            "line as entry:line: text; jars without a match are omitted. Example: which mods in a pack depend on " +
            "Fzzy Config is jarPath='<pack>/mods', fileMask='mods.toml', regexPattern='(?i)fzzy'. Java regex, " +
            "case-sensitive unless it starts with (?i). maxResults (default 200) caps listed entries or matching " +
            "lines across all jars. Read an entry with mixin_get_dep_source(jarPath=..., entry=...) or " +
            "url=jar://<jar path>!/<entry>.",
    )
    @Suppress("unused")
    suspend fun mixin_list_jar_entries(
        jarPath: String? = null,
        jar: String? = null,
        pathPrefix: String? = null,
        fileMask: String? = null,
        includeClasses: Boolean = false,
        regexPattern: String? = null,
        maxResults: Int = 200,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val explicitPath: String? = jarPath?.trim()?.takeIf { it.isNotEmpty() }
        val nameFilter: String? = jar?.trim()?.takeIf { it.isNotEmpty() }
        if (explicitPath == null && nameFilter == null) {
            return McpToolCallResult.error(
                "Pass `jarPath` (a jar or a directory of jars on disk) or `jar` (a substring of a classpath jar's " +
                    "file name or coordinates).",
            )
        }
        if (explicitPath != null && nameFilter != null) {
            return McpToolCallResult.error("Pass either `jarPath` or `jar`, not both.")
        }
        if (maxResults < 1 || maxResults > 5000) {
            return McpToolCallResult.error("maxResults must be between 1 and 5000 (got $maxResults)")
        }
        val contentPattern: Pattern? = try {
            regexPattern?.takeIf { it.isNotEmpty() }?.let { Pattern.compile(it) }
        } catch (e: PatternSyntaxException) {
            return McpToolCallResult.error("Invalid regexPattern: ${e.message}")
        }

        val candidates: List<JarCandidate> = when (val resolved = resolveCandidates(project, explicitPath, nameFilter, MAX_JARS_PER_CALL)) {
            is CandidateResolution.Found -> resolved.jars
            is CandidateResolution.Failed -> return resolved.error
        }

        val normalizedPrefix: String? = pathPrefix?.trim()?.replace('\\', '/')?.removePrefix("/")?.takeIf { it.isNotEmpty() }
        val matchesMask: (String) -> Boolean = buildFileMaskMatcher(fileMask)

        val text: String = withContext(Dispatchers.IO) {
            if (contentPattern != null) {
                renderGrep(candidates, normalizedPrefix, matchesMask, contentPattern, regexPattern.orEmpty(), maxResults)
            } else {
                renderListing(candidates, normalizedPrefix, matchesMask, includeClasses, maxResults)
            }
        }
        return McpToolCallResult.text(text)
    }

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription(
        "Searches compiled classes for constant-pool strings: the member names, owner classes, descriptors, string " +
            "literals, and annotation values (mixin targets, @At strings) a class references or declares. Works on " +
            "jars without source and on jars that are on no classpath, and recurses into jar-in-jar (META-INF/jarjar, " +
            "META-INF/jars) up to three levels. Use it to find every mod in a pack that touches a vanilla member, e.g. " +
            "patterns=['actuallyHurt'] or patterns=['AttributeInstance.getValue'], jarPath='<pack>/mods'. patterns: " +
            "literal substrings (dotted class names and Owner.member / Owner#member / Lowner;member forms are also " +
            "normalized to internal bytecode names), or Java regexes with isRegex=true, matched against each constant " +
            "and member ref (a class matches when any pattern does). " +
            "jarPath: a jar or a directory of jars (every *.jar directly inside, such as a pack's mods folder); " +
            "relative paths resolve against the project directory. jar: a case-insensitive substring of classpath jar " +
            "file names or coordinates. With neither, every classpath and decompiled-cache jar is searched. pathPrefix " +
            "limits classes by internal-name prefix (e.g. net/minecraft/world/). Each hit is listed per class by role: " +
            "declares field / declares method (the class's own member, e.g. an override or a mixin handler), extends / " +
            "implements, field ref / method ref (Owner.name descriptor), class ref, string, or utf8 (any other constant, " +
            "such as an annotation value). kinds narrows hits to roles: any of 'declares', 'refs' (field and method " +
            "refs), 'types' (extends, implements, class refs), 'strings', 'other'; e.g. kinds=['declares'] finds the " +
            "classes that declare or override a member rather than everything that calls it. maxResults " +
            "(default 200) caps matching classes; timeout (ms, default 60000) stops the scan and reports which jars " +
            "were not reached. Read a hit with mixin_get_dep_source(jarPath=..., entry='<class>.class') or " +
            "mixin_class_bytecode. Those tools cannot address a jar nested inside another; mixin_get_dep_source" +
            "(className=...) still reads such a class when another copy of its jar is on the classpath, and names " +
            "that jar in its header, which may be a different version.",
    )
    @Suppress("unused")
    suspend fun mixin_search_bytecode(
        patterns: List<String>,
        jarPath: String? = null,
        jar: String? = null,
        isRegex: Boolean = false,
        kinds: List<String>? = null,
        pathPrefix: String? = null,
        maxResults: Int = 200,
        timeout: Long = 60_000,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val needles: List<String> = patterns.filter { it.isNotEmpty() }
        if (needles.isEmpty()) return McpToolCallResult.error("Pass at least one non-empty entry in `patterns`.")
        val allowedCategories: Set<MatchCategory>? = kinds?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { requested ->
            val unknown: List<String> = requested.filter { MatchCategory.fromKey(it) == null }
            if (unknown.isNotEmpty()) {
                return McpToolCallResult.error(
                    "Unknown kinds: ${unknown.joinToString()}. Use any of: ${MatchCategory.entries.joinToString { it.key }}.",
                )
            }
            requested.mapNotNull(MatchCategory::fromKey).toSet()
        }
        if (maxResults < 1 || maxResults > 5000) {
            return McpToolCallResult.error("maxResults must be between 1 and 5000 (got $maxResults)")
        }
        if (timeout < 1000 || timeout > 600_000) {
            return McpToolCallResult.error("timeout must be between 1000 and 600000 ms (got $timeout)")
        }
        val explicitPath: String? = jarPath?.trim()?.takeIf { it.isNotEmpty() }
        val nameFilter: String? = jar?.trim()?.takeIf { it.isNotEmpty() }
        if (explicitPath != null && nameFilter != null) {
            return McpToolCallResult.error("Pass either `jarPath` or `jar`, not both.")
        }
        val matcher: ConstantMatcher = try {
            ConstantMatcher(needles, isRegex)
        } catch (e: PatternSyntaxException) {
            return McpToolCallResult.error("Invalid regex in patterns: ${e.message}")
        }
        val candidates: List<JarCandidate> = when (val resolved = resolveCandidates(project, explicitPath, nameFilter, null)) {
            is CandidateResolution.Found -> resolved.jars
            is CandidateResolution.Failed -> return resolved.error
        }
        val normalizedPrefix: String? = pathPrefix?.trim()?.replace('\\', '/')?.replace('.', '/')
            ?.removePrefix("/")?.takeIf { it.isNotEmpty() }

        val startedAt: Long = System.currentTimeMillis()
        val deadline: Long = startedAt + timeout
        val classHits = AtomicInteger(0)
        // The scanner is blocking code: it polls this instead of throwing, so a cancelled MCP call
        // stops within one class and no CancellationException is caught as an "unreadable" jar.
        val callJob: Job = currentCoroutineContext().job
        fun shouldStop(): Boolean =
            !callJob.isActive || classHits.get() >= maxResults || System.currentTimeMillis() > deadline

        val results: List<JarScanResult> = withContext(Dispatchers.IO.limitedParallelism(BYTECODE_SCAN_PARALLELISM)) {
            candidates.map { candidate ->
                async {
                    val stats = BytecodeScanStats()
                    if (shouldStop()) return@async JarScanResult(candidate, emptyList(), stats, scanned = false, error = null)
                    val hits: MutableList<ClassHit> = mutableListOf()
                    val error: String? = try {
                        scanJarBytecode(candidate.file, normalizedPrefix, matcher, stats, ::shouldStop) { hit ->
                            val kept: ClassHit = if (allowedCategories == null) hit else {
                                hit.copy(matches = hit.matches.filter { it.category in allowedCategories })
                            }
                            if (kept.matches.isNotEmpty() && classHits.incrementAndGet() <= maxResults) hits.add(kept)
                        }
                        null
                    } catch (e: Exception) {
                        e.message ?: e.toString()
                    }
                    JarScanResult(candidate, hits, stats, scanned = true, error = error)
                }
            }.awaitAll()
        }
        callJob.ensureActive()
        return McpToolCallResult.text(
            renderBytecodeResults(results, needles, maxResults, timedOut = System.currentTimeMillis() > deadline, startedAt),
        )
    }

    private class JarScanResult(
        val candidate: JarCandidate,
        val hits: List<ClassHit>,
        val stats: BytecodeScanStats,
        val scanned: Boolean,
        val error: String?,
    )

    private fun renderBytecodeResults(
        results: List<JarScanResult>,
        needles: List<String>,
        maxResults: Int,
        timedOut: Boolean,
        startedAt: Long,
    ): String = buildString {
        var shownClasses = 0
        val jarsWithHits: MutableSet<String> = mutableSetOf()
        for (result in results) {
            result.error?.let { appendLine("=== ${result.candidate.file.name} [${result.candidate.label}] (unreadable: $it) ===") }
            for ((container, hits) in result.hits.groupBy { it.container }) {
                if (shownClasses >= maxResults) break
                jarsWithHits.add(result.candidate.file.absolutePath)
                val heading: String = if (container == result.candidate.file.name) {
                    "${result.candidate.file.name} (${result.candidate.file.absolutePath}) [${result.candidate.label}]"
                } else {
                    "$container [nested, ${result.candidate.label}]"
                }
                appendLine("=== $heading ===")
                for (hit in hits.sortedBy { it.className }) {
                    if (shownClasses >= maxResults) break
                    shownClasses++
                    appendLine("  ${hit.className}")
                    for (match in hit.matches.take(MAX_MATCHES_PER_CLASS)) {
                        appendLine("    ${match.kind} ${match.text}")
                    }
                    if (hit.matches.size > MAX_MATCHES_PER_CLASS) {
                        appendLine("    ... and ${hit.matches.size - MAX_MATCHES_PER_CLASS} more")
                    }
                }
                appendLine()
            }
        }
        val scanned: List<JarScanResult> = results.filter { it.scanned }
        val classes: Int = scanned.sumOf { it.stats.classesScanned }
        val nested: Int = scanned.sumOf { it.stats.nestedJarsScanned }
        val elapsedS: String = "%.1f".format((System.currentTimeMillis() - startedAt) / 1000.0)
        append("Patterns ${needles.joinToString(", ") { "\"$it\"" }} matched $shownClasses class(es) in ")
        append("${jarsWithHits.size} of ${scanned.size} jar(s) scanned ($classes classes, $nested nested jars, ${elapsedS}s)")
        val notReached: List<JarScanResult> = results.filter { !it.scanned }
        when {
            shownClasses >= maxResults -> append("; maxResults reached, so later jars may be incomplete or unscanned")
            timedOut -> append("; timeout reached")
        }
        if (notReached.isNotEmpty()) {
            append(". Not scanned: ${notReached.take(10).joinToString(", ") { it.candidate.file.name }}")
            if (notReached.size > 10) append(" and ${notReached.size - 10} more")
        }
        appendLine(".")
        val unreadable: List<String> = scanned.flatMap { it.stats.unreadable }
        if (unreadable.isNotEmpty()) appendLine("Unreadable nested jars: ${unreadable.take(5).joinToString("; ")}")
    }

    private fun renderListing(
        candidates: List<JarCandidate>,
        prefix: String?,
        matchesMask: (String) -> Boolean,
        includeClasses: Boolean,
        maxResults: Int,
    ): String = buildString {
        var remaining: Int = maxResults
        for (candidate in candidates) {
            val entries: List<JarEntry> = try {
                listEntries(candidate.file, prefix, matchesMask, includeClasses)
            } catch (e: Exception) {
                appendLine("=== ${candidate.file.name} [${candidate.label}] ===")
                appendLine("  (unreadable: ${e.message})")
                appendLine()
                continue
            }
            if (entries.isEmpty() && candidates.size > 1) continue
            appendLine("=== ${candidate.file.name} (${candidate.file.absolutePath}) [${candidate.label}] ===")
            if (entries.isEmpty()) {
                appendLine("  (no entries match" + (if (includeClasses) ")" else "; .class entries are hidden unless includeClasses=true)"))
                appendLine()
                continue
            }
            for (entry in entries.take(remaining)) {
                appendLine("  ${entry.name} (${entry.size} bytes)")
            }
            if (entries.size > remaining) {
                appendLine("  ... and ${entries.size - remaining} more (raise maxResults or narrow with pathPrefix/fileMask)")
            }
            remaining = (remaining - entries.size).coerceAtLeast(0)
            appendLine()
            if (remaining == 0 && candidates.size > 1) {
                appendLine("(maxResults reached; remaining jars not listed)")
                break
            }
        }
        if (isEmpty()) appendLine("No entry in the ${candidates.size} jars matches the filters.\n")
        append("Read an entry with mixin_get_dep_source(jarPath=\"<jar path>\", entry=\"<entry>\").")
    }

    private fun renderGrep(
        candidates: List<JarCandidate>,
        prefix: String?,
        matchesMask: (String) -> Boolean,
        pattern: Pattern,
        regexPattern: String,
        maxResults: Int,
    ): String = buildString {
        var remaining: Int = maxResults
        var jarsWithMatches = 0
        var jarsScanned = 0
        for (candidate in candidates) {
            if (remaining == 0) break
            jarsScanned++
            val lines: List<String> = try {
                grepJar(candidate.file, prefix, matchesMask, pattern, remaining)
            } catch (e: Exception) {
                appendLine("=== ${candidate.file.name} [${candidate.label}] (unreadable: ${e.message}) ===")
                continue
            }
            if (lines.isEmpty()) continue
            jarsWithMatches++
            appendLine("=== ${candidate.file.name} (${candidate.file.absolutePath}) [${candidate.label}] ===")
            lines.forEach { appendLine(it) }
            appendLine()
            remaining -= lines.size
        }
        val unscanned: Int = candidates.size - jarsScanned
        val cutoff: String = if (unscanned > 0) "; maxResults reached, $unscanned jars not scanned" else ""
        append("regexPattern \"$regexPattern\" matched in $jarsWithMatches of $jarsScanned jars scanned$cutoff. ")
        append("Binary entries (.class, images, sounds) are skipped.")
    }

    private fun listEntries(
        jar: File,
        prefix: String?,
        matchesMask: (String) -> Boolean,
        includeClasses: Boolean,
    ): List<JarEntry> = ZipFile(jar).use { zip ->
        zip.entries().asSequence()
            .filter { !it.isDirectory }
            .map { JarEntry(it.name, it.size) }
            .filter { includeClasses || !it.name.endsWith(".class") }
            .filter { prefix == null || it.name.startsWith(prefix, ignoreCase = true) }
            .filter { matchesMask(it.name) }
            .sortedBy { it.name }
            .toList()
    }
}

/** `  entry:line: text` for every line of [jar]'s text entries matching [pattern], at most [limit] lines. */
internal fun grepJar(
    jar: File,
    prefix: String?,
    matchesMask: (String) -> Boolean,
    pattern: Pattern,
    limit: Int,
): List<String> {
    val out = mutableListOf<String>()
    ZipFile(jar).use { zip ->
        val entries = zip.entries().asSequence()
            .filter { !it.isDirectory && !isBinaryEntryName(it.name) && it.size <= MAX_GREP_ENTRY_BYTES }
            .filter { prefix == null || it.name.startsWith(prefix, ignoreCase = true) }
            .filter { matchesMask(it.name) }
            .sortedBy { it.name }
        for (entry in entries) {
            val bytes: ByteArray = zip.getInputStream(entry).use { it.readBytes() }
            if (looksBinary(bytes)) continue
            for ((i, line) in String(bytes, StandardCharsets.UTF_8).lines().withIndex()) {
                if (!pattern.matcher(line).find()) continue
                out.add("  ${entry.name}:${i + 1}: ${line.trim().take(MAX_GREP_LINE_CHARS)}")
                if (out.size >= limit) return out
            }
        }
    }
    return out
}
