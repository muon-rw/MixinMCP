package dev.mixinmcp.tools

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import dev.mixinmcp.resolve.BytecodeAnalyzer
import dev.mixinmcp.resolve.ClassFileLocator
import kotlin.coroutines.coroutineContext

private fun staleWarning(located: ClassFileLocator.LocateResult.Found): String =
    if (located.maybeStale) {
        "WARNING: the compiled .class is older than its source (unsaved or unbuilt changes); " +
            "the bytecode below may be stale. Rebuild the project for current output.\n\n"
    } else {
        ""
    }

/**
 * Bytecode-inspection tools: class-level and method-level javap-style output
 * for classes the IDE can resolve via [dev.mixinmcp.resolve.ClassFileLocator].
 */
class BytecodeInspectionToolset : McpToolset {

    @McpTool
    @McpDescription("Returns bytecode-level class overview including synthetic methods, lambda targets, method descriptors, and access flags. Use this tool when decompiled source hides the real method names you need for mixin targets. filter: all (default), synthetic (only compiler-generated: lambdas, bridges, access methods), methods, fields. includeInstructions: javap -c style bytecode per method (large output). Use filter=synthetic to discover lambda mixin target names (e.g. lambda\$tick\$0). For method-level bytecode use mixin_method_bytecode. Works on project classes after a build.")
    @Suppress("unused") // Discovered and invoked by MCP framework via reflection
    suspend fun mixin_class_bytecode(
        className: String,
        filter: String = "all",
        includeInstructions: Boolean = false,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val located: ClassFileLocator.LocateResult.Found =
            when (val result = ClassFileLocator.locateDetailed(project, className)) {
                is ClassFileLocator.LocateResult.Found -> result
                ClassFileLocator.LocateResult.NotBuilt -> return McpToolCallResult.error(
                    "$className is project source with no compiled .class in the module output. " +
                        "Build the project, then retry.",
                )
                ClassFileLocator.LocateResult.NotFound -> return McpToolCallResult.error(
                    "Class not found or could not locate bytecode: $className",
                )
            }
        val classBytes: ByteArray = located.bytes

        val analysis: BytecodeAnalyzer.ClassAnalysis =
            BytecodeAnalyzer.analyze(classBytes, includeInstructions)

        val showMethods: Boolean = filter == "all" || filter == "methods" || filter == "synthetic"
        val showFields: Boolean = filter == "all" || filter == "fields" || filter == "synthetic"
        val syntheticOnly: Boolean = filter == "synthetic"

        val result: String = buildString {
            append(staleWarning(located))
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
    @McpDescription("Returns javap-style bytecode instructions for a single method. Every INVOKE* instruction shows the actual owner class, method name, and descriptor — use this to find the exact @At(target = \"...\") string for mixin injections. Also use for lambda/synthetic targets (e.g. lambda\$tick\$0). Pass methodDescriptor in JVM format to disambiguate overloads (e.g. (Lnet/minecraft/world/entity/Entity;)V, or ()V for no-arg methods). For class-level bytecode overview use mixin_class_bytecode. Works on project classes after a build.")
    @Suppress("unused")
    suspend fun mixin_method_bytecode(
        className: String,
        methodName: String,
        methodDescriptor: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val located: ClassFileLocator.LocateResult.Found =
            when (val result = ClassFileLocator.locateDetailed(project, className)) {
                is ClassFileLocator.LocateResult.Found -> result
                ClassFileLocator.LocateResult.NotBuilt -> return McpToolCallResult.error(
                    "$className is project source with no compiled .class in the module output. " +
                        "Build the project, then retry.",
                )
                ClassFileLocator.LocateResult.NotFound -> return McpToolCallResult.error(
                    "Class not found or could not locate bytecode: $className",
                )
            }
        val classBytes: ByteArray = located.bytes

        val result: String? = BytecodeAnalyzer.analyzeMethod(
            classBytes,
            methodName,
            methodDescriptor,
        )

        if (result != null) {
            return McpToolCallResult.text(buildString {
                append(staleWarning(located))
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
        })
    }
}