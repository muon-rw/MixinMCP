package dev.mixinmcp.tools.semantic

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import dev.mixinmcp.resolve.FqcnResolver
import dev.mixinmcp.resolve.MethodResolver
import kotlin.coroutines.coroutineContext

/**
 * Semantic-navigation tools: type hierarchy, implementors, mixin target
 * discovery, super-method chains, references, and call hierarchy.
 */
class SemanticNavigationToolset : McpToolset {

    @McpTool
    @McpDescription("Retrieves the type hierarchy of a class. Use this tool to understand inheritance before writing mixins. direction: supers (superclass chain + all interfaces, direct and transitively inherited), subs (inheritors), both (default). maxDepth limits superclass traversal (default 10, must be >= 1); also bounds how far up the superclass chain inherited interfaces are collected from. includeInterfaces: default true. Inherited interfaces are collected from the full superclass chain (within maxDepth) and from super-interface extension, deduplicated by qualified name, and each tagged with origin (from X = introduced by superclass X; via X = inherited by extending interface X). maxResults caps subclasses/implementors (default 50, must be >= 1) — raise for heavily-inherited classes like LivingEntity or Block.")
    @Suppress("unused")
    suspend fun mixin_type_hierarchy(
        className: String,
        direction: String = "both",
        maxDepth: Int = 10,
        includeInterfaces: Boolean = true,
        maxResults: Int = 50,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")
        if (maxResults < 1) {
            return McpToolCallResult.error("maxResults must be >= 1 (got $maxResults)")
        }
        if (maxDepth < 1) {
            return McpToolCallResult.error("maxDepth must be >= 1 (got $maxDepth)")
        }

        val result: String? = ReadAction.nonBlocking<String?> {
            val psiClass: PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@nonBlocking null

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
                    if (includeInterfaces) {
                        appendLine()
                        appendInterfaceSections(psiClass, maxDepth)
                    }
                    appendLine()
                }

                if (direction == "subs" || direction == "both") {
                    appendLine("--- Subclasses / implementors (max $maxResults) ---")
                    val query = ClassInheritorsSearch.search(psiClass, scope, true)
                    var count: Int = 0
                    query.forEach { sub: PsiClass ->
                        if (count >= maxResults) return@forEach
                        val displayName: String =
                            sub.qualifiedName
                                ?: sub.containingClass?.qualifiedName?.let { "$it\$anonymous" }
                                ?: sub.name
                                ?: return@forEach
                        appendLine("  $displayName")
                        count++
                    }
                    if (count >= maxResults) {
                        appendLine("  ... (truncated at $maxResults results)")
                    }
                    appendLine()
                }
            }
        }.inSmartMode(project).executeSynchronously()

        return when {
            result != null -> McpToolCallResult.text(result)
            else -> McpToolCallResult.error("Class not found: $className")
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

    @McpTool
    @McpDescription("Finds all implementations of an interface or abstract class across project and dependencies. maxResults: 50 default.")
    @Suppress("unused")
    suspend fun mixin_find_impls(
        className: String,
        maxResults: Int = 50,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        val result: String? = ReadAction.nonBlocking<String?> {
            val psiClass: PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@nonBlocking null

            val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)
            val query = ClassInheritorsSearch.search(psiClass, scope, true)

            buildString {
                appendLine("=== Implementors of ${psiClass.qualifiedName} ===")
                appendLine()
                var count: Int = 0
                query.forEach { sub: PsiClass ->
                    if (count >= maxResults) return@forEach
                    // Filter null: anonymous/inner classes may have null qualifiedName
                    val displayName: String =
                        sub.qualifiedName
                            ?: sub.containingClass?.qualifiedName?.let { "$it\$anonymous" }
                            ?: sub.name
                            ?: "(anonymous)"
                    appendLine("  $displayName")
                    count++
                }
                if (count >= maxResults) {
                    appendLine("  ... (truncated at $maxResults results)")
                }
            }
        }.inSmartMode(project).executeSynchronously()

        return when {
            result != null -> McpToolCallResult.text(result)
            else -> McpToolCallResult.error("Class not found: $className")
        }
    }

    @McpTool
    @McpDescription("Finds all @Mixin classes that target a given class (and optionally a specific method). Use this for cross-mod conflict analysis — discover which other mods inject into the same target. Returns mixin FQCN, injection points (@Inject, @Redirect, @Overwrite, etc.), and source location. methodName: optionally narrow to mixins targeting that method. maxResults: 50 default.")
    @Suppress("unused")
    suspend fun mixin_find_targeting_mixins(
        className: String,
        methodName: String? = null,
        maxResults: Int = 50,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        val result: String = ReadAction.nonBlocking<String> {
            val normalizedTarget: String = className.replace('/', '.')
            val mixinAnnotationClass: PsiClass? =
                FqcnResolver.resolveNested(project, "org.spongepowered.asm.mixin.Mixin")

            val mixins: MutableList<Pair<PsiClass, List<String>>> = mutableListOf()

            if (mixinAnnotationClass != null) {
                val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)
                val query = AnnotatedElementsSearch.searchPsiClasses(mixinAnnotationClass, scope)
                query.forEach { psiClass: PsiClass ->
                    if (mixins.size >= maxResults) return@forEach
                    val targets: List<String> = extractMixinTargets(psiClass)
                    if (targets.any { normalizeForMatch(it) == normalizeForMatch(normalizedTarget) }) {
                        val injections: List<String> = if (methodName != null) {
                            extractInjectionsForMethod(psiClass, methodName)
                        } else {
                            extractAllInjections(psiClass)
                        }
                        if (methodName == null || injections.isNotEmpty()) {
                            mixins.add(psiClass to injections)
                        }
                    }
                }
            }

            if (mixins.isEmpty()) {
                val fallback: List<Pair<String, String>> = findTargetingMixinsByRegex(project, normalizedTarget, methodName, maxResults)
                buildString {
                    appendLine("=== Mixins targeting $normalizedTarget${if (methodName != null) "#$methodName" else ""} ===")
                    appendLine()
                    if (fallback.isEmpty()) {
                        appendLine("No mixins found targeting this class.")
                        if (mixinAnnotationClass == null) {
                            appendLine("(Mixin library may not be on classpath — add org.spongepowered:mixin as dependency)")
                        }
                    } else {
                        for ((i, pair) in fallback.withIndex()) {
                            appendLine("${i + 1}. ${pair.first}")
                            appendLine("   Source: ${pair.second}")
                            appendLine()
                        }
                    }
                }
            } else {
                buildString {
                    appendLine("=== Mixins targeting $normalizedTarget${if (methodName != null) "#$methodName" else ""} ===")
                    appendLine()
                    for ((i, pair) in mixins.withIndex()) {
                        val (psiClass, injections) = pair
                        val fqcn: String = psiClass.qualifiedName ?: psiClass.name ?: "?"
                        val source: String = psiClass.containingFile?.virtualFile?.path ?: "(unknown)"
                        appendLine("${i + 1}. $fqcn")
                        for (inj in injections.take(10)) {
                            appendLine("   $inj")
                        }
                        if (injections.size > 10) {
                            appendLine("   ... (${injections.size - 10} more)")
                        }
                        appendLine("   Source: $source")
                        appendLine()
                    }
                    if (mixins.size >= maxResults) {
                        appendLine("  ... (truncated at $maxResults results)")
                    }
                }
            }
        }.inSmartMode(project).executeSynchronously()

        return McpToolCallResult.text(result)
    }

    @McpTool
    @McpDescription("Walks the full super-method chain for a method through all superclasses and super-interfaces to the original declaration(s). Use this to confirm where a method is originally declared before targeting it in a mixin — root declarations (those with no further super) are usually the best mixin targets for base behavior. Output indents by depth (2 spaces per level) and tags each entry as [root declaration] and/or [interface] where applicable. When multiple roots exist (e.g. a class root and an interface default), they are summarized at the end. For overloaded methods, pass parameterTypes or methodDescriptor to disambiguate. methodDescriptor accepts JVM format (e.g. (Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z) — same as in @Inject(method = \"...\"). For parameterless methods: parameterTypes: [] or methodDescriptor: \"()V\".")
    @Suppress("unused")
    suspend fun mixin_super_methods(
        className: String,
        methodName: String,
        parameterTypes: List<String>? = null,
        methodDescriptor: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        val resolution = MethodResolver.resolveDetailed(
            project, className, methodName,
            parameterTypes = parameterTypes,
            methodDescriptor = methodDescriptor,
        )
        if (resolution is MethodResolver.Resolution.Error) {
            return McpToolCallResult.error(resolution.message)
        }
        val psiMethod: PsiMethod = (resolution as MethodResolver.Resolution.Found).method

        val result: String = ReadAction.nonBlocking<String> {
            val containingClass: PsiClass? = psiMethod.containingClass

            val chain: List<SuperChainEntry> = buildSuperChain(psiMethod)
            val rootEntries: List<SuperChainEntry> = chain.filter { it.isRoot }

            buildString {
                appendLine("=== Super methods for ${psiMethod.name} ===")
                appendLine()
                appendLine("Declared in: ${containingClass?.qualifiedName ?: "?"}")
                appendLine("Signature: ${psiMethod.name}(${psiMethod.parameterList.parameters.joinToString { "${it.type.presentableText} ${it.name}" }})")
                appendLine()
                if (chain.isEmpty()) {
                    appendLine("No super methods (method is declared here, not inherited).")
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
        }.inSmartMode(project).executeSynchronously()

        return McpToolCallResult.text(result)
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
     * Recursively walks super methods of [start] via DFS. De-duplicates by
     * (declaringClassFqn, methodName, canonicalParamTypes) so diamond
     * interface hierarchies don't inflate the chain. Must be called inside a
     * ReadAction.
     */
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
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")
        if (maxResults < 1) {
            return McpToolCallResult.error("maxResults must be >= 1 (got $maxResults)")
        }

        val resolution = MethodResolver.resolveDetailed(
            project, className, methodName,
            parameterTypes = parameterTypes,
            methodDescriptor = methodDescriptor,
        )
        if (resolution is MethodResolver.Resolution.Error) {
            return McpToolCallResult.error(resolution.message)
        }
        val psiMethod: PsiMethod = (resolution as MethodResolver.Resolution.Found).method

        val result: String = ReadAction.nonBlocking<String> {
            val containingClass: PsiClass? = psiMethod.containingClass
            val declFqcn: String = containingClass?.qualifiedName ?: className
            val paramList: String = psiMethod.parameterList.parameters
                .joinToString { "${it.type.presentableText} ${it.name}" }

            buildString {
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
                val overrides: MutableList<PsiMethod> = mutableListOf()
                query.forEach { overriding: PsiMethod ->
                    if (overrides.size >= maxResults) return@forEach
                    overrides.add(overriding)
                }

                if (overrides.isEmpty()) {
                    appendLine("  (no overrides found in the current classpath)")
                    return@buildString
                }

                for ((i, overriding: PsiMethod) in overrides.withIndex()) {
                    val declClass: PsiClass? = overriding.containingClass
                    val fqcn: String =
                        declClass?.qualifiedName
                            ?: declClass?.containingClass?.qualifiedName?.let { "$it\$anonymous" }
                            ?: declClass?.name
                            ?: "(unknown)"
                    val isAbstract: Boolean = overriding.hasModifierProperty(PsiModifier.ABSTRACT)
                    val abstractTag: String = if (isAbstract) " [abstract]" else ""
                    val vf = overriding.containingFile?.virtualFile
                    val path: String = vf?.path ?: "(unknown)"
                    val line: Int = overriding.containingFile?.let { f ->
                        val doc = PsiDocumentManager.getInstance(project).getDocument(f) ?: return@let 0
                        val offset: Int = overriding.textOffset
                        if (offset < 0 || offset >= doc.textLength) 0
                        else doc.getLineNumber(offset) + 1
                    } ?: 0
                    val locationSuffix: String = if (line > 0) ":$line" else ""

                    appendLine("  ${i + 1}. $fqcn$abstractTag")
                    appendLine("     Source: $path$locationSuffix")
                }
                if (overrides.size >= maxResults) {
                    appendLine("  ... (truncated at $maxResults results)")
                }
            }
        }.inSmartMode(project).executeSynchronously()

        return McpToolCallResult.text(result)
    }

    @McpTool
    @McpDescription("Find all references to a class or member across project and dependencies. Without memberName: references to the class. With memberName: references to that method or field. For overloaded methods, pass parameterTypes or methodDescriptor to disambiguate. methodDescriptor accepts JVM format (e.g. (Lnet/minecraft/...;)V) — same as in mixin @Inject annotations. For parameterless methods: parameterTypes: [] or methodDescriptor: \"()V\". maxResults: 100 default.")
    @Suppress("unused")
    suspend fun mixin_find_references(
        className: String,
        memberName: String? = null,
        parameterTypes: List<String>? = null,
        methodDescriptor: String? = null,
        maxResults: Int = 100,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        if (memberName != null) {
            val fieldResult: PsiField? = ReadAction.nonBlocking<PsiField?> {
                val psiClass: PsiClass? = FqcnResolver.resolveNested(project, className)
                psiClass?.findFieldByName(memberName, true)
            }.inSmartMode(project).executeSynchronously()

            if (fieldResult != null && parameterTypes == null && methodDescriptor == null) {
                val result: String = ReadAction.nonBlocking<String> {
                    val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)
                    val query = ReferencesSearch.search(fieldResult, scope, true)
                    val refs: MutableList<PsiReference> = mutableListOf()
                    var count: Int = 0
                    query.forEach { ref ->
                        if (count >= maxResults) return@forEach
                        refs.add(ref)
                        count++
                    }

                    buildString {
                        appendLine("=== References to $className.$memberName (field) ===")
                        appendLine()
                        appendLine("Field type: ${fieldResult.type.presentableText}")
                        appendLine()
                        for (ref: PsiReference in refs) {
                            val element = ref.element
                            val file = element.containingFile
                            val vf = file?.virtualFile
                            val path: String = vf?.path ?: "(unknown)"
                            val line: Int = element.containingFile?.let { f ->
                                val doc = PsiDocumentManager.getInstance(project).getDocument(f)
                                doc?.getLineNumber(element.textOffset)?.plus(1) ?: 0
                            } ?: 0
                            appendLine("  $path:$line  ${element.text.take(80)}${if (element.text.length > 80) "..." else ""}")
                        }
                        if (refs.size >= maxResults) {
                            appendLine("  ... (truncated at $maxResults results)")
                        }
                    }
                }.inSmartMode(project).executeSynchronously()
                return McpToolCallResult.text(result)
            }

            val resolution = MethodResolver.resolveDetailed(
                project, className, memberName,
                parameterTypes = parameterTypes,
                methodDescriptor = methodDescriptor,
            )
            if (resolution is MethodResolver.Resolution.Error) {
                if (fieldResult != null) {
                    val result: String = ReadAction.nonBlocking<String> {
                        val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)
                        val query = ReferencesSearch.search(fieldResult, scope, true)
                        val refs: MutableList<PsiReference> = mutableListOf()
                        var count: Int = 0
                        query.forEach { ref ->
                            if (count >= maxResults) return@forEach
                            refs.add(ref)
                            count++
                        }

                        buildString {
                            appendLine("=== References to $className.$memberName (field) ===")
                            appendLine()
                            appendLine("Field type: ${fieldResult.type.presentableText}")
                            appendLine("(Note: no method named '$memberName' was found; showing field references.)")
                            appendLine()
                            for (ref: PsiReference in refs) {
                                val element = ref.element
                                val file = element.containingFile
                                val vf = file?.virtualFile
                                val path: String = vf?.path ?: "(unknown)"
                                val line: Int = element.containingFile?.let { f ->
                                    val doc = PsiDocumentManager.getInstance(project).getDocument(f)
                                    doc?.getLineNumber(element.textOffset)?.plus(1) ?: 0
                                } ?: 0
                                appendLine("  $path:$line  ${element.text.take(80)}${if (element.text.length > 80) "..." else ""}")
                            }
                            if (refs.size >= maxResults) {
                                appendLine("  ... (truncated at $maxResults results)")
                            }
                        }
                    }.inSmartMode(project).executeSynchronously()
                    return McpToolCallResult.text(result)
                }
                return McpToolCallResult.error(resolution.message)
            }
            val psiMethod: PsiMethod = (resolution as MethodResolver.Resolution.Found).method

            val result: String = ReadAction.nonBlocking<String> {
                val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)
                val query = ReferencesSearch.search(psiMethod, scope, true)
                val refs: MutableList<PsiReference> = mutableListOf()
                var count: Int = 0
                query.forEach { ref ->
                    if (count >= maxResults) return@forEach
                    refs.add(ref)
                    count++
                }

                buildString {
                    appendLine("=== References to $className#$memberName ===")
                    appendLine()
                    for (ref: PsiReference in refs) {
                        val element = ref.element
                        val file = element.containingFile
                        val vf = file?.virtualFile
                        val path: String = vf?.path ?: "(unknown)"
                        val line: Int = element.containingFile?.let { f ->
                            val doc = PsiDocumentManager.getInstance(project).getDocument(f)
                            doc?.getLineNumber(element.textOffset)?.plus(1) ?: 0
                        } ?: 0
                        appendLine("  $path:$line  ${element.text.take(80)}${if (element.text.length > 80) "..." else ""}")
                    }
                    if (refs.size >= maxResults) {
                        appendLine("  ... (truncated at $maxResults results)")
                    }
                }
            }.inSmartMode(project).executeSynchronously()
            return McpToolCallResult.text(result)
        }

        // Class-level reference search
        val result: String? = ReadAction.nonBlocking<String?> {
            val psiClass: PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@nonBlocking null

            val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)
            val query = ReferencesSearch.search(psiClass, scope, true)
            val refs: MutableList<PsiReference> = mutableListOf()
            var count: Int = 0
            query.forEach { ref ->
                if (count >= maxResults) return@forEach
                refs.add(ref)
                count++
            }

            buildString {
                appendLine("=== References to $className ===")
                appendLine()
                for (ref: PsiReference in refs) {
                    val element = ref.element
                    val file = element.containingFile
                    val vf = file?.virtualFile
                    val path: String = vf?.path ?: "(unknown)"
                    val line: Int = element.containingFile?.let { f ->
                        val doc = PsiDocumentManager.getInstance(project).getDocument(f)
                        doc?.getLineNumber(element.textOffset)?.plus(1) ?: 0
                    } ?: 0
                    appendLine("  $path:$line  ${element.text.take(80)}${if (element.text.length > 80) "..." else ""}")
                }
                if (refs.size >= maxResults) {
                    appendLine("  ... (truncated at $maxResults results)")
                }
            }
        }.inSmartMode(project).executeSynchronously()

        return when {
            result != null -> McpToolCallResult.text(result)
            else -> McpToolCallResult.error("Class not found: $className")
        }
    }

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
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

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

        val resolution = MethodResolver.resolveDetailed(
            project, className, methodName,
            parameterTypes = parameterTypes,
            methodDescriptor = methodDescriptor,
        )
        if (resolution is MethodResolver.Resolution.Error) {
            return McpToolCallResult.error(resolution.message)
        }
        val psiMethod: PsiMethod = (resolution as MethodResolver.Resolution.Found).method

        val result: String = ReadAction.nonBlocking<String> {
            val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)
            val visited: MutableSet<String> = mutableSetOf(CallHierarchyExpander.cycleKeyOf(psiMethod))
            val budget = CallHierarchyExpander.Budget(maxResults)

            buildString {
                val targetSig: String = CallHierarchyExpander.presentableSignature(psiMethod)
                appendLine("=== Call hierarchy ($direction): $targetSig ===")
                appendLine()
                val header: String = if (direction == "callers") "Callers" else "Callees"
                appendLine("--- $header (max depth $maxDepth, max results $maxResults) ---")

                when (direction) {
                    "callers" -> CallHierarchyExpander.expandCallers(
                        project, psiMethod, 0, maxDepth, scope, visited, budget, this,
                    )
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
        }.inSmartMode(project).executeSynchronously()

        return McpToolCallResult.text(result)
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