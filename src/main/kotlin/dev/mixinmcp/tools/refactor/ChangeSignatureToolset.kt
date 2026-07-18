package dev.mixinmcp.tools.refactor

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.util.Ref
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.refactoring.ConflictsDialogBase
import com.intellij.refactoring.changeSignature.ChangeSignatureProcessor
import com.intellij.refactoring.changeSignature.ChangeSignatureProcessorBase
import com.intellij.refactoring.changeSignature.ChangeSignatureUsageProcessor
import com.intellij.refactoring.changeSignature.JavaChangeInfoImpl
import com.intellij.refactoring.changeSignature.ParameterInfoImpl
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.refactoring.util.CanonicalTypes
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import com.intellij.util.VisibilityUtil
import com.intellij.util.containers.MultiMap
import dev.mixinmcp.resolve.MethodResolver
import dev.mixinmcp.tools.requireProject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

@Suppress("FunctionName") // @McpTool functions are snake_case by MCP convention
class ChangeSignatureToolset : McpToolset {

    private companion object {
        val JSON: Json = Json { ignoreUnknownKeys = false }
    }

    @Serializable
    data class ParameterSpec(
        val oldIndex: Int = -1,
        val name: String? = null,
        val type: String? = null,
        val defaultValue: String? = null,
    )

    @McpTool
    @McpDescription(
        "Change a Java method's signature with every call site and override updated: rename, change return " +
            "type or visibility, and add/remove/reorder/retype parameters in one atomic refactoring. " +
            "'parametersJson' is a JSON array string describing the complete new parameter list in order, " +
            "e.g. '[{\"oldIndex\":0},{\"oldIndex\":-1,\"name\":\"count\",\"type\":\"int\",\"defaultValue\":\"0\"}]'; " +
            "omit it to keep parameters unchanged. Each entry either references an existing parameter by " +
            "0-based oldIndex (name/type override the old ones when given) or declares a new parameter with " +
            "oldIndex=-1, which requires name, type, and defaultValue (the expression inserted at every " +
            "existing call site). Existing parameters omitted from the list are removed everywhere. " +
            "Overloads are disambiguated with parameterTypes " +
            "or methodDescriptor. Java sources only. On conflicts, each is reported with its file and tagged " +
            "[library] or [source]; ignoreConflicts=true proceeds anyway, same as the IDE conflict dialog's " +
            "Continue button. dryRun=true reports usages and conflicts without modifying anything.",
    )
    @Suppress("unused")
    suspend fun mixin_change_signature(
        className: String,
        methodName: String,
        parameterTypes: List<String>? = null,
        methodDescriptor: String? = null,
        newName: String? = null,
        newVisibility: String? = null,
        newReturnType: String? = null,
        parametersJson: String? = null,
        ignoreConflicts: Boolean = false,
        dryRun: Boolean = false,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val parameters: List<ParameterSpec>? = parametersJson?.let {
            try {
                JSON.decodeFromString<List<ParameterSpec>>(it)
            } catch (e: kotlinx.serialization.SerializationException) {
                return McpToolCallResult.error(
                    "parametersJson is not a valid JSON array of {oldIndex, name, type, defaultValue}: ${e.message}",
                )
            }
        }

        if (newName == null && newVisibility == null && newReturnType == null && parameters == null) {
            return McpToolCallResult.error(
                "No change requested; pass at least one of newName, newVisibility, newReturnType, parametersJson.",
            )
        }
        if (newName != null && !RefactorSupport.IDENTIFIER.matches(newName)) {
            return McpToolCallResult.error("newName '$newName' is not a valid Java identifier.")
        }
        val visibilityModifier: String? = newVisibility?.let {
            RefactorSupport.visibilityModifier(it)
                ?: return McpToolCallResult.error("Unknown visibility '$it'; ${RefactorSupport.VISIBILITY_HINT}.")
        }

        val prepared: Prep = smartReadAction(project) {
            prepare(
                project, className, methodName, parameterTypes, methodDescriptor,
                newName, visibilityModifier, newReturnType, parameters,
            )
        }
        val prep: Prep.Ok = when (prepared) {
            is Prep.Failure -> return McpToolCallResult.error(prepared.message)
            is Prep.Ok -> prepared
        }

        if (dryRun) return dryRun(project, prep)
        return execute(project, prep, ignoreConflicts)
    }

    // ───────────────────────────────────── Preparation ─────────────────────────────────────

