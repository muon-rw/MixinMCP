package dev.mixinmcp.tools.source

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.search.PsiShortNamesCache
import dev.mixinmcp.cache.SourceAutoAttacher
import dev.mixinmcp.util.FqcnResolver
import kotlin.coroutines.coroutineContext
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Source-navigation tools: FQCN lookup, short-name search, dependency regex
 * grep, dependency source reading, and source-root diagnostics.
 */
class SourceNavigationToolset : McpToolset {

    @McpTool
    @McpDescription("Use when you know the exact fully-qualified class name; prefer mixin_search_symbols when the class name is only partially known. Looks up any class by FQCN — project, dependencies, and JDK. Use dots for inner classes (e.g. net.minecraft.world.item.Item.Properties). Returns package, modifiers, supertypes, source location, and SourceKind: Library SOURCES (published -sources.jar or MDG merged jar after MixinMCP auto-attach), Decompiled cache (MixinMCP Vineflower), MDG merged artifact (binary-only / before attach — includeSource may use Fernflower), Project source (hand-written project code), or Classes JAR (binary — prefer mixin_get_dep_source for better source). includeMembers (default true): all methods with signatures and all fields with types. includeSource: full source code — can be very large, prefer includeMembers for API overview.")
    @Suppress("unused") // Discovered and invoked by MCP framework via reflection
    suspend fun mixin_find_class(
        className: String,
        includeMembers: Boolean = true,
        includeSource: Boolean = false,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        val result: String? = ReadAction.compute<String?, Throwable> {
            val psiClass: PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@compute null

            buildString {
                appendLine("=== ${psiClass.qualifiedName} ===")
                appendLine()
                val pkg: String = psiClass.qualifiedName?.let { q ->
                    if ('.' in q) q.substringBeforeLast('.') else "(default)"
                } ?: "(default)"
                appendLine("Package: $pkg")
                psiClass.modifierList?.let { appendLine("Modifiers: ${it.text.trim()}") }
                psiClass.superClass?.let { appendLine("Superclass: ${it.qualifiedName}") }
                val interfaces: Array<PsiClass> = psiClass.interfaces
                if (interfaces.isNotEmpty()) {
                    appendLine("Interfaces: ${interfaces.joinToString { it.qualifiedName ?: it.name ?: "?" }}")
                }
                psiClass.containingFile?.virtualFile?.let { vf ->
                    val sourceKind = classifySourceFile(project, vf)
                    appendLine("Source: ${vf.path}")
                    appendLine("SourceKind: $sourceKind")
                }
                appendLine()

                if (includeMembers) {
                    appendLine("--- Methods ---")
                    for (method: PsiMethod in psiClass.methods) {
                        val params: String = method.parameterList.parameters
                            .joinToString(", ") { "${it.type.presentableText} ${it.name}" }
                        val ret: String = method.returnType?.presentableText ?: "void"
                        val mods: String = method.modifierList.text?.trim() ?: ""
                        appendLine("  $mods $ret ${method.name}($params)")
                    }
                    appendLine()
                    appendLine("--- Fields ---")
                    for (field: PsiField in psiClass.fields) {
                        val mods: String = field.modifierList?.text?.trim() ?: ""
                        appendLine("  $mods ${field.type.presentableText} ${field.name}")
                    }
                    appendLine()
                }

                if (includeSource) {
                    appendLine("--- Source ---")
                    psiClass.containingFile?.text?.let { appendLine(it) }
                }
            }
        }

        return when {
            result != null -> McpToolCallResult.text(result)
            else -> McpToolCallResult.error("Class not found: $className")
        }
    }

