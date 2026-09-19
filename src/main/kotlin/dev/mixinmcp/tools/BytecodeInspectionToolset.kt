package dev.mixinmcp.tools

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue.FALSE
import com.intellij.mcpserver.annotations.McpToolHintValue.TRUE
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import dev.mixinmcp.resolve.BytecodeAnalyzer
import dev.mixinmcp.resolve.ClassFileLocator
import dev.mixinmcp.resolve.ClassVariants
import dev.mixinmcp.resolve.FqcnResolver
import dev.mixinmcp.resolve.ModuleScopeResult
import dev.mixinmcp.resolve.ModuleScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

private val VALID_FILTERS: Set<String> = linkedSetOf("all", "synthetic", "methods", "fields")

private fun staleWarning(located: ClassFileLocator.LocateResult.Found): String =
    if (located.maybeStale) {
        "WARNING: the compiled .class is older than its source (unsaved or unbuilt changes); " +
            "the bytecode below may be stale. Rebuild the project for current output.\n\n"
    } else {
        ""
    }

private sealed class ScopedLocate {
    class Success(
        val found: ClassFileLocator.LocateResult.Found,
        val pinnedModule: String?,
        val report: ClassVariants.VariantReport?,
        val jarName: String? = null,
    ) : ScopedLocate() {
        val originNote: String
            get() = pinnedModule?.let { ", module: $it" } ?: jarName?.let { ", jar: $it" } ?: ""
    }

    class Failure(val message: String) : ScopedLocate()
}

/** Index-free lookup for jars outside the project classpath; runs off the read lock. */
private fun locateInJar(basePath: String?, jarPath: String, className: String, module: String?): ScopedLocate {
    if (module != null) {
        return ScopedLocate.Failure("jarPath reads the class straight from that jar and bypasses classpath resolution; drop module=.")
    }
    val jar = File(resolveAgainstBase(basePath, jarPath))
    if (!jar.isFile) return ScopedLocate.Failure("jarPath does not exist or is not a file: $jarPath")
    return when (val lookup = ClassFileLocator.readClassEntry(jar, className)) {
        is ClassFileLocator.JarClassLookup.Found ->
            ScopedLocate.Success(ClassFileLocator.LocateResult.Found(lookup.bytes), null, null, jar.name)
        is ClassFileLocator.JarClassLookup.NotFound -> ScopedLocate.Failure(
            buildString {
                append("No class $className in ${jar.name}. Use the dot FQCN (Outer.Inner or Outer\$Inner for nested classes). ")
                if (lookup.similarEntries.isNotEmpty()) {
                    append("Entries with a similar name: ${lookup.similarEntries.joinToString(", ")}. ")
                }
                append("mixin_list_jar_entries(jarPath=\"$jarPath\", includeClasses=true, fileMask=\"<name>\") lists the classes.")
            },
        )
        is ClassFileLocator.JarClassLookup.Unreadable ->
            ScopedLocate.Failure("Could not read ${jar.name}: ${lookup.message}")
    }
}

@RequiresReadLock
private fun locateScoped(project: Project, className: String, module: String?): ScopedLocate {
    val scope: GlobalSearchScope
    val pinnedModule: String?
    if (module != null) {
        when (val resolved = ModuleScopes.resolve(project, module)) {
            is ModuleScopeResult.Found -> {
                scope = resolved.scope
                pinnedModule = resolved.module.name
            }
            is ModuleScopeResult.Error -> return ScopedLocate.Failure(resolved.message)
        }
    } else {
        scope = GlobalSearchScope.everythingScope(project)
        pinnedModule = null
    }
    return when (val result = ClassFileLocator.locateDetailed(project, className, scope)) {
        is ClassFileLocator.LocateResult.Found -> ScopedLocate.Success(
            result,
            pinnedModule,
            if (module == null) ClassVariants.findVariants(project, className) else null,
        )
        ClassFileLocator.LocateResult.NotBuilt -> ScopedLocate.Failure(
            "$className is project source with no compiled .class in the module output. " +
                "Build the project, then retry.",
        )
        ClassFileLocator.LocateResult.NotFound -> ScopedLocate.Failure(
            "Class $className resolved but its .class bytes could not be read" +
                (if (pinnedModule != null) " (module: $pinnedModule)" else "") +
                "; the jar entry or compiled output may be corrupt, removed, or decompiled-only. " +
                "Re-sync dependencies or rebuild, then retry.",
        )
        ClassFileLocator.LocateResult.Unresolved -> ScopedLocate.Failure(
            if (pinnedModule != null && FqcnResolver.resolveNested(project, className) != null) {
                "Class $className exists on the classpath but not in the compile scope of module '$pinnedModule' " +
                    "(declared runtimeOnly or test-only there, or only on another module), so that module cannot " +
                    "compile against it; drop module= to search the whole project, or pin a different module."
            } else {
                FqcnResolver.notFoundMessage(
                    project,
                    className,
                    "Class not found: $className" + (if (pinnedModule != null) " (module: $pinnedModule)" else ""),
                ) + " For a jar that is not on the classpath, pass jarPath."
            },
        )
    }
}

