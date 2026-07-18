package dev.mixinmcp.tools.refactor

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiExpressionStatement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.extractMethod.newImpl.CodeFragmentAnalyzer
import com.intellij.refactoring.extractMethod.newImpl.ExtractException
import com.intellij.refactoring.extractMethod.newImpl.ExtractMethodPipeline
import com.intellij.refactoring.extractMethod.newImpl.ExtractSelector
import com.intellij.refactoring.extractMethod.newImpl.MethodExtractor
import com.intellij.refactoring.extractMethod.newImpl.inplace.DuplicatesMethodExtractor
import com.intellij.refactoring.extractMethod.newImpl.structures.DataOutput
import com.intellij.refactoring.extractMethod.newImpl.structures.ExtractOptions
import com.intellij.refactoring.introduceVariable.InputValidator
import com.intellij.refactoring.introduceVariable.IntroduceVariableBase
import com.intellij.refactoring.introduceVariable.IntroduceVariableSettings
import com.intellij.refactoring.introduceVariable.VariableExtractor
import com.intellij.refactoring.ui.TypeSelectorManagerImpl
import com.intellij.refactoring.util.ConflictsUtil
import com.intellij.refactoring.util.RefactoringUtil
import com.intellij.util.containers.MultiMap
import dev.mixinmcp.tools.requireProject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