    @McpTool
    @McpDescription("Use when you don't know the full class name — search by short name substring across project and dependencies. Pass a simple name like 'LivingEntity' or 'getHealth', NOT a fully-qualified name (FQCNs are auto-simplified). kind: class (default), method, field, all. scope: all (default), project, libraries. Returns FQCN for classes, class#method(params) for methods, class.field: type for fields. maxResults defaults to 50.")
    @Suppress("unused")
    suspend fun mixin_search_symbols(
        query: String,
        kind: String = "class",
        scope: String = "all",
        caseSensitive: Boolean = false,
        maxResults: Int = 50,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        val searchScope: GlobalSearchScope = when (scope) {
            "project" -> ProjectScope.getContentScope(project)
            "libraries" -> ProjectScope.getLibrariesScope(project)
            else -> GlobalSearchScope.allScope(project)
        }

        val effectiveQuery: String = extractSimpleName(query)

        val result: String = ReadAction.compute<String, Throwable> {
            val cache: PsiShortNamesCache = PsiShortNamesCache.getInstance(project)
            val q: String = if (caseSensitive) effectiveQuery else effectiveQuery.lowercase()
            fun matches(name: String): Boolean {
                val n = if (caseSensitive) name else name.lowercase()
                return n.contains(q)
            }

            buildString {
                if (effectiveQuery != query) {
                    appendLine("(query '$query' looks like an FQCN — searching short names for '$effectiveQuery')")
                    appendLine()
                }

                if (kind == "class" || kind == "all") {
                    appendLine("--- Classes ---")
                    val allClassNames: Array<String> = cache.allClassNames
                    var count: Int = 0
                    for (name: String in allClassNames) {
                        if (count >= maxResults) break
                        if (!matches(name)) continue
                        val classes: Array<PsiClass> = cache.getClassesByName(name, searchScope)
                        for (c: PsiClass in classes) {
                            if (count >= maxResults) break
                            appendLine("  ${c.qualifiedName ?: name}")
                            count++
                        }
                    }
                    if (count >= maxResults) appendLine("  ... (truncated)")
                    appendLine()
                }

                if (kind == "method" || kind == "all") {
                    appendLine("--- Methods ---")
                    val allMethodNames: Array<String> = cache.allMethodNames
                    var count: Int = 0
                    for (name: String in allMethodNames) {
                        if (count >= maxResults) break
                        if (!matches(name)) continue
                        val methods: Array<PsiMethod> = cache.getMethodsByName(name, searchScope)
                        for (m: PsiMethod in methods) {
                            if (count >= maxResults) break
                            val declClass: PsiClass? = m.containingClass
                            val params: String = m.parameterList.parameters
                                .joinToString(", ") { it.type.presentableText }
                            appendLine("  ${declClass?.qualifiedName ?: "?"}#$name($params)")
                            count++
                        }
                    }
                    if (count >= maxResults) appendLine("  ... (truncated)")
                    appendLine()
                }

                if (kind == "field" || kind == "all") {
                    appendLine("--- Fields ---")
                    val allFieldNames: Array<String> = cache.allFieldNames
                    var count: Int = 0
                    for (name: String in allFieldNames) {
                        if (count >= maxResults) break
                        if (!matches(name)) continue
                        val fields: Array<PsiField> = cache.getFieldsByName(name, searchScope)
                        for (f: PsiField in fields) {
                            if (count >= maxResults) break
                            val declClass: PsiClass? = f.containingClass
                            appendLine("  ${declClass?.qualifiedName ?: "?"}.${f.name}: ${f.type.presentableText}")
                            count++
                        }
                    }
                    if (count >= maxResults) appendLine("  ... (truncated)")
                }
            }
        }

        return McpToolCallResult.text(result)
    }

