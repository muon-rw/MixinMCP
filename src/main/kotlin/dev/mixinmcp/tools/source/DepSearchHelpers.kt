package dev.mixinmcp.tools.source

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import dev.mixinmcp.cache.DecompilationCacheService
import dev.mixinmcp.cache.MixinDecompiledRootsProvider
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Known `.java` paths (relative to a Library SOURCES jar root) used to detect whether
 * vanilla / Forge / NeoForge game API sources are attached — avoids false negatives from
 * sampling files in alphabetical tree order (e.g. `com/mojang/...` before `net/minecraft/...`).
 */
internal val VANILLA_LIBRARY_SOURCE_SENTINELS: List<String> = listOf(
    // Mojang-mapped names
    "net/minecraft/world/level/Level.java",
    "net/minecraft/world/item/Item.java",
    "net/minecraft/core/Registry.java",
    // Yarn names (Fabric Loom with yarn, neo-loom with Modern Yarn)
    "net/minecraft/world/World.java",
    "net/minecraft/item/Item.java",
    "net/minecraft/entity/Entity.java",
)

internal val FORGE_EVENT_LIBRARY_SOURCE_SENTINELS: List<String> = listOf(
    "net/minecraftforge/event/entity/EntityLeaveLevelEvent.java",
    "net/minecraftforge/event/entity/EntityEvent.java",
)

internal val NEOFORGE_NEOFORGE_EVENT_SOURCE_SENTINELS: List<String> = listOf(
    "net/neoforged/neoforge/event/entity/EntityEvent.java",
    "net/neoforged/neoforge/event/Event.java",
    "net/neoforged/neoforge/common/NeoForge.java",
)

internal data class SourceRootInfo(val root: VirtualFile, val typeLabel: String)

internal data class DepSearchHit(
    val url: String,
    val filePath: String,
    val rootLabel: String,
    val lineNum: Int,
    val highlighted: String,
)

internal data class DepRegexScanResult(
    val hits: List<DepSearchHit>,
    val timedOut: Boolean,
    val noMatchHints: List<String>,
)

/**
 * Collects all source roots: SOURCES from library order entries plus
 * source roots from AdditionalLibraryRootsProvider (decompiled cache).
 */
@RequiresReadLock
internal fun collectAllSourceRoots(project: Project): List<VirtualFile> {
    return collectSourceRootsWithMetadata(project).map { it.root }
}

/**
 * Collects source roots with type metadata for diagnostic output.
 * Returns (root, typeLabel) pairs.
 */
@RequiresReadLock
internal fun collectSourceRootsWithMetadata(project: Project): List<SourceRootInfo> {
    val seen = mutableSetOf<VirtualFile>()
    val result = mutableListOf<SourceRootInfo>()

    for (module in ModuleManager.getInstance(project).modules) {
        for (entry in ModuleRootManager.getInstance(module).orderEntries) {
            if (entry is LibraryOrderEntry) {
                val lib = entry.library ?: continue
                val libName: String = lib.name ?: "(unnamed)"
                lib.getFiles(OrderRootType.SOURCES)?.forEach { root ->
                    if (seen.add(root)) {
                        // Cache dirs attached by SourceAutoAttacher keep their decompiled label so the
                        // library/decompiled roots contract of mixin_search_in_deps holds.
                        val label: String = if (DecompilationCacheService.isDecompiledCachePath(root.path)) {
                            "Decompiled cache (MixinMCP)"
                        } else {
                            "Library SOURCES: $libName"
                        }
                        result.add(SourceRootInfo(root, label))
                    }
                }
            }
        }
    }

    for (provider in AdditionalLibraryRootsProvider.EP_NAME.extensionList) {
        if (provider !is MixinDecompiledRootsProvider) continue
        for (synthLib in provider.getAdditionalProjectLibraries(project)) {
            for (root in synthLib.sourceRoots) {
                if (seen.add(root)) {
                    result.add(SourceRootInfo(root, "Decompiled cache (MixinMCP)"))
                }
            }
        }
    }

    return result
}

