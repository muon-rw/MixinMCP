package dev.mixinmcp.tools.refactor

import com.intellij.codeInsight.CodeInsightUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.refactoring.util.classMembers.MemberInfo
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.containers.MultiMap
import dev.mixinmcp.resolve.FqcnResolver
import dev.mixinmcp.resolve.MethodResolver
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
        data class Failure(val message: String) : RangeResult()
    }

    @RequiresReadLock
    fun resolveRange(
        project: Project,
        filePath: String,
        startLine: Int,
        endLine: Int,
    ): RangeResult {
        if (startLine < 1 || endLine < startLine) {
            return RangeResult.Failure(
                "Invalid range: lines are 1-based and startLine ($startLine) must not exceed endLine ($endLine).",
            )
        }
        val virtualFile: VirtualFile = findVirtualFile(project, filePath)
            ?: return RangeResult.Failure(
                "File not found: $filePath. Pass a path absolute or relative to the project root.",
            )
        if (ProjectFileIndex.getInstance(project).isInLibrary(virtualFile)) {
            return RangeResult.Failure(
                "$filePath is inside library ${VfsUtilCore.getRootFile(virtualFile).name}; " +
                    "this tool only operates on project source.",
            )
        }
        val psiFile: PsiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return RangeResult.Failure("Could not get PSI for $filePath.")
        if (psiFile !is PsiJavaFile) {
            return if (isKotlinFile(psiFile)) {
                RangeResult.Failure(KOTLIN_REFUSAL)
            } else {
                RangeResult.Failure(
                    "$filePath is not a Java source file (language: ${psiFile.language.displayName}).",
                )
            }
        }
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return RangeResult.Failure("No document available for $filePath.")
        if (endLine > document.lineCount) {
            return RangeResult.Failure(
                "endLine $endLine is past the end of $filePath (${document.lineCount} lines).",
            )
        }
        val startOffset: Int = document.getLineStartOffset(startLine - 1)
        val endOffset: Int = document.getLineEndOffset(endLine - 1)

        val statements: Array<PsiElement> = CodeInsightUtil.findStatementsInRange(psiFile, startOffset, endOffset)
        if (statements.isNotEmpty()) return RangeResult.Statements(psiFile, statements)

        val expression: PsiExpression? = CodeInsightUtil.findExpressionInRange(psiFile, startOffset, endOffset)
        if (expression != null) return RangeResult.Expression(psiFile, expression)

        return RangeResult.Failure(
            "Lines $startLine-$endLine of $filePath cover neither whole statements nor a single expression. " +
                "Align the range with full statement boundaries (lines are taken whole).",
        )
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

    fun projectRelative(project: Project, file: VirtualFile): String {
        val basePath: String? = project.basePath
        val absolute: String = file.path
        if (basePath != null && absolute.startsWith("$basePath/")) {
            return absolute.removePrefix("$basePath/")
        }
        return absolute
    }

    private fun findVirtualFile(project: Project, filePath: String): VirtualFile? {
        val normalized: String = filePath.replace('\\', '/')
        val lfs = LocalFileSystem.getInstance()
        if (File(normalized).isAbsolute) return lfs.findFileByPath(normalized)
        val basePath: String = project.basePath ?: return null
        return lfs.findFileByPath("$basePath/$normalized")
    }
}