    @McpTool
    @McpDescription("Lists all source roots that mixin_search_in_deps and mixin_get_dep_source search — Library SOURCES (-sources.jar) and MixinMCP decompiled cache. Detects MDG merged JARs under build/moddev/; MixinMCP auto-attaches them as Library SOURCES after Gradle sync so vanilla/Forge/NeoForge .java files are usually searchable. Diagnoses vanilla (net/minecraft/*), Forge game API (net/minecraftforge/event/*), and NeoForge game API (net/neoforged/neoforge/event/*) plus last auto-attach run. Shows root URL, type, and sample file paths per root. maxSamplesPerRoot: 5 default.")
    @Suppress("unused")
    suspend fun mixin_list_source_roots(
        maxSamplesPerRoot: Int = 5,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        val result: String = ReadAction.compute<String, Throwable> {
            val roots: List<SourceRootInfo> = collectSourceRootsWithMetadata(project)
            buildString {
                appendLine("=== Source roots (mixin_search_in_deps / mixin_get_dep_source scope) ===")
                appendLine()
                appendLine("These roots are searched by mixin_search_in_deps and mixin_get_dep_source.")
                appendLine("Prefer mixin_search_in_deps first for net/minecraft/, net/minecraftforge/, and net/neoforged/ —")
                appendLine("MixinMCP auto-attaches MDG merged jars as Library SOURCES after Gradle sync (see section below).")
                appendLine()
                appendLine("If vanilla Minecraft (net/minecraft/*) is still missing from search results:")
                appendLine("  - Fabric Loom: run ./gradlew genSources to generate Minecraft sources")
                appendLine("  - MDG: confirm Gradle sync finished and check the MDG auto-attach section for warnings")
                appendLine("  - Any loader: run ./gradlew genDependencySources --force to decompile large JARs")
                appendLine("  - Then call mixin_sync_project to refresh IntelliJ's project model")
                appendLine()
                appendLine("If Forge game API (net/minecraftforge/event/*) or NeoForge (net/neoforged/neoforge/event/*) is still missing:")
                appendLine("  - Read the \"MDG merged-jar source auto-attach\" section below (warnings mean attachment failed).")
                appendLine("  - Fallback: mixin_find_class(includeSource=true), mixin_search_symbols, or mixin_search_in_deps without pathPrefix.")
                appendLine()

                val libRoots = roots.filter { it.typeLabel.startsWith("Library SOURCES") }
                val cacheRoots = roots.filter { it.typeLabel == "Decompiled cache (MixinMCP)" }

                val mergedJars = detectMergedJars(project)
                val hasVanillaInLibSources = libRoots.any { info: SourceRootInfo ->
                    libraryRootContainsAnySentinelJava(info.root, VANILLA_LIBRARY_SOURCE_SENTINELS)
                }
                val hasForgeGameEventsInLibSources = hasForgeGameEventApiInLibrarySources(libRoots)
                val hasNeoForgeGameEventsInLibSources = hasNeoForgeNeoforgeEventApiInLibrarySources(libRoots)
                if (mergedJars.isNotEmpty()) {
                    appendLine("=== Minecraft / MDG merged artifacts (Forge or NeoForge) ===")
                    for (path in mergedJars) {
                        appendLine("  $path")
                    }
                    appendLine()
                    val attachReport: SourceAutoAttacher.Report? = SourceAutoAttacher.getLastReport(project)
                    appendLine("=== MDG merged-jar source auto-attach (MixinMCP) ===")
                    if (attachReport == null) {
                        appendLine("  (no report yet — runs ~1.5s after Gradle sync or project open; try mixin_sync_project)")
                    } else {
                        appendLine("  Last run: reason=${attachReport.reason}, epochMs=${attachReport.runAtMillis}")
                        if (attachReport.attached.isNotEmpty()) {
                            appendLine("  Attached Library SOURCES roots:")
                            for (line in attachReport.attached) {
                                appendLine("    $line")
                            }
                        } else if (attachReport.hadMdgMergedCandidates) {
                            appendLine("  No new roots attached (already present or nothing eligible this pass).")
                        }
                        if (attachReport.warnings.isNotEmpty()) {
                            appendLine("  Warnings (auto-attach incomplete — include in bug reports):")
                            for (w in attachReport.warnings) {
                                appendLine("    $w")
                            }
                        }
                    }
                    appendLine()
                    if (hasVanillaInLibSources) {
                        appendLine("Vanilla Minecraft sources ARE available in Library SOURCES roots (merged jar and/or other libs).")
                        appendLine("mixin_search_in_deps CAN search net/minecraft/ files.")
                    } else {
                        appendLine("No vanilla Minecraft .java sentinels (e.g. net/minecraft/world/level/Level.java) were found")
                        appendLine("in any Library SOURCES root. If the auto-attach section shows warnings, fix those first;")
                        appendLine("the merged jar may omit .java when MDG disableRecompilation is true (Gradle *-sources.jar fallback).")
                        appendLine("Fallback: mixin_find_class(includeSource=true), mixin_method_bytecode, mixin_class_bytecode.")
                    }
                    appendLine()
                    when {
                        hasForgeGameEventsInLibSources -> {
                            appendLine("Forge game event API sources (net.minecraftforge.event.*) ARE present in")
                            appendLine("Library SOURCES. mixin_search_in_deps CAN grep net/minecraftforge/event/ paths.")
                        }
                        hasNeoForgeGameEventsInLibSources -> {
                            appendLine("NeoForge game event API sources (net.neoforged.neoforge.event.*) ARE present in")
                            appendLine("Library SOURCES. mixin_search_in_deps CAN grep net/neoforged/neoforge/event/ paths.")
                        }
                        else -> {
                            appendLine("Neither Forge nor NeoForge game-event .java sentinels were found in Library SOURCES")
                            appendLine("(e.g. net/minecraftforge/event/entity/EntityEvent.java, net/neoforged/neoforge/event/Event.java).")
                            if (attachReport?.warnings?.isNotEmpty() == true) {
                                appendLine("MixinMCP MDG source auto-attach reported warnings — see above; include them in bug reports.")
                            } else {
                                appendLine("If sources should be present, confirm Project Structure → Libraries; otherwise use")
                                appendLine("mixin_find_class(includeSource=true) or mixin_search_symbols as a fallback.")
                            }
                        }
                    }
                    appendLine()
                } else if (!hasVanillaInLibSources) {
                    appendLine("=== No Minecraft source roots detected ===")
                    appendLine("Vanilla Minecraft classes were not found in any Library SOURCES root")
                    appendLine("or in a local MDG merged artifact. PSI-based tools (mixin_find_class,")
                    appendLine("type_hierarchy, find_references, etc.) may still work if the classes")
                    appendLine("are on the classpath. Run ./gradlew genSources (Fabric Loom) or")
                    appendLine("./gradlew genDependencySources --force to generate searchable sources.")
                    appendLine()
                } else if (!hasForgeGameEventsInLibSources && !hasNeoForgeGameEventsInLibSources) {
                    appendLine("=== No Forge / NeoForge game API event sources in Library SOURCES ===")
                    appendLine("Forge/NeoForge game-event .java sentinels were not found in any Library SOURCES root.")
                    appendLine("Loader FML/bus -sources.jar trees may still be searchable; universal game API may be missing.")
                    appendLine()
                }

                appendLine("=== Library SOURCES roots (${libRoots.size}) ===")
                appendLine()
                for ((i, info: SourceRootInfo) in libRoots.withIndex()) {
                    appendLine("--- Root ${i + 1}: ${info.typeLabel} ---")
                    appendLine("  URL: ${info.root.url}")
                    val samples: List<String> = collectSamplePaths(info.root, maxSamplesPerRoot)
                    if (samples.isNotEmpty()) {
                        appendLine("  Sample paths:")
                        for (p in samples) {
                            appendLine("    $p")
                        }
                    } else {
                        appendLine("  (no .java files found or root empty)")
                    }
                    appendLine()
                }

                appendLine("=== Decompiled cache roots (${cacheRoots.size}) ===")
                appendLine()
                for ((i, info: SourceRootInfo) in cacheRoots.withIndex()) {
                    appendLine("--- Root ${i + 1}: ${info.typeLabel} ---")
                    appendLine("  URL: ${info.root.url}")
                    val samples: List<String> = collectSamplePaths(info.root, maxSamplesPerRoot)
                    if (samples.isNotEmpty()) {
                        appendLine("  Sample paths:")
                        for (p in samples) {
                            appendLine("    $p")
                        }
                    } else {
                        appendLine("  (empty — dependency may not have classes or decompilation pending)")
                    }
                    appendLine()
                }

                if (roots.isEmpty()) {
                    appendLine("No source roots found. Add dependencies and run ./gradlew genDependencySources for compiled-only jars.")
                }
            }
        }

        return McpToolCallResult.text(result)
    }