@Suppress("FunctionName") // @McpTool functions are snake_case by MCP convention
class ExtractToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Extract a range of Java statements or a single expression into a new method, replacing the fragment " +
            "with a call. IntelliJ's control-flow analysis derives the parameters, return value, and thrown " +
            "exceptions, which is exactly the part manual extraction gets wrong. startLine/endLine are " +
            "1-based, inclusive, and taken whole, so align them with statement boundaries. visibility " +
            "defaults to private. makeStatic=true passes referenced fields as parameters where possible, " +
            "makeStatic=false refuses a static result, omitted lets the analysis decide. Name clashes in the " +
            "target class are reported as conflicts tagged [library] or [source]; ignoreConflicts=true " +
            "proceeds anyway. Java sources only. dryRun=true reports the derived signature and target class " +
            "without modifying anything.",
    )
    @Suppress("unused")
    suspend fun mixin_extract_method(
        filePath: String,
        startLine: Int,
        endLine: Int,
        methodName: String,
        visibility: String = "private",
        makeStatic: Boolean? = null,
        ignoreConflicts: Boolean = false,
        dryRun: Boolean = false,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        if (!RefactorSupport.IDENTIFIER.matches(methodName)) {
            return McpToolCallResult.error("methodName '$methodName' is not a valid Java identifier.")
        }
        val visibilityModifier: String = RefactorSupport.visibilityModifier(visibility)
            ?: return McpToolCallResult.error("Unknown visibility '$visibility'; ${RefactorSupport.VISIBILITY_HINT}.")

        val prepared: ExtractPrep = smartReadAction(project) {
            prepareExtract(project, filePath, startLine, endLine, methodName, visibilityModifier, makeStatic)
        }
        val prep: ExtractPrep.Ok = when (prepared) {
            is ExtractPrep.Failure -> return McpToolCallResult.error(prepared.message)
            is ExtractPrep.Ok -> prepared
        }

        if (dryRun) {
            return McpToolCallResult.text(buildString {
                appendLine("Dry run for extract method from lines $startLine-$endLine of ${prep.filePath}")
                appendLine("  new method: ${prep.signature}")
                appendLine("  target class: ${prep.targetClassName}")
                appendLine("  ${prep.outputDescription}")
                appendLine()
                if (prep.conflicts.isEmpty()) {
                    appendLine("No conflicts detected.")
                } else {
                    append(RefactorSupport.formatConflicts(prep.conflicts))
                }
                appendLine("Re-run without dryRun=true to perform the extraction.")
            })
        }
        if (prep.conflicts.isNotEmpty() && !ignoreConflicts) {
            return McpToolCallResult.error(
                "Cannot extract '$methodName' into ${prep.targetClassName}.\n" +
                    RefactorSupport.formatConflicts(prep.conflicts),
            )
        }

        val valid: Boolean = smartReadAction(project) {
            prep.rangeMarker.isValid && prep.options.elements.all { it.isValid }
        }
        if (!valid) return McpToolCallResult.error(RefactorSupport.STALE_TARGET)

        val extracted = try {
            DuplicatesMethodExtractor(prep.options, prep.options.targetClass, prep.rangeMarker).extract()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return McpToolCallResult.error("Extract method failed: ${t.message ?: t.javaClass.simpleName}")
        }
        RefactorSupport.runRefactoringOnEdt(project) {}?.let {
            return McpToolCallResult.error("Extract method applied, but saving documents failed: $it")
        }

        val extractedDisplay: String = smartReadAction(project) {
            extracted.method.takeIf { it.isValid }?.let { RefactorSupport.methodDisplayName(it, prep.targetClassName) }
        } ?: return McpToolCallResult.error(
            "The extraction produced no method (concurrent modification?); check the file before retrying.",
        )

        return McpToolCallResult.text(buildString {
            appendLine("Extracted lines $startLine-$endLine of ${prep.filePath} into $extractedDisplay")
            appendLine("  signature: ${prep.signature}")
            appendLine("  ${prep.outputDescription}")
            if (prep.conflicts.isNotEmpty()) {
                appendLine("  proceeded despite ${prep.conflicts.size} conflict(s) (ignoreConflicts=true)")
            }
            appendLine()
            appendLine("The fragment was replaced with a call to the new method.")
        })
    }

    @McpTool
    @McpDescription(
        "Introduce a local variable for a Java expression, replacing the expression with a reference to it. " +
            "The 1-based, inclusive, whole-line range must cover exactly one expression; a lone expression " +
            "statement is unwrapped automatically. name defaults to IntelliJ's suggestion; " +
            "replaceAllOccurrences=true also replaces every other occurrence of the same expression in " +
            "scope. Name collisions are reported as conflicts tagged [library] or [source]; " +
            "ignoreConflicts=true proceeds anyway. Java sources only. dryRun=true reports the variable name, " +
            "type, and occurrence count without modifying anything.",
    )
    @Suppress("unused")
    suspend fun mixin_introduce_variable(
        filePath: String,
        startLine: Int,
        endLine: Int,
        name: String? = null,
        replaceAllOccurrences: Boolean = false,
        ignoreConflicts: Boolean = false,
        dryRun: Boolean = false,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        if (name != null && !RefactorSupport.IDENTIFIER.matches(name)) {
            return McpToolCallResult.error("name '$name' is not a valid Java identifier.")
        }

        val prepared: IntroducePrep = smartReadAction(project) {
            prepareIntroduce(project, filePath, startLine, endLine, name, replaceAllOccurrences, ignoreConflicts)
        }
        val prep: IntroducePrep.Ok = when (prepared) {
            is IntroducePrep.Failure -> return McpToolCallResult.error(prepared.message)
            is IntroducePrep.Ok -> prepared
        }

        if (dryRun) {
            return McpToolCallResult.text(buildString {
                appendLine("Dry run for introduce variable from lines $startLine-$endLine of ${prep.filePath}")
                appendLine("  expression: ${prep.expressionText}")
                appendLine("  variable: ${prep.typeText} ${prep.variableName}")
                appendLine("  occurrences: ${prep.occurrences.size} of ${prep.totalOccurrences} replaced")
                appendLine()
                if (prep.conflicts.isEmpty()) {
                    appendLine("No conflicts detected.")
                } else {
                    append(RefactorSupport.formatConflicts(prep.conflicts))
                }
                appendLine("Re-run without dryRun=true to introduce the variable.")
            })
        }
        if (prep.blocked) {
            return McpToolCallResult.error(
                "Cannot introduce variable '${prep.variableName}'.\n" +
                    RefactorSupport.formatConflicts(prep.conflicts),
            )
        }

        var introducedName: String? = null
        val error: String? = RefactorSupport.runRefactoringOnEdt(project) {
            check(
                prep.expression.isValid && prep.anchor.isValid && prep.occurrences.all { it.isValid },
            ) { RefactorSupport.STALE_TARGET }
            CommandProcessor.getInstance().executeCommand(project, {
                introducedName = VariableExtractor.introduce(
                    project, prep.expression, null, prep.anchor, prep.occurrences, prep.settings,
                )?.name
            }, RefactoringBundle.message("introduce.variable.title"), null)
        }
        if (error != null) return McpToolCallResult.error("Introduce variable failed: $error")
        val finalName: String = introducedName ?: return McpToolCallResult.error(RefactorSupport.BAIL_OUT)

        return McpToolCallResult.text(buildString {
            appendLine("Introduced ${prep.typeText} $finalName in lines $startLine-$endLine of ${prep.filePath}")
            appendLine("  replaced ${prep.occurrences.size} of ${prep.totalOccurrences} occurrence(s)")
            if (prep.conflicts.isNotEmpty()) {
                appendLine("  proceeded despite ${prep.conflicts.size} conflict(s) (ignoreConflicts=true)")
            }
        })
    }

    // ───────────────────────────────────── Extract method ─────────────────────────────────────

    private sealed class ExtractPrep {
        class Ok(
            val options: ExtractOptions,
            val rangeMarker: RangeMarker,
            val filePath: String,
            val targetClassName: String,
            val signature: String,
            val outputDescription: String,
            val conflicts: List<RefactorSupport.ConflictRef>,
        ) : ExtractPrep()

        data class Failure(val message: String) : ExtractPrep()
    }

    private fun prepareExtract(
        project: Project,
        filePath: String,
        startLine: Int,
        endLine: Int,
        methodName: String,
        visibilityModifier: String,
        makeStatic: Boolean?,
    ): ExtractPrep {
        val range = RefactorSupport.resolveRange(project, filePath, startLine, endLine)
        val resolved: List<PsiElement> = when (range) {
            is RefactorSupport.RangeResult.Failure -> return ExtractPrep.Failure(range.message)
            is RefactorSupport.RangeResult.Statements -> range.statements.toList()
            is RefactorSupport.RangeResult.Expression -> listOf(range.expression)
        }
        val file = resolved.first().containingFile
        if (!file.isWritable) return ExtractPrep.Failure("$filePath is not writable.")
        val document = PsiDocumentManager.getInstance(project).getDocument(file)
            ?: return ExtractPrep.Failure("No document available for $filePath.")

        val textRange = TextRange(resolved.first().textRange.startOffset, resolved.last().textRange.endOffset)
        val elements: List<PsiElement> = ExtractSelector().suggestElementsToExtract(file, textRange)
        if (elements.isEmpty()) {
            return ExtractPrep.Failure(
                "Lines $startLine-$endLine of $filePath do not form an extractable fragment " +
                    "(comments or switch labels only).",
            )
        }

        val allOptions: List<ExtractOptions> = try {
            ExtractMethodPipeline.findAllOptionsToExtract(elements)
        } catch (e: ExtractException) {
            return ExtractPrep.Failure(buildString {
                append("Cannot extract lines $startLine-$endLine: ${e.message}")
                val lines = e.problems.map { document.getLineNumber(it.startOffset) + 1 }.distinct().sorted()
                if (lines.isNotEmpty()) append(" (line ${lines.joinToString(", ")})")
            })
        }
        var options: ExtractOptions = allOptions.firstOrNull { it.targetClass !is PsiAnonymousClass }
            ?: allOptions.firstOrNull()
            ?: return ExtractPrep.Failure("No enclosing class can host the extracted method.")
        options = options.copy(methodName = methodName, visibility = visibilityModifier)
        when (makeStatic) {
            true -> if (!options.isStatic) {
                val analyzer = CodeFragmentAnalyzer.createAnalyzer(options.elements)
                    ?: return ExtractPrep.Failure("Control-flow analysis failed for lines $startLine-$endLine.")
                options = ExtractMethodPipeline.withForcedStatic(analyzer, options)
                    ?: return ExtractPrep.Failure(
                        "The fragment references instance state that cannot be passed as parameters; " +
                            "makeStatic=true is not possible here.",
                    )
            }
            false -> if (options.isStatic) {
                return ExtractPrep.Failure(
                    "The fragment is in a static context; the extracted method must be static.",
                )
            }
            null -> {}
        }

        val previewMethod: PsiMethod = MethodExtractor().prepareRefactoringElements(options).method
        val conflictMap: MultiMap<PsiElement, String> = MultiMap()
        ConflictsUtil.checkMethodConflicts(options.targetClass, null, previewMethod, conflictMap)

        val rangeMarker: RangeMarker = document.createRangeMarker(textRange)
        rangeMarker.isGreedyToLeft = true
        rangeMarker.isGreedyToRight = true

        return ExtractPrep.Ok(
            options = options,
            rangeMarker = rangeMarker,
            filePath = file.virtualFile?.let { RefactorSupport.projectRelative(project, it) } ?: filePath,
            targetClassName = options.targetClass.qualifiedName ?: options.targetClass.name ?: "(anonymous class)",
            signature = renderSignature(options, previewMethod),
            outputDescription = describeOutput(options.dataOutput),
            conflicts = RefactorSupport.renderConflicts(project, conflictMap),
        )
    }

    private fun renderSignature(options: ExtractOptions, method: PsiMethod): String = buildString {
        options.visibility?.takeUnless { it == PsiModifier.PACKAGE_LOCAL }?.let { append(it).append(' ') }
        if (options.isStatic) append("static ")
        append(method.returnType?.presentableText ?: "void")
        append(' ').append(options.methodName)
        append('(')
        append(method.parameterList.parameters.joinToString(", ") { "${it.type.presentableText} ${it.name}" })
        append(')')
        val thrown = method.throwsList.referencedTypes
        if (thrown.isNotEmpty()) {
            append(" throws ").append(thrown.joinToString(", ") { it.presentableText })
        }
    }

    private fun describeOutput(output: DataOutput): String = when (output) {
        is DataOutput.VariableOutput ->
            "returns ${output.type.presentableText}, assigned to '${output.name}' at the call site"
        is DataOutput.ExpressionOutput -> "returns ${output.type.presentableText}"
        is DataOutput.ArtificialBooleanOutput ->
            "returns boolean, a generated early-exit flag for the fragment's conditional control flow"
        is DataOutput.EmptyOutput -> "returns void"
    }

    // ───────────────────────────────────── Introduce variable ─────────────────────────────────────

    private sealed class IntroducePrep {
        class Ok(
            val expression: PsiExpression,
            val occurrences: Array<PsiExpression>,
            val totalOccurrences: Int,
            val anchor: PsiElement,
            val settings: IntroduceVariableSettings,
            val variableName: String,
            val typeText: String,
            val filePath: String,
            val expressionText: String,
            val conflicts: List<RefactorSupport.ConflictRef>,
            val blocked: Boolean,
        ) : IntroducePrep()

        data class Failure(val message: String) : IntroducePrep()
    }

    private fun prepareIntroduce(
        project: Project,
        filePath: String,
        startLine: Int,
        endLine: Int,
        name: String?,
        replaceAllOccurrences: Boolean,
        ignoreConflicts: Boolean,
    ): IntroducePrep {
        val range = RefactorSupport.resolveRange(project, filePath, startLine, endLine)
        val expression: PsiExpression = when (range) {
            is RefactorSupport.RangeResult.Failure -> return IntroducePrep.Failure(range.message)
            is RefactorSupport.RangeResult.Expression -> range.expression
            is RefactorSupport.RangeResult.Statements ->
                (range.statements.singleOrNull() as? PsiExpressionStatement)?.expression
                    ?: return IntroducePrep.Failure(
                        "Lines $startLine-$endLine of $filePath resolve to whole statements, not an " +
                            "expression; introduce variable needs a range covering exactly one expression " +
                            "(a lone expression statement is unwrapped automatically).",
                    )
        }
        RefactorSupport.guardJavaSourceTarget(project, expression, "The selected expression")
            ?.let { return IntroducePrep.Failure(it) }

        val result = IntroduceVariableBase.getIntroduceVariableContext(project, expression, null)
        val context = result as? IntroduceVariableBase.IntroduceVariableResult.Context
            ?: return IntroducePrep.Failure(
                (result as IntroduceVariableBase.IntroduceVariableResult.Error).message
                    ?: "The expression cannot be introduced as a variable.",
            )

        val occurrenceManager = context.occurrenceManager()
        val allOccurrences: Array<PsiExpression> = occurrenceManager.occurrences
        val occurrencesMap = context.occurrencesInfo().buildOccurrencesMap(context.expression())
        val choice: IntroduceVariableBase.JavaReplaceChoice =
            if (replaceAllOccurrences && allOccurrences.size > 1) {
                occurrencesMap.entries.firstOrNull { (c, targets) -> !c.isChain && targets.size == allOccurrences.size }?.key
                    ?: return IntroducePrep.Failure(
                        "Cannot replace all ${allOccurrences.size} occurrences (some have write access or " +
                            "incompatible scopes); re-run with replaceAllOccurrences=false.",
                    )
            } else {
                occurrencesMap.entries.firstOrNull { (c, targets) -> !c.isChain && targets.size == 1 }?.key
                    ?: occurrencesMap.keys.firstOrNull { !it.isChain }
                    ?: return IntroducePrep.Failure("No applicable replacement choice for this expression.")
            }

        val handler = HeadlessIntroduceVariableHandler(ignoreConflicts)
        val validator = InputValidator(handler, project, occurrenceManager)
        val typeSelectorManager = TypeSelectorManagerImpl(project, context.originalType(), context.expression(), allOccurrences)
        val hasWriteAccess: Boolean =
            allOccurrences.size > 1 && allOccurrences.any { RefactoringUtil.isAssignmentLHS(it) }
        val baseSettings: IntroduceVariableSettings = handler.getSettings(
            project, null, context.expression(), allOccurrences, typeSelectorManager,
            occurrenceManager.isInFinalContext, hasWriteAccess, validator, context.anchorStatement(), choice,
        )
        val settings: IntroduceVariableSettings =
            if (name != null) NamedSettings(baseSettings, name) else baseSettings

        val proceed: Boolean = validator.isOK(settings)
        val conflicts: List<RefactorSupport.ConflictRef> =
            handler.capturedConflicts?.let { RefactorSupport.renderConflicts(project, it) } ?: emptyList()

        val selected: Array<PsiExpression> = settings.replaceChoice.filter(occurrenceManager)
        if (selected.isEmpty()) return IntroducePrep.Failure("No matching occurrences to replace.")
        val anchor: PsiElement = IntroduceVariableBase.getAnchor(selected)
            ?: return IntroducePrep.Failure("Cannot find a statement to anchor the new variable.")

        return IntroducePrep.Ok(
            expression = context.expression(),
            occurrences = selected,
            totalOccurrences = allOccurrences.size,
            anchor = anchor,
            settings = settings,
            variableName = settings.enteredName,
            typeText = settings.selectedType.presentableText,
            filePath = expression.containingFile.virtualFile
                ?.let { RefactorSupport.projectRelative(project, it) } ?: filePath,
            expressionText = expression.text.let { if (it.length > 80) it.take(77) + "..." else it },
            conflicts = conflicts,
            blocked = !proceed,
        )
    }

    private class NamedSettings(
        private val base: IntroduceVariableSettings,
        private val name: String,
    ) : IntroduceVariableSettings by base {
        override fun getEnteredName(): String = name

        override fun isDeclareVarType(): Boolean = base.isDeclareVarType

        override fun getReplaceChoice(): IntroduceVariableBase.JavaReplaceChoice = base.replaceChoice
    }

    private class HeadlessIntroduceVariableHandler(
        private val ignoreConflicts: Boolean,
    ) : IntroduceVariableBase() {
        var capturedConflicts: MultiMap<PsiElement, String>? = null
            private set

        override fun showErrorMessage(project: Project, editor: Editor?, message: String) {}

        override fun reportConflicts(
            conflicts: MultiMap<PsiElement, String>,
            project: Project,
            settings: IntroduceVariableSettings,
        ): Boolean {
            capturedConflicts = conflicts
            return ignoreConflicts
        }
    }
}