/**
 * Determines which source root type a VirtualFile belongs to.
 */
@RequiresReadLock
internal fun classifySourceFile(project: Project, vf: VirtualFile): String {
    val vfUrl = vf.url
    for (info in collectSourceRootsWithMetadata(project)) {
        val rootUrl = info.root.url.trimEnd('/')
        if (vfUrl.startsWith(rootUrl)) {
            return info.typeLabel
        }
    }
    val normPath: String = vf.path.replace('\\', '/')
    val projectPath = project.basePath?.replace('\\', '/')
    if (projectPath != null && normPath.startsWith(projectPath)) {
        if (isLoomCacheArtifactPath(normPath, projectPath)) {
            return "Loom toolchain artifact (binary under .gradle/loom-cache; genSources provides real sources)"
        }
        if (isGradleToolchainMergedOrBinaryInBuild(normPath, projectPath)) {
            return "MDG merged artifact (binary .class under build/)"
        }
        return "Project source"
    }
    return "Classes JAR (binary)"
}

/**
 * Loom-family toolchains (Fabric Loom, Architectury Loom, neo-loom) keep processed game and
 * remapped mod jars under the project-local `.gradle/loom-cache/`; without this check they
 * classify as "Project source". Deliberately NOT part of [isGradleToolchainMergedOrBinaryInBuild]:
 * these are proper GAV libraries whose sources come from genSources, so the MDG merged-artifact
 * sections and hints must not claim them.
 */
internal fun isLoomCacheArtifactPath(filePath: String, projectPath: String): Boolean {
    val rel: String = filePath.removePrefix(projectPath).trimStart('/').lowercase()
    return rel.startsWith(".gradle/loom-cache/")
}

/**
 * True for MDG (ModDevGradle) / NeoGradle merged jars and similar under the project's build directory.
 * These are not hand-written project sources; [classifySourceFile] used to label them "Project source".
 *
 * Only applies to paths already known to be under the project directory.
 * ForgeGradle (older toolchain) puts its artifacts in `~/.gradle/caches/forge_gradle/` instead,
 * which are NOT under the project path — those are correctly handled as normal Gradle library
 * entries and have proper Library SOURCES roots (making vanilla MC searchable).
 */
internal fun isGradleToolchainMergedOrBinaryInBuild(filePath: String, projectPath: String): Boolean {
    val rel: String = filePath.removePrefix(projectPath).trimStart('/').lowercase()
    return rel.startsWith("build/moddev/") ||
        rel.contains("-merged.jar") ||
        (rel.startsWith("build/") && rel.contains("neoforge")) ||
        (rel.startsWith("build/") && rel.contains("minecraft") && rel.endsWith(".jar"))
}

/**
 * Detects MDG merged JARs (vanilla, Forge, or NeoForge) and similar Minecraft artifacts in the project build directory.
 * They contain vanilla + loader game classes; MixinMCP also registers them as Library SOURCES when possible
 * so dependency grep can see `.java` entries (or Gradle universal *-sources.jar fallback).
 */
internal fun detectMergedJars(project: Project): List<String> {
    val projectPath = project.basePath?.replace('\\', '/') ?: return emptyList()
    val results = mutableListOf<String>()
    for (module in ModuleManager.getInstance(project).modules) {
        for (entry in ModuleRootManager.getInstance(module).orderEntries) {
            if (entry is LibraryOrderEntry) {
                val lib = entry.library ?: continue
                lib.getFiles(OrderRootType.CLASSES)?.forEach { root ->
                    val path = root.path.replace('\\', '/')
                    if (path.startsWith(projectPath) && isGradleToolchainMergedOrBinaryInBuild(path, projectPath)) {
                        results.add(path)
                    }
                }
            }
        }
    }
    return results.distinct()
}

/**
 * True if a Library SOURCES root contains at least one of the given paths (slash-separated, relative to jar root).
 */