    @McpTool
    @McpDescription("Searches dependency/library sources with a Java regex pattern — both published -sources.jar and auto-decompiled. Use this tool to grep across your entire classpath. Results are grouped by file: each group shows the file path, a url: line (pass to mixin_get_dep_source), and matching lines with ||markers||. regexPattern: Java regex — prefer simple single-term patterns; make separate calls for multiple patterns. Escape regex metacharacters if you want literal matching (e.g. use 'addEffect\\(' not 'addEffect('). fileMask: filters which files to search. Without wildcards (* ?) it matches as a case-insensitive substring anywhere in the path (e.g. 'LivingEntity' matches net/minecraft/…/LivingEntity.java). With wildcards, treated as a glob (e.g. '*minecraft*'). pathPrefix: optional — only search files whose logical path starts with this (use forward slashes, e.g. net/minecraft/ or net/minecraftforge/fml/ or net/neoforged/neoforge/). On MDG, MixinMCP auto-attaches merged game jars as Library SOURCES after sync — try this tool first for vanilla/Forge/NeoForge; empty results append hints (check mixin_list_source_roots auto-attach section). roots: all (default) — search Gradle library -sources.jar then MixinMCP cache; when all, cache files are skipped if the same path already matched in library sources (no duplicate FML/vanilla hits). library — only published -sources.jar roots. decompiled — only MixinMCP decompiled cache. timeout: 15s default — set 20000–30000 for broad unfiltered searches. maxResults: 100 default.")
    @Suppress("unused")
    suspend fun mixin_search_in_deps(
        regexPattern: String,
        fileMask: String? = null,
        caseSensitive: Boolean = true,
        maxResults: Int = 100,
        timeout: Long = 15000,
        pathPrefix: String? = null,
        roots: String = "all",
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        val pattern: Pattern = try {
            Pattern.compile(
                regexPattern,
                if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE,
            )
        } catch (e: java.util.regex.PatternSyntaxException) {
            val hint: String = buildString {
                append("Invalid regex: ${e.message}")
                val unescaped = setOf('(', ')', '[', ']', '{', '}', '.', '+', '*', '?', '|', '^', '$')
                val offending = regexPattern.toSet().intersect(unescaped)
                if (offending.isNotEmpty()) {
                    append("\nHint: Escape regex metacharacters with \\\\. ")
                    append("For example: ")
                    append(offending.take(3).joinToString(", ") { "'\\\\$it' instead of '$it'" })
                }
            }
            return McpToolCallResult.error(hint)
        } catch (e: Exception) {
            return McpToolCallResult.error("Invalid regex: ${e.message}")
        }

        val rootsMode: String = roots.trim().lowercase()
        if (rootsMode !in setOf("all", "library", "decompiled")) {
            return McpToolCallResult.error(
                "Invalid roots: \"$roots\". Use all, library, or decompiled.",
            )
        }

        val normalizedPathPrefix: String? = pathPrefix?.trim()?.replace('\\', '/')
            ?.removePrefix("/")
            ?.takeIf { it.isNotEmpty() }

        val matchesMask: (String) -> Boolean = buildFileMaskMatcher(fileMask)

        val startTime: Long = System.currentTimeMillis()
        val scanResult: DepRegexScanResult = ReadAction.compute<DepRegexScanResult, Throwable> {
            val hits: MutableList<DepSearchHit> = mutableListOf()
            var timedOut: Boolean = false
            val pathPrefixFilesSeen: BooleanArray? =
                if (normalizedPathPrefix != null) booleanArrayOf(false) else null

            val allRoots: List<SourceRootInfo> = collectSourceRootsWithMetadata(project)
            val libraryRoots: List<SourceRootInfo> =
                allRoots.filter { it.typeLabel.startsWith("Library SOURCES") }
            val cacheRoots: List<SourceRootInfo> =
                allRoots.filter { it.typeLabel == "Decompiled cache (MixinMCP)" }

            fun scanRoots(rootsToScan: List<SourceRootInfo>, skipPath: (String) -> Boolean) {
                for (info in rootsToScan) {
                    if (System.currentTimeMillis() - startTime > timeout) {
                        timedOut = true
                        return
                    }
                    if (hits.size >= maxResults) return
                    collectRegexHits(
                        info.root,
                        info.root,
                        pattern,
                        matchesMask,
                        hits,
                        maxResults,
                        startTime,
                        timeout,
                        info.typeLabel,
                        normalizedPathPrefix,
                        skipPath,
                        pathPrefixFilesSeen,
                    )
                }
            }

            when (rootsMode) {
                "library" -> scanRoots(libraryRoots, skipPath = { false })
                "decompiled" -> scanRoots(cacheRoots, skipPath = { false })
                else -> {
                    scanRoots(libraryRoots, skipPath = { false })
                    val pathsHitInLibrary: Set<String> = hits.map { it.filePath }.toSet()
                    if (hits.size < maxResults && !timedOut) {
                        scanRoots(cacheRoots, skipPath = { it in pathsHitInLibrary })
                    }
                }
            }
            if (!timedOut && System.currentTimeMillis() - startTime > timeout) timedOut = true

            val noMatchHints: List<String> =
                if (hits.isEmpty() && !timedOut) {
                    buildNoMatchHintsForDepSearch(
                        project,
                        normalizedPathPrefix,
                        sawAnyFileUnderPathPrefix = pathPrefixFilesSeen?.get(0) == true,
                    )
                } else {
                    emptyList()
                }
            DepRegexScanResult(hits = hits.toList(), timedOut = timedOut, noMatchHints = noMatchHints)
        }

        val elapsed: Long = System.currentTimeMillis() - startTime
        val hits: List<DepSearchHit> = scanResult.hits
        val timedOut: Boolean = scanResult.timedOut
        val result: String = buildString {
            appendLine("=== Regex search in dependencies: $regexPattern ===")
            if (normalizedPathPrefix != null) {
                appendLine("(pathPrefix: $normalizedPathPrefix)")
            }
            if (rootsMode != "all") {
                appendLine("(roots: $rootsMode)")
            }
            appendLine()
            if (hits.isEmpty()) {
                appendLine("No matches found.")
                for (line: String in scanResult.noMatchHints) {
                    appendLine(line)
                }
                if (timedOut) {
                    appendLine("(search timed out after ${elapsed}ms — try a more specific pattern, add fileMask, pathPrefix, or increase timeout)")
                }
            } else {
                formatGroupedHits(this, hits)
                if (hits.size >= maxResults) {
                    appendLine("  ... (truncated at $maxResults matches)")
                }
                if (timedOut) {
                    appendLine("  ... (search timed out after ${elapsed}ms — not all files were searched)")
                }
            }
        }

        return McpToolCallResult.text(result)
    }

