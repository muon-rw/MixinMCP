package dev.mixinmcp.tools.source

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import dev.mixinmcp.cache.DecompilationCacheService
import dev.mixinmcp.tools.resolveAgainstBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class JarCandidate(val file: File, val label: String)

internal sealed interface CandidateResolution {
    class Found(val jars: List<JarCandidate>) : CandidateResolution
    class Failed(val error: McpToolCallResult) : CandidateResolution
}

/** Jars named by [explicitPath] or [nameFilter]; with neither, every classpath jar. [maxJars] caps a [nameFilter] match. */
internal suspend fun resolveCandidates(
    project: Project,
    explicitPath: String?,
    nameFilter: String?,
    maxJars: Int?,
): CandidateResolution {
    if (explicitPath != null) {
        val target = File(resolveAgainstBase(project.basePath, explicitPath))
        return withContext(Dispatchers.IO) { resolveDiskCandidates(target) }
    }
    val all: List<JarCandidate> = classpathJars(project)
    if (nameFilter == null) return CandidateResolution.Found(all)
    val needle: String = nameFilter.lowercase()
    val matched: List<JarCandidate> = all.filter {
        it.file.name.lowercase().contains(needle) || it.label.lowercase().contains(needle)
    }
    if (matched.isEmpty()) {
        return CandidateResolution.Failed(
            McpToolCallResult.error(
                "No classpath jar's file name or coordinates contain \"$nameFilter\" (${all.size} jars checked). " +
                    "For a jar that is not on the classpath pass jarPath instead; mixin_list_source_roots(filter=...) " +
                    "shows which dependencies are attached.",
            ),
        )
    }
    if (maxJars != null && matched.size > maxJars) {
        return CandidateResolution.Failed(
            McpToolCallResult.error(
                "\"$nameFilter\" matches ${matched.size} jars; narrow it. First matches: " +
                    matched.take(15).joinToString(", ") { it.file.name },
            ),
        )
    }
    return CandidateResolution.Found(matched)
}

internal fun resolveDiskCandidates(target: File): CandidateResolution = when {
    target.isFile -> CandidateResolution.Found(listOf(JarCandidate(target, "jar on disk")))
    target.isDirectory -> {
        val jars: List<File> = target.listFiles { f -> f.isFile && f.name.endsWith(".jar", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        if (jars.isEmpty()) {
            CandidateResolution.Failed(McpToolCallResult.error("No .jar files directly inside ${target.path}."))
        } else {
            CandidateResolution.Found(jars.map { JarCandidate(it, "in ${target.name}") })
        }
    }
    else -> CandidateResolution.Failed(McpToolCallResult.error("jarPath does not exist: ${target.path}"))
}

// Module roots need neither indexes nor smart mode, and getCachedRoots() reads manifests from
// disk, which must stay out of the (restartable) read action.
internal suspend fun classpathJars(project: Project): List<JarCandidate> {
    val libraryJars: List<JarCandidate> = readAction {
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
        out
    }
    return withContext(Dispatchers.IO) {
        val seen: MutableSet<String> = libraryJars.mapTo(mutableSetOf()) { it.file.path.replace('\\', '/').lowercase() }
        val cacheJars: List<JarCandidate> = DecompilationCacheService.getInstance(project).getCachedRoots().mapNotNull { info ->
            val diskPath: String = info.classesJarPath.replace('\\', '/')
            val file = File(diskPath)
            if (seen.add(diskPath.lowercase()) && file.isFile) JarCandidate(file, "${info.libraryName} (decompiled cache)") else null
        }
        libraryJars + cacheJars
    }
}
