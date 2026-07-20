package dev.mixinmcp.tools.refactor

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue.FALSE
import com.intellij.mcpserver.annotations.McpToolHintValue.TRUE
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommandExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiArrayAccessExpression
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.inline.InlineConstantFieldHandler
import com.intellij.refactoring.inline.InlineConstantFieldProcessor
import com.intellij.refactoring.inline.InlineLocalHandler
import com.intellij.refactoring.inline.InlineMethodHandler
import com.intellij.refactoring.inline.InlineMethodProcessor
import com.intellij.refactoring.util.InlineUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import dev.mixinmcp.resolve.FqcnResolver
import dev.mixinmcp.resolve.MethodResolver
import dev.mixinmcp.tools.requireProject
import kotlin.coroutines.coroutineContext

@Suppress("FunctionName") // @McpTool functions are snake_case by MCP convention
class InlineToolset : McpToolset {

    override fun isExperimental(): Boolean = false

    @McpToolHints(readOnlyHint = FALSE, destructiveHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription(
        "Inline a method, a constant field, or a local variable into all its usages, updating every reference " +
            "with IntelliJ's reference-aware inline refactoring. kind='method' inlines the method body into " +
            "every call site (memberName, with parameterTypes or methodDescriptor for overloads); kind='field' " +
            "inlines a final or never-reassigned field's initializer into every read (memberName); kind='local' " +
            "inlines a local variable's initializer inside its declaring method (methodName + localName; the " +
            "declaration is always removed for locals). deleteDeclaration=false keeps the method or field " +
            "declaration after inlining. Java sources only. On conflicts, each is reported with its file and " +
            "tagged [library] or [source]; ignoreConflicts=true proceeds anyway, same as the IDE conflict " +
            "dialog's Continue button. dryRun=true reports usages and conflicts without modifying anything.",
    )
    @Suppress("unused")
    suspend fun mixin_inline(
        kind: String,
        className: String,
        memberName: String? = null,
        parameterTypes: List<String>? = null,
        methodDescriptor: String? = null,
        methodName: String? = null,
        localName: String? = null,
        deleteDeclaration: Boolean = true,
        ignoreConflicts: Boolean = false,
        dryRun: Boolean = false,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        when (kind) {
            "method", "field" -> {
                if (memberName == null) {
                    return McpToolCallResult.error("memberName is required for kind='$kind'.")
                }
                if (methodName != null || localName != null) {
                    return McpToolCallResult.error("methodName/localName only apply to kind='local'.")
                }
                if (kind == "field" && (parameterTypes != null || methodDescriptor != null)) {
                    return McpToolCallResult.error("parameterTypes/methodDescriptor only apply to methods.")
                }
            }
            "local" -> {
                if (methodName == null || localName == null) {
                    return McpToolCallResult.error("kind='local' requires methodName and localName.")
                }
                if (memberName != null) {
                    return McpToolCallResult.error(
                        "memberName does not apply to kind='local'; pass methodName and localName.",
                    )
                }
                if (!deleteDeclaration) {
                    return McpToolCallResult.error(
                        "Inlining a local always removes its declaration; deleteDeclaration=false is not supported for kind='local'.",
                    )
                }
            }
            else -> return McpToolCallResult.error("kind must be 'method', 'field', or 'local'.")
        }

        return when (kind) {
            "method" -> inlineMethod(
                project, className, memberName!!, parameterTypes, methodDescriptor,
                deleteDeclaration, ignoreConflicts, dryRun,
            )
            "field" -> inlineField(project, className, memberName!!, deleteDeclaration, ignoreConflicts, dryRun)
            else -> inlineLocal(
                project, className, methodName!!, localName!!, parameterTypes, methodDescriptor,
                ignoreConflicts, dryRun,
            )
        }
    }

    // ───────────────────────────────────── Method ─────────────────────────────────────

    private suspend fun inlineMethod(
        project: Project,
        className: String,
        memberName: String,
        parameterTypes: List<String>?,
        methodDescriptor: String?,
        deleteDeclaration: Boolean,
        ignoreConflicts: Boolean,
        dryRun: Boolean,
    ): McpToolCallResult {
        val prepared: Prep = smartReadAction(project) {
            val method: PsiMethod = when (val r = MethodResolver.resolveDetailed(
                project, className, memberName,
                parameterTypes = parameterTypes,
                methodDescriptor = methodDescriptor,
            )) {
                is MethodResolver.Resolution.Error -> return@smartReadAction Prep.Failure(r.message)
                is MethodResolver.Resolution.Found -> r.method
            }
            val display: String = RefactorSupport.methodDisplayName(method, className)
            RefactorSupport.guardJavaSourceTarget(project, method, display)?.let {
                return@smartReadAction Prep.Failure(it)
            }
            if (method.isConstructor) {
                return@smartReadAction Prep.Failure("$display is a constructor; constructor inlining is not supported.")
            }
            if (method.body == null) {
                return@smartReadAction Prep.Failure(
                    "$display has no body (abstract or native method); cannot inline.",
                )
            }
            if (InlineMethodHandler.checkRecursive(method)) {
                return@smartReadAction Prep.Failure("$display is recursive; cannot inline.")
            }
            val refCount: Int = ReferencesSearch.search(method).findAll().size
            if (refCount == 0 && !dryRun) {
                return@smartReadAction Prep.Failure(
                    "$display has no usages; nothing to inline. Use mixin_safe_delete to remove it.",
                )
            }
            Prep.Target(method, "method", display, prepFilePath(project, method), refCount)
        }
        val prep: Prep.Target = when (prepared) {
            is Prep.Failure -> return McpToolCallResult.error(prepared.message)
            is Prep.Target -> prepared
        }

        return runProcessorInline(project, prep, deleteDeclaration, ignoreConflicts, dryRun) { onConflicts ->
            HeadlessInlineMethodProcessor(project, prep.element as PsiMethod, deleteDeclaration, onConflicts)
        }
    }

    // ───────────────────────────────────── Field ─────────────────────────────────────

    private suspend fun inlineField(
        project: Project,
        className: String,
        memberName: String,
        deleteDeclaration: Boolean,
        ignoreConflicts: Boolean,
        dryRun: Boolean,
    ): McpToolCallResult {
        val prepared: Prep = smartReadAction(project) {
            val psiClass = FqcnResolver.resolveNested(project, className)
                ?: return@smartReadAction Prep.Failure(
                    "Class not found: $className. ${FqcnResolver.CLASS_NOT_FOUND_HINT}",
                )
            val field: PsiField = psiClass.findFieldByName(memberName, true)
                ?: return@smartReadAction Prep.Failure(
                    "No field named '$memberName' in ${psiClass.qualifiedName ?: className}.",
                )
            val display = "${field.containingClass?.qualifiedName ?: className}#${field.name}"
            RefactorSupport.guardJavaSourceTarget(project, field, display)?.let {
                return@smartReadAction Prep.Failure(it)
            }
            if (field is PsiEnumConstant) {
                return@smartReadAction Prep.Failure("$display is an enum constant; enum constants cannot be inlined.")
            }
            if (InlineConstantFieldHandler.getInitializer(field) == null) {
                return@smartReadAction Prep.Failure(
                    "$display has no initializer; only fields initialized at declaration " +
                        "(or final fields assigned once in a constructor) can be inlined.",
                )
            }
            if (!field.hasModifierProperty(PsiModifier.FINAL) && hasWriteUsages(field)) {
                return@smartReadAction Prep.Failure(
                    "$display is written outside its initializer; only final or never-reassigned fields can be inlined.",
                )
            }
            val refCount: Int = ReferencesSearch.search(field).findAll().size
            if (refCount == 0 && !dryRun) {
                return@smartReadAction Prep.Failure(
                    "$display has no usages; nothing to inline. Use mixin_safe_delete to remove it.",
                )
            }
            Prep.Target(field, "field", display, prepFilePath(project, field), refCount)
        }
        val prep: Prep.Target = when (prepared) {
            is Prep.Failure -> return McpToolCallResult.error(prepared.message)
            is Prep.Target -> prepared
        }

        return runProcessorInline(project, prep, deleteDeclaration, ignoreConflicts, dryRun) { onConflicts ->
            HeadlessInlineFieldProcessor(prep.element as PsiField, project, deleteDeclaration, onConflicts)
        }
    }

    private fun hasWriteUsages(field: PsiField): Boolean {
        for (reference in ReferencesSearch.search(field).findAll()) {
            ProgressManager.checkCanceled()
            var expression: PsiExpression = reference.element as? PsiExpression ?: continue
            while (expression.parent is PsiArrayAccessExpression) {
                expression = expression.parent as PsiExpression
            }
            if (PsiUtil.isAccessedForWriting(expression)) return true
        }
        return false
    }

    // ───────────────────────────────────── Local ─────────────────────────────────────

    private suspend fun inlineLocal(
        project: Project,
        className: String,
        methodName: String,
        localName: String,
        parameterTypes: List<String>?,
        methodDescriptor: String?,
        ignoreConflicts: Boolean,
        dryRun: Boolean,
    ): McpToolCallResult {
        val prepared: LocalPrep = smartReadAction(project) {
            val method: PsiMethod = when (val r = MethodResolver.resolveDetailed(
                project, className, methodName,
                parameterTypes = parameterTypes,
                methodDescriptor = methodDescriptor,
            )) {
                is MethodResolver.Resolution.Error -> return@smartReadAction LocalPrep.Failure(r.message)
                is MethodResolver.Resolution.Found -> r.method
            }
            val methodDisplayName: String = RefactorSupport.methodDisplayName(method, className)
            val body = method.body
                ?: return@smartReadAction LocalPrep.Failure("$methodDisplayName has no body; no locals to inline.")

            val candidates: MutableList<PsiLocalVariable> = mutableListOf()
            for (variable in PsiTreeUtil.findChildrenOfType(body, PsiLocalVariable::class.java)) {
                ProgressManager.checkCanceled()
                if (variable.name == localName) candidates.add(variable)
            }
            val display = "local variable '$localName' in $methodDisplayName"
            val variable: PsiLocalVariable = when {
                candidates.isEmpty() -> return@smartReadAction LocalPrep.Failure(
                    "No local variable named '$localName' in $methodDisplayName.",
                )
                candidates.size > 1 -> {
                    val document = PsiDocumentManager.getInstance(project).getDocument(method.containingFile)
                    val lines: String = candidates.joinToString(", ") { v ->
                        document?.let { "line ${it.getLineNumber(v.textOffset) + 1}" } ?: "unknown line"
                    }
                    return@smartReadAction LocalPrep.Failure(
                        "Found ${candidates.size} local variables named '$localName' in $methodDisplayName " +
                            "($lines); ambiguous target. Rename one with mixin_rename first.",
                    )
                }
                else -> candidates.single()
            }
            RefactorSupport.guardJavaSourceTarget(project, variable, display)?.let {
                return@smartReadAction LocalPrep.Failure(it)
            }
            val initializer: PsiExpression = variable.initializer
                ?: return@smartReadAction LocalPrep.Failure("$display has no initializer; cannot inline.")
            val refCount: Int = ReferencesSearch.search(variable).findAll().size
            if (refCount == 0) {
                return@smartReadAction LocalPrep.Failure(
                    "$display is never used; delete the declaration instead.",
                )
            }
            val conflictMap: MultiMap<PsiElement, String> = MultiMap()
            InlineUtil.checkChangedBeforeLastAccessConflicts(conflictMap, initializer, variable)
            LocalPrep.Ok(
                variable = variable,
                display = display,
                filePath = prepFilePath(project, variable),
                refCount = refCount,
                conflicts = RefactorSupport.renderConflicts(project, conflictMap),
            )
        }
        val prep: LocalPrep.Ok = when (prepared) {
            is LocalPrep.Failure -> return McpToolCallResult.error(prepared.message)
            is LocalPrep.Ok -> prepared
        }

        if (dryRun) {
            return McpToolCallResult.text(buildString {
                appendLine("Dry run for inline of ${prep.display}")
                appendLine("  declared in: ${prep.filePath}")
                appendLine()
                appendLine("Found ${prep.refCount} usage(s) in the method body; the declaration would be removed.")
                appendLine()
                if (prep.conflicts.isEmpty()) {
                    appendLine("No conflicts detected.")
                } else {
                    append(RefactorSupport.formatConflicts(prep.conflicts))
                }
                appendLine("Re-run without dryRun=true to perform the inline.")
            })
        }
        if (prep.conflicts.isNotEmpty() && !ignoreConflicts) {
            return McpToolCallResult.error(
                "Cannot inline ${prep.display}.\n" + RefactorSupport.formatConflicts(prep.conflicts),
            )
        }

        var batch: ModCommandExecutor.BatchExecutionResult? = null
        val error: String? = RefactorSupport.runRefactoringOnEdt(project) {
            check(prep.variable.isValid) { RefactorSupport.STALE_TARGET }
            val context = ActionContext.from(null, prep.variable.containingFile).withElement(prep.variable)
            CommandProcessor.getInstance().executeCommand(project, {
                val command = InlineLocalHandler.doInline(
                    context, prep.variable, null, InlineLocalHandler.InlineMode.INLINE_ALL_AND_DELETE,
                )
                batch = ModCommandExecutor.getInstance().executeInBatch(context, command)
            }, "Inline Local Variable", null)
        }
        if (error != null) return McpToolCallResult.error("Inline failed: $error")
        val result: ModCommandExecutor.BatchExecutionResult = batch
            ?: return McpToolCallResult.error("Inline failed: no result from the batch executor.")
        if (result != ModCommandExecutor.Result.SUCCESS) {
            return McpToolCallResult.error("Inline failed: ${result.message}")
        }

        return McpToolCallResult.text(buildString {
            appendLine("Inlined ${prep.display} into ${prep.refCount} usage(s); declaration removed")
            appendLine("  declared in: ${prep.filePath}")
        })
    }

    // ───────────────────────────────────── Processor-driven execution ─────────────────────────────────────

    private fun runProcessorInline(
        project: Project,
        prep: Prep.Target,
        deleteDeclaration: Boolean,
        ignoreConflicts: Boolean,
        dryRun: Boolean,
        makeProcessor: (onConflicts: (MultiMap<PsiElement, String>, Array<out UsageInfo>?) -> Boolean) -> BaseRefactoringProcessor,
    ): McpToolCallResult {
        if (dryRun) return dryRunProcessorInline(project, prep, deleteDeclaration, makeProcessor)

        val capture = RefactorSupport.ConflictCapture(ignoreConflicts)
        val error: String? = RefactorSupport.runRefactoringOnEdt(project) {
            check(prep.element.isValid) { RefactorSupport.STALE_TARGET }
            makeProcessor { conflicts, _ -> capture.onConflicts(conflicts) }.run()
        }
        if (error != null) return McpToolCallResult.error("Inline failed: $error")
        capture.captured?.let { conflicts ->
            val rendered = ApplicationManager.getApplication().runReadAction<List<RefactorSupport.ConflictRef>> {
                RefactorSupport.renderConflicts(project, conflicts)
            }
            return McpToolCallResult.error(
                "Cannot inline ${prep.kind} ${prep.display}.\n" + RefactorSupport.formatConflicts(rendered),
            )
        }
        if (deleteDeclaration) {
            // BaseRefactoringProcessor.doRun has silent bail-outs (preview escalation on
            // read-only usages, dumb mode, canceled progress); verify the declaration is gone.
            val deleted: Boolean = ApplicationManager.getApplication().runReadAction<Boolean> {
                !prep.element.isValid
            }
            if (!deleted) {
                return McpToolCallResult.error(RefactorSupport.BAIL_OUT)
            }
        }

        return McpToolCallResult.text(buildString {
            val declarationNote: String = if (deleteDeclaration) "declaration removed" else "declaration kept"
            appendLine("Inlined ${prep.kind} ${prep.display} into ${prep.refCount} usage(s); $declarationNote")
            appendLine("  declared in: ${prep.filePath}")
        })
    }

    private fun dryRunProcessorInline(
        project: Project,
        prep: Prep.Target,
        deleteDeclaration: Boolean,
        makeProcessor: (onConflicts: (MultiMap<PsiElement, String>, Array<out UsageInfo>?) -> Boolean) -> BaseRefactoringProcessor,
    ): McpToolCallResult {
        var capturedConflicts: MultiMap<PsiElement, String>? = null
        var capturedUsages: Array<out UsageInfo>? = null
        val error: String? = RefactorSupport.runRefactoringOnEdt(project) {
            check(prep.element.isValid) { RefactorSupport.STALE_TARGET }
            makeProcessor { conflicts, usages ->
                capturedConflicts = conflicts
                capturedUsages = usages
                false
            }.run()
        }
        if (error != null) return McpToolCallResult.error("Dry run failed: $error")
        val conflictMap: MultiMap<PsiElement, String> = capturedConflicts
            ?: return McpToolCallResult.error(RefactorSupport.DRY_RUN_BAIL_OUT)

        data class DryRunData(val conflicts: List<RefactorSupport.ConflictRef>, val usagesByFile: Map<String, Int>)
        val usages: Array<out UsageInfo> = capturedUsages ?: emptyArray()
        val data: DryRunData = ApplicationManager.getApplication().runReadAction<DryRunData> {
            DryRunData(
                conflicts = RefactorSupport.renderConflicts(project, conflictMap),
                usagesByFile = usages
                    .mapNotNull { usage -> usage.virtualFile?.let { RefactorSupport.projectRelative(project, it) } }
                    .groupingBy { it }
                    .eachCount(),
            )
        }

        return McpToolCallResult.text(buildString {
            appendLine("Dry run for inline of ${prep.kind} ${prep.display}")
            appendLine("  declared in: ${prep.filePath}")
            appendLine("  declaration would be ${if (deleteDeclaration) "removed" else "kept"}")
            appendLine()
            if (data.usagesByFile.isEmpty()) {
                appendLine("No usages found.")
            } else {
                appendLine("Found ${usages.size} usage(s) in ${data.usagesByFile.size} file(s):")
                for ((file, count) in data.usagesByFile.entries.sortedByDescending { it.value }) {
                    appendLine("  $file  ($count)")
                }
            }
            appendLine()
            if (data.conflicts.isEmpty()) {
                appendLine("No conflicts detected.")
            } else {
                append(RefactorSupport.formatConflicts(data.conflicts))
            }
            appendLine("Re-run without dryRun=true to perform the inline.")
        })
    }

    // ───────────────────────────────────── Shared helpers ─────────────────────────────────────

    private sealed class Prep {
        data class Target(
            val element: PsiMember,
            val kind: String,
            val display: String,
            val filePath: String,
            val refCount: Int,
        ) : Prep()

        data class Failure(val message: String) : Prep()
    }

    private sealed class LocalPrep {
        data class Ok(
            val variable: PsiLocalVariable,
            val display: String,
            val filePath: String,
            val refCount: Int,
            val conflicts: List<RefactorSupport.ConflictRef>,
        ) : LocalPrep()

        data class Failure(val message: String) : LocalPrep()
    }

    private fun prepFilePath(project: Project, element: PsiElement): String =
        element.containingFile?.virtualFile?.let { RefactorSupport.projectRelative(project, it) } ?: "(no file)"

    private class HeadlessInlineMethodProcessor(
        project: Project,
        method: PsiMethod,
        deleteDeclaration: Boolean,
        private val onConflictsFound: (MultiMap<PsiElement, String>, Array<out UsageInfo>?) -> Boolean,
    ) : InlineMethodProcessor(project, method, null, null, false, false, false, deleteDeclaration) {
        override fun isPreviewUsages(usages: Array<UsageInfo>): Boolean = false
        override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo>?): Boolean =
            onConflictsFound(conflicts, usages)
    }

    private class HeadlessInlineFieldProcessor(
        field: PsiField,
        project: Project,
        deleteDeclaration: Boolean,
        private val onConflictsFound: (MultiMap<PsiElement, String>, Array<out UsageInfo>?) -> Boolean,
    ) : InlineConstantFieldProcessor(field, project, null, false, false, false, deleteDeclaration) {
        override fun isPreviewUsages(usages: Array<UsageInfo>): Boolean = false
        override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo>?): Boolean =
            onConflictsFound(conflicts, usages)
    }
}