    @McpTool
    @McpDescription("Reads source from dependency jars or decompiled cache. Use this tool to view library code that grep/read_file cannot access. Pass url (exact url: string from mixin_search_in_deps results — may be jar://…!/path/File.java or file://…/path/File.java) or path (package path with / separators and .java extension, e.g. net/minecraft/world/entity/LivingEntity.java — not a filesystem path). url takes precedence if both given. lineNumber, linesBefore (default 30), linesAfter (default 70) define a window around a specific line.")
    @Suppress("unused")
    suspend fun mixin_get_dep_source(
        url: String? = null,
        path: String? = null,
        lineNumber: Int = 1,
        linesBefore: Int = 30,
        linesAfter: Int = 70,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        if (url.isNullOrBlank() && path.isNullOrBlank()) {
            return McpToolCallResult.error(
                "Missing required parameter. Pass `url` (the jar:// URL from mixin_search_in_deps results) or `path` (e.g. io/redspace/ironsspellbooks/api/util/Utils.java).",
            )
        }

        val vf: VirtualFile? = when {
            !url.isNullOrBlank() -> VirtualFileManager.getInstance().findFileByUrl(url!!)
            else -> locateDepSourceByPath(project, path!!.trim())
        }

        if (vf == null || !vf.isValid) {
            val hint: String = if (!url.isNullOrBlank()) {
                "Pass the exact jar:// URL from mixin_search_in_deps results, or try the `path` parameter (e.g. io/redspace/.../Utils.java)."
            } else {
                val normalizedPath: String = path!!.trim()
                if (normalizedPath.startsWith("net/minecraft/")) {
                    "Vanilla Minecraft classes may not be available via path lookup " +
                        "(they live in the merged jar, not the decompiled cache). " +
                        "Use mixin_find_class with includeSource=true to read the source, " +
                        "or mixin_search_in_deps to get the jar url. " +
                        "If Minecraft sources are missing entirely, the user may need to run " +
                        "./gradlew genSources (Fabric) or ./gradlew genDependencySources --force."
                } else {
                    "Path not found in dependency sources. " +
                        "Use mixin_search_in_deps to find the file, then pass its `url` to this tool."
                }
            }
            return McpToolCallResult.error("File not found. $hint")
        }

        val content: String = try {
            String(vf.contentsToByteArray(), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            return McpToolCallResult.error("Failed to read file: ${e.message}")
        }

        val sourceKind: String = ReadAction.compute<String, Throwable> {
            classifySourceFile(project, vf)
        }

        val lines: List<String> = content.lines()
        val start: Int = (lineNumber - linesBefore).coerceAtLeast(1)
        val end: Int = (lineNumber + linesAfter).coerceAtMost(lines.size)

        val result: String = buildString {
            appendLine("=== ${vf.name} (lines $start-$end) [sourceKind: $sourceKind] ===")
            appendLine()
            for (i in start..end) {
                if (i >= 1 && i <= lines.size) {
                    val marker: String = if (i == lineNumber) ">" else " "
                    appendLine("$marker $i| ${lines[i - 1]}")
                }
            }
        }

        return McpToolCallResult.text(result)
    }
}