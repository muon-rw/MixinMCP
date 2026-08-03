package dev.mixinmcp.tools.source

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue.FALSE
import com.intellij.mcpserver.annotations.McpToolHintValue.TRUE
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.search.PsiShortNamesCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.mixinmcp.tools.projectRelativePath
import dev.mixinmcp.cache.DecompilationCacheService
import dev.mixinmcp.cache.SourceAutoAttacher
import dev.mixinmcp.cache.compareGradlePluginVersions
import dev.mixinmcp.cache.isGradlePluginVersionAtLeast
import dev.mixinmcp.startup.declaredGradlePluginVersion
import dev.mixinmcp.startup.hasGradlePlugin
import dev.mixinmcp.settings.MixinMcpSettings
import dev.mixinmcp.resolve.ClassVariants
import dev.mixinmcp.resolve.FqcnResolver
import dev.mixinmcp.resolve.ModuleScopeResult
import dev.mixinmcp.resolve.ModuleScopes
import dev.mixinmcp.tools.ClassContentDeduper
import dev.mixinmcp.tools.VARIANT_GROUPING_FOOTER
import dev.mixinmcp.tools.requireProject
import kotlin.coroutines.coroutineContext
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Source-navigation tools: FQCN lookup, short-name search, dependency regex
 * grep, dependency source reading, and source-root diagnostics.
 */
@Suppress("FunctionName") // @McpTool functions are snake_case by MCP convention
class SourceNavigationToolset : McpToolset {