    private sealed class Prep {
        class Ok(
            val method: PsiMethod,
            val display: String,
            val filePath: String,
            val newName: String,
            val newVisibility: String?,
            val newType: PsiType?,
            val parameterInfos: Array<ParameterInfoImpl>,
            val newSignature: String,
            val removedParameters: List<String>,
        ) : Prep()

        data class Failure(val message: String) : Prep()
    }

    private fun prepare(
        project: Project,
        className: String,
        methodName: String,
        parameterTypes: List<String>?,
        methodDescriptor: String?,
        newName: String?,
        visibilityModifier: String?,
        newReturnType: String?,
        parameters: List<ParameterSpec>?,
    ): Prep {
        val method: PsiMethod = when (val r = MethodResolver.resolveDetailed(
            project, className, methodName,
            parameterTypes = parameterTypes,
            methodDescriptor = methodDescriptor,
        )) {
            is MethodResolver.Resolution.Error -> return Prep.Failure(r.message)
            is MethodResolver.Resolution.Found -> r.method
        }
        val display: String = RefactorSupport.methodDisplayName(method, className)
        RefactorSupport.guardJavaSourceTarget(project, method, display)?.let { return Prep.Failure(it) }
        if (method.isConstructor && (newName != null || newReturnType != null)) {
            return Prep.Failure("$display is a constructor; newName and newReturnType do not apply.")
        }

        // JavaChangeInfoImpl.fillOldParams dereferences the new return type for
        // non-constructors; null does not mean unchanged there, so pass the old type.
        val newType: PsiType? = when {
            newReturnType != null -> when (val r = parseType(project, newReturnType, method, "newReturnType")) {
                is TypeResult.Failure -> return Prep.Failure(r.message)
                is TypeResult.Ok -> r.type
            }
            method.isConstructor -> null
            else -> method.returnType
        }

        if (visibilityModifier != null) {
            val oldVisibility: String = VisibilityUtil.getVisibilityModifier(method.modifierList)
            val weakening: Boolean = visibilityModifier != oldVisibility &&
                VisibilityUtil.getHighestVisibility(visibilityModifier, oldVisibility) == oldVisibility
            if (weakening && OverridingMethodsSearch.search(method).findFirst() != null) {
                return Prep.Failure(
                    "Weakening the visibility of $display is not supported while it has overriding methods " +
                        "(the platform opens an interactive propagation dialog). Narrow the overriders first, " +
                        "or keep the visibility unchanged.",
                )
            }
        }

        val oldParameters = method.parameterList.parameters
        val removed: MutableList<Int> = oldParameters.indices.toMutableList()
        val parameterInfos: Array<ParameterInfoImpl> = if (parameters == null) {
            removed.clear()
            ParameterInfoImpl.fromMethod(method)
        } else {
            val seen: MutableSet<Int> = mutableSetOf()
            parameters.map { spec ->
                when {
                    spec.oldIndex >= oldParameters.size -> return Prep.Failure(
                        "oldIndex ${spec.oldIndex} is out of range; $display has ${oldParameters.size} parameter(s).",
                    )
                    spec.oldIndex >= 0 -> {
                        if (!seen.add(spec.oldIndex)) {
                            return Prep.Failure("oldIndex ${spec.oldIndex} is referenced twice.")
                        }
                        if (spec.defaultValue != null) {
                            return Prep.Failure(
                                "defaultValue only applies to new parameters (oldIndex=-1); " +
                                    "parameter ${spec.oldIndex} already has values at call sites.",
                            )
                        }
                        removed.removeAll(listOf(spec.oldIndex))
                        val old = oldParameters[spec.oldIndex]
                        val name: String = spec.name ?: old.name
                        if (!RefactorSupport.IDENTIFIER.matches(name)) {
                            return Prep.Failure("Parameter name '$name' is not a valid Java identifier.")
                        }
                        val type: PsiType = spec.type?.let {
                            when (val r = parseType(project, it, method, "parameter '$name' type")) {
                                is TypeResult.Failure -> return Prep.Failure(r.message)
                                is TypeResult.Ok -> r.type
                            }
                        } ?: old.type
                        ParameterInfoImpl.create(spec.oldIndex).withName(name).withType(type)
                    }
                    else -> {
                        val name: String = spec.name
                            ?: return Prep.Failure("A new parameter (oldIndex=-1) requires a name.")
                        if (!RefactorSupport.IDENTIFIER.matches(name)) {
                            return Prep.Failure("Parameter name '$name' is not a valid Java identifier.")
                        }
                        val typeText: String = spec.type
                            ?: return Prep.Failure("New parameter '$name' requires a type.")
                        val defaultValue: String = spec.defaultValue
                            ?: return Prep.Failure(
                                "New parameter '$name' requires a defaultValue expression to insert at " +
                                    "existing call sites (the platform otherwise prompts interactively).",
                            )
                        val type: PsiType = when (val r = parseType(project, typeText, method, "parameter '$name' type")) {
                            is TypeResult.Failure -> return Prep.Failure(r.message)
                            is TypeResult.Ok -> r.type
                        }
                        ParameterInfoImpl.createNew().withName(name).withType(type).withDefaultValue(defaultValue)
                    }
                }
            }.toTypedArray()
        }

        val effectiveName: String = newName ?: method.name
        val newSignature: String = buildString {
            append(visibilityModifier ?: VisibilityUtil.getVisibilityModifier(method.modifierList))
            append(' ')
            if (!method.isConstructor) {
                append((newType ?: method.returnType)?.presentableText ?: "void")
                append(' ')
            }
            append(effectiveName)
            append('(')
            append(parameterInfos.joinToString(", ") { "${it.typeText} ${it.name}" })
            append(')')
        }
        return Prep.Ok(
            method = method,
            display = display,
            filePath = method.containingFile?.virtualFile
                ?.let { RefactorSupport.projectRelative(project, it) } ?: "(no file)",
            newName = effectiveName,
            newVisibility = visibilityModifier,
            newType = newType,
            parameterInfos = parameterInfos,
            newSignature = newSignature,
            removedParameters = removed.map { oldParameters[it].name },
        )
    }

