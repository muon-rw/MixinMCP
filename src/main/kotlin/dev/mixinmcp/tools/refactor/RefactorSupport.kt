package dev.mixinmcp.tools.refactor

import com.intellij.codeInsight.CodeInsightUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiField
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParenthesizedExpression
import com.intellij.psi.PsiSuperExpression
import com.intellij.psi.PsiTypes
import com.intellij.psi.SyntaxTraverser
import com.intellij.util.CommonJavaRefactoringUtil
import com.intellij.util.IncorrectOperationException
import com.intellij.refactoring.util.classMembers.MemberInfo
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.containers.MultiMap
import dev.mixinmcp.resolve.FqcnResolver
import dev.mixinmcp.resolve.MethodResolver
import dev.mixinmcp.tools.flushVfsToDisk
import dev.mixinmcp.tools.projectRelativePath
import java.io.File

internal object RefactorSupport {

    const val KOTLIN_REFUSAL: String = "Java sources only; Kotlin targets are not yet supported"

    const val STALE_TARGET: String = "Target PSI element is no longer valid (was it modified concurrently?)."

    const val BAIL_OUT: String = "The platform refactoring bailed out without applying changes " +
        "(preview escalation from read-only or non-code usages, dumb mode, or canceled progress). " +
        "Check the IDE for an opened usage preview; nothing was changed."

    const val DRY_RUN_BAIL_OUT: String = "The platform refactoring bailed out before conflict analysis completed " +
        "(read-only files, dumb mode, or canceled progress); no dry-run data."

    val IDENTIFIER: Regex = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

    const val VISIBILITY_HINT: String = "visibility must be 'public', 'protected', 'packageLocal', or 'private'"

    fun visibilityModifier(name: String): String? = when (name) {
        "public" -> PsiModifier.PUBLIC
        "protected" -> PsiModifier.PROTECTED
        "private" -> PsiModifier.PRIVATE
        "packageLocal", "package-private", "package" -> PsiModifier.PACKAGE_LOCAL
        else -> null
    }

    // ───────────────────────────────────── Range addressing ─────────────────────────────────────

    sealed class RangeResult {
        class Statements(val file: PsiJavaFile, val statements: Array<PsiElement>) : RangeResult()
        class Expression(val file: PsiJavaFile, val expression: PsiExpression) : RangeResult()

        /** A field declaration, which is neither a statement nor an expression but has an introducible initializer. */
        class Field(val file: PsiJavaFile, val field: PsiField) : RangeResult()
        data class Failure(val message: String) : RangeResult()
    }

    class FileRange(
        val file: PsiJavaFile,
        val document: Document,
        val startOffset: Int,
        val endOffset: Int,
    )

    @RequiresReadLock
    fun resolveFileRange(
        project: Project,
        filePath: String,
        startLine: Int,
        endLine: Int,
    ): Result<FileRange> {
        if (startLine < 1 || endLine < startLine) {
            return Result.failure(
                IllegalArgumentException(
                    "Invalid range: lines are 1-based and startLine ($startLine) must not exceed endLine ($endLine).",
                ),
            )
        }
        val virtualFile: VirtualFile = findVirtualFile(project, filePath)
            ?: return Result.failure(
                IllegalArgumentException(
                    "File not found: $filePath. Pass a path absolute or relative to the project root.",
                ),
            )
        if (ProjectFileIndex.getInstance(project).isInLibrary(virtualFile)) {
            return Result.failure(
                IllegalArgumentException(
                    "$filePath is inside library ${VfsUtilCore.getRootFile(virtualFile).name}; " +
                        "this tool only operates on project source.",
                ),
            )
        }
        val psiFile: PsiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return Result.failure(IllegalArgumentException("Could not get PSI for $filePath."))
        if (psiFile !is PsiJavaFile) {
            val message = if (isKotlinFile(psiFile)) {
                KOTLIN_REFUSAL
            } else {
                "$filePath is not a Java source file (language: ${psiFile.language.displayName})."
            }
            return Result.failure(IllegalArgumentException(message))
        }
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return Result.failure(IllegalArgumentException("No document available for $filePath."))
        if (endLine > document.lineCount) {
            return Result.failure(
                IllegalArgumentException(
                    "endLine $endLine is past the end of $filePath (${document.lineCount} lines).",
                ),
            )
        }
        return Result.success(
            FileRange(
                file = psiFile,
                document = document,
                startOffset = document.getLineStartOffset(startLine - 1),
                endOffset = document.getLineEndOffset(endLine - 1),
            ),
        )
    }

