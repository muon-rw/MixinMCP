package dev.mixinmcp.tools.semantic

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue.FALSE
import com.intellij.mcpserver.annotations.McpToolHintValue.TRUE
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import com.intellij.util.concurrency.annotations.RequiresReadLock
import dev.mixinmcp.tools.projectRelativePath
import dev.mixinmcp.resolve.FqcnResolver
import dev.mixinmcp.resolve.MethodResolver
import dev.mixinmcp.tools.ClassContentDeduper
import dev.mixinmcp.tools.VARIANT_GROUPING_FOOTER
import dev.mixinmcp.tools.requireProject
import kotlin.coroutines.coroutineContext

/**
 * Semantic-navigation tools: type hierarchy, implementors, mixin target
 * discovery, super-method chains, references, and call hierarchy.
 */
@Suppress("FunctionName") // @McpTool functions are snake_case by MCP convention
class SemanticNavigationToolset : McpToolset {

    override fun isExperimental(): Boolean = false

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription("Retrieves the type hierarchy of a class. Use this tool to understand inheritance before writing mixins. direction: supers (superclass chain + all interfaces, direct and transitively inherited), subs (inheritors), both (default). maxDepth limits superclass traversal (default 10, must be >= 1); also bounds how far up the superclass chain inherited interfaces are collected from. includeInterfaces: default true. Inherited interfaces are collected from the full superclass chain (within maxDepth) and from super-interface extension, deduplicated by qualified name, and each tagged with origin (from X = introduced by superclass X; via X = inherited by extending interface X). maxResults caps subclasses/implementors (default 50, must be >= 1) — raise for heavily-inherited classes like LivingEntity or Block. If the IDE is indexing, the call waits for indexing to finish rather than failing.")
    @Suppress("unused")
    suspend fun mixin_type_hierarchy(
        className: String,
        direction: String = "both",
        maxDepth: Int = 10,
        includeInterfaces: Boolean = true,
        maxResults: Int = 50,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }
        if (direction != "supers" && direction != "subs" && direction != "both") {
            return McpToolCallResult.error(
                "direction must be 'supers', 'subs', or 'both' (got '$direction')",
            )
        }
        if (maxResults < 1) {
            return McpToolCallResult.error("maxResults must be >= 1 (got $maxResults)")
        }
        if (maxDepth < 1) {
            return McpToolCallResult.error("maxDepth must be >= 1 (got $maxDepth)")
        }

        val result: String? = smartReadAction(project) {
            val psiClass: PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@smartReadAction null

            val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)

