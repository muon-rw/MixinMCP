package dev.mixinmcp.tools.source

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue.FALSE
import com.intellij.mcpserver.annotations.McpToolHintValue.TRUE
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import dev.mixinmcp.cache.DecompilationCacheService
import dev.mixinmcp.tools.requireProject
import dev.mixinmcp.tools.resolveAgainstBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

internal data class JarCandidate(val file: File, val label: String)

internal data class JarEntry(val name: String, val size: Long)

private const val MAX_JARS_PER_CALL: Int = 10
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

        val candidates: List<JarCandidate> = if (explicitPath != null) {
            val target = File(resolveAgainstBase(project.basePath, explicitPath))
            when {
                target.isFile -> listOf(JarCandidate(target, "jar on disk"))
                target.isDirectory -> {
                    val jars: List<File> = target.listFiles { f -> f.isFile && f.name.endsWith(".jar", ignoreCase = true) }
                        ?.sortedBy { it.name.lowercase() }
                        .orEmpty()
                    if (jars.isEmpty()) {
                        return McpToolCallResult.error("No .jar files directly inside ${target.path}.")
                    }
                    jars.map { JarCandidate(it, "in ${target.name}") }
                }
                else -> return McpToolCallResult.error("jarPath does not exist: ${target.path}")
            }
        } else {
            val all: List<JarCandidate> = smartReadAction(project) { classpathJars(project) }
            val needle: String = nameFilter!!.lowercase()
            val matched: List<JarCandidate> = all.filter {
                it.file.name.lowercase().contains(needle) || it.label.lowercase().contains(needle)
            }
            if (matched.isEmpty()) {
                return McpToolCallResult.error(
                    "No classpath jar's file name or coordinates contain \"$nameFilter\" (${all.size} jars checked). " +
                        "For a jar that is not on the classpath pass jarPath instead; mixin_list_source_roots(filter=...) " +
                        "shows which dependencies are attached.",
                )
            }
            if (matched.size > MAX_JARS_PER_CALL) {
                return McpToolCallResult.error(
                    "\"$nameFilter\" matches ${matched.size} jars; narrow it. First matches: " +
                        matched.take(15).joinToString(", ") { it.file.name },
                )
            }
            matched
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

    private fun classpathJars(project: Project): List<JarCandidate> {
        val seen: MutableSet<String> = mutableSetOf()
        val out: MutableList<JarCandidate> = mutableListOf()
        for (module in ModuleManager.getInstance(project).modules) {
            for (entry in ModuleRootManager.getInstance(module).orderEntries) {
                if (entry !is LibraryOrderEntry) continue
                val lib = entry.library ?: continue
                val libName: String = lib.name ?: ""
                for (root in lib.getFiles(OrderRootType.CLASSES)) {
                    val diskPath: String = root.path.replace('\\', '/').substringBefore("!/")
                    if (!diskPath.endsWith(".jar", ignoreCase = true)) continue
                    if (!seen.add(diskPath.lowercase())) continue
                    out.add(JarCandidate(File(diskPath), libName))
                }
            }
        }
        for (info in DecompilationCacheService.getInstance(project).getCachedRoots()) {
            val diskPath: String = info.classesJarPath.replace('\\', '/')
            if (!seen.add(diskPath.lowercase())) continue
            val file = File(diskPath)
            if (file.isFile) out.add(JarCandidate(file, "${info.libraryName} (decompiled cache)"))
        }
        return out
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