    @RequiresReadLock
    fun resolveRange(
        project: Project,
        filePath: String,
        startLine: Int,
        endLine: Int,
    ): RangeResult {
        val fileRange: FileRange = resolveFileRange(project, filePath, startLine, endLine)
            .getOrElse { return RangeResult.Failure(it.message ?: "Could not resolve $filePath.") }
        val psiFile: PsiJavaFile = fileRange.file
        val startOffset: Int = fileRange.startOffset
        val endOffset: Int = fileRange.endOffset

        val statements: Array<PsiElement> = CodeInsightUtil.findStatementsInRange(psiFile, startOffset, endOffset)
        if (statements.isNotEmpty()) return RangeResult.Statements(psiFile, statements)

        val expression: PsiExpression? = CodeInsightUtil.findExpressionInRange(psiFile, startOffset, endOffset)
        if (expression != null) return RangeResult.Expression(psiFile, expression)

        fieldInRange(psiFile, startOffset, endOffset)?.let { return RangeResult.Field(psiFile, it) }

        return RangeResult.Failure(
            "Lines $startLine-$endLine of $filePath cover neither whole statements nor a single expression. " +
                "Align the range with full statement boundaries (lines are taken whole).",
        )
    }

    // ──────────────────────────────── Sub-expression addressing ────────────────────────────────

    class ExpressionCandidate(
        val expression: PsiExpression,
        val line: Int,
        val column: Int,
        val text: String,
    )

    sealed class ExpressionPick {
        class Ok(val expression: PsiExpression) : ExpressionPick()
        data class Failure(val message: String) : ExpressionPick()
    }

    /**
     * Every selectable sub-expression fully inside the range, in document order, outermost first at each
     * position. Mirrors the filtering of CommonJavaRefactoringUtil.collectExpressions, which backs the IDE's
     * "select expression" chooser, so the offered set matches what a user sees in IntelliJ.
     */
    @RequiresReadLock
    fun collectExpressionCandidates(
        fileRange: FileRange,
        acceptVoid: Boolean,
    ): List<ExpressionCandidate> {
        val start: PsiElement = fileRange.file.findElementAt(fileRange.startOffset) ?: return emptyList()
        val last: PsiElement = fileRange.file.findElementAt((fileRange.endOffset - 1).coerceAtLeast(0))
            ?: return emptyList()
        val root: PsiElement = PsiTreeUtil.findCommonParent(start, last) ?: return emptyList()
        return SyntaxTraverser.psiTraverser().withRoot(root).traverse()
            .filter(PsiExpression::class.java)
            .filter { expression ->
                expression.textRange.startOffset >= fileRange.startOffset &&
                    expression.textRange.endOffset <= fileRange.endOffset &&
                    expression !is PsiParenthesizedExpression &&
                    expression !is PsiSuperExpression &&
                    (acceptVoid || PsiTypes.voidType() != expression.type) &&
                    CommonJavaRefactoringUtil.isExtractable(expression)
            }
            .map { expression ->
                val offset: Int = expression.textRange.startOffset
                val line: Int = fileRange.document.getLineNumber(offset)
                ExpressionCandidate(
                    expression = expression,
                    line = line + 1,
                    column = offset - fileRange.document.getLineStartOffset(line) + 1,
                    text = expression.text,
                )
            }
            .toList()
    }