    private sealed class TypeResult {
        class Ok(val type: PsiType) : TypeResult()
        data class Failure(val message: String) : TypeResult()
    }

    private fun parseType(project: Project, text: String, context: PsiMethod, what: String): TypeResult {
        val type: PsiType = try {
            JavaPsiFacade.getElementFactory(project).createTypeFromText(text, context)
        } catch (_: IncorrectOperationException) {
            return TypeResult.Failure("$what '$text' is not a valid Java type.")
        }
        if (type is PsiClassType && type.resolve() == null) {
            return TypeResult.Failure(
                "$what '$text' does not resolve in the context of ${context.containingClass?.qualifiedName}; " +
                    "use a fully qualified name.",
            )
        }
        return TypeResult.Ok(type)
    }

    // ───────────────────────────────────── Dry run ─────────────────────────────────────

    private suspend fun dryRun(project: Project, prep: Prep.Ok): McpToolCallResult {
        return smartReadAction(project) {
            if (!prep.method.isValid) return@smartReadAction McpToolCallResult.error(RefactorSupport.STALE_TARGET)
            val changeInfo = JavaChangeInfoImpl.generateChangeInfo(
                prep.method, false, true, prep.newVisibility, prep.newName,
                prep.newType?.let { CanonicalTypes.createTypeWrapper(it) },
                prep.parameterInfos, null, null, null,
            )
            val refUsages: Ref<Array<UsageInfo>> = Ref(ChangeSignatureProcessorBase.findUsages(changeInfo))
            val conflictMap: MultiMap<PsiElement, String> = MultiMap()
            for (processor in ChangeSignatureUsageProcessor.EP_NAME.extensions) {
                conflictMap.putAllValues(processor.findConflicts(changeInfo, refUsages))
            }
            RenameUtil.addConflictDescriptions(refUsages.get(), conflictMap)
            val conflicts = RefactorSupport.renderConflicts(project, conflictMap)
            val usagesByFile: Map<String, Int> = refUsages.get()
                .mapNotNull { usage -> usage.virtualFile?.let { RefactorSupport.projectRelative(project, it) } }
                .groupingBy { it }
                .eachCount()

            McpToolCallResult.text(buildString {
                appendLine("Dry run for change signature of ${prep.display}")
                appendLine("  declared in: ${prep.filePath}")
                appendLine("  new signature: ${prep.newSignature}")
                if (prep.removedParameters.isNotEmpty()) {
                    appendLine("  removed parameter(s): ${prep.removedParameters.joinToString(", ")}")
                }
                appendLine()
                if (usagesByFile.isEmpty()) {
                    appendLine("No usages found across project and dependencies.")
                } else {
                    appendLine("Found ${refUsages.get().size} usage(s) in ${usagesByFile.size} file(s):")
                    for ((file, count) in usagesByFile.entries.sortedByDescending { it.value }) {
                        appendLine("  $file  ($count)")
                    }
                }
                appendLine()
                if (conflicts.isEmpty()) {
                    appendLine("No conflicts detected.")
                } else {
                    append(RefactorSupport.formatConflicts(conflicts))
                }
                appendLine("Re-run without dryRun=true to perform the change.")
            })
        }
    }