private fun matchesDiffKey(key: String, methodName: String, methodDescriptor: String?): Boolean =
    if (methodDescriptor != null) key == methodName + methodDescriptor
    else key.substringBefore('(') == methodName

private fun methodVariantNote(
    report: ClassVariants.VariantReport?,
    methodName: String,
    methodDescriptor: String?,
): String? {
    if (report == null || !report.hasMultipleVariants) return null
    val differing: List<ClassVariants.VariantGroup> = report.groups.filter { group ->
        group.diff?.let { diff ->
            (diff.methodsChanged + diff.methodsAdded + diff.methodsRemoved)
                .any { matchesDiffKey(it, methodName, methodDescriptor) }
        } == true
    }
    if (differing.isEmpty()) return null
    val origins: String = differing
        .flatMap { group -> group.variants.map { ClassVariants.originWithProvenance(it) } }
        .distinct()
        .sorted()
        .joinToString(", ")
    return "NOTE: $methodName bytecode differs in $origins; pass module= to pin resolution to one classpath."
}

/**
 * Bytecode-inspection tools: class-level and method-level javap-style output
 * for classes the IDE can resolve via [dev.mixinmcp.resolve.ClassFileLocator].
 */
@Suppress("FunctionName") // @McpTool functions are snake_case by MCP convention
class BytecodeInspectionToolset : McpToolset {