            buildString {
                appendLine("=== Type hierarchy: ${psiClass.qualifiedName} ===")
                appendLine()

                if (direction == "supers" || direction == "both") {
                    appendLine("--- Superclasses ---")
                    var current: PsiClass? = psiClass.superClass
                    var depth: Int = 0
                    while (current != null && depth < maxDepth) {
                        appendLine("  ${"  ".repeat(depth)}${current.qualifiedName}")
                        current = current.superClass
                        depth++
                    }
                    if (depth == 0) {
                        appendLine("  (none)")
                    }
                    if (includeInterfaces) {
                        appendLine()
                        appendInterfaceSections(psiClass, maxDepth)
                    }
                    appendLine()
                }

                if (direction == "subs" || direction == "both") {
                    appendLine("--- Subclasses / implementors (max $maxResults) ---")
                    val query = ClassInheritorsSearch.search(psiClass, scope, true)
                    val deduper = ClassContentDeduper()
                    val entries: MutableList<String> = mutableListOf()
                    var sawMore = false
                    query.forEach(Processor<PsiClass> { sub ->
                        val key: String? =
                            sub.qualifiedName
                                ?: sub.containingClass?.qualifiedName?.let { "$it\$anonymous" }
                                ?: sub.name
                        if (key != null && deduper.record(key, sub.containingFile?.virtualFile)) {
                            if (entries.size >= maxResults) {
                                sawMore = true
                                return@Processor false
                            }
                            entries.add(key)
                        }
                        true
                    })
                    if (entries.isEmpty()) {
                        appendLine(
                            if (psiClass.hasModifierProperty(PsiModifier.FINAL)) "  (none; class is final)"
                            else "  (none)",
                        )
                    }
                    var annotated = false
                    for (key: String in entries) {
                        val annotation: String? = if ("\$anonymous" in key) null else deduper.annotationFor(key)
                        if (annotation != null) annotated = true
                        appendLine("  $key${annotation ?: ""}")
                    }
                    if (sawMore) {
                        appendLine("  ... (truncated at $maxResults results)")
                    }
                    if (annotated) {
                        appendLine("  $VARIANT_GROUPING_FOOTER")
                    }
                    appendLine()
                }
            }
        }

        return when {
            result != null -> McpToolCallResult.text(result)
            else -> McpToolCallResult.error("Class not found: $className\n${FqcnResolver.CLASS_NOT_FOUND_HINT}")
        }
    }

    /**
     * Emits the "Direct interfaces" and "Inherited interfaces" sections for
     * [mixin_type_hierarchy]. The inherited set is collected by walking the
     * superclass chain (bounded by [maxDepth] — the same budget used for the
     * Superclasses listing) plus the full super-interface extension graph,
     * deduplicated by qualified name.
     *
     * Origin tagging: "from X" means the interface is introduced by superclass
     * X (declared in its implements clause). "via X" means the interface is
     * reached by extending interface X. First-seen wins, so closer origins
     * are preferred: the class's own direct interfaces are seeded first, then
     * each superclass in order from nearest to furthest; super-interface
     * chains are traversed in BFS order from those seeds.
     */
    private fun StringBuilder.appendInterfaceSections(psiClass: PsiClass, maxDepth: Int) {
        val directInterfaces: List<PsiClass> = psiClass.interfaces.toList()
        val directQns: HashSet<String> = directInterfaces.mapNotNull { it.qualifiedName }.toHashSet()

        // qn -> (PsiClass, origin). LinkedHashMap preserves BFS insertion order.
        val inherited: LinkedHashMap<String, Pair<PsiClass, String>> = LinkedHashMap()
        val toProcess: ArrayDeque<Pair<PsiClass, String>> = ArrayDeque()

        // Seed: super-interfaces of the class's direct interfaces become "via <directIface>".
        for (iface: PsiClass in directInterfaces) {
            val qn: String = iface.qualifiedName ?: continue
            toProcess.addLast(iface to qn)
        }

        // Seed: interfaces introduced by each superclass become "from <superclass>".
        var sc: PsiClass? = psiClass.superClass
        var scDepth: Int = 0
        while (sc != null && scDepth < maxDepth) {
            val scQn: String = sc.qualifiedName ?: "(unknown)"
            for (iface: PsiClass in sc.interfaces) {
                val qn: String = iface.qualifiedName ?: continue
                if (qn !in directQns && qn !in inherited) {
                    inherited[qn] = iface to "from $scQn"
                    toProcess.addLast(iface to qn)
                }
            }
            sc = sc.superClass
            scDepth++
        }

        // BFS across super-interface extension. Dedupe by qualified name; first-seen wins.
        while (toProcess.isNotEmpty()) {
            val (iface: PsiClass, ifaceQn: String) = toProcess.removeFirst()
            for (superIface: PsiClass in iface.interfaces) {
                val superQn: String = superIface.qualifiedName ?: continue
                if (superQn !in directQns && superQn !in inherited) {
                    inherited[superQn] = superIface to "via $ifaceQn"
                    toProcess.addLast(superIface to superQn)
                }
            }
        }

        if (directInterfaces.isNotEmpty()) {
            appendLine("--- Direct interfaces ---")
            for (iface: PsiClass in directInterfaces) {
                appendLine("  ${iface.qualifiedName ?: iface.name ?: "?"}")
            }
        }

        if (inherited.isNotEmpty()) {
            if (directInterfaces.isNotEmpty()) appendLine()
            appendLine("--- Inherited interfaces ---")
            for ((qn: String, entry: Pair<PsiClass, String>) in inherited) {
                appendLine("  $qn  (${entry.second})")
            }
        }

        if (directInterfaces.isEmpty() && inherited.isEmpty()) {
            appendLine("--- Interfaces ---")
            appendLine("  (none)")
        }
    }

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription("Finds all implementations of an interface or abstract class across project and dependencies. maxResults: 50 default.")
    @Suppress("unused")
    suspend fun mixin_find_impls(
        className: String,
        maxResults: Int = 50,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val result: String? = smartReadAction(project) {
            val psiClass: PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@smartReadAction null

            val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)
            val query = ClassInheritorsSearch.search(psiClass, scope, true)

            buildString {
                appendLine("=== Implementors of ${psiClass.qualifiedName} ===")
                appendLine()
                val deduper = ClassContentDeduper()
                val entries: MutableList<String> = mutableListOf()
                var sawMore = false
                query.forEach(Processor<PsiClass> { sub ->
                    // Fallback naming: anonymous/inner classes may have null qualifiedName
                    val key: String =
                        sub.qualifiedName
                            ?: sub.containingClass?.qualifiedName?.let { "$it\$anonymous" }
                            ?: sub.name
                            ?: "(anonymous)"
                    if (deduper.record(key, sub.containingFile?.virtualFile)) {
                        if (entries.size >= maxResults) {
                            sawMore = true
                            return@Processor false
                        }
                        entries.add(key)
                    }
                    true
                })
                if (entries.isEmpty()) {
                    val reason: String? = when {
                        psiClass.hasModifierProperty(PsiModifier.FINAL) -> "class is final"
                        psiClass.isRecord -> "records are implicitly final"
                        psiClass.isEnum -> "enum cannot be extended"
                        else -> null
                    }
                    appendLine(
                        if (reason != null) "  ($reason; no implementors possible)"
                        else "  (no implementors found in the current classpath)",
                    )
                }
                var annotated = false
                for (key: String in entries) {
                    val annotation: String? =
                        if ("\$anonymous" in key || key == "(anonymous)") null else deduper.annotationFor(key)
                    if (annotation != null) annotated = true
                    appendLine("  $key${annotation ?: ""}")
                }
                if (sawMore) {
                    appendLine("  ... (truncated at $maxResults results)")
                }
                if (annotated) {
                    appendLine("  $VARIANT_GROUPING_FOOTER")
                }
            }
        }

        return when {
            result != null -> McpToolCallResult.text(result)
            else -> McpToolCallResult.error("Class not found: $className\n${FqcnResolver.CLASS_NOT_FOUND_HINT}")
        }
    }

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription("Finds all @Mixin classes that target a given class (and optionally a specific method). Use this for cross-mod conflict analysis — discover which other mods inject into the same target. Returns mixin FQCN, injection points (@Inject, @Redirect, @Overwrite, etc.), and source location. methodName: optionally narrow to mixins targeting that method. maxResults: 50 default. If the IDE is indexing, the call waits for indexing to finish rather than failing.")
    @Suppress("unused")
    suspend fun mixin_find_targeting_mixins(
        className: String,
        methodName: String? = null,
        maxResults: Int = 50,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }
        if (maxResults < 1) {
            return McpToolCallResult.error("maxResults must be >= 1 (got $maxResults)")
        }

        return smartReadAction(project) {
            val targetClass: PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@smartReadAction McpToolCallResult.error(
                    "Class not found: $className (no mixin scan performed)\n${FqcnResolver.CLASS_NOT_FOUND_HINT}",
                )
            val normalizedTarget: String = targetClass.qualifiedName ?: className.replace('/', '.')
            val mixinAnnotationClass: PsiClass? =
                FqcnResolver.resolveNested(project, "org.spongepowered.asm.mixin.Mixin")

            val mixins: MutableList<Pair<PsiClass, List<String>>> = mutableListOf()
            val deduper = ClassContentDeduper()
            var ownBuildSkipped: Int = 0
            var methodFilteredOut: Int = 0
            val methodFilteredFqcns: MutableList<String> = mutableListOf()
            var sawMore = false

            if (mixinAnnotationClass != null) {
                val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)
                val ownRoots: List<String> = projectContentRootPaths(project)
                val query = AnnotatedElementsSearch.searchPsiClasses(mixinAnnotationClass, scope)
                query.forEach(Processor<PsiClass> { psiClass ->
                    val targets: List<String> = extractMixinTargets(psiClass)
                    if (targets.any { normalizeForMatch(it) == normalizeForMatch(normalizedTarget) }) {
                        val vf = psiClass.containingFile?.virtualFile
                        if (isOwnBuildOutput(vf, ownRoots)) {
                            ownBuildSkipped++
                        } else if (deduper.record(psiClass.qualifiedName, vf)) {
                            val injections: List<String> = if (methodName != null) {
                                extractInjectionsForMethod(psiClass, methodName)
                            } else {
                                extractAllInjections(psiClass)
                            }
                            if (methodName == null || injections.isNotEmpty()) {
                                if (mixins.size >= maxResults) {
                                    sawMore = true
                                    return@Processor false
                                }
                                mixins.add(psiClass to injections)
                            } else {
                                methodFilteredOut++
                                if (methodFilteredFqcns.size < 10) {
                                    methodFilteredFqcns.add(psiClass.qualifiedName ?: psiClass.name ?: "?")
                                }
                            }
                        }
                    }
                    true
                })
            }

            fun StringBuilder.appendSkipFooter() {
                if (ownBuildSkipped == 0) return
                val noun: String = if (ownBuildSkipped == 1) "stale copy" else "stale copies"
                appendLine("(skipped $ownBuildSkipped $noun from this project's build output)")
            }

            val text: String = if (mixins.isEmpty()) {
                val fallback: List<Pair<String, String>> = findTargetingMixinsByRegex(project, normalizedTarget, methodName, maxResults)
                buildString {
                    appendLine("=== Mixins targeting $normalizedTarget${if (methodName != null) "#$methodName" else ""} ===")
                    appendLine()
                    if (fallback.isEmpty()) {
                        if (methodName != null && methodFilteredOut > 0) {
                            val more: String = if (methodFilteredOut > methodFilteredFqcns.size) ", ..." else ""
                            appendLine(
                                "No mixins found injecting into '$methodName', but $methodFilteredOut mixin(s) target " +
                                    "$normalizedTarget via other members: ${methodFilteredFqcns.joinToString(", ")}$more. " +
                                    "Rerun without methodName for their full injection lists.",
                            )
                        } else {
                            appendLine("No mixins found targeting this class (annotation index and textual source fallback both empty).")
                            if (mixinAnnotationClass == null) {
                                appendLine("(Mixin library may not be on classpath; add org.spongepowered:mixin as dependency)")
                            }
                        }
                    } else {
                        appendLine(
                            "(annotation index found no verified @Mixin targets; entries below are heuristic text " +
                                "matches from dependency-attached sources and may be false positives; verify with mixin_find_class)",
                        )
                        appendLine()
                        for ((i, pair) in fallback.withIndex()) {
                            appendLine("${i + 1}. ${pair.first}")
                            appendLine("   Source: ${pair.second}")
                            appendLine()
                        }
                    }
                    appendSkipFooter()
                }
            } else {
                buildString {
                    appendLine("=== Mixins targeting $normalizedTarget${if (methodName != null) "#$methodName" else ""} ===")
                    appendLine()
                    var annotated = false
                    for ((i, pair) in mixins.withIndex()) {
                        val (psiClass, injections) = pair
                        val fqcn: String = psiClass.qualifiedName ?: psiClass.name ?: "?"
                        val annotation: String? = deduper.annotationFor(psiClass.qualifiedName)
                        if (annotation != null) annotated = true
                        val source: String = psiClass.containingFile?.virtualFile
                            ?.let { projectRelativePath(project, it) } ?: "(unknown)"
                        appendLine("${i + 1}. $fqcn${annotation ?: ""}")
                        for (inj in injections.take(10)) {
                            appendLine("   $inj")
                        }
                        if (injections.size > 10) {
                            appendLine("   ... (${injections.size - 10} more)")
                        }
                        appendLine("   Source: $source")
                        appendLine()
                    }
                    if (sawMore) {
                        appendLine("  ... (truncated at $maxResults results)")
                    }
                    if (annotated) {
                        appendLine(VARIANT_GROUPING_FOOTER)
                    }
                    appendSkipFooter()
                }
            }
            McpToolCallResult.text(text)
        }
    }

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription("Walks the full super-method chain for a method through all superclasses and super-interfaces to the original declaration(s). Use this to confirm where a method is originally declared before targeting it in a mixin — root declarations (those with no further super) are usually the best mixin targets for base behavior. Output indents by depth (2 spaces per level), tags each entry as [root declaration] and/or [interface] where applicable, and emits a Source: path:line line for each method when source is available. If the queried class inherits the method from an ancestor (rather than declaring it itself), the response calls this out explicitly so you can mixin into the actual declaring class — when this happens with no further supers, the chain is empty because the resolved declaration is already the root. When multiple roots exist (e.g. a class root and an interface default), they are summarized at the end. For overloaded methods, pass parameterTypes or methodDescriptor to disambiguate. methodDescriptor accepts JVM format (e.g. (Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z) — same as in @Inject(method = \"...\"). For parameterless methods: parameterTypes: [] or methodDescriptor: \"()V\".")
    @Suppress("unused")
    suspend fun mixin_super_methods(
        className: String,
        methodName: String,
        parameterTypes: List<String>? = null,
        methodDescriptor: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        return smartReadAction(project) {
            val resolution = MethodResolver.resolveDetailed(
                project, className, methodName,
                parameterTypes = parameterTypes,
                methodDescriptor = methodDescriptor,
            )
            if (resolution is MethodResolver.Resolution.Error) {
                return@smartReadAction McpToolCallResult.error(resolution.message)
            }
            val psiMethod: PsiMethod = (resolution as MethodResolver.Resolution.Found).method

            val containingClass: PsiClass? = psiMethod.containingClass
            val containingFqn: String = containingClass?.qualifiedName ?: "?"
            val queriedFqn: String? = FqcnResolver.resolveNested(project, className)?.qualifiedName
            val inheritedFromAncestor: Boolean =
                queriedFqn != null && containingClass?.qualifiedName != null && queriedFqn != containingClass.qualifiedName

            val chain: List<SuperChainEntry> = buildSuperChain(psiMethod)
            val rootEntries: List<SuperChainEntry> = chain.filter { it.isRoot }

            val result: String = buildString {
                appendLine("=== Super methods for ${psiMethod.name} ===")
                appendLine()
                appendLine("Declared in: $containingFqn")
                appendLine("Signature: ${psiMethod.name}(${psiMethod.parameterList.parameters.joinToString { "${it.type.presentableText} ${it.name}" }})")
                sourceLocation(project, psiMethod)?.let { appendLine("Source: $it") }
                if (inheritedFromAncestor) {
                    appendLine()
                    appendLine("Note: $queriedFqn does not declare this method directly — it is inherited from $containingFqn. Mixin into $containingFqn (or a subclass that overrides it) to affect this behavior.")
                }
                appendLine()
                if (chain.isEmpty()) {
                    val interfaceNote: String = if (containingClass?.isInterface == true) " (interface)" else ""
                    appendLine("$containingFqn$interfaceNote is the root declaration — no super methods to walk.")
                } else {
                    appendLine("--- Super method chain (most specific → most general) ---")
                    for (entry: SuperChainEntry in chain) {
                        val indent: String = "  ".repeat(entry.depth)
                        val declClass: PsiClass? = entry.method.containingClass
                        val fqn: String = declClass?.qualifiedName ?: "?"
                        val tags: List<String> = buildList {
                            if (entry.isRoot) add("root declaration")
                            if (declClass?.isInterface == true) add("interface")
                        }
                        val tagSuffix: String = if (tags.isEmpty()) "" else "  [${tags.joinToString(", ")}]"
                        appendLine("$indent$fqn#${entry.method.name}(...)$tagSuffix")
                        sourceLocation(project, entry.method)?.let { appendLine("$indent  Source: $it") }
                    }
                    if (rootEntries.size > 1) {
                        appendLine()
                        appendLine("--- Root declarations (${rootEntries.size}) ---")
                        for (r: SuperChainEntry in rootEntries) {
                            val declClass: PsiClass? = r.method.containingClass
                            val fqn: String = declClass?.qualifiedName ?: "?"
                            val kind: String = if (declClass?.isInterface == true) " (interface)" else ""
                            appendLine("  $fqn#${r.method.name}(...)$kind")
                        }
                    }
                }
            }
            McpToolCallResult.text(result)
        }
    }

    /**
     * Entry in the super-method chain. [depth] is 1 for the direct super of
     * the starting method, 2 for the super-of-super, and so on. [isRoot] is
     * true when this method has no further super methods — the original
     * declaration.
     */
    private data class SuperChainEntry(
        val method: PsiMethod,
        val depth: Int,
        val isRoot: Boolean,
    )

    /**
     * Returns "path:line" for [method] when a containing file with line info
     * is available, or null otherwise.
     */
    @RequiresReadLock
    private fun sourceLocation(project: Project, method: PsiMethod): String? {
        val file = method.containingFile ?: return null
        val path: String = file.virtualFile?.let { projectRelativePath(project, it) } ?: return null
        val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return path
        val offset: Int = method.textOffset
        if (offset < 0 || offset >= doc.textLength) return path
        return "$path:${doc.getLineNumber(offset) + 1}"
    }

    /**
     * Recursively walks super methods of [start] via DFS. De-duplicates by
     * (declaringClassFqn, methodName, canonicalParamTypes) so diamond
     * interface hierarchies don't inflate the chain.
     */
    @RequiresReadLock
    private fun buildSuperChain(start: PsiMethod): List<SuperChainEntry> {
        fun keyOf(method: PsiMethod): String {
            val clazz: String = method.containingClass?.qualifiedName ?: "?"
            val params: String = method.parameterList.parameters.joinToString(",") { it.type.canonicalText }
            return "$clazz#${method.name}($params)"
        }

        val visited: MutableSet<String> = mutableSetOf(keyOf(start))
        val entries: MutableList<SuperChainEntry> = mutableListOf()

        fun walk(method: PsiMethod, depth: Int) {
            val supers: Array<PsiMethod> = method.findSuperMethods(false)
            for (sup: PsiMethod in supers) {
                val supKey: String = keyOf(sup)
                if (!visited.add(supKey)) continue
                val supHasSupers: Boolean = sup.findSuperMethods(false).isNotEmpty()
                entries.add(SuperChainEntry(sup, depth, isRoot = !supHasSupers))
                walk(sup, depth + 1)
            }
        }
        walk(start, 1)
        return entries
    }

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription("Finds all overriders of a method across project and dependencies (mods, loader, libraries). Use this tool to see how a method is extended before writing a mixin — complements mixin_super_methods (which walks upward to the original declaration). Returns each overriding class with source location (line numbers when source is available). Abstract overrides (e.g. interface re-declarations) are tagged [abstract]. Interface methods return every implementation. Non-overridable methods (static, private, final, constructors, or methods in final classes) return an explanation instead of an empty list. For overloaded methods, pass parameterTypes or methodDescriptor to disambiguate. methodDescriptor accepts JVM format (e.g. (Lnet/minecraft/...;)V) — same as in mixin @Inject annotations. For parameterless methods: parameterTypes: [] or methodDescriptor: \"()V\". maxResults: 50 default (must be >= 1) — raise for heavily-inherited methods like Entity#tick or Object#toString.")
    @Suppress("unused")
    suspend fun mixin_find_overrides(
        className: String,
        methodName: String,
        parameterTypes: List<String>? = null,
        methodDescriptor: String? = null,
        maxResults: Int = 50,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }
        if (maxResults < 1) {
            return McpToolCallResult.error("maxResults must be >= 1 (got $maxResults)")
        }

        return smartReadAction(project) {
            val resolution = MethodResolver.resolveDetailed(
                project, className, methodName,
                parameterTypes = parameterTypes,
                methodDescriptor = methodDescriptor,
            )
            if (resolution is MethodResolver.Resolution.Error) {
                return@smartReadAction McpToolCallResult.error(resolution.message)
            }
            val psiMethod: PsiMethod = (resolution as MethodResolver.Resolution.Found).method

            val containingClass: PsiClass? = psiMethod.containingClass
            val declFqcn: String = containingClass?.qualifiedName ?: className
            val paramList: String = psiMethod.parameterList.parameters
                .joinToString { "${it.type.presentableText} ${it.name}" }

            val result: String = buildString {
                appendLine("=== Overrides of $declFqcn#${psiMethod.name} ===")
                appendLine()
                appendLine("Declared in: $declFqcn")
                appendLine("Signature: ${psiMethod.name}($paramList)")
                appendLine()

                val unoverridableReason: String? = when {
                    psiMethod.isConstructor -> "constructor"
                    psiMethod.hasModifierProperty(PsiModifier.STATIC) -> "static"
                    psiMethod.hasModifierProperty(PsiModifier.PRIVATE) -> "private"
                    psiMethod.hasModifierProperty(PsiModifier.FINAL) -> "final method"
                    containingClass?.hasModifierProperty(PsiModifier.FINAL) == true -> "declared in a final class"
                    else -> null
                }

                if (unoverridableReason != null) {
                    appendLine("Method is not overridable ($unoverridableReason). No overrides possible.")
                    return@buildString
                }

                appendLine("--- Overrides (max $maxResults) ---")
                val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)
                val query = OverridingMethodsSearch.search(psiMethod, scope, true)
                val deduper = ClassContentDeduper()
                val overrides: MutableList<Pair<String, PsiMethod>> = mutableListOf()
                var sawMore = false
                query.forEach(Processor<PsiMethod> { overriding ->
                    val overridingClass: PsiClass? = overriding.containingClass
                    val key: String =
                        overridingClass?.qualifiedName
                            ?: overridingClass?.containingClass?.qualifiedName?.let { "$it\$anonymous" }
                            ?: overridingClass?.name
                            ?: "(unknown)"
                    if (deduper.record(key, overridingClass?.containingFile?.virtualFile)) {
                        if (overrides.size >= maxResults) {
                            sawMore = true
                            return@Processor false
                        }
                        overrides.add(key to overriding)
                    }
                    true
                })

                if (overrides.isEmpty()) {
                    appendLine("  (no overrides found in the current classpath)")
                    return@buildString
                }

                var annotated = false
                for ((i, entry: Pair<String, PsiMethod>) in overrides.withIndex()) {
                    val (fqcn: String, overriding: PsiMethod) = entry
                    val annotation: String? = if (fqcn == "(unknown)") null else deduper.annotationFor(fqcn)
                    if (annotation != null) annotated = true
                    val isAbstract: Boolean = overriding.hasModifierProperty(PsiModifier.ABSTRACT)
                    val abstractTag: String = if (isAbstract) " [abstract]" else ""
                    val vf = overriding.containingFile?.virtualFile
                    val path: String = vf?.let { projectRelativePath(project, it) } ?: "(unknown)"
                    val line: Int = overriding.containingFile?.let { f ->
                        val doc = PsiDocumentManager.getInstance(project).getDocument(f) ?: return@let 0
                        val offset: Int = overriding.textOffset
                        if (offset < 0 || offset >= doc.textLength) 0
                        else doc.getLineNumber(offset) + 1
                    } ?: 0
                    val locationSuffix: String = if (line > 0) ":$line" else ""

                    appendLine("  ${i + 1}. $fqcn$abstractTag${annotation ?: ""}")
                    appendLine("     Source: $path$locationSuffix")
                }
                if (sawMore) {
                    appendLine("  ... (truncated at $maxResults results)")
                }
                if (annotated) {
                    appendLine("  $VARIANT_GROUPING_FOOTER")
                }
            }
            McpToolCallResult.text(result)
        }
    }

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription("Find all references to a class or member across project and dependencies. Without memberName: references to the class. With memberName: references to that method or field. For overloaded methods, pass parameterTypes or methodDescriptor to disambiguate. methodDescriptor accepts JVM format (e.g. (Lnet/minecraft/...;)V) — same as in mixin @Inject annotations. For parameterless methods: parameterTypes: [] or methodDescriptor: \"()V\". maxResults: 100 default. If the IDE is indexing, the call waits for indexing to finish rather than failing.")
    @Suppress("unused")
    suspend fun mixin_find_references(
        className: String,
        memberName: String? = null,
        parameterTypes: List<String>? = null,
        methodDescriptor: String? = null,
        maxResults: Int = 100,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }
        if (maxResults < 1) {
            return McpToolCallResult.error("maxResults must be >= 1 (got $maxResults)")
        }

        if (memberName != null) {
            return smartReadAction(project) {
                val psiClass: PsiClass? = FqcnResolver.resolveNested(project, className)
                val fieldResult: PsiField? = psiClass?.findFieldByName(memberName, true)

                if (fieldResult != null && parameterTypes == null && methodDescriptor == null) {
                    val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)
                    val (refs: List<PsiReference>, sawMore: Boolean) = collectReferences(fieldResult, scope, maxResults)
                    val sameNameMethods: Int = psiClass.findMethodsByName(memberName, true).size

                    val result: String = buildString {
                        appendLine("=== References to $className.$memberName (field) ===")
                        appendLine()
                        appendLine("Field type: ${fieldResult.type.presentableText}")
                        if (sameNameMethods > 0) {
                            appendLine(
                                "(Note: $sameNameMethods method(s) named '$memberName' also exist in this class or its " +
                                    "supertypes and were not searched; pass parameterTypes or methodDescriptor to " +
                                    "search method references instead.)",
                            )
                        }
                        appendLine()
                        appendReferenceList(project, refs, sawMore, maxResults)
                    }
                    return@smartReadAction McpToolCallResult.text(result)
                }

                val resolution = MethodResolver.resolveDetailed(
                    project, className, memberName,
                    parameterTypes = parameterTypes,
                    methodDescriptor = methodDescriptor,
                )
                if (resolution is MethodResolver.Resolution.Error) {
                    if (fieldResult != null) {
                        val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)
                        val (refs: List<PsiReference>, sawMore: Boolean) = collectReferences(fieldResult, scope, maxResults)

                        val result: String = buildString {
                            appendLine("=== References to $className.$memberName (field) ===")
                            appendLine()
                            appendLine("Field type: ${fieldResult.type.presentableText}")
                            appendLine("(Note: no method named '$memberName' was found; showing field references.)")
                            appendLine()
                            appendReferenceList(project, refs, sawMore, maxResults)
                        }
                        return@smartReadAction McpToolCallResult.text(result)
                    }
                    return@smartReadAction McpToolCallResult.error(resolution.message)
                }
                val psiMethod: PsiMethod = (resolution as MethodResolver.Resolution.Found).method

                val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)
                val (refs: List<PsiReference>, sawMore: Boolean) = collectReferences(psiMethod, scope, maxResults)

                val result: String = buildString {
                    appendLine("=== References to $className#$memberName ===")
                    appendLine()
                    appendReferenceList(project, refs, sawMore, maxResults)
                }
                McpToolCallResult.text(result)
            }
        }

        // Class-level reference search
        val result: String? = smartReadAction(project) {
            val psiClass: PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@smartReadAction null

            val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)
            val (refs: List<PsiReference>, sawMore: Boolean) = collectReferences(psiClass, scope, maxResults)

            buildString {
                appendLine("=== References to $className ===")
                appendLine()
                appendReferenceList(project, refs, sawMore, maxResults)
            }
        }

        return when {
            result != null -> McpToolCallResult.text(result)
            else -> McpToolCallResult.error("Class not found: $className\n${FqcnResolver.CLASS_NOT_FOUND_HINT}")
        }
    }

    @RequiresReadLock
    private fun collectReferences(
        target: PsiElement,
        scope: GlobalSearchScope,
        maxResults: Int,
    ): Pair<List<PsiReference>, Boolean> {
        val refs: MutableList<PsiReference> = mutableListOf()
        var sawMore = false
        ReferencesSearch.search(target, scope, true).forEach(Processor<PsiReference> { ref ->
            if (refs.size >= maxResults) {
                sawMore = true
                return@Processor false
            }
            refs.add(ref)
            true
        })
        return refs to sawMore
    }

    @RequiresReadLock
    private fun StringBuilder.appendReferenceList(
        project: Project,
        refs: List<PsiReference>,
        sawMore: Boolean,
        maxResults: Int,
    ) {
        if (refs.isEmpty()) {
            appendLine("  (no references found in the current classpath)")
            return
        }
        for (ref: PsiReference in refs) {
            val element = ref.element
            val vf = element.containingFile?.virtualFile
            val path: String = vf?.let { projectRelativePath(project, it) } ?: "(unknown)"
            val line: Int = element.containingFile?.let { f ->
                val doc = PsiDocumentManager.getInstance(project).getDocument(f)
                doc?.getLineNumber(element.textOffset)?.plus(1) ?: 0
            } ?: 0
            appendLine("  $path:$line  ${element.text.take(80)}${if (element.text.length > 80) "..." else ""}")
        }
        if (sawMore) {
            appendLine("  ... (truncated at $maxResults results)")
        }
    }

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription("Finds callers or callees of a method, recursively up to maxDepth levels. Use this tool to trace execution flow when writing mixins. direction: callers (default) — expands each caller into its own callers; callees — walks the method body for outgoing calls, recursing into each callee's body. Callees cover direct method calls, constructor invocations (new Foo(...)), and method references (Foo::bar, Foo::new); synthetic lambda targets are resolved through INVOKEDYNAMIC bootstrap handles so the real lambda\$X\$N target is reported (tagged [lambda]), with constructors tagged [ctor]. Output is owner#name(descriptor) in JVM format (ready to paste into @At(target=\"...\")), indented per depth with [L1], [L2] tags; cycles and already-expanded nodes are marked inline with [cycle]. Callees falls back to bytecode INVOKE analysis when a method body is not available (binary merged JAR classes). maxDepth: default 3 (1 = direct callers/callees only, matching legacy behavior); must be between 1 and 10. maxResults: default 50, shared global budget across all depths and branches; raise for wide hierarchies. For overloaded methods, pass parameterTypes or methodDescriptor to disambiguate. methodDescriptor accepts JVM format (e.g. (Lnet/minecraft/...;)V) — same as in mixin @Inject annotations. For parameterless methods: parameterTypes: [] or methodDescriptor: \"()V\".")
    @Suppress("unused")
    suspend fun mixin_call_hierarchy(
        className: String,
        methodName: String,
        parameterTypes: List<String>? = null,
        methodDescriptor: String? = null,
        direction: String = "callers",
        maxDepth: Int = 3,
        maxResults: Int = 50,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        if (direction != "callers" && direction != "callees") {
            return McpToolCallResult.error(
                "direction must be 'callers' or 'callees' (got '$direction')",
            )
        }
        if (maxDepth < 1 || maxDepth > CALL_HIERARCHY_MAX_DEPTH_CAP) {
            return McpToolCallResult.error(
                "maxDepth must be between 1 and $CALL_HIERARCHY_MAX_DEPTH_CAP (got $maxDepth)",
            )
        }
        if (maxResults < 1) {
            return McpToolCallResult.error("maxResults must be >= 1 (got $maxResults)")
        }

        return smartReadAction(project) {
            val resolution = MethodResolver.resolveDetailed(
                project, className, methodName,
                parameterTypes = parameterTypes,
                methodDescriptor = methodDescriptor,
            )
            if (resolution is MethodResolver.Resolution.Error) {
                return@smartReadAction McpToolCallResult.error(resolution.message)
            }
            val psiMethod: PsiMethod = (resolution as MethodResolver.Resolution.Found).method

            val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)
            val visited: MutableSet<String> = mutableSetOf(CallHierarchyExpander.cycleKeyOf(psiMethod))
            val budget = CallHierarchyExpander.Budget(maxResults)

            val result: String = buildString {
                val targetSig: String = CallHierarchyExpander.presentableSignature(psiMethod)
                appendLine("=== Call hierarchy ($direction): $targetSig ===")
                appendLine()
                val header: String = if (direction == "callers") "Callers" else "Callees"
                appendLine("--- $header (max depth $maxDepth, max results $maxResults) ---")

                when (direction) {
                    "callers" -> {
                        CallHierarchyExpander.expandCallers(
                            project, psiMethod, 0, maxDepth, scope, visited, budget, this,
                        )
                        if (budget.used == 0) {
                            appendLine(
                                "  (no callers found in source or the indexed classpath; reflective, event-bus, " +
                                    "or mixin-framework invocations will not appear here)",
                            )
                        }
                    }
                    "callees" -> {
                        val target = CallHierarchyExpander.targetFor(psiMethod, methodDescriptor)
                        CallHierarchyExpander.expandCallees(
                            project, target, 0, maxDepth, visited, budget, this,
                        )
                    }
                }

                if (budget.truncated) {
                    appendLine()
                    appendLine(
                        "  ... (truncated at $maxResults results — raise maxResults or narrow the query)",
                    )
                }
            }
            McpToolCallResult.text(result)
        }
    }

    companion object {
        /**
         * Hard cap on `mixin_call_hierarchy` recursion depth. A runaway walk
         * is still bounded by `maxResults`, but capping depth up front avoids
         * burning search time on pathological values (e.g. `maxDepth=1000`
         * from a user typo). Documented in the tool description.
         */
        const val CALL_HIERARCHY_MAX_DEPTH_CAP: Int = 10
    }
}