internal fun libraryRootContainsAnySentinelJava(root: VirtualFile, relativeJavaPaths: List<String>): Boolean {
    for (rel: String in relativeJavaPaths) {
        ProgressManager.checkCanceled()
        if (root.findFileByRelativePath(rel) != null) return true
    }
    return false
}

/**
 * True if Library SOURCES roots contain Forge **game** events (`net.minecraftforge.event.*`),
 * distinct from `net.minecraftforge.eventbus` and `net.minecraftforge.fml`.
 */
internal fun hasForgeGameEventApiInLibrarySources(libRoots: List<SourceRootInfo>): Boolean {
    return libRoots.any { info: SourceRootInfo ->
        libraryRootContainsAnySentinelJava(info.root, FORGE_EVENT_LIBRARY_SOURCE_SENTINELS)
    }
}

/**
 * True if Library SOURCES roots contain NeoForge **game** API (`net.neoforged.neoforge.event.*`),
 * distinct from `net.neoforged.bus` and other loader artifacts.
 */
internal fun hasNeoForgeNeoforgeEventApiInLibrarySources(libRoots: List<SourceRootInfo>): Boolean {
    return libRoots.any { info: SourceRootInfo ->
        libraryRootContainsAnySentinelJava(info.root, NEOFORGE_NEOFORGE_EVENT_SOURCE_SENTINELS)
    }
}

/**
 * True for Library SOURCES roots holding game code: loom-cache or MDG merged
 * artifacts under the project, or any root containing a vanilla / Forge /
 * NeoForge sentinel. These keep full detail in condensed mixin_list_source_roots output.
 */
@RequiresReadLock
internal fun isGameSourceRoot(project: Project, root: VirtualFile): Boolean {
    val path: String = root.path.replace('\\', '/')
    val projectPath: String? = project.basePath?.replace('\\', '/')
    if (projectPath != null && path.startsWith(projectPath) &&
        (isLoomCacheArtifactPath(path, projectPath) || isGradleToolchainMergedOrBinaryInBuild(path, projectPath))
    ) {
        return true
    }
    return libraryRootContainsAnySentinelJava(root, VANILLA_LIBRARY_SOURCE_SENTINELS) ||
        libraryRootContainsAnySentinelJava(root, FORGE_EVENT_LIBRARY_SOURCE_SENTINELS) ||
        libraryRootContainsAnySentinelJava(root, NEOFORGE_NEOFORGE_EVENT_SOURCE_SENTINELS)
}

/**
 * Jar file name for a jar root, last path segment for a directory root.
 */
internal fun sourceRootDisplayName(root: VirtualFile): String {
    return root.url.substringBefore("!/").trimEnd('/').substringAfterLast('/')
}

private fun hasLocalForgeLikeMergedArtifacts(project: Project): Boolean {
    return detectMergedJars(project).any { path: String ->
        val low: String = path.lowercase()
        low.contains("forge") && !low.contains("neoforge") &&
            (low.contains("merged") || low.contains("moddev"))
    }
}

private fun hasLocalNeoForgeLikeMergedArtifacts(project: Project): Boolean {
    return detectMergedJars(project).any { path: String ->
        val low: String = path.lowercase()
        low.contains("neoforge") && (low.contains("merged") || low.contains("moddev"))
    }
}

