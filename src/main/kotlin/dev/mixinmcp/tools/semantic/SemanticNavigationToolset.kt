package dev.mixinmcp.tools.semantic

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.search.searches.ReferencesSearch
import dev.mixinmcp.resolve.BytecodeAnalyzer
import dev.mixinmcp.resolve.ClassFileLocator
import dev.mixinmcp.resolve.FqcnResolver
import dev.mixinmcp.resolve.MethodResolver
import kotlin.coroutines.coroutineContext

/**
 * Semantic-navigation tools: type hierarchy, implementors, mixin target
 * discovery, super-method chains, references, and call hierarchy.
 */
class SemanticNavigationToolset : McpToolset {

    @McpTool
    @McpDescription("Retrieves the type hierarchy of a class. Use this tool to understand inheritance before writing mixins. direction: supers (superclass chain + interfaces), subs (inheritors), both (default). maxDepth limits superclass traversal (default 10). includeInterfaces: default true.")
    @Suppress("unused")
    suspend fun mixin_type_hierarchy(
        className: String,
        direction: String = "both",
        maxDepth: Int = 10,
        includeInterfaces: Boolean = true,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        val result: String? = ReadAction.compute<String?, Throwable> {
            val psiClass: PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@compute null

            val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)
            val subsLimit: Int = 50 // Limit inheritors to avoid performance issues

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
                        appendLine("--- Direct interfaces ---")
                        for (iface: PsiClass in psiClass.interfaces) {
                            appendLine("  ${iface.qualifiedName}")
                        }
                    }
                    appendLine()
                }

                if (direction == "subs" || direction == "both") {
                    appendLine("--- Subclasses / implementors (max $subsLimit) ---")
                    val query = ClassInheritorsSearch.search(psiClass, scope, true)
                    var count: Int = 0
                    query.forEach { sub: PsiClass ->
                        if (count >= subsLimit) return@forEach
                        val displayName: String =
                            sub.qualifiedName
                                ?: sub.containingClass?.qualifiedName?.let { "$it\$anonymous" }
                                ?: sub.name
                                ?: return@forEach
                        appendLine("  $displayName")
                        count++
                    }
                    if (count >= subsLimit) {
                        appendLine("  ... (truncated at $subsLimit results)")
                    }
                    appendLine()
                }
            }
        }

        return when {
            result != null -> McpToolCallResult.text(result)
            else -> McpToolCallResult.error("Class not found: $className")
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

        val result: String? = ReadAction.compute<String?, Throwable> {
            val psiClass: PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@compute null

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
        }

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

        val result: String = ReadAction.compute<String, Throwable> {
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
        }

        return McpToolCallResult.text(result)
    }

    @McpTool
    @McpDescription("Returns the super method declaration chain for a method. Use this tool to confirm where a method is originally declared before targeting it in a mixin. Shows all overrides from most specific to most general. For overloaded methods, pass parameterTypes or methodDescriptor to disambiguate. methodDescriptor accepts JVM format (e.g. (Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z) — same as in @Inject(method = \"...\"). For parameterless methods: parameterTypes: [] or methodDescriptor: \"()V\".")
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

        val result: String = ReadAction.compute<String, Throwable> {
            val superMethods: Array<PsiMethod> = psiMethod.findSuperMethods(false)
            val containingClass: PsiClass? = psiMethod.containingClass

            buildString {
                appendLine("=== Super methods for ${psiMethod.name} ===")
                appendLine()
                appendLine("Declared in: ${containingClass?.qualifiedName ?: "?"}")
                appendLine("Signature: ${psiMethod.name}(${psiMethod.parameterList.parameters.joinToString { "${it.type.presentableText} ${it.name}" }})")
                appendLine()
                if (superMethods.isEmpty()) {
                    appendLine("No super methods (method is declared here, not inherited).")
                } else {
                    appendLine("--- Super method chain ---")
                    for ((i, superMethod: PsiMethod) in superMethods.withIndex()) {
                        val declClass: PsiClass? = superMethod.containingClass
                        appendLine("  ${i + 1}. ${declClass?.qualifiedName ?: "?"}#${superMethod.name}(...)")
                    }
                }
            }
        }

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
            val fieldResult: PsiField? = ReadAction.compute<PsiField?, Throwable> {
                val psiClass: PsiClass? = FqcnResolver.resolveNested(project, className)
                psiClass?.findFieldByName(memberName, true)
            }

            if (fieldResult != null && parameterTypes == null && methodDescriptor == null) {
                val result: String = ReadAction.compute<String, Throwable> {
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
                }
                return McpToolCallResult.text(result)
            }

            val resolution = MethodResolver.resolveDetailed(
                project, className, memberName,
                parameterTypes = parameterTypes,
                methodDescriptor = methodDescriptor,
            )
            if (resolution is MethodResolver.Resolution.Error) {
                if (fieldResult != null) {
                    val result: String = ReadAction.compute<String, Throwable> {
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
                    }
                    return McpToolCallResult.text(result)
                }
                return McpToolCallResult.error(resolution.message)
            }
            val psiMethod: PsiMethod = (resolution as MethodResolver.Resolution.Found).method

            val result: String = ReadAction.compute<String, Throwable> {
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
            }
            return McpToolCallResult.text(result)
        }

        // Class-level reference search
        val result: String? = ReadAction.compute<String?, Throwable> {
            val psiClass: PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@compute null

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
        }

        return when {
            result != null -> McpToolCallResult.text(result)
            else -> McpToolCallResult.error("Class not found: $className")
        }
    }

    @McpTool
    @McpDescription("Finds callers or callees of a method. Use this tool to trace execution flow when writing mixins. direction: callers (default) — finds call sites; callees — walks method body for outgoing calls (falls back to bytecode INVOKE analysis if source body is not available, e.g. binary merged JAR classes). For overloaded methods, pass parameterTypes or methodDescriptor to disambiguate. methodDescriptor accepts JVM format (e.g. (Lnet/minecraft/...;)V) — same as in mixin @Inject annotations. For parameterless methods: parameterTypes: [] or methodDescriptor: \"()V\". maxResults: 50 default.")
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

        val resolution = MethodResolver.resolveDetailed(
            project, className, methodName,
            parameterTypes = parameterTypes,
            methodDescriptor = methodDescriptor,
        )
        if (resolution is MethodResolver.Resolution.Error) {
            return McpToolCallResult.error(resolution.message)
        }
        val psiMethod: PsiMethod = (resolution as MethodResolver.Resolution.Found).method

        val result: String = ReadAction.compute<String, Throwable> {
            val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)

            buildString {
                appendLine("=== Call hierarchy: ${psiMethod.containingClass?.qualifiedName}#${psiMethod.name} ===")
                appendLine()

                if (direction == "callers") {
                    appendLine("--- Callers (max $maxResults) ---")
                    val query = MethodReferencesSearch.search(psiMethod, scope, false)
                    var count: Int = 0
                    query.forEach { ref ->
                        if (count >= maxResults) return@forEach
                        val element = ref.element
                        val file = element.containingFile
                        val vf = file?.virtualFile
                        val path: String = vf?.path ?: "(unknown)"
                        val line: Int = element.containingFile?.let { f ->
                            val doc = PsiDocumentManager.getInstance(project).getDocument(f)
                            doc?.getLineNumber(element.textOffset)?.plus(1) ?: 0
                        } ?: 0
                        appendLine("  $path:$line  ${element.text.take(80)}${if (element.text.length > 80) "..." else ""}")
                        count++
                    }
                    if (count >= maxResults) {
                        appendLine("  ... (truncated at $maxResults results)")
                    }
                } else {
                    appendLine("--- Callees ---")
                    val body = psiMethod.body
                    if (body != null) {
                        val callees: MutableSet<String> = mutableSetOf()
                        body.accept(object : JavaRecursiveElementVisitor() {
                            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                                super.visitMethodCallExpression(expression)
                                val called: PsiMethod? = expression.resolveMethod()
                                called?.let { m ->
                                    val declClass: PsiClass? = m.containingClass
                                    val sig: String = "${declClass?.qualifiedName ?: "?"}#${m.name}(...)"
                                    callees.add(sig)
                                }
                            }
                        })
                        var count: Int = 0
                        for (sig: String in callees.sorted()) {
                            if (count >= maxResults) break
                            appendLine("  $sig")
                            count++
                        }
                        if (callees.size >= maxResults) {
                            appendLine("  ... (truncated at $maxResults results)")
                        }
                    } else {
                        val qualName = psiMethod.containingClass?.qualifiedName ?: className
                        val classBytes: ByteArray? = ClassFileLocator.locate(project, qualName)
                        if (classBytes != null) {
                            val bytecodeResult: String? = BytecodeAnalyzer.analyzeMethod(
                                classBytes, psiMethod.name, methodDescriptor,
                            )
                            if (bytecodeResult != null) {
                                appendLine("  (source body not available — extracting from bytecode)")
                                appendLine()
                                val invokePattern = Regex("""INVOKE\w+\s+(\S+)\.(\w[\w${'$'}]*)\s""")
                                val callees: MutableSet<String> = mutableSetOf()
                                for (line in bytecodeResult.lines()) {
                                    val trimmed = line.trim()
                                    if (trimmed.startsWith("INVOKE")) {
                                        val match = invokePattern.find(trimmed)
                                        if (match != null) {
                                            val owner = match.groupValues[1].replace('/', '.')
                                            val name = match.groupValues[2]
                                            callees.add("$owner#$name(...)")
                                        }
                                    }
                                }
                                var count: Int = 0
                                for (sig: String in callees.sorted()) {
                                    if (count >= maxResults) break
                                    appendLine("  $sig")
                                    count++
                                }
                                if (callees.size >= maxResults) {
                                    appendLine("  ... (truncated at $maxResults results)")
                                }
                                if (callees.isEmpty()) {
                                    appendLine("  (no outgoing calls found in bytecode)")
                                }
                            } else {
                                appendLine("  (abstract or native method — no body available in source or bytecode)")
                            }
                        } else {
                            appendLine("  (abstract or native method — no body available)")
                        }
                    }
                }
            }
        }

        return McpToolCallResult.text(result)
    }
}