    // ───────────────────────────────────── Execution ─────────────────────────────────────

    private fun execute(project: Project, prep: Prep.Ok, ignoreConflicts: Boolean): McpToolCallResult {
        var captured: MultiMap<PsiElement, String>? = null
        val error: String? = RefactorSupport.runRefactoringOnEdt(project) {
            check(prep.method.isValid) { RefactorSupport.STALE_TARGET }
            val processor = HeadlessChangeSignatureProcessor(
                project, prep.method, prep.newVisibility, prep.newName, prep.newType,
                prep.parameterInfos, ignoreConflicts,
            )
            processor.run()
            captured = processor.capturedConflicts
        }
        if (error != null) return McpToolCallResult.error("Change signature failed: $error")
        captured?.let { conflictMap ->
            val conflicts = ApplicationManager.getApplication().runReadAction<List<RefactorSupport.ConflictRef>> {
                RefactorSupport.renderConflicts(project, conflictMap)
            }
            return McpToolCallResult.error(
                "Cannot change signature of ${prep.display}.\n" + RefactorSupport.formatConflicts(conflicts),
            )
        }

        val expectedNames: List<String> = prep.parameterInfos.map { it.name }
        val applied: Boolean = ApplicationManager.getApplication().runReadAction<Boolean> {
            prep.method.isValid &&
                prep.method.name == prep.newName &&
                prep.method.parameterList.parameters.map { it.name } == expectedNames &&
                (prep.newVisibility == null ||
                    VisibilityUtil.getVisibilityModifier(prep.method.modifierList) == prep.newVisibility) &&
                (prep.newType == null || prep.method.returnType?.canonicalText == prep.newType.canonicalText)
        }
        if (!applied) return McpToolCallResult.error(RefactorSupport.BAIL_OUT)

        return McpToolCallResult.text(buildString {
            appendLine("Changed signature of ${prep.display}")
            appendLine("  new signature: ${prep.newSignature}")
            if (prep.removedParameters.isNotEmpty()) {
                appendLine("  removed parameter(s): ${prep.removedParameters.joinToString(", ")}")
            }
            appendLine("  declared in: ${prep.filePath}")
            appendLine()
            appendLine("Call sites, overrides, and javadoc references updated project-wide by IntelliJ.")
        })
    }

    /**
     * The conflict dialog is only reached when the prepare-successful callback is
     * non-null (ChangeSignatureProcessor.preprocessUsages), so a headless run must
     * install a no-op callback for the capture stub to see conflicts at all.
     */
    private class HeadlessChangeSignatureProcessor(
        project: Project,
        method: PsiMethod,
        newVisibility: String?,
        newName: String,
        newType: PsiType?,
        parameters: Array<ParameterInfoImpl>,
        private val ignoreConflicts: Boolean,
    ) : ChangeSignatureProcessor(project, method, false, newVisibility, newName, newType, parameters) {

        var capturedConflicts: MultiMap<PsiElement, String>? = null
            private set

        init {
            @Suppress("UsePropertyAccessSyntax") // setter is public, field is private
            setPrepareSuccessfulSwingThreadCallback(EmptyRunnable.INSTANCE)
        }

        override fun isPreviewUsages(usages: Array<UsageInfo>): Boolean = false

        // Test-mode default: covariant overriders keep their return types instead of prompting.
        override fun isProcessCovariantOverriders(): Boolean = false

        override fun prepareConflictsDialog(
            conflicts: MultiMap<PsiElement, String>,
            usages: Array<out UsageInfo>?,
        ): ConflictsDialogBase = object : ConflictsDialogBase {
            override fun setCommandName(name: String?) {}
            override fun isShowConflicts(): Boolean = false
            override fun showAndGet(): Boolean {
                if (ignoreConflicts) return true
                capturedConflicts = conflicts
                return false
            }
        }
    }
}