    /** Collapses newlines and runs of whitespace for display. */
    private fun normalizeExpressionText(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    fun abbreviate(text: String, max: Int = 80): String {
        val collapsed: String = normalizeExpressionText(text)
        return if (collapsed.length > max) collapsed.take(max - 3) + "..." else collapsed
    }

    /**
     * Comparison key that drops whitespace *between* tokens but preserves it inside them, so a caller need
     * not match the source's spacing (`compute(3)+1` finds `compute(3) + 1`) while string, char, and text
     * block literals still compare by their real content (`"a b"` never matches `"ab"`).
     */
    private fun expressionKey(expression: PsiElement): String =
        SyntaxTraverser.psiTraverser().withRoot(expression).traverse()
            .filter { it.firstChild == null && it !is PsiWhiteSpace && it !is PsiComment }
            .toList()
            .joinToString("") { it.text }

    @RequiresReadLock
    fun pickExpression(
        project: Project,
        context: PsiElement,
        candidates: List<ExpressionCandidate>,
        requestedText: String,
        occurrenceIndex: Int?,
        rangeDescription: String,
    ): ExpressionPick {
        if (requestedText.isBlank()) {
            return ExpressionPick.Failure("expression must not be blank.")
        }
        // Parsing the request gives it the same token stream treatment as the candidates, so spacing differs
        // freely while literal contents still have to match exactly.
        // createExpressionFromText reports malformed input either by throwing or by returning a tree with
        // error elements, so both have to be checked to name the real problem instead of "no match".
        val parsed: PsiExpression? = try {
            JavaPsiFacade.getElementFactory(project).createExpressionFromText(requestedText, context)
        } catch (_: IncorrectOperationException) {
            null
        }
        if (parsed == null || PsiTreeUtil.hasErrorElements(parsed)) {
            return ExpressionPick.Failure(
                "expression '$requestedText' is not a parseable Java expression.\n" +
                    describeCandidates(candidates),
            )
        }
        val wanted: String = expressionKey(parsed)
        val matches: List<ExpressionCandidate> =
            candidates.filter { expressionKey(it.expression) == wanted }
        if (matches.isEmpty()) {
            return ExpressionPick.Failure(
                "No selectable expression matching '$requestedText' in $rangeDescription.\n" +
                    describeCandidates(candidates),
            )
        }
        if (matches.size == 1) {
            return if (occurrenceIndex == null || occurrenceIndex == 0) {
                ExpressionPick.Ok(matches[0].expression)
            } else {
                ExpressionPick.Failure(
                    "occurrenceIndex $occurrenceIndex is out of range: '$requestedText' occurs once in " +
                        "$rangeDescription (valid index: 0).",
                )
            }
        }
        if (occurrenceIndex == null) {
            return ExpressionPick.Failure(
                "'$requestedText' matches ${matches.size} expressions in $rangeDescription; " +
                    "pass occurrenceIndex to choose one (0-based, document order):\n" +
                    matches.withIndex().joinToString("\n") { (index, candidate) ->
                        "  [$index] line ${candidate.line}, column ${candidate.column}"
                    },
            )
        }
        return matches.getOrNull(occurrenceIndex)?.let { ExpressionPick.Ok(it.expression) }
            ?: ExpressionPick.Failure(
                "occurrenceIndex $occurrenceIndex is out of range: '$requestedText' occurs ${matches.size} " +
                    "times in $rangeDescription (valid indices: 0..${matches.size - 1}).",
            )
    }

    private fun describeCandidates(candidates: List<ExpressionCandidate>): String {
        if (candidates.isEmpty()) return "No selectable expressions in that range."
        val shown: List<ExpressionCandidate> = candidates.take(MAX_LISTED_CANDIDATES)
        return buildString {
            appendLine("Selectable expressions:")
            shown.forEach { candidate ->
                val text: String = normalizeExpressionText(candidate.text).let {
                    if (it.length > 100) it.take(97) + "..." else it
                }
                appendLine("  line ${candidate.line}, column ${candidate.column}: $text")
            }
            if (candidates.size > shown.size) {
                append("  ... and ${candidates.size - shown.size} more")
            }
        }.trimEnd()
    }

    private const val MAX_LISTED_CANDIDATES = 20

    private fun fieldInRange(file: PsiJavaFile, startOffset: Int, endOffset: Int): PsiField? {
        var element: PsiElement? = file.findElementAt(startOffset)
        while (element is PsiWhiteSpace) element = element.nextSibling
        val field: PsiField = PsiTreeUtil.getParentOfType(element, PsiField::class.java, false) ?: return null
        if (field is PsiEnumConstant || field.initializer == null) return null
        if (field.textRange.startOffset < startOffset || field.textRange.endOffset > endOffset) return null
        // A range covering more than this one declaration stays a failure rather than silently refactoring
        // whichever field happened to come first.
        var next: PsiElement? = field.nextSibling
        while (next is PsiWhiteSpace || next is PsiComment) next = next.nextSibling
        if (next != null && next.textRange.startOffset < endOffset) return null
        return field
    }

    // ───────────────────────────────────── Target guards ─────────────────────────────────────

    /** Returns an error message when [element] is not a writable Java project-source target, null when it is. */
    @RequiresReadLock
    fun guardJavaSourceTarget(project: Project, element: PsiElement, displayName: String): String? {
        val containingVirtualFile: VirtualFile? = element.containingFile?.virtualFile
        if (element is PsiCompiledElement) {
            val jar: String = containingVirtualFile?.let { " inside ${VfsUtilCore.getRootFile(it).name}" } ?: ""
            return "$displayName is a compiled element$jar, not project source; this tool only operates on project source."
        }
        val file: PsiFile = element.containingFile
            ?: return "$displayName has no containing file."
        val virtualFile: VirtualFile = file.virtualFile
            ?: return "$displayName is not backed by a physical file."
        if (ProjectFileIndex.getInstance(project).isInLibrary(virtualFile)) {
            return "$displayName is a library element inside ${VfsUtilCore.getRootFile(virtualFile).name}; " +
                "this tool only operates on project source."
        }
        if (isKotlinFile(file)) return KOTLIN_REFUSAL
        if (file !is PsiJavaFile) {
            return "$displayName is not in a Java source file (language: ${file.language.displayName})."
        }
        if (!element.isWritable) return "$displayName is not writable."
        return null
    }

    fun isKotlinFile(file: PsiFile): Boolean =
        file.language.id == "kotlin" ||
            file.virtualFile?.extension in setOf("kt", "kts")

    // ───────────────────────────────────── Conflict handling ─────────────────────────────────────

    data class ConflictRef(
        val message: String,
        val file: String,
        val tag: String, // "library" (stale build jars) or "source" (real semantic conflict)
    )

    @RequiresReadLock
    fun renderConflicts(project: Project, conflicts: MultiMap<PsiElement, String>): List<ConflictRef> {
        val index: ProjectFileIndex = ProjectFileIndex.getInstance(project)
        val result: MutableList<ConflictRef> = mutableListOf()
        for (entry in conflicts.entrySet()) {
            val virtualFile: VirtualFile? = entry.key?.containingFile?.virtualFile
            val path: String = virtualFile?.let { projectRelative(project, it) } ?: "(unknown file)"
            val tag: String = if (virtualFile != null && index.isInLibrary(virtualFile)) "library" else "source"
            for (message in entry.value) {
                result.add(ConflictRef(StringUtil.removeHtmlTags(message), path, tag))
            }
        }
        return result
    }

    fun formatConflicts(conflicts: List<ConflictRef>): String = buildString {
        appendLine("Found ${conflicts.size} conflict(s):")
        for (c: ConflictRef in conflicts) {
            appendLine("  [${c.tag}] ${c.file}  ${c.message}")
        }
        appendLine()
        if (conflicts.any { it.tag == "library" }) {
            appendLine(
                "[library] conflicts usually mean stale build-output jars on the project model; rebuild or " +
                    "resync to clear them.",
            )
        }
        appendLine(
            "Re-run with ignoreConflicts=true to proceed anyway (equivalent to the Continue button in the " +
                "IDE's conflict dialog).",
        )
    }

    /**
     * Headless conflict gate for BaseRefactoringProcessor subclasses. The subclass
     * overrides isPreviewUsages(usages) to false and delegates showConflicts to
     * [onConflicts]; a false return aborts the refactoring and the captured
     * MultiMap is read back after run() to report the conflicts.
     */
    class ConflictCapture(private val ignoreConflicts: Boolean) {
        var captured: MultiMap<PsiElement, String>? = null
            private set

        fun onConflicts(conflicts: MultiMap<PsiElement, String>): Boolean {
            if (conflicts.isEmpty || ignoreConflicts) return true
            captured = conflicts
            return false
        }
    }

    // ───────────────────────────────────── Execution ─────────────────────────────────────

    /** Runs [block] on the EDT, commits and saves documents on success; returns error text on failure. */
    fun runRefactoringOnEdt(project: Project, block: () -> Unit): String? {
        var error: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                block()
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                FileDocumentManager.getInstance().saveAllDocuments()
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
        }
        // The MCP caller reads the mutated file back off disk, so the async VFS write must land first.
        if (error == null) flushVfsToDisk()
        return error
    }