    override fun isExperimental(): Boolean = false

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription("Use when you know the exact fully-qualified class name; prefer mixin_search_symbols when the class name is only partially known. Looks up any class by FQCN — project, dependencies, and JDK. Use dots for inner classes (e.g. net.minecraft.world.item.Item.Properties). Returns package, modifiers, supertypes, source location, and SourceKind: Library SOURCES (published -sources.jar or MDG merged jar after MixinMCP auto-attach), Decompiled cache (MixinMCP Vineflower), MDG merged artifact (binary-only / before attach — includeSource may use Fernflower), Loom toolchain artifact (binary under .gradle/loom-cache; genSources provides real sources), Project source (hand-written project code), Buildscript classpath (Gradle plugin or other buildscript dependency), or Classes JAR (binary — prefer mixin_get_dep_source for better source). includeMembers (default true): all methods with signatures, all fields with types, and any nested classes/interfaces/enums/records (with FQCN follow-up calls suggested). For utility classes that organise constants in nested classes (e.g. net.minecraftforge.common.Tags) the Methods/Fields sections may look empty even though the API lives in nested classes — always check the Nested classes section before concluding a class is empty. includeSource: full source code; can be very large for classes like Block/BlockBehaviour. Prefer methodName for a single method's body, or includeMembers for an API overview. methodName: when set, returns ONLY the source of methods with that name (every overload) plus the class header. Skip the includeSource dump for huge classes. fieldName: same idea for a single field declaration. module: pins ALL resolution to one module's classpath (exact or dot-boundary suffix name, e.g. common.main or MyMod.neoforge.main); unknown names list available modules. Without module, when multiple classpath copies of the class differ, a Variants block (bytecode-structural diff per jar) is appended; with module it is suppressed and the pinned module is noted in the header. If the IDE is indexing, the call waits for indexing to finish rather than failing.")
    @Suppress("unused") // Discovered and invoked by MCP framework via reflection
    suspend fun mixin_find_class(
        className: String,
        includeMembers: Boolean = true,
        includeSource: Boolean = false,
        methodName: String? = null,
        fieldName: String? = null,
        module: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val focused: Boolean = !methodName.isNullOrBlank() || !fieldName.isNullOrBlank()

        return smartReadAction(project) {
            val pinned: ModuleScopeResult.Found? = if (module.isNullOrBlank()) {
                null
            } else {
                when (val r = ModuleScopes.resolve(project, module)) {
                    is ModuleScopeResult.Found -> r
                    is ModuleScopeResult.Error -> return@smartReadAction McpToolCallResult.error(r.message)
                }
            }
            val scope: GlobalSearchScope = pinned?.scope ?: GlobalSearchScope.everythingScope(project)
            val pinnedModule: String? = pinned?.module?.name

            val psiClass: PsiClass = FqcnResolver.resolveNested(project, className, scope)
                ?: return@smartReadAction McpToolCallResult.error(
                    if (pinnedModule != null && FqcnResolver.resolveNested(project, className) != null) {
                        "Class $className exists on the classpath but not in the dependency scope of module " +
                            "'$pinnedModule'; drop module= to search the whole project, or pin a different module."
                    } else if (pinnedModule != null) {
                        "Class not found in module '$pinnedModule' or anywhere else on the classpath: $className. " +
                            FqcnResolver.CLASS_NOT_FOUND_HINT
                    } else {
                        "Class not found: $className. ${FqcnResolver.CLASS_NOT_FOUND_HINT}"
                    },
                )

            val text: String = buildString {
                appendLine("=== ${psiClass.qualifiedName} ===")
                if (pinnedModule != null) {
                    appendLine("Pinned module: $pinnedModule (variants suppressed)")
                }
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
                // navigationElement maps compiled classes to their attached -sources.jar file
                val navigationFile: PsiFile? = psiClass.navigationElement.containingFile ?: psiClass.containingFile
                navigationFile?.virtualFile?.let { vf ->
                    val sourceKind = classifySourceFile(project, vf)
                    appendLine("Source: ${projectRelativePath(project, vf)}")
                    appendLine("SourceKind: $sourceKind")
                }
                appendLine()

                if (focused) {
                    if (!methodName.isNullOrBlank()) {
                        appendMethodSource(project, psiClass, methodName)
                    }
                    if (!fieldName.isNullOrBlank()) {
                        appendFieldSource(project, psiClass, fieldName)
                    }
                    return@buildString
                }

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
                    val nested: Array<PsiClass> = psiClass.innerClasses
                    if (nested.isNotEmpty()) {
                        appendLine("--- Nested classes ---")
                        for (inner: PsiClass in nested) {
                            val mods: String = inner.modifierList?.text?.trim() ?: ""
                            val kind: String = when {
                                inner.isInterface -> "interface"
                                inner.isEnum -> "enum"
                                inner.isRecord -> "record"
                                inner.isAnnotationType -> "@interface"
                                else -> "class"
                            }
                            val name: String = inner.name ?: "?"
                            val methodCount: Int = inner.methods.size
                            val fieldCount: Int = inner.fields.size
                            val nestedCount: Int = inner.innerClasses.size
                            val nestedSummary: String = if (nestedCount > 0) ", $nestedCount nested" else ""
                            appendLine("  $mods $kind $name ($fieldCount fields, $methodCount methods$nestedSummary)")
                            inner.qualifiedName?.let { fqcn ->
                                appendLine("    → mixin_find_class(className=\"$fqcn\")")
                            }
                        }
                        appendLine()
                    }
                }

                if (includeSource) {
                    appendLine("--- Source ---")
                    navigationFile?.text?.let { appendLine(it) }
                }
            }

            val variantsFooter: String? = if (pinnedModule == null) {
                ClassVariants.findVariants(project, psiClass.qualifiedName ?: className)
                    ?.let { ClassVariants.renderIfMultiple(it) }
            } else {
                null
            }
            McpToolCallResult.text(
                if (variantsFooter == null) text else text.trimEnd('\n') + "\n\n" + variantsFooter,
            )
        }
    }

    /**
     * Appends source for every method on [psiClass] with the given [name].
     * If the class declares overrides for the method, those win; otherwise
     * the first inherited declaration is shown with an "(inherited from X)"
     * tag so the agent can decide whether to follow up with mixin_super_methods.
     */
    private fun StringBuilder.appendMethodSource(
        project: Project,
        psiClass: PsiClass,
        name: String,
    ) {
        val classQn: String? = psiClass.qualifiedName
        val declared: List<PsiMethod> = psiClass.methods.filter { it.name == name }
        val candidates: List<Pair<PsiMethod, Boolean>> = if (declared.isNotEmpty()) {
            declared.map { it to false }
        } else {
            psiClass.findMethodsByName(name, true).map { it to true }
        }

        if (candidates.isEmpty()) {
            appendLine("--- Method: $name ---")
            val all: List<String> = psiClass.allMethods.map { it.name }.distinct().sorted()
            val close: List<String> = all.filter {
                it.contains(name, ignoreCase = true) || name.contains(it, ignoreCase = true)
            }
            appendLine("  No method named '$name' on ${classQn ?: psiClass.name} (declared or inherited).")
            if (close.isNotEmpty()) {
                appendLine("  Similar names: ${close.joinToString(", ")}")
            }
            return
        }

        for ((idx, pair: Pair<PsiMethod, Boolean>) in candidates.withIndex()) {
            val (method, inherited) = pair
            val params: String = method.parameterList.parameters
                .joinToString(", ") { "${it.type.presentableText} ${it.name}" }
            val ret: String = method.returnType?.presentableText ?: "void"
            val sigSuffix: String = if (candidates.size > 1) " (overload ${idx + 1}/${candidates.size})" else ""
            val inheritedFrom: String? =
                if (inherited) method.containingClass?.qualifiedName ?: method.containingClass?.name else null

            val sourceElement: PsiElement = method.navigationElement
            val text: String? = sourceElement.text
            val lineRange: Pair<Int, Int>? = lineRangeOf(project, sourceElement)
            val rangeSuffix: String = lineRange?.let { (s, e) -> ", lines $s-$e" } ?: ""

            appendLine("--- Method: $ret $name($params)$sigSuffix$rangeSuffix ---")
            if (inheritedFrom != null) {
                appendLine("  (inherited from $inheritedFrom; not declared on ${classQn ?: psiClass.name})")
            }
            if (text == null) {
                appendLine("  (no source available; class is binary, use mixin_method_bytecode)")
                appendLine()
                continue
            }
            val startLine: Int = lineRange?.first ?: 1
            for ((i, line: String) in text.lines().withIndex()) {
                appendLine("  ${startLine + i}| $line")
            }
            appendLine()
        }
    }

    /**
     * Appends the source declaration for a field. Mirrors [appendMethodSource]
     * but for a single field (no overload concept).
     */
    private fun StringBuilder.appendFieldSource(
        project: Project,
        psiClass: PsiClass,
        name: String,
    ) {
        val declared: PsiField? = psiClass.fields.firstOrNull { it.name == name }
        val field: PsiField? = declared ?: psiClass.findFieldByName(name, true)
        val classQn: String? = psiClass.qualifiedName

        if (field == null) {
            appendLine("--- Field: $name ---")
            val all: List<String> = psiClass.allFields.map { it.name }.distinct().sorted()
            val close: List<String> = all.filter {
                it.contains(name, ignoreCase = true) || name.contains(it, ignoreCase = true)
            }
            appendLine("  No field named '$name' on ${classQn ?: psiClass.name} (declared or inherited).")
            if (close.isNotEmpty()) {
                appendLine("  Similar names: ${close.joinToString(", ")}")
            }
            return
        }

        val inherited: Boolean = declared == null
        val inheritedFrom: String? =
            if (inherited) field.containingClass?.qualifiedName ?: field.containingClass?.name else null
        val sourceElement: PsiElement = field.navigationElement
        val lineRange: Pair<Int, Int>? = lineRangeOf(project, sourceElement)
        val rangeSuffix: String = lineRange?.let { (s, e) -> ", lines $s-$e" } ?: ""

        appendLine("--- Field: ${field.type.presentableText} ${field.name}$rangeSuffix ---")
        if (inheritedFrom != null) {
            appendLine("  (inherited from $inheritedFrom; not declared on ${classQn ?: psiClass.name})")
        }
        val text: String? = sourceElement.text
        if (text == null) {
            appendLine("  (no source available; class is binary, use mixin_class_bytecode)")
            appendLine()
            return
        }
        val startLine: Int = lineRange?.first ?: 1
        for ((i, line: String) in text.lines().withIndex()) {
            appendLine("  ${startLine + i}| $line")
        }
        appendLine()
    }

    /**
     * Resolves the 1-based start/end line range of [element] in its containing
     * file. Returns null when the file has no document (binary class) or the
     * offsets fall outside the document length.
     */
    private fun lineRangeOf(
        project: Project,
        element: PsiElement,
    ): Pair<Int, Int>? {
        val file = element.containingFile ?: return null
        val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val range = element.textRange ?: return null
        val start: Int = range.startOffset
        val end: Int = range.endOffset
        if (start < 0 || end > doc.textLength) return null
        val startLine: Int = doc.getLineNumber(start) + 1
        val endLine: Int = doc.getLineNumber((end - 1).coerceAtLeast(start)) + 1
        return startLine to endLine
    }

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription("Use when you don't know the full class name — search by short name substring across project and dependencies. Pass a simple name like 'LivingEntity' or 'getHealth', NOT a fully-qualified name (FQCNs are auto-simplified). kind: class (default), method, field, all. scope: all (default), project, libraries. Results are ranked: exact simple-name matches first, then prefix matches, then substring matches. Returns FQCN for classes, class#method(params) for methods, class.field: type for fields. maxResults defaults to 50. If the IDE is indexing, the call waits for indexing to finish rather than failing.")
    @Suppress("unused")
    suspend fun mixin_search_symbols(
        query: String,
        kind: String = "class",
        scope: String = "all",
        caseSensitive: Boolean = false,
        maxResults: Int = 50,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val kindMode: String = kind.trim().lowercase()
        if (kindMode !in setOf("class", "method", "field", "all")) {
            return McpToolCallResult.error(
                "Invalid kind: \"$kind\". Use class, method, field, or all.",
            )
        }
        val scopeMode: String = scope.trim().lowercase()
        if (scopeMode !in setOf("all", "project", "libraries")) {
            return McpToolCallResult.error(
                "Invalid scope: \"$scope\". Use all, project, or libraries.",
            )
        }

        val searchScope: GlobalSearchScope = when (scopeMode) {
            "project" -> ProjectScope.getContentScope(project)
            "libraries" -> ProjectScope.getLibrariesScope(project)
            else -> GlobalSearchScope.allScope(project)
        }

        val effectiveQuery: String = extractSimpleName(query)

        val result: String = smartReadAction(project) {
            val cache: PsiShortNamesCache = PsiShortNamesCache.getInstance(project)
            val q: String = if (caseSensitive) effectiveQuery else effectiveQuery.lowercase()
            fun rankOf(name: String): Int {
                val n = if (caseSensitive) name else name.lowercase()
                return when {
                    n == q -> 0
                    n.startsWith(q) -> 1
                    n.contains(q) -> 2
                    else -> 3
                }
            }
            fun rankedNames(names: Array<String>): List<String> {
                val ranked: MutableList<Pair<String, Int>> = mutableListOf()
                for (name: String in names) {
                    ProgressManager.checkCanceled()
                    val rank: Int = rankOf(name)
                    if (rank < 3) ranked.add(name to rank)
                }
                return ranked.sortedBy { it.second }.map { it.first }
            }

            buildString {
                if (effectiveQuery != query) {
                    appendLine("(query '$query' looks like an FQCN — searching short names for '$effectiveQuery')")
                    appendLine()
                }

                var annotationEmitted: Boolean = false

                fun renderEntries(entries: List<Pair<String?, String>>, deduper: ClassContentDeduper) {
                    for ((key, line) in entries.take(maxResults)) {
                        val note: String? = deduper.annotationFor(key)
                        if (note != null) annotationEmitted = true
                        appendLine(line + (note ?: ""))
                    }
                    if (entries.size > maxResults) appendLine("  ... (truncated at $maxResults results; more exist)")
                }

                fun emptySectionNote(noNames: Boolean, inScopeCandidates: Int): String = when {
                    noNames || inScopeCandidates == 0 && scopeMode == "all" ->
                        "  (no symbols matching '$effectiveQuery')"
                    inScopeCandidates == 0 ->
                        "  (names matching '$effectiveQuery' exist, but none in scope=$scopeMode; retry with scope=all)"
                    else ->
                        "  (all matches were shaded Gradle-internal duplicates and are hidden)"
                }

                if (kindMode == "class" || kindMode == "all") {
                    appendLine("--- Classes ---")
                    val deduper = ClassContentDeduper()
                    val entries: MutableList<Pair<String?, String>> = mutableListOf()
                    val names: List<String> = rankedNames(cache.allClassNames)
                    var candidates = 0
                    outer@ for (name: String in names) {
                        ProgressManager.checkCanceled()
                        for (c: PsiClass in cache.getClassesByName(name, searchScope)) {
                            candidates++
                            if (isShadedImpldep(c.qualifiedName)) continue
                            if (!deduper.record(c.qualifiedName, c.containingFile?.virtualFile)) continue
                            entries.add(c.qualifiedName to "  ${c.qualifiedName ?: name}")
                            if (entries.size > maxResults) break@outer
                        }
                    }
                    renderEntries(entries, deduper)
                    if (entries.isEmpty()) appendLine(emptySectionNote(names.isEmpty(), candidates))
                    appendLine()
                }

                if (kindMode == "method" || kindMode == "all") {
                    appendLine("--- Methods ---")
                    val deduper = ClassContentDeduper()
                    val entries: MutableList<Pair<String?, String>> = mutableListOf()
                    val names: List<String> = rankedNames(cache.allMethodNames)
                    var candidates = 0
                    outer@ for (name: String in names) {
                        ProgressManager.checkCanceled()
                        for (m: PsiMethod in cache.getMethodsByName(name, searchScope)) {
                            candidates++
                            val declClass: PsiClass? = m.containingClass
                            val ownerFqcn: String? = declClass?.qualifiedName
                            if (isShadedImpldep(ownerFqcn)) continue
                            val signature: String = m.parameterList.parameters
                                .joinToString(",") { it.type.canonicalText }
                            val key: String? = ownerFqcn?.let { "$it#$name($signature)" }
                            if (!deduper.record(key, declClass?.containingFile?.virtualFile)) continue
                            val params: String = m.parameterList.parameters
                                .joinToString(", ") { it.type.presentableText }
                            entries.add(key to "  ${ownerFqcn ?: "?"}#$name($params)")
                            if (entries.size > maxResults) break@outer
                        }
                    }
                    renderEntries(entries, deduper)
                    if (entries.isEmpty()) appendLine(emptySectionNote(names.isEmpty(), candidates))
                    appendLine()
                }

                if (kindMode == "field" || kindMode == "all") {
                    appendLine("--- Fields ---")
                    val deduper = ClassContentDeduper()
                    val entries: MutableList<Pair<String?, String>> = mutableListOf()
                    val names: List<String> = rankedNames(cache.allFieldNames)
                    var candidates = 0
                    outer@ for (name: String in names) {
                        ProgressManager.checkCanceled()
                        for (f: PsiField in cache.getFieldsByName(name, searchScope)) {
                            candidates++
                            val declClass: PsiClass? = f.containingClass
                            val ownerFqcn: String? = declClass?.qualifiedName
                            if (isShadedImpldep(ownerFqcn)) continue
                            val key: String? = ownerFqcn?.let { "$it#${f.name}" }
                            if (!deduper.record(key, declClass?.containingFile?.virtualFile)) continue
                            entries.add(key to "  ${ownerFqcn ?: "?"}.${f.name}: ${f.type.presentableText}")
                            if (entries.size > maxResults) break@outer
                        }
                    }
                    renderEntries(entries, deduper)
                    if (entries.isEmpty()) appendLine(emptySectionNote(names.isEmpty(), candidates))
                }

                if (annotationEmitted) {
                    appendLine(VARIANT_GROUPING_FOOTER)
                }
            }
        }

        return McpToolCallResult.text(result)
    }

    private fun isShadedImpldep(fqcn: String?): Boolean {
        return fqcn?.startsWith("org.gradle.internal.impldep.") == true
    }

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription("Lists all source roots that mixin_search_in_deps and mixin_get_dep_source search — Library SOURCES (-sources.jar, JDK src.zip, other plugins' synthetic library sources) and MixinMCP decompiled cache. Detects MDG merged JARs under build/moddev/; MixinMCP auto-attaches them as Library SOURCES after Gradle sync so vanilla/Forge/NeoForge .java files are usually searchable. Loom toolchains (Fabric Loom, Architectury Loom, neo-loom) instead get sources from their genSources jar or the decompiled cache; no MDG section appears for them. Diagnoses vanilla (net/minecraft/*), Forge game API (net/minecraftforge/event/*), and NeoForge game API (net/neoforged/neoforge/event/*) plus last auto-attach run. Default output is condensed: Minecraft/game roots, roots with warnings, and decompiled-cache roots show full URL plus sample file paths; other library sources roots collapse to a grouped jar-name list. A Buildscript classpath section lists Gradle plugin / buildSrc / Gradle API sources roots (searched last by mixin_search_in_deps, or alone via roots=buildscript); an empty section usually means the indexBuildscriptClasspath setting is off or the project has not synced, not a failure. verbose: true restores full per-root URL and sample paths for every root. maxSamplesPerRoot: 5 default.")
    @Suppress("unused")
    suspend fun mixin_list_source_roots(
        maxSamplesPerRoot: Int = 5,
        verbose: Boolean = false,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val result: String = smartReadAction(project) {
            val roots: List<SourceRootInfo> = collectSourceRootsWithMetadata(project)
            buildString {
                appendLine("=== Source roots (mixin_search_in_deps / mixin_get_dep_source scope) ===")
                appendLine()
                appendLine("These roots are searched by mixin_search_in_deps and mixin_get_dep_source.")
                appendLine("Prefer mixin_search_in_deps first for net/minecraft/, net/minecraftforge/, and net/neoforged/ —")
                appendLine("MixinMCP auto-attaches MDG merged jars as Library SOURCES after Gradle sync (see section below).")
                appendLine()
                appendLine("If vanilla Minecraft (net/minecraft/*) is still missing from search results:")
                appendLine("  - Loom toolchains (Fabric Loom, Architectury Loom, neo-loom): run ./gradlew genSources to generate Minecraft sources")
                appendLine("  - MDG: confirm Gradle sync finished and check the MDG auto-attach section for warnings")
                appendLine("  - Any loader: run ./gradlew genDependencySources --force to decompile large JARs")
                appendLine("  - Then call mixin_sync_project to refresh IntelliJ's project model")
                appendLine()
                appendLine("If Forge game API (net/minecraftforge/event/*) or NeoForge (net/neoforged/neoforge/event/*) is still missing:")
                appendLine("  - On MDG: read the \"MDG merged-jar source auto-attach\" section below (warnings mean attachment failed).")
                appendLine("  - On a Loom-style Forge/NeoForge toolchain (neo-loom, Architectury Loom): run ./gradlew genSources; no MDG section will appear and that is expected.")
                appendLine("  - Fallback: mixin_find_class(includeSource=true), mixin_search_symbols, or mixin_search_in_deps without pathPrefix.")
                appendLine()

                val libRoots = roots.filter { it.typeLabel.startsWith("Library SOURCES") }
                val cacheRoots = roots.filter { it.typeLabel == "Decompiled cache (MixinMCP)" }
                val buildscriptRoots = roots.filter { it.typeLabel.startsWith(BUILDSCRIPT_LABEL_PREFIX) }

                val mergedJars = detectMergedJars(project)
                val hasVanillaInLibSources = libRoots.any { info: SourceRootInfo ->
                    libraryRootContainsAnySentinelJava(info.root, VANILLA_LIBRARY_SOURCE_SENTINELS)
                }
                val hasForgeGameEventsInLibSources = hasForgeGameEventApiInLibrarySources(libRoots)
                val hasNeoForgeGameEventsInLibSources = hasNeoForgeNeoforgeEventApiInLibrarySources(libRoots)
                if (mergedJars.isNotEmpty()) {
                    appendLine("=== Minecraft / MDG merged artifacts ===")
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
                        appendLine("No vanilla Minecraft .java sentinels (mojmap net/minecraft/world/level/Level.java or")
                        appendLine("yarn net/minecraft/world/World.java) were found")
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
                    appendLine("are on the classpath. Recovery by toolchain:")
                    appendLine("  - Loom toolchains (Fabric Loom, Architectury Loom, neo-loom): ./gradlew genSources")
                    appendLine("  - NeoForge/Forge via MDG: ./gradlew downloadAssets")
                    appendLine("  - Any loader: ./gradlew genDependencySources --force to generate searchable sources")
                    appendLine("Then call mixin_sync_project to refresh IntelliJ's project model.")
                    appendLine()
                } else if (!hasForgeGameEventsInLibSources && !hasNeoForgeGameEventsInLibSources) {
                    appendLine("=== No Forge / NeoForge game API event sources in Library SOURCES ===")
                    appendLine("Forge/NeoForge game-event .java sentinels were not found in any Library SOURCES root.")
                    appendLine("Loader FML/bus -sources.jar trees may still be searchable; universal game API may be missing.")
                    appendLine()
                }

                fun appendRootDetail(index: Int, info: SourceRootInfo, emptyNote: String) {
                    appendLine("--- Root $index: ${info.typeLabel} ---")
                    appendLine("  URL: ${info.root.url}")
                    val samples: List<String> = collectSamplePaths(info.root, maxSamplesPerRoot)
                    if (samples.isNotEmpty()) {
                        appendLine("  Sample paths:")
                        for (p in samples) {
                            appendLine("    $p")
                        }
                    } else {
                        appendLine(emptyNote)
                    }
                    appendLine()
                }

                val libEmptyNote = "  (no .java files found or root empty)"
                if (verbose) {
                    appendLine("=== Library SOURCES roots (${libRoots.size}) ===")
                    appendLine()
                    for ((i, info: SourceRootInfo) in libRoots.withIndex()) {
                        appendRootDetail(i + 1, info, libEmptyNote)
                    }
                } else {
                    val (gameRoots, otherRoots) = libRoots.partition { isGameSourceRoot(project, it.root) }
                    val (emptyRoots, genericRoots) = otherRoots.partition {
                        collectSamplePaths(it.root, 1).isEmpty()
                    }

                    appendLine("=== Minecraft / game Library SOURCES roots (${gameRoots.size} of ${libRoots.size}) ===")
                    appendLine()
                    if (gameRoots.isEmpty()) {
                        appendLine("  (none detected)")
                        appendLine()
                    }
                    for ((i, info: SourceRootInfo) in gameRoots.withIndex()) {
                        appendRootDetail(i + 1, info, libEmptyNote)
                    }

                    if (emptyRoots.isNotEmpty()) {
                        appendLine("=== Library SOURCES roots with warnings (${emptyRoots.size}) ===")
                        appendLine()
                        for ((i, info: SourceRootInfo) in emptyRoots.withIndex()) {
                            appendRootDetail(i + 1, info, libEmptyNote)
                        }
                    }

                    appendLine("=== Other library sources roots (${genericRoots.size}) ===")
                    appendLine("  (jar names only; pass verbose=true for per-root URLs and sample paths)")
                    val nameCounts: Map<String, Int> = genericRoots
                        .groupingBy { sourceRootDisplayName(it.root) }
                        .eachCount()
                    val names: List<String> = nameCounts.entries
                        .sortedBy { it.key.lowercase() }
                        .map { (n, c) -> if (c > 1) "$n (x$c)" else n }
                    val cap = 50
                    for (chunk: List<String> in names.take(cap).chunked(3)) {
                        appendLine("  ${chunk.joinToString(", ")}")
                    }
                    if (names.size > cap) {
                        appendLine("  and ${names.size - cap} more (verbose=true lists all)")
                    }
                    appendLine()
                }

                appendLine("=== Decompiled cache roots (${cacheRoots.size}) ===")
                appendLine()
                for ((i, info: SourceRootInfo) in cacheRoots.withIndex()) {
                    appendRootDetail(i + 1, info, "  (empty — dependency may not have classes or decompilation pending)")
                }
                val cacheStats = DecompilationCacheService.getInstance(project).lastScanStats
                val projectRootPath: java.nio.file.Path? = project.basePath?.let { java.nio.file.Path.of(it) }
                val pluginPresent: Boolean = projectRootPath != null && hasGradlePlugin(projectRootPath)
                if (cacheRoots.isEmpty()) {
                    when {
                        cacheStats.noVirtualFile > 0 -> {
                            appendLine("  (none attached, but ${cacheStats.noVirtualFile} cache entries exist on disk;")
                            appendLine("   run mixin_sync_project to attach them, no re-decompile needed)")
                        }
                        pluginPresent -> {
                            appendLine("  (none: the dev.mixinmcp.decompile plugin is applied but no cache entries exist;")
                            appendLine("   run ./gradlew genDependencySources, then mixin_sync_project)")
                        }
                        else -> {
                            appendLine("  (none: dependencies without published sources are not searchable; apply the")
                            appendLine("   dev.mixinmcp.decompile Gradle plugin and run ./gradlew genDependencySources)")
                        }
                    }
                    appendLine()
                } else if (pluginPresent) {
                    val required: String = DecompilationCacheService.REQUIRED_GRADLE_PLUGIN_VERSION
                    val installed: String? = listOfNotNull(
                        DecompilationCacheService.getInstance(project).installedGradlePluginVersion(),
                        declaredGradlePluginVersion(projectRootPath),
                    ).maxWithOrNull(::compareGradlePluginVersions)
                    if (!isGradlePluginVersionAtLeast(installed, required)) {
                        appendLine("  Caveat: applied Gradle plugin (${installed ?: "pre-$required"}) is older than $required;")
                        appendLine("  cache entries for some dependencies may be missing (affects only dependencies without")
                        appendLine("  a published -sources.jar). Update dev.mixinmcp.decompile and rerun ./gradlew genDependencySources.")
                        appendLine()
                    }
                }

                appendLine("=== Buildscript classpath source roots (${buildscriptRoots.size}) ===")
                appendLine("  (Gradle plugins, buildSrc, Gradle API; searched last by mixin_search_in_deps, or alone via roots=buildscript)")
                val buildscriptNames: List<String> = buildscriptRoots
                    .groupingBy { sourceRootDisplayName(it.root) }
                    .eachCount()
                    .entries
                    .sortedBy { it.key.lowercase() }
                    .map { (n, c) -> if (c > 1) "$n (x$c)" else n }
                for (chunk: List<String> in buildscriptNames.take(50).chunked(3)) {
                    appendLine("  ${chunk.joinToString(", ")}")
                }
                if (buildscriptNames.size > 50) {
                    appendLine("  and ${buildscriptNames.size - 50} more")
                }
                if (buildscriptRoots.isEmpty()) {
                    if (!MixinMcpSettings.getInstance(project).indexBuildscriptClasspath) {
                        appendLine("  (none: buildscript indexing is disabled in Settings | Tools | MixinMCP)")
                    } else {
                        appendLine("  (none: IDE Gradle support unavailable or project not yet synced; build plugins")
                        appendLine("   without published sources also need the dev.mixinmcp.decompile Gradle plugin")
                        appendLine("   and ./gradlew genDependencySources)")
                    }
                }
                appendLine()

                if (roots.isEmpty()) {
                    appendLine("No source roots found. Add dependencies and run ./gradlew genDependencySources for compiled-only jars.")
                }
            }
        }

        return McpToolCallResult.text(result)
    }

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription("Searches dependency/library sources with a Java regex pattern — both published -sources.jar and auto-decompiled. Use this tool to grep across your entire classpath, including JDK src.zip (project SDK) and synthetic library sources contributed by other plugins. Results are grouped by file: each group shows the file path, a url: line (pass to mixin_get_dep_source), and matching lines with ||markers||. regexPattern: Java regex — prefer simple single-term patterns; make separate calls for multiple patterns. Escape regex metacharacters if you want literal matching (e.g. use 'addEffect\\(' not 'addEffect('). fileMask: filters which files to search. Without wildcards (* ?) it matches as a case-insensitive substring anywhere in the path (e.g. 'LivingEntity' matches net/minecraft/…/LivingEntity.java). With wildcards, treated as a glob (e.g. '*minecraft*'). pathPrefix: optional — only search files whose logical path starts with this (use forward slashes, e.g. net/minecraft/ or net/minecraftforge/fml/ or net/neoforged/neoforge/). On MDG, MixinMCP auto-attaches merged game jars as Library SOURCES after sync — try this tool first for vanilla/Forge/NeoForge; on Loom toolchains vanilla comes from the genSources jar or the decompiled cache; empty results append hints (check mixin_list_source_roots auto-attach section). roots: all (default) — search Gradle library -sources.jar, then MixinMCP cache, then buildscript classpath last; later tiers skip paths already matched (no duplicate hits). library — only published -sources.jar roots (incl. JDK src.zip). decompiled — only MixinMCP decompiled cache. buildscript — only Gradle buildscript classpath sources (Loom, ModDevGradle, mod-publish-plugin and other build plugins). timeout: 15s default — set 20000–30000 for broad unfiltered searches. maxResults: 100 default. contextLines: include N lines of context around each match (default 0). Use small values (3–10) to capture short method bodies inline so you don't need a follow-up mixin_get_dep_source call; max 200. Match lines are prefixed with `>`, context lines with two spaces; overlapping windows are merged per file. If the IDE is indexing, the call waits for indexing to finish rather than failing.")
    @Suppress("unused")
    suspend fun mixin_search_in_deps(
        regexPattern: String,
        fileMask: String? = null,
        caseSensitive: Boolean = true,
        maxResults: Int = 100,
        timeout: Long = 15000,
        pathPrefix: String? = null,
        roots: String = "all",
        contextLines: Int = 0,
    ): McpToolCallResult {
        if (contextLines < 0 || contextLines > 200) {
            return McpToolCallResult.error("contextLines must be between 0 and 200 (got $contextLines)")
        }
        if (timeout < 1000) {
            return McpToolCallResult.error(
                "timeout is in milliseconds; minimum 1000 (got $timeout)",
            )
        }
        val project = coroutineContext.requireProject { return it }

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
        if (rootsMode !in setOf("all", "library", "decompiled", "buildscript")) {
            return McpToolCallResult.error(
                "Invalid roots: \"$roots\". Use all, library, decompiled, or buildscript.",
            )
        }

        val normalizedPathPrefix: String? = pathPrefix?.trim()?.replace('\\', '/')
            ?.removePrefix("/")
            ?.takeIf { it.isNotEmpty() }

        val matchesMask: (String) -> Boolean = buildFileMaskMatcher(fileMask)

        val requestStart: Long = System.currentTimeMillis()
        // Scan one hit past the cap so the truncation footer can state definitively
        // whether more matches exist, without counting everything past the cap.
        val scanCap: Int = if (maxResults == Int.MAX_VALUE) maxResults else maxResults + 1
        val scanResult: DepRegexScanResult = smartReadAction(project) {
            val startTime: Long = System.currentTimeMillis()
            val hits: MutableList<DepSearchHit> = mutableListOf()
            var timedOut: Boolean = false
            val scannedFiles = IntArray(1)

            val allRoots: List<SourceRootInfo> = collectSourceRootsWithMetadata(project)
            val libraryRoots: List<SourceRootInfo> =
                allRoots.filter { it.typeLabel.startsWith("Library SOURCES") }
            val cacheRoots: List<SourceRootInfo> =
                allRoots.filter { it.typeLabel == "Decompiled cache (MixinMCP)" }
            val buildscriptRoots: List<SourceRootInfo> =
                allRoots.filter { it.typeLabel.startsWith(BUILDSCRIPT_LABEL_PREFIX) }

            fun scanRoots(rootsToScan: List<SourceRootInfo>, skipPath: (String) -> Boolean) {
                for (info in rootsToScan) {
                    if (System.currentTimeMillis() - startTime > timeout) {
                        timedOut = true
                        return
                    }
                    if (hits.size >= scanCap) return
                    collectRegexHits(
                        info.root,
                        info.root,
                        pattern,
                        matchesMask,
                        hits,
                        scanCap,
                        startTime,
                        timeout,
                        info.typeLabel,
                        normalizedPathPrefix,
                        skipPath,
                        scannedFiles,
                    )
                }
            }

            when (rootsMode) {
                "library" -> scanRoots(libraryRoots, skipPath = { false })
                "decompiled" -> scanRoots(cacheRoots, skipPath = { false })
                "buildscript" -> scanRoots(buildscriptRoots, skipPath = { false })
                else -> {
                    scanRoots(libraryRoots, skipPath = { false })
                    val pathsHitInLibrary: Set<String> = hits.map { it.filePath }.toSet()
                    if (hits.size < scanCap && !timedOut) {
                        scanRoots(cacheRoots, skipPath = { it in pathsHitInLibrary })
                    }
                    // Buildscript roots scan last: game and mod classpath hits stay first
                    // and duplicate paths already found are not repeated.
                    val pathsHit: Set<String> = hits.map { it.filePath }.toSet()
                    if (hits.size < scanCap && !timedOut) {
                        scanRoots(buildscriptRoots, skipPath = { it in pathsHit })
                    }
                }
            }
            if (!timedOut && System.currentTimeMillis() - startTime > timeout) timedOut = true

            val noMatchHints: List<String> =
                if (hits.isEmpty() && !timedOut) {
                    val base: List<String> = buildNoMatchHintsForDepSearch(
                        project,
                        normalizedPathPrefix,
                        rootsMode,
                    )
                    val zeroTierNotice: String? = when {
                        rootsMode == "buildscript" && buildscriptRoots.isEmpty() -> {
                            val cause: String =
                                if (!MixinMcpSettings.getInstance(project).indexBuildscriptClasspath) {
                                    "buildscript indexing is disabled in Settings | Tools | MixinMCP."
                                } else {
                                    "IDE Gradle support unavailable or project not yet synced. Build plugins " +
                                        "without published sources also need the MixinMCP Gradle plugin: apply " +
                                        "dev.mixinmcp.decompile and run ./gradlew genDependencySources."
                                }
                            "No buildscript classpath roots are available; nothing was searched. $cause"
                        }
                        rootsMode == "library" && libraryRoots.isEmpty() ->
                            "No Library SOURCES roots are attached; nothing was searched for roots=library. " +
                                "Run mixin_list_source_roots for diagnostics."
                        rootsMode == "decompiled" && cacheRoots.isEmpty() ->
                            "No decompiled cache roots are attached; nothing was searched for roots=decompiled. " +
                                "Run mixin_list_source_roots for diagnostics."
                        else -> null
                    }
                    if (zeroTierNotice != null) base + zeroTierNotice else base
                } else {
                    emptyList()
                }
            DepRegexScanResult(
                hits = hits.take(maxResults),
                timedOut = timedOut,
                noMatchHints = noMatchHints,
                scannedFiles = scannedFiles[0],
                sawMore = hits.size > maxResults,
            )
        }

        val elapsed: Long = System.currentTimeMillis() - requestStart
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
                if (timedOut) {
                    appendLine("Search INCOMPLETE (timed out after ${elapsed}ms); not all files were searched, so this is not a confirmed negative.")
                    appendLine("No matches in the ${scanResult.scannedFiles} files scanned before the cutoff. Retry with a more specific pattern, fileMask, or pathPrefix, or increase timeout.")
                } else {
                    appendLine("No matches found.")
                    appendLine(describeEmptyScan(scanResult.scannedFiles, fileMask, normalizedPathPrefix))
                    for (line: String in scanResult.noMatchHints) {
                        appendLine(line)
                    }
                }
            } else {
                if (timedOut) {
                    appendLine("Search INCOMPLETE (timed out after ${elapsed}ms); not all files were searched, results below are partial.")
                    appendLine()
                }
                formatGroupedHitsWithContext(this, hits, contextLines)
                if (scanResult.sawMore) {
                    appendLine("  ... (truncated at $maxResults matches; more exist)")
                } else if (timedOut && hits.size >= maxResults) {
                    appendLine("  ... (stopped at $maxResults matches; unscanned files may hold more)")
                }
            }
        }

        return McpToolCallResult.text(result)
    }

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription("Reads source from dependency jars or decompiled cache. Use this tool to view library code that grep/read_file cannot access. Pass url (exact url: string from mixin_search_in_deps results — may be jar://…!/path/File.java or file://…/path/File.java) or path (package path with / separators and .java extension, e.g. net/minecraft/world/entity/LivingEntity.java — not a filesystem path). url takes precedence if both given. lineNumber, linesBefore (default 30), linesAfter (default 70) define a window around a specific line. module: restricts the path lookup to source roots on that module's classpath (exact or dot-boundary suffix name, e.g. common.main or MyMod.neoforge.main); url lookups only validate the name.")
    @Suppress("unused")
    suspend fun mixin_get_dep_source(
        url: String? = null,
        path: String? = null,
        lineNumber: Int = 1,
        linesBefore: Int = 30,
        linesAfter: Int = 70,
        module: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        if (url.isNullOrBlank() && path.isNullOrBlank()) {
            return McpToolCallResult.error(
                "Missing required parameter. Pass `url` (the jar:// URL from mixin_search_in_deps results) or `path` (e.g. io/redspace/ironsspellbooks/api/util/Utils.java).",
            )
        }

        val moduleResult: ModuleScopeResult? = if (module.isNullOrBlank()) {
            null
        } else {
            smartReadAction(project) { ModuleScopes.resolve(project, module) }
        }
        if (moduleResult is ModuleScopeResult.Error) {
            return McpToolCallResult.error(moduleResult.message)
        }
        val pinned: ModuleScopeResult.Found? = moduleResult as? ModuleScopeResult.Found

        val trimmedPath: String? = path?.trim()?.takeIf { it.isNotEmpty() }
        val fromUrl: VirtualFile? =
            if (!url.isNullOrBlank()) VirtualFileManager.getInstance().findFileByUrl(url) else null
        val urlFailed: Boolean = !url.isNullOrBlank() && (fromUrl == null || !fromUrl.isValid)
        val vf: VirtualFile? = when {
            fromUrl != null && fromUrl.isValid -> fromUrl
            trimmedPath != null -> smartReadAction(project) { locateDepSourceByPath(project, trimmedPath, pinned?.scope) }
            else -> null
        }
        val viaPathFallback: Boolean = urlFailed && vf != null && vf.isValid

        if (vf == null || !vf.isValid) {
            fun pathMissHint(normalizedPath: String, rootsTotal: Int): String {
                if (normalizedPath.startsWith("net/minecraft/")) {
                    return "Vanilla Minecraft classes may not be available via path lookup: on MDG they live in the " +
                        "merged jar; on Loom toolchains they come from the genSources jar, or from the decompiled " +
                        "cache when genSources has not run. " +
                        "Use mixin_find_class with includeSource=true to read the source, " +
                        "or mixin_search_in_deps to get the jar url. " +
                        "If Minecraft sources are missing entirely, the user may need to run " +
                        "./gradlew genSources (Loom toolchains) or ./gradlew genDependencySources --force."
                }
                if (rootsTotal == 0) {
                    return "No dependency source roots are attached; nothing was searched. " +
                        "Run mixin_list_source_roots for diagnostics; sources may require " +
                        "./gradlew genDependencySources (or genSources on Loom toolchains)."
                }
                val pinHint: String = pinned?.let {
                    " Module pin '${it.module.name}' restricts the lookup to that module's classpath; drop module= to search all roots." +
                        " Module pinning always excludes decompiled-cache and buildscript-classpath roots (synthetic roots with no module order entries), so drop module= for those paths."
                } ?: ""
                return "Path not found in any of the $rootsTotal dependency source roots searched. " +
                    "Use mixin_search_in_deps to find the file, then pass its `url` to this tool.$pinHint"
            }

            val rootsTotal: Int =
                if (trimmedPath != null) smartReadAction(project) { collectAllSourceRoots(project).size } else 0
            val hint: String = when {
                urlFailed && trimmedPath != null ->
                    "url `$url` did not resolve, and path `$trimmedPath` was not found either. " +
                        pathMissHint(trimmedPath, rootsTotal)
                urlFailed ->
                    "Pass the exact jar:// URL from mixin_search_in_deps results, or try the `path` parameter (e.g. io/redspace/.../Utils.java)."
                else -> pathMissHint(trimmedPath!!, rootsTotal)
            }
            return McpToolCallResult.error("File not found. $hint")
        }

        val content: String = try {
            withContext(Dispatchers.IO) { String(vf.contentsToByteArray(), StandardCharsets.UTF_8) }
        } catch (e: Exception) {
            return McpToolCallResult.error("Failed to read file: ${e.message}")
        }

        val sourceKind: String = smartReadAction(project) {
            classifySourceFile(project, vf)
        }

        val lines: List<String> = content.lines()
        val outOfRange: Boolean = lineNumber < 1 || lineNumber > lines.size
        val anchor: Int = lineNumber.coerceIn(1, lines.size)
        val start: Int = (anchor - linesBefore).coerceAtLeast(1)
        val end: Int = (anchor + linesAfter).coerceAtMost(lines.size)

        val result: String = buildString {
            if (viaPathFallback) {
                appendLine("(url `$url` did not resolve; located via the `path` parameter instead. The url may be stale; re-run mixin_search_in_deps for a fresh one.)")
            }
            if (outOfRange) {
                appendLine("(requested line $lineNumber is out of range; ${vf.name} has ${lines.size} lines; showing lines $start-$end. Line numbers may be stale; re-run mixin_search_in_deps.)")
            }
            val modSuffix: String = pinned?.let { " [pinned module: ${it.module.name}]" } ?: ""
            appendLine("=== ${vf.name} (lines $start-$end) [sourceKind: $sourceKind]$modSuffix ===")
            appendLine()
            for (i in start..end) {
                val marker: String = if (i == lineNumber) ">" else " "
                appendLine("$marker $i| ${lines[i - 1]}")
            }
        }

        return McpToolCallResult.text(result)
    }
}
