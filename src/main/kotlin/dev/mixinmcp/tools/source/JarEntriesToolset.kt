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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

internal data class JarCandidate(val file: File, val label: String)

internal data class JarEntry(val name: String, val size: Long)

private const val MAX_JARS_PER_CALL: Int = 10

@Suppress("FunctionName") // @McpTool functions are snake_case by MCP convention
class JarEntriesToolset : McpToolset {

    override fun isExperimental(): Boolean = false

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription(
        "Lists the entries inside a jar: resources such as META-INF/mods.toml or neoforge.mods.toml, fabric.mod.json, " +
            "assets/<modid>/lang/*.json, models, textures, data/<modid>/recipes and loot tables, *.mixins.json, " +
            "accesstransformer.cfg, and (with includeClasses=true) .class files. jarPath: any jar on disk, on the " +
            "classpath or not (a mod sitting in a modpack folder); backslashes are fine. jar: instead of a path, a " +
            "case-insensitive substring of a classpath jar's file name or Maven coordinates (e.g. 'jade' or " +
            "'snownee:jade'); every matching classpath or decompiled-cache jar is listed, up to 10. pathPrefix " +
            "filters entries by prefix (e.g. data/minecraft/recipes/), fileMask by case-insensitive substring or " +
            "glob (e.g. '*.toml', 'lang', '*/en_us.json'). maxResults: 200 default. Each entry prints with its size; " +
            "read one with mixin_get_dep_source(jarPath=..., entry=...) or url=jar://<jar path>!/<entry>.",
    )
    @Suppress("unused")
    suspend fun mixin_list_jar_entries(
        jarPath: String? = null,
        jar: String? = null,
        pathPrefix: String? = null,
        fileMask: String? = null,
        includeClasses: Boolean = false,
        maxResults: Int = 200,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val explicitPath: String? = jarPath?.trim()?.takeIf { it.isNotEmpty() }
        val nameFilter: String? = jar?.trim()?.takeIf { it.isNotEmpty() }
        if (explicitPath == null && nameFilter == null) {
            return McpToolCallResult.error(
                "Pass `jarPath` (any jar on disk) or `jar` (a substring of a classpath jar's file name or coordinates).",
            )
        }
        if (explicitPath != null && nameFilter != null) {
            return McpToolCallResult.error("Pass either `jarPath` or `jar`, not both.")
        }
        if (maxResults < 1 || maxResults > 5000) {
            return McpToolCallResult.error("maxResults must be between 1 and 5000 (got $maxResults)")
        }

        val candidates: List<JarCandidate> = if (explicitPath != null) {
            val file = File(explicitPath.replace('\\', '/'))
            if (!file.isFile) {
                return McpToolCallResult.error("jarPath does not exist or is not a file: $explicitPath")
            }
            listOf(JarCandidate(file, "jar on disk"))
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
            buildString {
                var remaining: Int = maxResults
                for (candidate in candidates) {
                    appendLine("=== ${candidate.file.name} (${candidate.file.absolutePath}) [${candidate.label}] ===")
                    val entries: List<JarEntry> = try {
                        listEntries(candidate.file, normalizedPrefix, matchesMask, includeClasses)
                    } catch (e: Exception) {
                        appendLine("  (unreadable: ${e.message})")
                        appendLine()
                        continue
                    }
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
                append("Read an entry with mixin_get_dep_source(jarPath=\"<jar path>\", entry=\"<entry>\").")
            }
        }
        return McpToolCallResult.text(text)
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
