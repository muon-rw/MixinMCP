package dev.mixinmcp.tools

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.ProjectScope
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.search.searches.ReferencesSearch
import dev.mixinmcp.util.BytecodeAnalyzer
import dev.mixinmcp.util.ClassFileLocator
import dev.mixinmcp.util.FqcnResolver
import dev.mixinmcp.util.MethodResolver
import kotlin.coroutines.coroutineContext
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * MixinMCP toolset — registers all mixin_* tools via the McpToolset extension point.
 * Tools are discovered via [@McpTool] annotations.
 */
class MixinMcpToolset : McpToolset {

    @McpTool
    @McpDescription("Resolves a fully-qualified Java class name and returns its declaration info. Works for project classes, Minecraft sources, mod APIs, and ALL dependency classes. Returns: package, modifiers, superclass, interfaces, source file location. With includeMembers=true (default): also lists all methods with full signatures and all fields with types. With includeSource=true: returns the full decompiled source code (can be large, use sparingly — prefer includeMembers for API overview).")
    @Suppress("unused") // Discovered and invoked by MCP framework via reflection
    suspend fun mixin_find_class(
        className: String,
        includeMembers: Boolean = true,
        includeSource: Boolean = false,
        projectPath: String? = null,
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
                    appendLine("Source: ${vf.path}")
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
    @McpDescription("Searches for classes, methods, or fields by name pattern across project and/or dependencies. Use kind=class|method|field|all. Use scope=all|project|libraries. Query matches names containing the pattern (case-sensitive if caseSensitive=true). Returns FQCN for classes, class#method for methods, class.field for fields.")
    @Suppress("unused")
    suspend fun mixin_search_symbols(
        query: String,
        kind: String = "class",
        scope: String = "all",
        caseSensitive: Boolean = false,
        maxResults: Int = 50,
        projectPath: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        val searchScope: GlobalSearchScope = when (scope) {
            "project" -> ProjectScope.getContentScope(project)
            "libraries" -> ProjectScope.getLibrariesScope(project)
            else -> GlobalSearchScope.allScope(project)
        }

        val result: String = ReadAction.compute<String, Throwable> {
            val cache: PsiShortNamesCache = PsiShortNamesCache.getInstance(project)
            val q: String = if (caseSensitive) query else query.lowercase()
            fun matches(name: String): Boolean {
                val n = if (caseSensitive) name else name.lowercase()
                return n.contains(q)
            }

            buildString {
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
                            appendLine("  ${declClass?.qualifiedName ?: "?"}#$name(...)")
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
                            appendLine("  ${declClass?.qualifiedName ?: "?"}.$name")
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
    @McpDescription("Regex search across dependency/library sources. Searches in SOURCES roots (attached -sources.jar, etc.). Returns file URL and line snippets with matches surrounded by ||. Use mixin_get_dep_source with the URL to read full content.")
    @Suppress("unused")
    suspend fun mixin_search_in_deps(
        regexPattern: String,
        fileMask: String? = null,
        caseSensitive: Boolean = true,
        maxResults: Int = 100,
        timeout: Long = 10000,
        projectPath: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        val pattern: Pattern = try {
            Pattern.compile(
                regexPattern,
                if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE,
            )
        } catch (e: Exception) {
            return McpToolCallResult.error("Invalid regex: ${e.message}")
        }

        val maskGlob: String = fileMask ?: "*"
        val matchesMask: (String) -> Boolean = { fileName ->
            if (maskGlob == "*") true
            else {
                val regex: String = maskGlob
                    .replace(".", "\\.")
                    .replace("*", ".*")
                    .replace("?", ".")
                Regex(regex).matches(fileName)
            }
        }

        val results: MutableList<String> = mutableListOf()
        val startTime: Long = System.currentTimeMillis()

        ReadAction.compute<Unit, Throwable> {
            val modules = ModuleManager.getInstance(project).modules

            for (module in modules) {
                val orderEntries = ModuleRootManager.getInstance(module).orderEntries
                for (entry in orderEntries) {
                if (System.currentTimeMillis() - startTime > timeout) break
                if (results.size >= maxResults) break

                    val libraryRoots = when (entry) {
                        is LibraryOrderEntry -> {
                            entry.library?.getFiles(OrderRootType.SOURCES)?.toList() ?: emptyList()
                        }
                        else -> emptyList()
                    }

                    for (root in libraryRoots) {
                        if (results.size >= maxResults) break
                        collectRegexMatches(root, pattern, matchesMask, results, maxResults, startTime, timeout)
                    }
                }
            }
        }

        val result: String = buildString {
            appendLine("=== Regex search in dependencies: $regexPattern ===")
            appendLine()
            if (results.isEmpty()) {
                appendLine("No matches found.")
            } else {
                for (line: String in results) {
                    appendLine(line)
                }
                if (results.size >= maxResults) {
                    appendLine("  ... (truncated at $maxResults results)")
                }
            }
        }

        return McpToolCallResult.text(result)
    }

    private fun collectRegexMatches(
        vf: VirtualFile,
        pattern: Pattern,
        matchesMask: (String) -> Boolean,
        results: MutableList<String>,
        maxResults: Int,
        startTime: Long,
        timeout: Long,
    ) {
        if (results.size >= maxResults) return
        if (System.currentTimeMillis() - startTime > timeout) return

        if (vf.isDirectory) {
            for (child in vf.children) {
                collectRegexMatches(child, pattern, matchesMask, results, maxResults, startTime, timeout)
            }
        } else {
            if (!matchesMask(vf.name)) return
            val content: String = try {
                String(vf.contentsToByteArray(), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                return
            }
            val lines: List<String> = content.lines()
            for ((i, line) in lines.withIndex()) {
                if (results.size >= maxResults) return
                val matcher = pattern.matcher(line)
                if (matcher.find()) {
                    val highlighted: String = matcher.replaceAll("||\$0||")
                    val lineNum: Int = i + 1
                    results.add("${vf.url}:$lineNum  $highlighted")
                }
            }
        }
    }

    @McpTool
    @McpDescription("Reads content from a dependency file by VirtualFile URL (from mixin_search_in_deps results). Returns a window of lines centered on lineNumber. Prefix each line with its line number for reference.")
    @Suppress("unused")
    suspend fun mixin_get_dep_source(
        url: String,
        lineNumber: Int = 1,
        linesBefore: Int = 30,
        linesAfter: Int = 70,
        projectPath: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        val vf: VirtualFile? =
            VirtualFileManager.getInstance().findFileByUrl(url)

        if (vf == null || !vf.isValid) {
            return McpToolCallResult.error("File not found: $url")
        }

        val content: String = try {
            String(vf.contentsToByteArray(), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            return McpToolCallResult.error("Failed to read file: ${e.message}")
        }

        val lines: List<String> = content.lines()
        val start: Int = (lineNumber - linesBefore).coerceAtLeast(1)
        val end: Int = (lineNumber + linesAfter).coerceAtMost(lines.size)

        val result: String = buildString {
            appendLine("=== ${vf.name} (lines $start-$end) ===")
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

    @McpTool
    @McpDescription("Returns bytecode-level information for a class, including synthetic methods, lambda targets, method descriptors, and access flags. Essential for mixin target resolution where decompiled source hides synthetic members. Use filter=synthetic to show only compiler-generated methods (lambdas, bridge methods, access methods). This is the primary use case for mixin development — finding the real method names for lambda targets. Set includeInstructions=true to get actual bytecode instructions for each method (equivalent to javap -c). Warning: output can be very large. Decompiled source does NOT show synthetic method names. If you need to target a lambda in @Redirect or @Inject mixin, you MUST use this tool.")
    @Suppress("unused") // Discovered and invoked by MCP framework via reflection
    suspend fun mixin_class_bytecode(
        className: String,
        filter: String = "all",
        includeInstructions: Boolean = false,
        projectPath: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        val classBytes: ByteArray? = ClassFileLocator.locate(project, className)
        if (classBytes == null) {
            return McpToolCallResult.error("Class not found or could not locate bytecode: $className")
        }

        val analysis: BytecodeAnalyzer.ClassAnalysis =
            BytecodeAnalyzer.analyze(classBytes, includeInstructions)

        val showMethods: Boolean = filter == "all" || filter == "methods" || filter == "synthetic"
        val showFields: Boolean = filter == "all" || filter == "fields"
        val syntheticOnly: Boolean = filter == "synthetic"

        val result: String = buildString {
            appendLine("=== ${analysis.name} (bytecode) ===")
            appendLine()
            appendLine("Version: ${analysis.version}")
            appendLine("Access: ${BytecodeAnalyzer.accessFlagsToString(analysis.access)}")
            analysis.superName?.let { appendLine("Superclass: $it") }
            if (analysis.interfaces.isNotEmpty()) {
                appendLine("Interfaces: ${analysis.interfaces.joinToString()}")
            }
            appendLine()

            if (showMethods) {
                val methodsToShow = if (syntheticOnly) {
                    analysis.methods.filter { it.isSynthetic }
                } else {
                    analysis.methods
                }
                appendLine("--- Methods ---")
                for (m in methodsToShow) {
                    val lambdaNote: String? = if (m.isLambda) {
                        val src = m.lambdaSourceMethod
                        val idx = m.name.substringAfterLast('$', "0")
                        " → lambda in method: $src, index: $idx"
                    } else null
                    appendLine("  ${BytecodeAnalyzer.accessFlagsToString(m.access)} ${m.name}${m.descriptor}${lambdaNote ?: ""}")
                    if (includeInstructions && m.instructions != null) {
                        m.instructions!!.lines().forEach { appendLine("    $it") }
                    }
                }
                appendLine()
            }

            if (showFields) {
                val fieldsToShow = if (syntheticOnly) {
                    analysis.fields.filter { it.isSynthetic }
                } else {
                    analysis.fields
                }
                appendLine("--- Fields ---")
                for (f in fieldsToShow) {
                    appendLine("  ${BytecodeAnalyzer.accessFlagsToString(f.access)} ${f.name} ${f.descriptor}")
                }
                appendLine()
            }

            val synthetics = analysis.methods.filter { it.isSynthetic }
            if (synthetics.isNotEmpty()) {
                appendLine("--- Synthetic Method Summary (mixin targets) ---")
                for (m in synthetics) {
                    val note: String = if (m.isLambda) {
                        "lambda in ${m.lambdaSourceMethod ?: "?"}"
                    } else if (m.isBridge) {
                        "bridge"
                    } else {
                        "synthetic"
                    }
                    appendLine("  ${m.name}${m.descriptor}  [$note]")
                }
            }
        }

        return McpToolCallResult.text(result)
    }

    @McpTool
    @McpDescription("Returns javap-style bytecode instructions for a single method in a class. Use for lambda targets (e.g. lambda\$tick\$0) or any method. Pass methodDescriptor for overload disambiguation when the method has multiple overloads. Essential for understanding mixin target bytecode.")
    @Suppress("unused")
    suspend fun mixin_method_bytecode(
        className: String,
        methodName: String,
        methodDescriptor: String? = null,
        projectPath: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        val classBytes: ByteArray? = ClassFileLocator.locate(project, className)
        if (classBytes == null) {
            return McpToolCallResult.error("Class not found or could not locate bytecode: $className")
        }

        val result: String? = BytecodeAnalyzer.analyzeMethod(
            classBytes,
            methodName,
            methodDescriptor,
        )

        return when {
            result != null -> McpToolCallResult.text(
                buildString {
                    appendLine("=== $className#$methodName (bytecode) ===")
                    appendLine()
                    append(result)
                },
            )
            else -> McpToolCallResult.error("Method not found: $className#$methodName")
        }
    }

    @McpTool
    @McpDescription("Traverses the type hierarchy of a class. Returns superclasses and/or subclasses. Use direction=supers for superclass chain and interfaces, direction=subs for inheritors, direction=both for full hierarchy. Essential before writing mixins to understand inheritance structure.")
    @Suppress("unused")
    suspend fun mixin_type_hierarchy(
        className: String,
        direction: String = "both",
        maxDepth: Int = 10,
        includeInterfaces: Boolean = true,
        projectPath: String? = null,
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
                        appendLine("  ${sub.qualifiedName}")
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
    @McpDescription("Finds all concrete implementations of an interface or abstract class. Uses ClassInheritorsSearch across project and dependencies. Results are limited by maxResults to avoid performance issues with large dependency sets.")
    @Suppress("unused")
    suspend fun mixin_find_impls(
        className: String,
        maxResults: Int = 50,
        projectPath: String? = null,
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
    @McpDescription("Returns the chain of super method declarations for a method. Shows where the method is originally declared and the full super chain from most specific to most general. Use to confirm mixin target declaration location. For overloaded methods, pass parameterTypes to disambiguate (e.g. [\"E\"] for add(E)). For parameterless methods, pass parameterTypes: [].")
    @Suppress("unused")
    suspend fun mixin_super_methods(
        className: String,
        methodName: String,
        parameterTypes: List<String>? = null,
        projectPath: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        val result: String? = ReadAction.compute<String?, Throwable> {
            val psiMethod: PsiMethod = MethodResolver.resolveSingle(
                project, className, methodName, parameterTypes,
            ) ?: return@compute null

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

        return when {
            result != null -> McpToolCallResult.text(result)
            else -> McpToolCallResult.error("Method not found: $className#$methodName")
        }
    }

    @McpTool
    @McpDescription("Finds all references to a class or class member. If memberName is null, finds references to the class itself. If memberName is set, finds references to that method. For overloaded methods, pass parameterTypes to disambiguate. For parameterless methods, pass parameterTypes: []. Searches across project and dependencies.")
    @Suppress("unused")
    suspend fun mixin_find_references(
        className: String,
        memberName: String? = null,
        parameterTypes: List<String>? = null,
        maxResults: Int = 100,
        projectPath: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        val result: String? = ReadAction.compute<String?, Throwable> {
            val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)

            val elementToSearch = if (memberName == null) {
                FqcnResolver.resolveNested(project, className)
            } else {
                MethodResolver.resolveSingle(project, className, memberName, parameterTypes)
            } ?: return@compute null

            val query = ReferencesSearch.search(elementToSearch, scope, true)
            val refs: MutableList<PsiReference> = mutableListOf()
            var count: Int = 0
            query.forEach { ref ->
                if (count >= maxResults) return@forEach
                refs.add(ref)
                count++
            }

            buildString {
                val targetDesc: String = if (memberName == null) className else "$className#$memberName"
                appendLine("=== References to $targetDesc ===")
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
            else -> McpToolCallResult.error("Element not found: $className${memberName?.let { "#$it" } ?: ""}")
        }
    }

    @McpTool
    @McpDescription("Finds callers or callees of a method. direction=callers uses MethodReferencesSearch to find where the method is called. direction=callees walks the method body to find methods it calls. Use for understanding call flow when writing mixins. For overloaded methods, pass parameterTypes to disambiguate. For parameterless methods, pass parameterTypes: [].")
    @Suppress("unused")
    suspend fun mixin_call_hierarchy(
        className: String,
        methodName: String,
        parameterTypes: List<String>? = null,
        direction: String = "callers",
        maxDepth: Int = 3,
        maxResults: Int = 50,
        projectPath: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        val result: String? = ReadAction.compute<String?, Throwable> {
            val psiMethod: PsiMethod = MethodResolver.resolveSingle(
                project, className, methodName, parameterTypes,
            ) ?: return@compute null

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
                    val body = psiMethod.body ?: run {
                        appendLine("  (abstract or native method — no body)")
                        return@buildString
                    }
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
                }
            }
        }

        return when {
            result != null -> McpToolCallResult.text(result)
            else -> McpToolCallResult.error("Method not found: $className#$methodName")
        }
    }

    @McpTool
    @McpDescription("Triggers Gradle or Maven project sync to refresh dependencies. Call after changing build.gradle or pom.xml. The sync runs in the background; dependencies will be updated when complete.")
    @Suppress("unused")
    suspend fun mixin_sync_project(
        projectPath: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        val basePath: String = project.basePath ?: return McpToolCallResult.error(
            "Project has no base path",
        )

        val externalPath: String = projectPath ?: basePath

        // External System refresh must run on EDT; use invokeLater to avoid blocking
        ApplicationManager.getApplication().invokeLater {
            FileDocumentManager.getInstance().saveAllDocuments()
            val gradleId: ProjectSystemId = ProjectSystemId("GRADLE")
            val spec: ImportSpecBuilder = ImportSpecBuilder(project, gradleId)
                .use(ProgressExecutionMode.START_IN_FOREGROUND_ASYNC)
            try {
                ExternalSystemUtil.refreshProject(externalPath, spec.build())
            } catch (_: Exception) {
                // Project may not be Gradle; Maven uses "Maven" as system ID
                try {
                    val mavenSpec: ImportSpecBuilder =
                        ImportSpecBuilder(project, ProjectSystemId("Maven"))
                            .use(ProgressExecutionMode.START_IN_FOREGROUND_ASYNC)
                    ExternalSystemUtil.refreshProject(externalPath, mavenSpec.build())
                } catch (_: Exception) {
                    // Ignore — project may not be Gradle/Maven
                }
            }
        }

        return McpToolCallResult.text(
            "Project sync triggered for $externalPath. Dependencies will refresh in the background.",
        )
    }
}