internal fun buildNoMatchHintsForDepSearch(
    project: Project,
    normalizedPathPrefix: String?,
    sawAnyFileUnderPathPrefix: Boolean,
): List<String> {
    val libRoots: List<SourceRootInfo> =
        collectSourceRootsWithMetadata(project).filter { it.typeLabel.startsWith("Library SOURCES") }
    val hasForgeGameEvents: Boolean = hasForgeGameEventApiInLibrarySources(libRoots)
    val hasNeoForgeGameEvents: Boolean = hasNeoForgeNeoforgeEventApiInLibrarySources(libRoots)
    val hasVanilla: Boolean = libRoots.any { info: SourceRootInfo ->
        libraryRootContainsAnySentinelJava(info.root, VANILLA_LIBRARY_SOURCE_SENTINELS)
    }
    val merged: List<String> = detectMergedJars(project)
    val hasForgeMerged: Boolean = hasLocalForgeLikeMergedArtifacts(project)
    val hasNeoForgeMerged: Boolean = hasLocalNeoForgeLikeMergedArtifacts(project)

    val prefix: String = normalizedPathPrefix?.lowercase() ?: ""
    val targetsForgeGameEvents: Boolean =
        prefix.startsWith("net/minecraftforge/event/") || prefix == "net/minecraftforge/event"
    val targetsNeoForgeGameEvents: Boolean =
        prefix.startsWith("net/neoforged/neoforge/event/") || prefix == "net/neoforged/neoforge/event"
    val targetsVanillaPrefix: Boolean = prefix.startsWith("net/minecraft/")
    val targetsForgeTree: Boolean = prefix.startsWith("net/minecraftforge/")
    val targetsNeoForgeTree: Boolean = prefix.startsWith("net/neoforged/")

    val hints: MutableList<String> = mutableListOf()
    if (normalizedPathPrefix != null) {
        if (!sawAnyFileUnderPathPrefix) {
            hints.add(
                "No dependency source files under pathPrefix \"$normalizedPathPrefix\" (with the given fileMask, if any) exist in Library SOURCES or the decompiled cache.",
            )
        } else {
            hints.add(
                "No lines matched the regex under pathPrefix \"$normalizedPathPrefix\" in Library SOURCES or the decompiled cache — try another pattern, fileMask, or drop pathPrefix.",
            )
        }
    }
    val loomStyleHint: String =
        "No MDG merged artifacts detected; on a Loom-style toolchain (Fabric Loom, Architectury Loom, neo-loom) " +
            "run ./gradlew genSources then mixin_sync_project. Until then, ./gradlew genDependencySources makes " +
            "the game jars searchable via the decompiled cache."
    val genDepSourcesMemoryNote: String =
        "Fallback ./gradlew genDependencySources needs org.gradle.jvmargs=-Xmx4g in gradle.properties " +
            "to decompile the game jars."

    if (targetsForgeGameEvents && !hasForgeGameEvents) {
        if (merged.isNotEmpty()) {
            hints.add(
                "Forge game API (net.minecraftforge.event.*) should be in Library SOURCES after MixinMCP auto-attaches the MDG merged jar " +
                    "(or a Gradle *-sources.jar fallback when the merged jar has no .java entries).",
            )
            hints.add(
                "If search is still empty: run mixin_list_source_roots (MDG auto-attach section), then try mixin_find_class(includeSource=true), mixin_search_symbols, or drop pathPrefix.",
            )
            hints.add(
                "This project uses MDG merged artifacts — if mixin_list_source_roots shows auto-attach warnings, attachment failed and universal API sources may be missing.",
            )
            hints.add(genDepSourcesMemoryNote)
        } else {
            hints.add("Forge game API (net.minecraftforge.event.*) is not in any Library SOURCES root. $loomStyleHint")
        }
    }
    if (targetsNeoForgeGameEvents && !hasNeoForgeGameEvents) {
        if (merged.isNotEmpty()) {
            hints.add(
                "NeoForge game API (net.neoforged.neoforge.event.*) should be in Library SOURCES after MixinMCP auto-attaches the MDG merged jar " +
                    "(or a Gradle *-sources.jar fallback when the merged jar has no .java entries).",
            )
            hints.add(
                "If search is still empty: run mixin_list_source_roots (MDG auto-attach section), then try mixin_find_class(includeSource=true), mixin_search_symbols, or drop pathPrefix.",
            )
            hints.add(
                "This project uses MDG merged artifacts — if mixin_list_source_roots shows auto-attach warnings, attachment failed and universal API sources may be missing.",
            )
            hints.add(genDepSourcesMemoryNote)
        } else {
            hints.add("NeoForge game API (net.neoforged.neoforge.event.*) is not in any Library SOURCES root. $loomStyleHint")
        }
    }
    if (!targetsForgeGameEvents &&
        targetsForgeTree &&
        !hasForgeGameEvents &&
        hasForgeMerged
    ) {
        hints.add(
            "Under net/minecraftforge/, mixin_search_in_deps only sees packages present in Library SOURCES. " +
                "Confirm mixin_list_source_roots reports a successful MDG auto-attach for the merged jar.",
        )
    }
    if (!targetsNeoForgeGameEvents &&
        targetsNeoForgeTree &&
        !hasNeoForgeGameEvents &&
        hasNeoForgeMerged
    ) {
        hints.add(
            "Under net/neoforged/, mixin_search_in_deps only sees packages present in Library SOURCES. " +
                "Confirm mixin_list_source_roots reports a successful MDG auto-attach for the merged jar.",
        )
    }
    if (targetsVanillaPrefix && !hasVanilla) {
        if (merged.isNotEmpty()) {
            hints.add(
                "Vanilla (net/minecraft/*) should appear in Library SOURCES when the MDG merged jar is auto-attached. " +
                    "Check mixin_list_source_roots (MDG auto-attach warnings), then try mixin_find_class(includeSource=true).",
            )
            hints.add(genDepSourcesMemoryNote)
        } else {
            hints.add("Vanilla (net/minecraft/*) is not in any Library SOURCES root. $loomStyleHint")
        }
    }
    return hints
}

