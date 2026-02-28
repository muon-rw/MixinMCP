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
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
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
import dev.mixinmcp.cache.DecompilationCacheService
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
    @McpDescription("Look up any class by fully-qualified name — project, dependencies, and JDK. Use dots for inner classes (e.g. net.minecraft.world.item.Item.Properties). Returns package, modifiers, supertypes, source location. includeMembers (default true): all methods with signatures and all fields with types. includeSource: full source code — can be very large, prefer includeMembers for API overview.")
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
    @McpDescription("Find classes, methods, or fields by name substring across project and dependencies. kind: class (default), method, field, all. scope: all (default), project, libraries. Returns FQCN for classes, class#method(…) for methods, class.field for fields. maxResults defaults to 50.")
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
    @McpDescription("Searches dependency/library sources with a regex pattern — both published -sources.jar and auto-decompiled. Use this tool to grep across your entire classpath. Returns url (pass to mixin_get_dep_source) and matching line snippets with matches in ||markers||. regexPattern: prefer simple single-term patterns; make separate calls for multiple patterns. fileMask: glob matched against the full file path inside the jar (e.g. *minecraft* matches net/minecraft/…/Level.java; *LivingEntity* matches that specific class); defaults to all files. timeout: 15s default — set 20000–30000 for broad unfiltered searches. maxResults: 100 default.")
    @Suppress("unused")
    suspend fun mixin_search_in_deps(
        regexPattern: String,
        fileMask: String? = null,
        caseSensitive: Boolean = true,
        maxResults: Int = 100,
        timeout: Long = 15000,
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
                Regex(regex, RegexOption.IGNORE_CASE).matches(fileName)
            }
        }

        val results: MutableList<String> = mutableListOf()
        val startTime: Long = System.currentTimeMillis()
        var timedOut = false

        ReadAction.compute<Unit, Throwable> {
            for (root in collectAllSourceRoots(project)) {
                if (System.currentTimeMillis() - startTime > timeout) { timedOut = true; break }
                if (results.size >= maxResults) break
                collectRegexMatches(root, root, pattern, matchesMask, results, maxResults, startTime, timeout)
            }
            if (!timedOut && System.currentTimeMillis() - startTime > timeout) timedOut = true
        }

        val elapsed: Long = System.currentTimeMillis() - startTime
        val result: String = buildString {
            appendLine("=== Regex search in dependencies: $regexPattern ===")
            appendLine()
            if (results.isEmpty()) {
                appendLine("No matches found.")
                if (timedOut) {
                    appendLine("(search timed out after ${elapsed}ms — try a more specific pattern, add fileMask, or increase timeout)")
                }
            } else {
                for (line: String in results) {
                    appendLine(line)
                }
                if (results.size >= maxResults) {
                    appendLine("  ... (truncated at $maxResults results)")
                }
                if (timedOut) {
                    appendLine("  ... (search timed out after ${elapsed}ms — not all files were searched)")
                }
            }
        }

        return McpToolCallResult.text(result)
    }

    /**
     * Collects all source roots: SOURCES from library order entries plus
     * source roots from AdditionalLibraryRootsProvider (decompiled cache).
     * Must be called inside ReadAction.
     */
    private fun collectAllSourceRoots(project: Project): List<VirtualFile> {
        val fromLibraries = mutableListOf<VirtualFile>()
        val fromSynthetic = AdditionalLibraryRootsProvider.EP_NAME.extensionList
            .flatMap { it.getAdditionalProjectLibraries(project) }
            .flatMap { it.sourceRoots }
        for (module in ModuleManager.getInstance(project).modules) {
            for (entry in ModuleRootManager.getInstance(module).orderEntries) {
                if (entry is LibraryOrderEntry) {
                    entry.library?.getFiles(OrderRootType.SOURCES)?.toList()?.let { fromLibraries.addAll(it) }
                }
            }
        }
        return (fromLibraries + fromSynthetic).distinct()
    }

    /**
     * Locates a dependency source file by path (e.g. io/redspace/.../Utils.java).
     * Searches SOURCES roots and synthetic library roots; returns first match.
     */
    private fun locateDepSourceByPath(project: Project, path: String): VirtualFile? {
        val normalizedPath: String = path.replace('\\', '/').removePrefix("/")
        var found: VirtualFile? = null

        ReadAction.compute<Unit, Throwable> {
            for (root in collectAllSourceRoots(project)) {
                found = findFileByPathInTree(root, normalizedPath)
                if (found != null) return@compute
            }
        }

        return found
    }

    private fun findFileByPathInTree(vf: VirtualFile, targetPath: String): VirtualFile? {
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
     * Returns the path used for fileMask matching: for JAR entries, the path inside
     * the jar (e.g. net/minecraft/world/entity/LivingEntity.java); for directory
     * roots (decompiled cache), the path relative to root.
     */
    private fun getPathForMask(root: VirtualFile, vf: VirtualFile): String {
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

    private fun collectRegexMatches(
        vf: VirtualFile,
        root: VirtualFile,
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
                collectRegexMatches(child, root, pattern, matchesMask, results, maxResults, startTime, timeout)
            }
        } else {
            val pathToMatch: String = getPathForMask(root, vf)
            if (!matchesMask(pathToMatch)) return
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
                    results.add("url: ${vf.url}")
                    results.add("  line $lineNum: $highlighted")
                }
            }
        }
    }

    @McpTool
    @McpDescription("Reads source from dependency jars or decompiled cache. Use this tool to view library code that grep/read_file cannot access. Pass url (exact string from mixin_search_in_deps results, e.g. jar://…/sources.jar!/path/File.java) or path (package path with / separators and .java extension, e.g. net/minecraft/world/entity/LivingEntity.java — not a filesystem path). url takes precedence if both given. lineNumber, linesBefore (default 30), linesAfter (default 70) define a window around a specific line.")
    @Suppress("unused")
    suspend fun mixin_get_dep_source(
        url: String? = null,
        path: String? = null,
        lineNumber: Int = 1,
        linesBefore: Int = 30,
        linesAfter: Int = 70,
        projectPath: String? = null,
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
                        "or mixin_search_in_deps to get the jar url."
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
    @McpDescription("Returns bytecode-level class overview including synthetic methods, lambda targets, method descriptors, and access flags. Use this tool when decompiled source hides the real method names you need for mixin targets. filter: all (default), synthetic (only compiler-generated: lambdas, bridges, access methods), methods, fields. includeInstructions: javap -c style bytecode per method (large output). Use filter=synthetic to discover lambda mixin target names (e.g. lambda\$tick\$0).")
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
    @McpDescription("Returns javap-style bytecode instructions for a single method. Use this tool to inspect bytecode for lambda targets (e.g. lambda\$tick\$0) or any method. Pass methodDescriptor to disambiguate overloads.")
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

        if (result != null) {
            return McpToolCallResult.text(buildString {
                appendLine("=== $className#$methodName (bytecode) ===")
                appendLine()
                append(result)
            })
        }

        val analysis: BytecodeAnalyzer.ClassAnalysis = BytecodeAnalyzer.analyze(classBytes, false)
        val similar: List<BytecodeAnalyzer.MethodInfo> = analysis.methods
            .filter { it.name == methodName }
        return McpToolCallResult.error(buildString {
            if (similar.isEmpty()) {
                appendLine("No method named '$methodName' in $className bytecode.")
                val names: List<String> = analysis.methods.map { it.name }.distinct().sorted()
                appendLine("Available methods: ${names.joinToString(", ")}")
            } else {
                appendLine("No overload of $className#$methodName matches descriptor '$methodDescriptor'.")
                appendLine("Available overloads:")
                for (m in similar) {
                    appendLine("  ${m.name}${m.descriptor}")
                }
            }
        })
    }

    @McpTool
    @McpDescription("Retrieves the type hierarchy of a class. Use this tool to understand inheritance before writing mixins. direction: supers (superclass chain + interfaces), subs (inheritors), both (default). maxDepth limits superclass traversal (default 10). includeInterfaces: default true.")
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
    @McpDescription("Finds all implementations of an interface or abstract class across project and dependencies. maxResults: 50 default.")
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
    @McpDescription("Returns the super method declaration chain for a method. Use this tool to confirm where a method is originally declared before targeting it in a mixin. Shows all overrides from most specific to most general. For overloaded methods, pass parameterTypes to disambiguate (e.g. [\"E\"] for add(E)). For parameterless methods, pass parameterTypes: [].")
    @Suppress("unused")
    suspend fun mixin_super_methods(
        className: String,
        methodName: String,
        parameterTypes: List<String>? = null,
        projectPath: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        val resolution = MethodResolver.resolveDetailed(project, className, methodName, parameterTypes)
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
    @McpDescription("Find all references to a class or member across project and dependencies. Without memberName: references to the class. With memberName: references to that method/field. For overloaded methods, pass parameterTypes to disambiguate. For parameterless methods, pass parameterTypes: []. maxResults: 100 default.")
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

        if (memberName != null) {
            val resolution = MethodResolver.resolveDetailed(project, className, memberName, parameterTypes)
            if (resolution is MethodResolver.Resolution.Error) {
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
    @McpDescription("Finds callers or callees of a method. Use this tool to trace execution flow when writing mixins. direction: callers (default) — finds call sites; callees — walks method body for outgoing calls. For overloaded methods, pass parameterTypes to disambiguate. For parameterless methods, pass parameterTypes: []. maxDepth: 3 default, maxResults: 50 default.")
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

        val resolution = MethodResolver.resolveDetailed(project, className, methodName, parameterTypes)
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

        return McpToolCallResult.text(result)
    }

    @McpTool
    @McpDescription("Trigger Gradle/Maven project sync to refresh dependencies and decompilation cache. Call after changing build.gradle or pom.xml. Runs in background.")
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
            // Sync listener will fire AdditionalLibraryChanged when sync completes;
            // cache is populated by the Gradle plugin, IDE is read-only.
        }

        return McpToolCallResult.text(
            "Project sync triggered for $externalPath. Dependencies will refresh in the background.",
        )
    }
}