    // ───────────────────────────────────── Member resolution ─────────────────────────────────────

    data class MemberSpec(
        val name: String,
        val descriptor: String? = null, // JVM method descriptor; implies a method target
    )

    sealed class MemberResolution {
        data class Resolved(
            val sourceClass: PsiClass,
            val members: List<PsiMember>,
            val memberInfos: List<MemberInfo>,
        ) : MemberResolution()

        data class Failure(val message: String) : MemberResolution()
    }

    @RequiresReadLock
    fun resolveMembers(
        project: Project,
        className: String,
        memberSpecs: List<MemberSpec>,
    ): MemberResolution {
        if (memberSpecs.isEmpty()) return MemberResolution.Failure("No members given; pass at least one member name.")
        val psiClass: PsiClass = FqcnResolver.resolveNested(project, className)
            ?: return MemberResolution.Failure(
                "Class not found: $className. ${FqcnResolver.CLASS_NOT_FOUND_HINT}",
            )
        val classDisplay: String = psiClass.qualifiedName ?: className
        guardJavaSourceTarget(project, psiClass, classDisplay)?.let { return MemberResolution.Failure(it) }

        val members: MutableList<PsiMember> = mutableListOf()
        for (spec: MemberSpec in memberSpecs) {
            ProgressManager.checkCanceled()
            val member: PsiMember = when (val r = resolveSingleMember(project, psiClass, classDisplay, spec)) {
                is SingleMemberResult.Failure -> return MemberResolution.Failure(r.message)
                is SingleMemberResult.Found -> r.member
            }
            if (member.containingClass !== psiClass) {
                return MemberResolution.Failure(
                    "'${spec.name}' is declared in ${member.containingClass?.qualifiedName ?: "an unknown class"}, " +
                        "not $classDisplay; target the declaring class.",
                )
            }
            members.add(member)
        }
        return MemberResolution.Resolved(psiClass, members.toList(), members.map { MemberInfo(it) })
    }