/**
 * Collects up to maxSamples .java file paths from a root (for diagnostic output).
 */
@RequiresReadLock
internal fun collectSamplePaths(root: VirtualFile, maxSamples: Int): List<String> {
    val samples = mutableListOf<String>()
    collectSamplePathsRecursive(root, root, samples, maxSamples)
    return samples
}

private fun collectSamplePathsRecursive(
    vf: VirtualFile,
    root: VirtualFile,
    samples: MutableList<String>,
    maxSamples: Int,
) {
    ProgressManager.checkCanceled()
    if (samples.size >= maxSamples) return
    if (vf.isDirectory) {
        for (child in vf.children) {
            collectSamplePathsRecursive(child, root, samples, maxSamples)
        }
    } else if (vf.name.endsWith(".java") || vf.name.endsWith(".kt")) {
        samples.add(getPathForMask(root, vf))
    }
}

/**
 * Locates a dependency source file by path (e.g. io/redspace/.../Utils.java).
 * Searches SOURCES roots and synthetic library roots; returns first match.
 * A non-null [scope] limits the search to roots inside it (module pinning).
 */
@RequiresReadLock
internal fun locateDepSourceByPath(
    project: Project,
    path: String,
    scope: GlobalSearchScope? = null,
): VirtualFile? {
    val normalizedPath: String = path.replace('\\', '/').removePrefix("/")
    for (root in collectAllSourceRoots(project)) {
        if (scope != null && !scope.contains(root)) continue
        findFileByPathInTree(root, normalizedPath)?.let { return it }
    }
    return null
}

private fun findFileByPathInTree(vf: VirtualFile, targetPath: String): VirtualFile? {
    ProgressManager.checkCanceled()
    if (vf.isDirectory) {
        for (child in vf.children) {
            findFileByPathInTree(child, targetPath)?.let { return it }
        }
        return null
    }
    val pathInJar: String? = vf.url.substringAfter("!/", "").takeIf { it.isNotEmpty() }
    val normalized: String = (pathInJar ?: vf.path).replace('\\', '/')
    return if (normalized == targetPath || normalized.endsWith("/$targetPath")) vf else null
}

/**
 * If the query looks like an FQCN (contains dots with lowercase segments, e.g.
 * "net.minecraft.world.entity.LivingEntity"), extracts the simple name so it
 * can be matched against PsiShortNamesCache which only stores short names.
 * Also handles slash-separated paths ("net/minecraft/.../LivingEntity").
 */
internal fun extractSimpleName(query: String): String {
    val trimmed = query.trim()
    if ('/' in trimmed) return trimmed.substringAfterLast('/')
    if ('.' in trimmed) {
        val parts = trimmed.split('.')
        if (parts.size >= 3 && parts.dropLast(1).any { it.first().isLowerCase() }) {
            return parts.last()
        }
    }
    return trimmed
}