    override fun isExperimental(): Boolean = false

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription("Returns bytecode-level class overview including synthetic methods, lambda targets, method descriptors, and access flags. Use this tool when decompiled source hides the real method names you need for mixin targets. filter: all (default), synthetic (only compiler-generated: lambdas, bridges, access methods), methods, fields. includeInstructions: javap -c style bytecode per method (large output). Use filter=synthetic to discover lambda mixin target names (e.g. lambda\$tick\$0). module: pin resolution to one module's classpath when the class has multiple variants; accepts exact or dot-boundary suffix module names (e.g. common.main, MyMod.neoforge.main). jarPath: read the class straight from any jar on disk instead of the classpath (a mod in a modpack folder; no build change needed; relative paths resolve against the project directory); className is still the dot FQCN, and module must be omitted. For method-level bytecode use mixin_method_bytecode. Works on project classes after a build. If the IDE is indexing, the call waits for indexing to finish rather than failing (jarPath lookups never wait).")
    @Suppress("unused") // Discovered and invoked by MCP framework via reflection
    suspend fun mixin_class_bytecode(
        className: String,
        filter: String = "all",
        includeInstructions: Boolean = false,
        module: String? = null,
        jarPath: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        if (filter !in VALID_FILTERS) {
            return McpToolCallResult.error(
                "Invalid filter '$filter'; valid values: ${VALID_FILTERS.joinToString(", ")}.",
            )
        }

        val outcome: ScopedLocate = if (!jarPath.isNullOrBlank()) {
            withContext(Dispatchers.IO) { locateInJar(project.basePath, jarPath, className, module) }
        } else {
            smartReadAction(project) { locateScoped(project, className, module) }
        }
        val scoped: ScopedLocate.Success = when (outcome) {
            is ScopedLocate.Success -> outcome
            is ScopedLocate.Failure -> return McpToolCallResult.error(outcome.message)
        }
        val located: ClassFileLocator.LocateResult.Found = scoped.found
        val classBytes: ByteArray = located.bytes

        val analysis: BytecodeAnalyzer.ClassAnalysis =
            BytecodeAnalyzer.analyze(classBytes, includeInstructions)

        val showMethods: Boolean = filter == "all" || filter == "methods" || filter == "synthetic"
        val showFields: Boolean = filter == "all" || filter == "fields" || filter == "synthetic"
        val syntheticOnly: Boolean = filter == "synthetic"

        val result: String = buildString {
            append(staleWarning(located))
            appendLine("=== ${analysis.name} (bytecode${scoped.originNote}) ===")
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
                appendLine(if (syntheticOnly) "--- Synthetic Methods ---" else "--- Methods ---")
                if (methodsToShow.isEmpty()) {
                    appendLine(
                        if (syntheticOnly) {
                            "  (no synthetic methods; class declares ${analysis.methods.size}, none compiler-generated)"
                        } else {
                            "  (none declared)"
                        },
                    )
                }
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
                appendLine(if (syntheticOnly) "--- Synthetic Fields ---" else "--- Fields ---")
                if (fieldsToShow.isEmpty()) {
                    appendLine(
                        if (syntheticOnly) {
                            "  (no synthetic fields; class declares ${analysis.fields.size}, none compiler-generated)"
                        } else {
                            "  (none declared)"
                        },
                    )
                }
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

            scoped.report?.let(ClassVariants::renderIfMultiple)?.let { footer ->
                if (isNotEmpty() && !endsWith("\n\n")) appendLine()
                append(footer)
            }
        }

        return McpToolCallResult.text(result)
    }

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription("Returns javap-style bytecode instructions for a single method. Every INVOKE* instruction shows the actual owner class, method name, and descriptor; use this to find the exact @At(target = \"...\") string for mixin injections. Also use for lambda/synthetic targets (e.g. lambda\$tick\$0). Pass methodDescriptor in JVM format to disambiguate overloads (e.g. (Lnet/minecraft/world/entity/Entity;)V, or ()V for no-arg methods). module: pin resolution to one module's classpath when the class has multiple variants; accepts exact or dot-boundary suffix module names (e.g. common.main, MyMod.neoforge.main). jarPath: read the class straight from any jar on disk instead of the classpath (a mod in a modpack folder; relative paths resolve against the project directory); module must be omitted. regexPattern: keep only the instructions whose text matches this Java regex (e.g. 'forEachModifier' or 'INVOKE.*Attribute'), each with its source line and, when the same target occurs more than once in the method, its Mixin ordinal ([ordinal 1 of 2]); an INVOKEDYNAMIC also matches on its bootstrap arguments and prints the handle it binds, so 'lambda\\\$register\\\$3' finds the instruction that creates that lambda. Use it instead of reading a long method's full listing. For class-level bytecode overview use mixin_class_bytecode. Works on project classes after a build. If the IDE is indexing, the call waits for indexing to finish rather than failing (jarPath lookups never wait).")
    @Suppress("unused")
    suspend fun mixin_method_bytecode(
        className: String,
        methodName: String,
        methodDescriptor: String? = null,
        module: String? = null,
        jarPath: String? = null,
        regexPattern: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val instructionPattern: Regex? = try {
            regexPattern?.takeIf { it.isNotBlank() }?.let { Regex(it) }
        } catch (e: java.util.regex.PatternSyntaxException) {
            return McpToolCallResult.error("Invalid regexPattern: ${e.message}")
        }

        val outcome: ScopedLocate = if (!jarPath.isNullOrBlank()) {
            withContext(Dispatchers.IO) { locateInJar(project.basePath, jarPath, className, module) }
        } else {
            smartReadAction(project) { locateScoped(project, className, module) }
        }
        val scoped: ScopedLocate.Success = when (outcome) {
            is ScopedLocate.Success -> outcome
            is ScopedLocate.Failure -> return McpToolCallResult.error(outcome.message)
        }
        val located: ClassFileLocator.LocateResult.Found = scoped.found
        val classBytes: ByteArray = located.bytes

        val result: String? = BytecodeAnalyzer.analyzeMethod(
            classBytes,
            methodName,
            methodDescriptor,
        )

        if (result != null && instructionPattern != null) {
            val filtered: String = filterInstructions(result, instructionPattern)
                ?: return McpToolCallResult.text(
                    "=== $className#$methodName (bytecode${scoped.originNote}) ===\n\n" +
                        "No instruction matches regexPattern \"$regexPattern\" (${countInstructions(result)} " +
                        "instructions scanned). The pattern is matched against each instruction's text, e.g. " +
                        "'INVOKEVIRTUAL net/minecraft/world/entity/LivingEntity.hurt'; drop regexPattern for the full listing.",
                )
            return McpToolCallResult.text(buildString {
                append(staleWarning(located))
                appendLine(
                    "=== $className#$methodName (bytecode${scoped.originNote}, instructions matching \"$regexPattern\" " +
                        "of ${countInstructions(result)}) ===",
                )
                appendLine()
                append(filtered)
                methodVariantNote(scoped.report, methodName, methodDescriptor)?.let { note ->
                    appendLine()
                    append(note)
                }
            })
        }

        if (result != null) {
            return McpToolCallResult.text(buildString {
                append(staleWarning(located))
                appendLine("=== $className#$methodName (bytecode${scoped.originNote}) ===")
                appendLine()
                append(result)
                methodVariantNote(scoped.report, methodName, methodDescriptor)?.let { note ->
                    if (isNotEmpty() && !endsWith("\n")) appendLine()
                    appendLine()
                    append(note)
                }
            })
        }

        val analysis: BytecodeAnalyzer.ClassAnalysis = BytecodeAnalyzer.analyze(classBytes, false)
        val similar: List<BytecodeAnalyzer.MethodInfo> = analysis.methods
            .filter { it.name == methodName }
        return McpToolCallResult.error(buildString {
            if (similar.isEmpty()) {
                appendLine("No method named '$methodName' in $className bytecode.")
                val allNames: List<String> = analysis.methods.map { it.name }.distinct().sorted()
                val closestMatches: List<String> = allNames.filter {
                    it.contains(methodName, ignoreCase = true) ||
                        methodName.contains(it, ignoreCase = true)
                }
                if (closestMatches.isNotEmpty()) {
                    appendLine("Similar methods: ${closestMatches.joinToString(", ")}")
                    appendLine()
                }
                val maxShown = 40
                if (allNames.size <= maxShown) {
                    appendLine("Available methods (${allNames.size}): ${allNames.joinToString(", ")}")
                } else {
                    appendLine("Available methods (${allNames.size}, showing first $maxShown):")
                    appendLine("  ${allNames.take(maxShown).joinToString(", ")}")
                    appendLine("  ... and ${allNames.size - maxShown} more")
                }
            } else {
                appendLine("No overload of $className#$methodName matches descriptor '$methodDescriptor'.")
                appendLine("Available overloads:")
                for (m in similar) {
                    appendLine("  ${m.name}${m.descriptor}")
                }
            }
            scoped.report?.takeIf { it.hasMultipleVariants }?.let { report ->
                val addedIn: List<String> = report.groups
                    .filter { group ->
                        group.diff?.methodsAdded?.any { matchesDiffKey(it, methodName, methodDescriptor) } == true
                    }
                    .flatMap { group -> group.variants.map { ClassVariants.originWithProvenance(it) } }
                    .distinct()
                    .sorted()
                if (addedIn.isNotEmpty()) {
                    appendLine(
                        "'$methodName' exists in ${addedIn.joinToString(", ")}; " +
                            "pass module= to resolve against that classpath.",
                    )
                } else if (report.excluded.isNotEmpty() || report.truncatedCount > 0) {
                    appendLine(
                        "Some classpath variants of $className were not analyzed (not built, unreadable, " +
                            "or over the variant cap); absence here does not cover them.",
                    )
                }
            }
            if (located.maybeStale) {
                appendLine(
                    "NOTE: the compiled .class is older than its source (unsaved or unbuilt changes); " +
                        "the methods listed reflect the last build. If '$methodName' was added or its " +
                        "signature changed recently, rebuild and retry.",
                )
            }
        })
    }
}