    private sealed class SingleMemberResult {
        data class Found(val member: PsiMember) : SingleMemberResult()
        data class Failure(val message: String) : SingleMemberResult()
    }

    @RequiresReadLock
    private fun resolveSingleMember(
        project: Project,
        psiClass: PsiClass,
        classDisplay: String,
        spec: MemberSpec,
    ): SingleMemberResult {
        if (spec.descriptor != null) {
            return when (val r = MethodResolver.resolveDetailed(
                project, classDisplay, spec.name, methodDescriptor = spec.descriptor,
            )) {
                is MethodResolver.Resolution.Error -> SingleMemberResult.Failure(r.message)
                is MethodResolver.Resolution.Found -> SingleMemberResult.Found(r.method)
            }
        }

        val methods: List<PsiMethod> = psiClass.findMethodsByName(spec.name, false).toList()
        val candidates: List<PsiMember> = buildList {
            addAll(methods)
            psiClass.findFieldByName(spec.name, false)?.let { add(it) }
            psiClass.findInnerClassByName(spec.name, false)?.let { add(it) }
        }
        return when {
            candidates.isEmpty() -> SingleMemberResult.Failure(buildString {
                append("No member named '${spec.name}' declared in $classDisplay.")
                val declared: List<String> = buildList {
                    psiClass.methods.forEach { it.name.let(::add) }
                    psiClass.fields.forEach { it.name.let(::add) }
                }.distinct()
                if (declared.isNotEmpty()) {
                    append(" Declared members: ${declared.joinToString(", ") { "'$it'" }}.")
                }
            })
            candidates.size == 1 -> SingleMemberResult.Found(candidates.single())
            methods.size > 1 && methods.size == candidates.size -> SingleMemberResult.Failure(buildString {
                append("Multiple overloads of $classDisplay#${spec.name}; pass a descriptor to disambiguate:\n")
                for (m: PsiMethod in methods) {
                    val params: String = m.parameterList.parameters.joinToString(", ") { it.type.presentableText }
                    append("  ${m.name}($params)\n")
                }
            })
            else -> SingleMemberResult.Failure(
                "'${spec.name}' matches more than one member kind in $classDisplay " +
                    "(method/field/inner class); pass a descriptor to select a method overload.",
            )
        }
    }

    // ───────────────────────────────────── Shared helpers ─────────────────────────────────────

    fun methodDisplayName(method: PsiMethod, fallbackOwner: String): String {
        val owner: String = method.containingClass?.qualifiedName ?: fallbackOwner
        val params: String = method.parameterList.parameters.joinToString(", ") { it.type.presentableText }
        return "$owner#${method.name}($params)"
    }

    fun projectRelative(project: Project, file: VirtualFile): String =
        projectRelativePath(project, file)

    private fun findVirtualFile(project: Project, filePath: String): VirtualFile? {
        val normalized: String = filePath.replace('\\', '/')
        val lfs = LocalFileSystem.getInstance()
        if (File(normalized).isAbsolute) return lfs.findFileByPath(normalized)
        val basePath: String = project.basePath ?: return null
        return lfs.findFileByPath("$basePath/$normalized")
    }
}