/**
 * Builds a file-mask matcher from user input. Supports three modes:
 * - null / blank / "*" → match everything
 * - No glob characters (* ?) → case-insensitive substring match against the
 *   full path, so bare names like "LivingEntity" or "LivingEntity.java" work
 *   without requiring agents to wrap in wildcards.
 * - Contains glob characters → convert glob to regex anchored on the full path
 *   (single `*` crosses `/`, matching the documented behavior).
 * The regex is pre-compiled once instead of per-file.
 */
internal fun buildFileMaskMatcher(fileMask: String?): (String) -> Boolean {
    val mask = fileMask?.trim()
    if (mask.isNullOrBlank() || mask == "*") return { true }

    val hasGlob = '*' in mask || '?' in mask
    if (!hasGlob) {
        val lower = mask.lowercase()
        return { path -> path.lowercase().contains(lower) }
    }

    val regex: String = mask
        .replace(".", "\\.")
        .replace("*", ".*")
        .replace("?", ".")
    val compiled = Regex(regex, RegexOption.IGNORE_CASE)
    return { path -> compiled.containsMatchIn(path) }
}

/**
 * Returns the path used for fileMask matching: for JAR entries, the path inside
 * the jar (e.g. net/minecraft/world/entity/LivingEntity.java); for directory
 * roots (decompiled cache), the path relative to root.
 */
internal fun getPathForMask(root: VirtualFile, vf: VirtualFile): String {
    val pathInJar: String? = vf.url.substringAfter("!/", "").takeIf { it.isNotEmpty() }
    if (pathInJar != null) return pathInJar.replace('\\', '/')
    val rootPath: String = root.path.replace('\\', '/').trimEnd('/')
    val vfPath: String = vf.path.replace('\\', '/')
    return if (vfPath.startsWith(rootPath)) {
        vfPath.removePrefix(rootPath).trimStart('/')
    } else {
        vf.name
    }
}

