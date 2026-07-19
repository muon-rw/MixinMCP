package dev.mixinmcp.tools.refactor

import com.intellij.java.refactoring.JavaRefactoringBundle
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.java.JavaFeature
import com.intellij.psi.GenericsUtil
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.LambdaUtil
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiExpressionStatement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiForStatement
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiSwitchLabelStatementBase
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiVariable
import com.intellij.psi.PsiWhileStatement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.codeStyle.JavaCodeStyleSettings
import com.intellij.psi.util.JavaPsiPatternUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.refactoring.IntroduceVariableUtil
import com.intellij.refactoring.JavaRefactoringSettings
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.extractMethod.newImpl.CodeFragmentAnalyzer
import com.intellij.refactoring.extractMethod.newImpl.ExtractException
import com.intellij.refactoring.extractMethod.newImpl.ExtractMethodPipeline
import com.intellij.refactoring.extractMethod.newImpl.ExtractSelector
import com.intellij.refactoring.extractMethod.newImpl.MethodExtractor
import com.intellij.refactoring.extractMethod.newImpl.inplace.DuplicatesMethodExtractor
import com.intellij.refactoring.extractMethod.newImpl.structures.DataOutput
import com.intellij.refactoring.extractMethod.newImpl.structures.ExtractOptions
import com.intellij.refactoring.introduceVariable.IntroduceVariableSettings
import com.intellij.refactoring.introduceVariable.VariableExtractor
import com.intellij.refactoring.rename.JavaUnresolvableLocalCollisionDetector
import com.intellij.refactoring.util.ConflictsUtil
import com.intellij.refactoring.util.RefactoringUIUtil
import com.intellij.refactoring.util.RefactoringUtil
import com.intellij.refactoring.util.occurrences.ExpressionOccurrenceManager
import com.intellij.refactoring.util.occurrences.NotInConstructorCallFilter
import com.intellij.util.CommonJavaRefactoringUtil
import com.intellij.util.IncorrectOperationException
import com.intellij.util.containers.MultiMap
import com.siyeh.ig.psiutils.ExpressionUtils
import com.siyeh.ig.psiutils.VariableAccessUtils
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
            "1-based, inclusive, and taken whole, so align them with statement boundaries. To extract a " +
            "sub-expression rather than whole lines, pass expression=<its exact source text> " +
            "(whitespace-insensitive), disambiguating repeats with occurrenceIndex (0-based, document " +
            "order); a non-matching expression lists the selectable expressions with their positions. " +
            "visibility " +
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
        expression: String? = null,
        occurrenceIndex: Int? = null,
        visibility: String = "private",
        makeStatic: Boolean? = null,
        ignoreConflicts: Boolean = false,
        dryRun: Boolean = false,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        if (!RefactorSupport.IDENTIFIER.matches(methodName)) {
            return McpToolCallResult.error("methodName '$methodName' is not a valid Java identifier.")
        }
        if (occurrenceIndex != null && expression == null) {
            return McpToolCallResult.error("occurrenceIndex only applies together with expression.")
        }
        if (occurrenceIndex != null && occurrenceIndex < 0) {
            return McpToolCallResult.error("occurrenceIndex must not be negative.")
        }
        val visibilityModifier: String = RefactorSupport.visibilityModifier(visibility)
            ?: return McpToolCallResult.error("Unknown visibility '$visibility'; ${RefactorSupport.VISIBILITY_HINT}.")

        val prepared: ExtractPrep = smartReadAction(project) {
            prepareExtract(
                project, filePath, startLine, endLine, methodName, expression, occurrenceIndex,
                visibilityModifier, makeStatic,
            )
        }
        val prep: ExtractPrep.Ok = when (prepared) {
            is ExtractPrep.Failure -> return McpToolCallResult.error(prepared.message)
            is ExtractPrep.Ok -> prepared
        }

        if (dryRun) {
            return McpToolCallResult.text(buildString {
                appendLine("Dry run for extract method from lines $startLine-$endLine of ${prep.filePath}")
                prep.selectedExpressionText?.let { appendLine("  selected expression: $it") }
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
            prep.selectedExpressionText?.let { appendLine("  selected expression: $it") }
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
            "statement, return statement, or field declaration is unwrapped to its expression " +
            "automatically, and a field initializer is moved into an initializer block. To target a " +
            "sub-expression instead of the whole line, pass expression=<its exact source text> " +
            "(whitespace-insensitive); if that text occurs more than once in the range, disambiguate with " +
            "occurrenceIndex (0-based, document order). A non-matching expression lists the selectable " +
            "expressions with their positions. name defaults to IntelliJ's suggestion; " +
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
        expression: String? = null,
        occurrenceIndex: Int? = null,
        replaceAllOccurrences: Boolean = false,
        ignoreConflicts: Boolean = false,
        dryRun: Boolean = false,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        if (name != null && !RefactorSupport.IDENTIFIER.matches(name)) {
            return McpToolCallResult.error("name '$name' is not a valid Java identifier.")
        }
        if (occurrenceIndex != null && expression == null) {
            return McpToolCallResult.error("occurrenceIndex only applies together with expression.")
        }
        if (occurrenceIndex != null && occurrenceIndex < 0) {
            return McpToolCallResult.error("occurrenceIndex must not be negative.")
        }

        val prepared: IntroducePrep = smartReadAction(project) {
            prepareIntroduce(
                project, filePath, startLine, endLine, name, expression, occurrenceIndex,
                replaceAllOccurrences, ignoreConflicts,
            )
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
            if (expression != null) appendLine("  selected expression: ${prep.expressionText}")
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
            val selectedExpressionText: String?,
        ) : ExtractPrep()

        data class Failure(val message: String) : ExtractPrep()
    }

    private fun prepareExtract(
        project: Project,
        filePath: String,
        startLine: Int,
        endLine: Int,
        methodName: String,
        requestedExpression: String?,
        occurrenceIndex: Int?,
        visibilityModifier: String,
        makeStatic: Boolean?,
    ): ExtractPrep {
        val resolved: List<PsiElement> = if (requestedExpression != null) {
            when (
                val pick = selectExpressionInRange(
                    project, filePath, startLine, endLine, requestedExpression, occurrenceIndex,
                    acceptVoid = true,
                )
            ) {
                is RefactorSupport.ExpressionPick.Failure -> return ExtractPrep.Failure(pick.message)
                is RefactorSupport.ExpressionPick.Ok -> listOf(pick.expression)
            }
        } else {
            when (val range = RefactorSupport.resolveRange(project, filePath, startLine, endLine)) {
                is RefactorSupport.RangeResult.Failure -> return ExtractPrep.Failure(range.message)
                is RefactorSupport.RangeResult.Statements -> range.statements.toList()
                is RefactorSupport.RangeResult.Expression -> listOf(range.expression)
                is RefactorSupport.RangeResult.Field -> return ExtractPrep.Failure(
                    "Lines $startLine-$endLine of $filePath cover the declaration of field " +
                        "${range.field.name}; extract method needs statements or an expression.",
                )
            }
        }
        val file = resolved.first().containingFile
        if (!file.isWritable) return ExtractPrep.Failure("$filePath is not writable.")
        val document = PsiDocumentManager.getInstance(project).getDocument(file)
            ?: return ExtractPrep.Failure("No document available for $filePath.")

        val textRange = TextRange(resolved.first().textRange.startOffset, resolved.last().textRange.endOffset)
        val elements: List<PsiElement> = ExtractSelector().suggestElementsToExtract(file, textRange)
        if (elements.isEmpty()) {
            val selected: PsiElement? = resolved.singleOrNull()?.takeIf { requestedExpression != null }
            val reason: String = when {
                selected == null -> "comments or switch labels only"
                PsiTreeUtil.findChildOfType(selected, PsiAssignmentExpression::class.java, false) != null ->
                    "the selected expression contains an assignment"
                PsiTreeUtil.getParentOfType(selected, PsiAnnotation::class.java) != null ->
                    "the selected expression is inside an annotation"
                else -> "the selected expression cannot be extracted in this position"
            }
            return ExtractPrep.Failure(
                "Lines $startLine-$endLine of $filePath do not form an extractable fragment ($reason).",
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
            selectedExpressionText = resolved.singleOrNull()
                ?.takeIf { requestedExpression != null }
                ?.let { RefactorSupport.abbreviate(it.text) },
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
        requestedExpression: String?,
        occurrenceIndex: Int?,
        replaceAllOccurrences: Boolean,
        ignoreConflicts: Boolean,
    ): IntroducePrep {
        val expression: PsiExpression = if (requestedExpression != null) {
            when (
                val pick = selectExpressionInRange(
                    project, filePath, startLine, endLine, requestedExpression, occurrenceIndex,
                    acceptVoid = false,
                )
            ) {
                is RefactorSupport.ExpressionPick.Failure -> return IntroducePrep.Failure(pick.message)
                is RefactorSupport.ExpressionPick.Ok -> pick.expression
            }
        } else {
            when (val range = RefactorSupport.resolveRange(project, filePath, startLine, endLine)) {
                is RefactorSupport.RangeResult.Failure -> return IntroducePrep.Failure(range.message)
                is RefactorSupport.RangeResult.Expression -> range.expression
                is RefactorSupport.RangeResult.Field -> range.field.initializer
                    ?: return IntroducePrep.Failure(
                        "Field ${range.field.name} in $filePath has no initializer to introduce a variable for.",
                    )
                is RefactorSupport.RangeResult.Statements ->
                    when (val statement = range.statements.singleOrNull()) {
                        is PsiExpressionStatement -> statement.expression
                        is PsiReturnStatement -> statement.returnValue
                        else -> null
                    } ?: return IntroducePrep.Failure(
                        "Lines $startLine-$endLine of $filePath resolve to whole statements, not an " +
                            "expression; introduce variable needs a range covering exactly one expression " +
                            "(a lone expression or return statement is unwrapped automatically). Pass " +
                            "expression=<source text> to target a sub-expression.",
                    )
            }
        }
        RefactorSupport.guardJavaSourceTarget(project, expression, "The selected expression")
            ?.let { return IntroducePrep.Failure(it) }

        validateIntroducible(expression)?.let { return IntroducePrep.Failure(it) }

        val dumbService: DumbService = DumbService.getInstance(project)
        val originalType: PsiType? = dumbService.computeWithAlternativeResolveEnabled<PsiType?, RuntimeException> {
            CommonJavaRefactoringUtil.getTypeByExpressionWithExpectedType(expression)
        }
        if (originalType == null || LambdaUtil.notInferredType(originalType)) {
            return IntroducePrep.Failure(JavaRefactoringBundle.message("unknown.expression.type"))
        }
        if (PsiTypes.voidType() == originalType) {
            return IntroducePrep.Failure(JavaRefactoringBundle.message("selected.expression.has.void.type"))
        }
        val variableType: PsiType = dumbService.computeWithAlternativeResolveEnabled<PsiType, RuntimeException> {
            GenericsUtil.getVariableTypeByExpressionType(originalType)
        }
        try {
            JavaPsiFacade.getElementFactory(project)
                .createTypeElementFromText(variableType.canonicalText, expression)
        } catch (_: IncorrectOperationException) {
            return IntroducePrep.Failure(JavaRefactoringBundle.message("unknown.expression.type"))
        }
        for (patternVariable in JavaPsiPatternUtil.getExposedPatternVariables(expression)) {
            val escapes: Boolean = VariableAccessUtils.getVariableReferences(patternVariable)
                .any { !PsiTreeUtil.isAncestor(expression, it, true) }
            if (escapes) {
                return IntroducePrep.Failure(
                    JavaRefactoringBundle.message(
                        "selected.expression.introduces.pattern.variable", patternVariable.name,
                    ),
                )
            }
        }

        val anchorStatement: PsiElement = anchorFor(expression)
            ?: return IntroducePrep.Failure(unsupportedContextMessage())
        constructorCallAnchorMessage(anchorStatement)?.let { return IntroducePrep.Failure(it) }
        val container: PsiElement = anchorStatement.parent
            ?: return IntroducePrep.Failure(unsupportedContextMessage())
        if (container !is PsiCodeBlock &&
            !CommonJavaRefactoringUtil.isLoopOrIf(container) &&
            container !is PsiLambdaExpression &&
            container.parent is PsiLambdaExpression
        ) {
            return IntroducePrep.Failure(unsupportedContextMessage())
        }

        val occurrenceManager: ExpressionOccurrenceManager = createOccurrenceManager(expression, container)
        val allOccurrences: Array<PsiExpression> = occurrenceManager.occurrences

        val anyAssignmentLhs: Boolean =
            allOccurrences.size > 1 && allOccurrences.any { RefactoringUtil.isAssignmentLHS(it) }
        val replaceAll: Boolean = replaceAllOccurrences && allOccurrences.size > 1
        if (replaceAll && cannotReplaceAll(allOccurrences)) {
            return IntroducePrep.Failure(
                "Cannot replace all ${allOccurrences.size} occurrences (some have write access or " +
                    "incompatible scopes); re-run with replaceAllOccurrences=false.",
            )
        }
        val selected: Array<PsiExpression> =
            if (replaceAll) allOccurrences else arrayOf(occurrenceManager.mainOccurence)
        if (selected.isEmpty()) return IntroducePrep.Failure("No matching occurrences to replace.")

        val anchor: PsiElement = anchorForAll(selected)
            ?: return IntroducePrep.Failure("Cannot find a statement to anchor the new variable.")
        val scope: PsiElement = anchor.parent
            ?: return IntroducePrep.Failure("Cannot find a statement to anchor the new variable.")
        if (selected.any { !PsiTreeUtil.isAncestor(scope, it, false) }) {
            return IntroducePrep.Failure(
                "Cannot replace all ${allOccurrences.size} occurrences: some of them lie outside the scope " +
                    "where the new variable would be visible; re-run with replaceAllOccurrences=false.",
            )
        }
        // VariableExtractor only deletes the leftover `name;` statement when it is the anchor, so any other
        // occurrence that is a whole expression statement would be left as an uncompilable bare reference.
        val danglingCount: Int = selected.count { occurrence ->
            val parent: PsiElement? = occurrence.parent
            parent is PsiExpressionStatement && parent.expression === occurrence && parent !== anchor
        }
        if (danglingCount > 0) {
            return IntroducePrep.Failure(
                "Cannot replace all ${allOccurrences.size} occurrences: $danglingCount of them are complete " +
                    "expression statements that would be left as a bare variable reference, which does not " +
                    "compile; re-run with replaceAllOccurrences=false.",
            )
        }

        val variableName: String = name
            ?: CommonJavaRefactoringUtil.getSuggestedName(variableType, expression, anchorStatement)
                .names.firstOrNull()
            ?: "v"
        val declareFinal: Boolean = replaceAll && occurrenceManager.isInFinalContext ||
            !anyAssignmentLhs && finalsByDefault(anchorStatement.containingFile) ||
            anchorStatement is PsiSwitchLabelStatementBase
        val settings: IntroduceVariableSettings = HeadlessIntroduceVariableSettings(
            name = variableName,
            type = variableType,
            replaceAll = replaceAll,
            replaceLValues = anyAssignmentLhs && replaceAll,
            declareFinal = declareFinal,
        )

        val conflictMap: MultiMap<PsiElement, String> = MultiMap()
        collectIntroduceConflicts(anchor, scope, variableName, selected, conflictMap)
        val conflicts: List<RefactorSupport.ConflictRef> =
            if (conflictMap.isEmpty) emptyList() else RefactorSupport.renderConflicts(project, conflictMap)

        return IntroducePrep.Ok(
            expression = expression,
            occurrences = selected,
            totalOccurrences = allOccurrences.size,
            anchor = anchor,
            settings = settings,
            variableName = variableName,
            typeText = variableType.presentableText,
            filePath = expression.containingFile.virtualFile
                ?.let { RefactorSupport.projectRelative(project, it) } ?: filePath,
            expressionText = RefactorSupport.abbreviate(expression.text),
            conflicts = conflicts,
            blocked = conflicts.isNotEmpty() && !ignoreConflicts,
        )
    }

    private fun selectExpressionInRange(
        project: Project,
        filePath: String,
        startLine: Int,
        endLine: Int,
        requestedExpression: String,
        occurrenceIndex: Int?,
        acceptVoid: Boolean,
    ): RefactorSupport.ExpressionPick {
        val fileRange: RefactorSupport.FileRange =
            RefactorSupport.resolveFileRange(project, filePath, startLine, endLine)
                .getOrElse {
                    return RefactorSupport.ExpressionPick.Failure(it.message ?: "Could not resolve $filePath.")
                }
        val candidates = RefactorSupport.collectExpressionCandidates(fileRange, acceptVoid)
        return RefactorSupport.pickExpression(
            project, fileRange.file, candidates, requestedExpression, occurrenceIndex,
            "lines $startLine-$endLine of $filePath",
        )
    }

    private fun unsupportedContextMessage(): String = JavaRefactoringBundle.message(
        "refactoring.is.not.supported.in.the.current.context",
        RefactoringBundle.message("introduce.variable.title"),
    )

    private fun validateIntroducible(expression: PsiExpression): String? {
        // Gates assignments and bare class/package references, matching findExpressionInRange's
        // own isExtractable check on everything it returns.
        if (!CommonJavaRefactoringUtil.isExtractable(expression)) {
            return JavaRefactoringBundle.message("selected.block.should.represent.an.expression")
        }
        IntroduceVariableUtil.getErrorMessage(expression)?.let { return it }
        val field = ExpressionUtils.getTopLevelExpression(expression).parent as? PsiField
        if (field?.containingClass?.isInterface == true) {
            return JavaRefactoringBundle.message("introduce.variable.message.cannot.extract.variable.in.interface")
        }
        if (!expression.isPhysical) {
            return JavaRefactoringBundle.message("selected.block.should.represent.an.expression")
        }
        return RefactoringUtil.checkEnumConstantInSwitchLabel(expression)
    }

    private fun constructorCallAnchorMessage(anchorStatement: PsiElement): String? {
        if (PsiUtil.isAvailable(JavaFeature.STATEMENTS_BEFORE_SUPER, anchorStatement)) return null
        val enclosing = (anchorStatement as? PsiExpressionStatement)?.expression as? PsiMethodCallExpression
        if (enclosing?.resolveMethod()?.isConstructor == true) {
            return JavaRefactoringBundle.message("invalid.expression.context")
        }
        return null
    }

    private fun anchorFor(place: PsiElement): PsiElement? {
        CommonJavaRefactoringUtil.getParentStatement(place, false)?.let { return it }
        val field: PsiField = PsiTreeUtil.getParentOfType(place, PsiField::class.java, true, PsiStatement::class.java)
            ?: return null
        if (field is PsiEnumConstant) return null
        val initializer: PsiExpression = field.initializer ?: return null
        return initializer.takeIf { PsiTreeUtil.isAncestor(it, place, false) }
    }

    private fun anchorForAll(places: Array<PsiExpression>): PsiElement? {
        if (places.size == 1) return anchorFor(places[0])
        val anchor: PsiElement = CommonJavaRefactoringUtil.getAnchorElementForMultipleExpressions(places, null)
            ?: return null
        return if (anchor is PsiField && anchor !is PsiEnumConstant) anchor.initializer else anchor
    }

    private fun createOccurrenceManager(
        expression: PsiExpression,
        container: PsiElement,
    ): ExpressionOccurrenceManager {
        val vars: MutableSet<PsiVariable> = HashSet()
        for (element in SyntaxTraverser.psiTraverser().withRoot(expression).traverse()) {
            val resolved: PsiElement? = (element as? PsiReferenceExpression)?.resolve()
            if (resolved is PsiVariable) vars.add(resolved)
        }

        var current: PsiElement? = container
        var lastScope: PsiElement = container
        while (current != null && current !is PsiFile) {
            val node: PsiElement = current
            if (node is PsiMethod) {
                val containingClass = node.containingClass
                if (containingClass == null || !PsiUtil.isLocalOrAnonymousClass(containingClass)) break
                if (vars.any { PsiTreeUtil.isAncestor(containingClass, it, true) }) break
            }
            if (node is PsiLambdaExpression && node.parameterList.parameters.any { it in vars }) break
            if (node is PsiForStatement &&
                vars.any { PsiTreeUtil.isAncestor(node.initialization, it, true) }
            ) {
                break
            }
            current = node.parent
            if (current is PsiCodeBlock) lastScope = current
        }
        return ExpressionOccurrenceManager(expression, lastScope, NotInConstructorCallFilter.INSTANCE)
    }

    private fun cannotReplaceAll(occurrences: Array<PsiExpression>): Boolean {
        var sawNonWrite = false
        for (occurrence in occurrences) {
            if (!RefactoringUtil.isAssignmentLHS(occurrence)) {
                sawNonWrite = true
            } else if (isFinalVariableOnLhs(occurrence)) {
                return true
            } else if (sawNonWrite) {
                return true
            }
        }
        return false
    }

    private fun isFinalVariableOnLhs(expression: PsiExpression): Boolean {
        if (expression !is PsiReferenceExpression || !RefactoringUtil.isAssignmentLHS(expression)) return false
        val resolved = expression.resolve()
        return resolved is PsiVariable && resolved.hasModifierProperty(PsiModifier.FINAL)
    }

    private fun finalsByDefault(file: PsiFile): Boolean =
        JavaRefactoringSettings.getInstance().INTRODUCE_LOCAL_CREATE_FINALS
            ?: JavaCodeStyleSettings.getInstance(file).GENERATE_FINAL_LOCALS

    private fun collectIntroduceConflicts(
        anchor: PsiElement,
        scope: PsiElement,
        variableName: String,
        occurrences: Array<PsiExpression>,
        conflicts: MultiMap<PsiElement, String>,
    ) {
        val reported: MutableSet<PsiVariable> = HashSet()
        JavaUnresolvableLocalCollisionDetector.visitLocalsCollisions(
            anchor, variableName, scope, anchor,
        ) { colliding: PsiVariable ->
            if (reported.add(colliding)) {
                conflicts.putValue(
                    colliding,
                    JavaRefactoringBundle.message(
                        "introduced.variable.will.conflict.with.0",
                        RefactoringUIUtil.getDescription(colliding, true),
                    ),
                )
            }
        }
        for (occurrence in occurrences) addLoopConditionConflicts(occurrence, conflicts)
    }

    private fun addLoopConditionConflicts(occurrence: PsiExpression, conflicts: MultiMap<PsiElement, String>) {
        val loop: PsiElement = RefactoringUtil.getLoopForLoopCondition(occurrence) ?: return
        if (loop is PsiWhileStatement) return
        val modifiedInBody: List<PsiVariable> = RefactoringUtil.collectReferencedVariables(occurrence)
            .filter { RefactoringUtil.isModifiedInScope(it, loop) }
        if (modifiedInBody.isEmpty()) return
        for (variable in modifiedInBody) {
            conflicts.putValue(
                variable,
                StringUtil.capitalize(
                    JavaRefactoringBundle.message(
                        "is.modified.in.loop.body", RefactoringUIUtil.getDescription(variable, false),
                    ),
                ),
            )
        }
        conflicts.putValue(occurrence, JavaRefactoringBundle.message("introducing.variable.may.break.code.logic"))
    }

    /**
     * getReplaceChoice() is deliberately not overridden: its return type is platform-internal, and inheriting
     * the interface default keeps that type out of this plugin's bytecode. VariableExtractor never calls it.
     */
    private class HeadlessIntroduceVariableSettings(
        private val name: String,
        private val type: PsiType,
        private val replaceAll: Boolean,
        private val replaceLValues: Boolean,
        private val declareFinal: Boolean,
    ) : IntroduceVariableSettings {
        override fun getEnteredName(): String = name

        override fun getSelectedType(): PsiType = type

        override fun isReplaceAllOccurrences(): Boolean = replaceAll

        override fun isReplaceLValues(): Boolean = replaceLValues

        override fun isDeclareFinal(): Boolean = declareFinal

        override fun isDeclareVarType(): Boolean = false

        override fun isOK(): Boolean = true
    }
}
