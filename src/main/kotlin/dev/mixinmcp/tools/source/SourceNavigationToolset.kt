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
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
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
import dev.mixinmcp.tools.resolveAgainstBase
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
    @McpDescription("Use when you know the exact fully-qualified class name; prefer mixin_search_symbols when the class name is only partially known. Looks up any class by FQCN — project, dependencies, and JDK. Use dots for inner classes (e.g. net.minecraft.world.item.Item.Properties). Returns package, modifiers, supertypes, source location, and SourceKind: Library SOURCES (published -sources.jar or MDG merged jar after MixinMCP auto-attach), Decompiled cache (MixinMCP Vineflower), MDG merged artifact (binary-only / before attach — includeSource may use Fernflower), Loom toolchain artifact (binary under .gradle/loom-cache; genSources provides real sources), Project source (hand-written project code), Buildscript classpath (Gradle plugin or other buildscript dependency), or Classes JAR (binary — prefer mixin_get_dep_source for better source). includeMembers (default true): all methods with signatures, all fields with types, and any nested classes/interfaces/enums/records (with FQCN follow-up calls suggested). For utility classes that organise constants in nested classes (e.g. net.minecraftforge.common.Tags) the Methods/Fields sections may look empty even though the API lives in nested classes — always check the Nested classes section before concluding a class is empty. includeSource: full source code; can be very large for classes like Block/BlockBehaviour. Prefer methodName for a single method's body, or includeMembers for an API overview. methodName: when set, returns ONLY the source of methods with that name (every overload) plus the class header. Skip the includeSource dump for huge classes. fieldName: same idea for a single field declaration. The header's Modules line lists the modules whose classpath provides the class, tagged (RUNTIME) when that module cannot compile against the class through Gradle, or (TEST) when only its test sources can; compileOnly dependencies are compile-visible and stay untagged. module: pins ALL resolution to one module's compile scope (exact or dot-boundary suffix name, e.g. common.main or MyMod.neoforge.main); unknown names list available modules. A class found without module= but rejected with it is not compile-visible to that module (declared runtimeOnly or test-only there, or only on another module), so a mixin in that module fails the Gradle build even when inspections pass. Without module, when multiple classpath copies of the class differ, a Variants block (bytecode-structural diff per jar, with the same scope tags) is appended; with module it is suppressed and the pinned module is noted in the header. If the IDE is indexing, the call waits for indexing to finish rather than failing.")
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
                        "Class $className exists on the classpath but not in the compile scope of module " +
                            "'$pinnedModule' (declared runtimeOnly or test-only there, or only on another module), so " +
                            "that module cannot compile against it; drop module= to search the whole project, or pin " +
                            "a different module."
                    } else if (pinnedModule != null) {
                        FqcnResolver.notFoundMessage(
                            project,
                            className,
                            "Class not found in module '$pinnedModule' or anywhere else on the classpath: $className",
                        )
                    } else {
                        FqcnResolver.notFoundMessage(project, className)
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
                // navigationElement maps compiled classes to their attached -sources.jar file. Binary-only
                // classes fall back to the decompiled-cache copy, so line numbers match mixin_get_dep_source
                // and mixin_search_in_deps instead of the IDE decompiler's.
                val navigationFile: PsiFile? = psiClass.navigationElement.containingFile ?: psiClass.containingFile
                val binaryOnly: Boolean = navigationFile?.virtualFile?.extension.equals("class", ignoreCase = true)
                val cacheClass: PsiClass? = if (binaryOnly) decompiledCacheClass(project, psiClass) else null
                val sourceFile: PsiFile? = cacheClass?.containingFile ?: navigationFile
                sourceFile?.virtualFile?.let { vf ->
                    appendLine("Source: ${projectRelativePath(project, vf)}")
                    appendLine("SourceKind: ${classifySourceFile(project, vf)}")
                }
                if (binaryOnly && cacheClass == null && (focused || includeSource)) {
                    appendLine(
                        "Source view: IDE decompiler (no attached or decompiled-cache source); " +
                            "mixin_get_dep_source(className=...) shows the same text and line numbers",
                    )
                }
                val owners: List<String> = ClassVariants.ownerModules(project, psiClass.containingFile?.virtualFile)
                if (owners.isNotEmpty()) {
                    appendLine("Modules: ${ClassVariants.explainScopeTags(owners).joinToString(", ")}")
                }
                appendLine()

                if (focused) {
                    val sourceClass: PsiClass = cacheClass ?: psiClass
                    if (!methodName.isNullOrBlank()) {
                        appendMethodSource(project, sourceClass, methodName)
                    }
                    if (!fieldName.isNullOrBlank()) {
                        appendFieldSource(project, sourceClass, fieldName)
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
                    sourceFile?.text?.let { appendLine(it) }
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
    @McpDescription("Lists all source roots that mixin_search_in_deps and mixin_get_dep_source search: Library SOURCES (-sources.jar, JDK src.zip, other plugins' synthetic library sources) and MixinMCP decompiled cache. filter: answer 'is jar X attached?' in one call: a case-insensitive substring (or * ? glob) matched against each root's label, jar name, Maven coordinates, and URL (e.g. 'jade', 'snownee:jade', 'neoforge'); only matching roots are printed, with URL and sample paths, and the diagnostics below are skipped. Detects MDG merged JARs under build/moddev/; MixinMCP auto-attaches them as Library SOURCES after Gradle sync so vanilla/Forge/NeoForge .java files are usually searchable. Loom toolchains (Fabric Loom, Architectury Loom, neo-loom) instead get sources from their genSources jar or the decompiled cache; no MDG section appears for them. Diagnoses vanilla (net/minecraft/*), Forge game API (net/minecraftforge/event/*), and NeoForge game API (net/neoforged/neoforge/event/*) plus last auto-attach run. Default output is condensed: Minecraft/game roots and roots with warnings show full URL plus sample file paths; other library sources roots and decompiled-cache roots collapse to grouped name lists (decompiled roots are named by Maven coordinates and jar file name). A Buildscript classpath section lists Gradle plugin / buildSrc / Gradle API sources roots (searched last by mixin_search_in_deps, or alone via roots=buildscript); an empty section usually means the indexBuildscriptClasspath setting is off or the project has not synced, not a failure. verbose: true restores full per-root URL and sample paths for every root. maxSamplesPerRoot: 5 default.")
    @Suppress("unused")
    suspend fun mixin_list_source_roots(
        maxSamplesPerRoot: Int = 5,
        verbose: Boolean = false,
        filter: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val filterMask: String? = filter?.trim()?.takeIf { it.isNotEmpty() }
        val result: String = smartReadAction(project) {
            val roots: List<SourceRootInfo> = collectSourceRootsWithMetadata(project)
            if (filterMask != null) return@smartReadAction renderFilteredRoots(roots, filterMask, maxSamplesPerRoot)
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

                val libRoots = roots.filter { isLibrarySourcesLabel(it.typeLabel) }
                val cacheRoots = roots.filter { isDecompiledCacheLabel(it.typeLabel) }
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

                fun appendNameGrid(names: List<String>) {
                    val counted: List<String> = names.groupingBy { it }.eachCount().entries
                        .sortedBy { it.key.lowercase() }
                        .map { (n, c) -> if (c > 1) "$n (x$c)" else n }
                    val cap = 50
                    for (chunk: List<String> in counted.take(cap).chunked(3)) {
                        appendLine("  ${chunk.joinToString(", ")}")
                    }
                    if (counted.size > cap) {
                        appendLine("  and ${counted.size - cap} more (verbose=true lists all; filter=<name> finds one)")
                    }
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
                    appendLine("  (jar names only; pass verbose=true for per-root URLs and sample paths, or filter=<name> for one)")
                    appendNameGrid(genericRoots.map { sourceRootDisplayName(it.root) })
                    appendLine()
                }

                val cacheEmptyNote = "  (empty; dependency may not have classes or decompilation pending)"
                if (verbose) {
                    appendLine("=== Decompiled cache roots (${cacheRoots.size}) ===")
                    appendLine()
                    for ((i, info: SourceRootInfo) in cacheRoots.withIndex()) {
                        appendRootDetail(i + 1, info, cacheEmptyNote)
                    }
                } else {
                    val (emptyCacheRoots, populatedCacheRoots) = cacheRoots.partition {
                        collectSamplePaths(it.root, 1).isEmpty()
                    }
                    if (emptyCacheRoots.isNotEmpty()) {
                        appendLine("=== Decompiled cache roots with warnings (${emptyCacheRoots.size}) ===")
                        appendLine()
                        for ((i, info: SourceRootInfo) in emptyCacheRoots.withIndex()) {
                            appendRootDetail(i + 1, info, cacheEmptyNote)
                        }
                    }
                    appendLine("=== Decompiled cache roots (${populatedCacheRoots.size}) ===")
                    appendLine("  (dependency names only; pass verbose=true for per-root URLs and sample paths, or filter=<name> for one)")
                    appendNameGrid(populatedCacheRoots.map { cacheRootDisplayName(it) })
                    if (cacheRoots.isNotEmpty()) appendLine()
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

    private fun renderFilteredRoots(roots: List<SourceRootInfo>, filter: String, maxSamplesPerRoot: Int): String {
        val matches: (String) -> Boolean = buildFileMaskMatcher(filter)
        val matched: List<SourceRootInfo> = roots.filter { info ->
            matches("${info.typeLabel} ${sourceRootDisplayName(info.root)} ${info.root.url}")
        }
        return buildString {
            val library: Int = matched.count { isLibrarySourcesLabel(it.typeLabel) }
            val cache: Int = matched.count { isDecompiledCacheLabel(it.typeLabel) }
            val buildscript: Int = matched.count { it.typeLabel.startsWith(BUILDSCRIPT_LABEL_PREFIX) }
            appendLine(
                "=== ${matched.size} of ${roots.size} source roots match filter \"$filter\" " +
                    "(library $library, decompiled $cache, buildscript $buildscript) ===",
            )
            appendLine()
            if (matched.isEmpty()) {
                appendLine("No root's label, jar name, Maven coordinates, or URL contains \"$filter\" (case-insensitive; * and ? are wildcards).")
                appendLine("That dependency is not attached as a source root. Run mixin_list_source_roots without filter for the toolchain diagnostics,")
                appendLine("or ./gradlew genDependencySources then mixin_sync_project if it has no published -sources.jar.")
                return@buildString
            }
            for ((i, info: SourceRootInfo) in matched.withIndex()) {
                appendLine("--- Root ${i + 1}: ${info.typeLabel} ---")
                appendLine("  URL: ${info.root.url}")
                val samples: List<String> = collectSamplePaths(info.root, maxSamplesPerRoot)
                if (samples.isNotEmpty()) {
                    appendLine("  Sample paths:")
                    for (p in samples) appendLine("    $p")
                } else {
                    appendLine("  (no .java files found or root empty)")
                }
                appendLine()
            }
        }
    }

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription("Searches dependency/library sources with a Java regex pattern, both published -sources.jar and auto-decompiled. Use this tool to grep across your entire classpath, including JDK src.zip (project SDK) and synthetic library sources contributed by other plugins. Results are grouped by file: each group shows the file path, a url: line (pass to mixin_get_dep_source), and matching lines with ||markers||. regexPattern: Java regex; prefer simple single-term patterns; make separate calls for multiple patterns. Escape regex metacharacters if you want literal matching (e.g. use 'addEffect\\(' not 'addEffect('). fileMask: filters which files to search. Without wildcards (* ?) it matches as a case-insensitive substring anywhere in the path (e.g. 'LivingEntity' matches net/minecraft/…/LivingEntity.java). With wildcards, treated as a case-insensitive glob (e.g. '*minecraft*'); every other character is literal. pathPrefix: optional; only search files whose logical path inside the root starts with this, case-insensitive (forward slashes, e.g. net/minecraft/ or net/minecraftforge/fml/ or java/util/); it is not a URL or disk path. Decompiled-cache roots also hold the jar's resources (META-INF/mods.toml, fabric.mod.json, assets/…/lang, data/…/recipes, *.mixins.json), so pathPrefix='assets/' or fileMask='mods.toml' searches them; binary entries (.class, images, sounds) are skipped. On MDG, MixinMCP auto-attaches merged game jars as Library SOURCES after sync; try this tool first for vanilla/Forge/NeoForge; on Loom toolchains vanilla comes from the genSources jar or the decompiled cache; empty results append hints (check mixin_list_source_roots auto-attach section). roots=all (default) scans, in order, Minecraft/game roots, other library -sources.jar roots, the MixinMCP decompiled cache, JDK src.zip, then the buildscript classpath; later tiers skip paths already matched (no duplicate hits). roots=library: game and library -sources.jar roots, then JDK src.zip last. roots=game: only Minecraft / loader game source roots. roots=decompiled: only the MixinMCP decompiled cache. roots=jdk: only the project SDK's src.zip. roots=buildscript: only Gradle buildscript classpath sources (Loom, ModDevGradle, mod-publish-plugin and other build plugins). timeout: 15s default; set 20000–30000 for broad unfiltered searches; on timeout the output names the root the scan stopped in. maxResults: 100 default. contextLines: include N lines of context around each match (default 0). Use small values (3–10) to capture short method bodies inline so you don't need a follow-up mixin_get_dep_source call; max 200. Match lines are prefixed with `>`, context lines with two spaces; overlapping windows are merged per file. If the IDE is indexing, the call waits for indexing to finish rather than failing.")
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
        if (rootsMode !in SEARCH_ROOTS_MODES) {
            return McpToolCallResult.error(
                "Invalid roots: \"$roots\". Use all, library, decompiled, buildscript, jdk, or game. To grep jars " +
                    "on disk, such as a pack's mods folder, use mixin_list_jar_entries(jarPath=..., regexPattern=...).",
            )
        }

        val normalizedPathPrefix: String? = pathPrefix?.trim()?.replace('\\', '/')
            ?.removePrefix("/")
            ?.takeIf { it.isNotEmpty() }
        if (normalizedPathPrefix != null && looksLikeUrlOrDiskPath(normalizedPathPrefix)) {
            return McpToolCallResult.error(
                "pathPrefix is a logical path inside a source root (e.g. net/minecraft/ or java/util/), not a URL or " +
                    "disk path. To read one file, pass its url to mixin_get_dep_source instead; to search one jar, " +
                    "use fileMask with a fragment of the package path.",
            )
        }

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
            val unreadableFiles = IntArray(1)
            val unreadableByRoot: MutableMap<String, Int> = linkedMapOf()
            var rootsScanned = 0
            var stoppedInRoot: String? = null

            val tiers: SearchTiers = searchTiers(project, collectSourceRootsWithMetadata(project))
            val plan: List<List<SourceRootInfo>> = tiers.tiersFor(rootsMode)
            val rootsTotal: Int = plan.sumOf { it.size }

            fun scanRoots(rootsToScan: List<SourceRootInfo>, skipPath: (String) -> Boolean) {
                for (info in rootsToScan) {
                    if (System.currentTimeMillis() - startTime > timeout) {
                        timedOut = true
                        return
                    }
                    if (hits.size >= scanCap) return
                    val unreadableBefore: Int = unreadableFiles[0]
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
                        unreadableFiles,
                    )
                    val unreadableHere: Int = unreadableFiles[0] - unreadableBefore
                    if (unreadableHere > 0) unreadableByRoot.merge(info.typeLabel, unreadableHere, Int::plus)
                    if (System.currentTimeMillis() - startTime > timeout) {
                        timedOut = true
                        stoppedInRoot = info.typeLabel
                        return
                    }
                    rootsScanned++
                }
            }

            // Later tiers skip paths already matched so a class present both as -sources.jar
            // and in the decompiled cache is reported once, from the better root.
            for (tier: List<SourceRootInfo> in plan) {
                if (hits.size >= scanCap || timedOut) break
                val pathsHit: Set<String> = hits.map { it.filePath }.toSet()
                scanRoots(tier, skipPath = { it in pathsHit })
            }
            if (!timedOut && System.currentTimeMillis() - startTime > timeout) timedOut = true

            val unsearchedJars: List<String> =
                if (rootsMode in setOf("all", "library", "decompiled")) unsearchableClasspathJars(project) else emptyList()
            val noMatchHints: List<String> =
                if (hits.isEmpty() && !timedOut) {
                    val base: List<String> = buildNoMatchHintsForDepSearch(
                        project,
                        normalizedPathPrefix,
                        rootsMode,
                    )
                    val zeroTierNotice: String? = when {
                        rootsMode == "buildscript" && tiers.buildscript.isEmpty() -> {
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
                        rootsMode == "library" && rootsTotal == 0 ->
                            "No Library SOURCES roots are attached; nothing was searched for roots=library. " +
                                "Run mixin_list_source_roots for diagnostics."
                        rootsMode == "decompiled" && tiers.cache.isEmpty() ->
                            "No decompiled cache roots are attached; nothing was searched for roots=decompiled. " +
                                "Run mixin_list_source_roots for diagnostics."
                        rootsMode == "jdk" && tiers.jdk.isEmpty() ->
                            "No JDK source roots are attached (project SDK without src.zip); nothing was searched for roots=jdk."
                        rootsMode == "game" && tiers.game.isEmpty() ->
                            "No Minecraft / loader game source roots were detected; nothing was searched for roots=game. " +
                                "Run mixin_list_source_roots for the toolchain diagnostics."
                        else -> null
                    }
                    base + listOfNotNull(zeroTierNotice, describeUnsearchableJars(unsearchedJars))
                } else {
                    emptyList()
                }
            DepRegexScanResult(
                hits = hits.take(maxResults),
                timedOut = timedOut,
                noMatchHints = noMatchHints,
                scannedFiles = scannedFiles[0],
                sawMore = hits.size > maxResults,
                unreadableFilesByRoot = unreadableByRoot,
                rootsScanned = rootsScanned,
                rootsTotal = rootsTotal,
                stoppedInRoot = stoppedInRoot,
                unsearchedJars = unsearchedJars,
            )
        }

        val elapsed: Long = System.currentTimeMillis() - requestStart
        val hits: List<DepSearchHit> = scanResult.hits
        val timedOut: Boolean = scanResult.timedOut
        val stoppedNote: String = scanResult.stoppedInRoot?.let { label ->
            " Scanning stopped in root ${scanResult.rootsScanned + 1} of ${scanResult.rootsTotal} ($label); " +
                "the roots after it were not searched."
        } ?: ""
        val result: String = buildString {
            appendLine("=== Regex search in dependencies: $regexPattern ===")
            if (normalizedPathPrefix != null) {
                appendLine("(pathPrefix: $normalizedPathPrefix)")
            }
            if (rootsMode != "all") {
                appendLine("(roots: $rootsMode)")
            }
            appendLine()
            describeUnreadableFiles(scanResult.unreadableFilesByRoot)?.let {
                appendLine(it)
                appendLine()
            }
            if (hits.isEmpty()) {
                if (timedOut) {
                    appendLine("Search INCOMPLETE (timed out after ${elapsed}ms); not all files were searched, so this is not a confirmed negative.$stoppedNote")
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
                    appendLine("Search INCOMPLETE (timed out after ${elapsed}ms); not all files were searched, results below are partial.$stoppedNote")
                    appendLine()
                }
                formatGroupedHitsWithContext(this, hits, contextLines)
                if (scanResult.sawMore) {
                    appendLine("  ... (truncated at $maxResults matches; more exist)")
                } else if (timedOut && hits.size >= maxResults) {
                    appendLine("  ... (stopped at $maxResults matches; unscanned files may hold more)")
                }
                describeUnsearchedJarsBriefly(scanResult.unsearchedJars)?.let {
                    appendLine()
                    appendLine(it)
                }
            }
        }

        return McpToolCallResult.text(result)
    }

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription("Reads a file from a dependency jar, the decompiled cache, or any jar on disk. Use this tool to view library code and jar resources that grep/read_file cannot access. Four ways to address the file, in precedence order: url (exact url: string from mixin_search_in_deps results, jar://…!/path/File.java or file://…/File.java; a bare disk path with !/ is accepted too); jarPath + entry (any jar on disk, on the classpath or not, e.g. jarPath='C:/pack/mods/jade.jar', entry='META-INF/neoforge.mods.toml'; use mixin_list_jar_entries to find entry names); className (dot FQCN; resolves to an attached -sources.jar or MDG merged jar, then the MixinMCP decompiled cache, then the IDE decompiler for a class with neither, so any class mixin_find_class resolves can be read, with the same line numbers mixin_find_class(methodName=...) reports); path (classpath-relative path with / separators, e.g. net/minecraft/world/entity/LivingEntity.java or data/minecraft/enchantment/sharpness.json, not a filesystem path; resources inside classes jars are found too, and when several jars ship the same path the first is shown and the others are listed with their urls). Relative jarPath values and relative disk paths inside a url resolve against the project directory. Text resources work as well as source: mods.toml, fabric.mod.json, lang, models, recipes, loot tables, mixin configs. A .class entry is decompiled by the IDE; other binary entries report their size instead of content. To read one method or field, mixin_find_class(className, methodName=...) is shorter. Two ways to choose lines: a window, lineNumber (default 1) with linesBefore (default 30) and linesAfter (default 70) around it; or an explicit inclusive 1-based range, startLine and/or endLine, which overrides the window (startLine alone reads to end of file, endLine alone reads from line 1). module: restricts className and path lookups to source roots on that module's classpath (exact or dot-boundary suffix name, e.g. common.main or MyMod.neoforge.main); url and jarPath lookups only validate the name.")
    @Suppress("unused")
    suspend fun mixin_get_dep_source(
        url: String? = null,
        path: String? = null,
        className: String? = null,
        jarPath: String? = null,
        entry: String? = null,
        lineNumber: Int = 1,
        linesBefore: Int = 30,
        linesAfter: Int = 70,
        startLine: Int? = null,
        endLine: Int? = null,
        module: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val hasUrl: Boolean = !url.isNullOrBlank()
        val hasJar: Boolean = !jarPath.isNullOrBlank()
        val hasClass: Boolean = !className.isNullOrBlank()
        if (!hasUrl && !hasJar && !hasClass && path.isNullOrBlank()) {
            return McpToolCallResult.error(
                "Missing required parameter. Pass one of: `url` (a jar:// or file:// URL, e.g. from mixin_search_in_deps " +
                    "results), `jarPath` + `entry` (any jar on disk plus the entry inside it, e.g. META-INF/mods.toml), " +
                    "`className` (dot FQCN, resolved to its attached or decompiled source), or `path` (classpath-relative " +
                    "source path, e.g. io/redspace/ironsspellbooks/api/util/Utils.java).",
            )
        }
        if (hasJar && entry.isNullOrBlank()) {
            return McpToolCallResult.error(
                "`jarPath` needs `entry`, the path inside the jar (e.g. META-INF/mods.toml or assets/<modid>/lang/en_us.json). " +
                    "Use mixin_list_jar_entries(jarPath=...) to list the entries.",
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
        val resolvedJarPath: String? = jarPath?.takeIf { hasJar }?.let { resolveAgainstBase(project.basePath, it) }
        val effectiveUrl: String? = when {
            url != null && hasUrl -> normalizeSourceUrl(url, project.basePath)
            resolvedJarPath != null && entry != null -> jarEntryUrl(resolvedJarPath, entry)
            else -> null
        }
        val fromUrl: VirtualFile? = effectiveUrl?.let { findFileByUrlOrMountJar(it) }
        val urlResolved: Boolean = fromUrl != null && fromUrl.isValid
        val urlFailed: Boolean = effectiveUrl != null && !urlResolved
        val classLookup: ClassSourceLookup? =
            if (className != null && hasClass && !urlResolved) {
                smartReadAction(project) { lookupClassSource(project, className, pinned?.scope) }
            } else {
                null
            }
        if (classLookup is ClassSourceLookup.NotFound) {
            val prefix: String = "Class not found: $className" + (pinned?.let { " (module: ${it.module.name})" } ?: "")
            return McpToolCallResult.error(
                smartReadAction(project) { FqcnResolver.notFoundMessage(project, className.orEmpty(), prefix) },
            )
        }
        val pathMatches: List<VirtualFile> = if (!urlResolved && classLookup == null && trimmedPath != null) {
            smartReadAction(project) { locateDepFilesByPath(project, trimmedPath, pinned?.scope) }
        } else {
            emptyList()
        }
        val vf: VirtualFile? = when {
            urlResolved -> fromUrl
            classLookup is ClassSourceLookup.Found -> classLookup.file
            else -> pathMatches.firstOrNull()
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
                urlFailed && hasJar ->
                    "`$effectiveUrl` did not resolve: check that the jar exists at `$resolvedJarPath` and that `entry` matches an " +
                        "entry name exactly (case-sensitive, forward slashes). mixin_list_jar_entries(jarPath=\"$resolvedJarPath\") lists them."
                urlFailed && trimmedPath != null ->
                    "url `$url` did not resolve, and path `$trimmedPath` was not found either. " +
                        pathMissHint(trimmedPath, rootsTotal)
                urlFailed ->
                    "url `$effectiveUrl` did not resolve. Pass the exact url from mixin_search_in_deps results, a " +
                        "jar://<disk path>!/<entry> URL of a jar that exists on disk, or try `className` / `path` instead."
                else -> pathMissHint(trimmedPath!!, rootsTotal)
            }
            return McpToolCallResult.error("File not found. $hint")
        }

        val isClassEntry: Boolean = vf.extension.equals("class", ignoreCase = true)
        val content: String = if (isClassEntry) {
            smartReadAction(project) { runCatching { PsiManager.getInstance(project).findFile(vf)?.text }.getOrNull() }
                ?: return McpToolCallResult.error(
                    "`${vf.name}` is a compiled class and the IDE could not decompile it here. Use " +
                        "mixin_class_bytecode / mixin_method_bytecode (with jarPath for a jar outside the classpath) instead.",
                )
        } else {
            val bytes: ByteArray = try {
                withContext(Dispatchers.IO) { vf.contentsToByteArray() }
            } catch (e: Exception) {
                return McpToolCallResult.error("Failed to read file: ${e.message}")
            }
            if (looksBinary(bytes)) {
                return McpToolCallResult.text(
                    "=== ${vf.name} [binary entry, ${bytes.size} bytes; content not shown] ===\n" +
                        "This entry is not text. Use mixin_list_jar_entries to browse the jar; .class entries can be " +
                        "read through mixin_class_bytecode or by addressing them here with url/jarPath for an IDE decompile.",
                )
            }
            String(bytes, StandardCharsets.UTF_8)
        }

        val sourceKind: String = when {
            isClassEntry && classLookup != null -> "IDE-decompiled .class (no attached or decompiled-cache source)"
            isClassEntry -> "IDE-decompiled .class entry"
            else -> smartReadAction(project) { classifySourceFile(project, vf) }
        }

        val lines: List<String> = content.lines()
        val window: SourceWindow.Lines = when (
            val resolved: SourceWindow =
                resolveSourceWindow(vf.name, lines.size, lineNumber, linesBefore, linesAfter, startLine, endLine)
        ) {
            is SourceWindow.Invalid -> return McpToolCallResult.error(resolved.message)
            is SourceWindow.Lines -> resolved
        }

        val result: String = buildString {
            if (viaPathFallback) {
                appendLine("(url `$url` did not resolve; located via the `path` parameter instead. The url may be stale; re-run mixin_search_in_deps for a fresh one.)")
            }
            window.note?.let { appendLine("($it)") }
            val others: List<VirtualFile> = pathMatches.drop(1)
            if (others.isNotEmpty()) {
                val shown: String = others.take(3).joinToString("; ") { it.url }
                val more: String = if (others.size > 3) "; and ${others.size - 3} more" else ""
                appendLine("(the same path is also in ${others.size} other root(s), pass a url to read one: $shown$more)")
            }
            val modSuffix: String = pinned?.let { " [pinned module: ${it.module.name}]" } ?: ""
            appendLine("=== ${vf.name} (lines ${window.start}-${window.end}) [sourceKind: $sourceKind]$modSuffix ===")
            appendLine()
            val markedLine: Int? = if (startLine == null && endLine == null) lineNumber else null
            for (i in window.start..window.end) {
                val marker: String = if (i == markedLine) ">" else " "
                appendLine("$marker $i| ${lines[i - 1]}")
            }
        }

        return McpToolCallResult.text(result)
    }

    private fun findFileByUrlOrMountJar(url: String): VirtualFile? {
        VirtualFileManager.getInstance().findFileByUrl(url)?.let { return it }
        if (!url.startsWith("jar://")) return null
        // A jar outside every project root is unknown to the VFS until its local file is refreshed in.
        return JarFileSystem.getInstance().refreshAndFindFileByPath(url.removePrefix("jar://"))
    }
}