@RequiresReadLock
internal fun collectRegexHits(
    vf: VirtualFile,
    root: VirtualFile,
    pattern: Pattern,
    matchesMask: (String) -> Boolean,
    hits: MutableList<DepSearchHit>,
    maxResults: Int,
    startTime: Long,
    timeout: Long,
    rootLabel: String = "",
    pathPrefix: String? = null,
    skipPath: (String) -> Boolean = { false },
    pathPrefixFilesSeen: BooleanArray? = null,
) {
    ProgressManager.checkCanceled()
    if (hits.size >= maxResults) return
    if (System.currentTimeMillis() - startTime > timeout) return

    if (vf.isDirectory) {
        for (child in vf.children) {
            collectRegexHits(
                child,
                root,
                pattern,
                matchesMask,
                hits,
                maxResults,
                startTime,
                timeout,
                rootLabel,
                pathPrefix,
                skipPath,
                pathPrefixFilesSeen,
            )
        }
    } else {
        val pathToMatch: String = getPathForMask(root, vf)
        if (pathPrefix != null && !pathToMatch.startsWith(pathPrefix)) return
        if (skipPath(pathToMatch)) return
        if (!matchesMask(pathToMatch)) return
        pathPrefixFilesSeen?.let { seen -> seen[0] = true }
        val content: String = try {
            String(vf.contentsToByteArray(), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            return
        }
        val lines: List<String> = content.lines()
        for ((i, line) in lines.withIndex()) {
            if (hits.size >= maxResults) return
            val matcher = pattern.matcher(line)
            if (matcher.find()) {
                hits.add(DepSearchHit(
                    url = vf.url,
                    filePath = pathToMatch,
                    rootLabel = rootLabel,
                    lineNum = i + 1,
                    highlighted = matcher.replaceAll("||\$0||"),
                ))
            }
        }
    }
}

/**
 * Formats search hits grouped by file. Each file header shows the logical
 * path and URL once; matching lines are listed compactly underneath.
 */
internal fun formatGroupedHits(sb: StringBuilder, hits: List<DepSearchHit>) {
    val grouped: Map<String, List<DepSearchHit>> = hits.groupBy { it.url }
    for ((_, fileHits) in grouped) {
        val first = fileHits.first()
        sb.appendLine("--- ${first.filePath} [${first.rootLabel}] ---")
        sb.appendLine("url: ${first.url}")
        for (hit in fileHits) {
            sb.appendLine("  ${hit.lineNum}: ${hit.highlighted}")
        }
        sb.appendLine()
    }
}

/**
 * Formats hits with [contextLines] of surrounding lines per match, merging
 * overlapping windows per file so a short method body shows only once even
 * when the regex matches multiple lines inside it. Match lines keep the
 * `||markers||` highlighting from [collectRegexHits] and are prefixed with
 * `>`; context lines are prefixed with two spaces. Windows separated by gaps
 * are split with a `--` divider.
 *
 * Falls back to [formatGroupedHits] when [contextLines] is 0 or the file
 * content can't be read (binary entry, JAR closed, etc.).
 */
internal fun formatGroupedHitsWithContext(
    sb: StringBuilder,
    hits: List<DepSearchHit>,
    contextLines: Int,
) {
    if (contextLines <= 0) {
        formatGroupedHits(sb, hits)
        return
    }
    val grouped: Map<String, List<DepSearchHit>> = hits.groupBy { it.url }
    for ((url, fileHits) in grouped) {
        val first = fileHits.first()
        sb.appendLine("--- ${first.filePath} [${first.rootLabel}] ---")
        sb.appendLine("url: $url")
        val vf: VirtualFile? = VirtualFileManager.getInstance().findFileByUrl(url)
        val lines: List<String>? = vf?.let {
            try {
                String(it.contentsToByteArray(), StandardCharsets.UTF_8).lines()
            } catch (_: Exception) {
                null
            }
        }
        if (lines == null) {
            for (hit in fileHits) {
                sb.appendLine("  ${hit.lineNum}: ${hit.highlighted}")
            }
            sb.appendLine()
            continue
        }

        val hitsByLine: Map<Int, DepSearchHit> = fileHits.associateBy { it.lineNum }
        val windows: List<IntRange> = mergeWindows(
            fileHits.map { it.lineNum }.sorted().distinct(),
            contextLines,
            lines.size,
        )
        for ((idx, window: IntRange) in windows.withIndex()) {
            if (idx > 0) sb.appendLine("--")
            for (lineNum in window) {
                val match: DepSearchHit? = hitsByLine[lineNum]
                if (match != null) {
                    sb.appendLine("> $lineNum: ${match.highlighted}")
                } else {
                    val raw: String = lines.getOrNull(lineNum - 1) ?: ""
                    sb.appendLine("  $lineNum: $raw")
                }
            }
        }
        sb.appendLine()
    }
}

/**
 * Builds a list of disjoint, sorted line ranges by expanding each match line
 * by [context] in both directions and merging adjacent/overlapping ranges.
 * Clamps to `1..lineCount`.
 */
private fun mergeWindows(matchLines: List<Int>, context: Int, lineCount: Int): List<IntRange> {
    if (matchLines.isEmpty() || lineCount <= 0) return emptyList()
    val result: MutableList<IntRange> = mutableListOf()
    var curStart: Int = (matchLines.first() - context).coerceAtLeast(1)
    var curEnd: Int = (matchLines.first() + context).coerceAtMost(lineCount)
    for (line: Int in matchLines.drop(1)) {
        val windowStart: Int = (line - context).coerceAtLeast(1)
        val windowEnd: Int = (line + context).coerceAtMost(lineCount)
        if (windowStart <= curEnd + 1) {
            curEnd = maxOf(curEnd, windowEnd)
        } else {
            result.add(curStart..curEnd)
            curStart = windowStart
            curEnd = windowEnd
        }
    }
    result.add(curStart..curEnd)
    return result
}