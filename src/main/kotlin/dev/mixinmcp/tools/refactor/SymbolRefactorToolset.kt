package dev.mixinmcp.tools.refactor

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue.FALSE
import com.intellij.mcpserver.annotations.McpToolHintValue.TRUE
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.ConflictsDialogBase
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewUtil
import com.intellij.util.containers.MultiMap
import dev.mixinmcp.resolve.FqcnResolver
import dev.mixinmcp.resolve.MethodResolver
import kotlin.coroutines.coroutineContext
import dev.mixinmcp.tools.flushVfsToDisk
import dev.mixinmcp.tools.requireProject

/**
 * Rename, safe-delete, and move-file, built on IntelliJ's PSI/refactoring infrastructure so
 * cross-language references are picked up, including string references inside mixin config JSON,
 * mods.toml, and service-loader files when the relevant language plugins contribute PSI references.
 */
@Suppress("FunctionName") // @McpTool functions are snake_case by MCP convention
class SymbolRefactorToolset : McpToolset {
    override fun isExperimental(): Boolean = false

    @McpToolHints(readOnlyHint = FALSE, destructiveHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription(
        "Safely delete a class, method, or field after checking for usages across project AND dependencies. " +
            "Resolves the target by FQCN; pass methodName to narrow to a method (use parameterTypes or methodDescriptor " +
            "for overloaded methods), or fieldName to narrow to a field. With no methodName/fieldName, deletes the class itself; " +
            "for a top-level class this also deletes the containing file when no other declarations remain. " +
            "Method overrides count as blocking usages and are tagged [override] so you can see what would break. " +
            "References inside mixin config JSON, mods.toml, ServiceLoader files, etc. are picked up automatically " +
            "when the relevant IntelliJ language plugins (Minecraft Development, Forge/Fabric support) contribute PSI references. " +
            "By default refuses to delete if usages exist; pass force=true to delete anyway (will leave broken references), " +
            "or dryRun=true to only report usages without modifying anything. Modifies source files when deletion succeeds.",
    )
    @Suppress("unused")
    suspend fun mixin_safe_delete(
        className: String,
        methodName: String? = null,
        fieldName: String? = null,
        parameterTypes: List<String>? = null,
        methodDescriptor: String? = null,
        force: Boolean = false,
        dryRun: Boolean = false,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        if (methodName != null && fieldName != null) {
            return McpToolCallResult.error(
                "Pass either methodName or fieldName, not both.",
            )
        }
        if (fieldName != null && (parameterTypes != null || methodDescriptor != null)) {
            return McpToolCallResult.error(
                "parameterTypes/methodDescriptor only apply to methodName, not fieldName.",
            )
        }

        val prep: SafeDeletePreparation = when (val r = prepareSafeDelete(
            project, className, methodName, fieldName, parameterTypes, methodDescriptor,
        )) {
            is PreparationResult.Failure -> return McpToolCallResult.error(r.message)
            is PreparationResult.Ok -> r.preparation
        }

        if (dryRun || (prep.usages.isNotEmpty() && !force)) {
            return McpToolCallResult.text(formatBlocked(prep, dryRun, force))
        }

        return performDelete(project, prep, force)
    }

    @McpToolHints(readOnlyHint = FALSE, destructiveHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription(
        "Move a class to a new package, updating its package declaration and every import/reference across " +
            "the project. Resolves the source class by FQCN and moves the file containing it (Kotlin files with " +
            "multiple top-level declarations move all of them together). The destination directory under the same " +
            "source root as the source file is created if missing. References inside mixin config JSON, mods.toml, " +
            "ServiceLoader files, etc. are updated automatically when the relevant IntelliJ language plugins " +
            "(Minecraft Development, Forge/Fabric support) contribute PSI references; plain-string occurrences in " +
            "non-Java files are also rewritten. Errors instead of overwriting if a file with the same name already " +
            "exists in the target package, or if the source file is outside any source root.",
    )
    @Suppress("unused")
    suspend fun mixin_move_file(
        className: String,
        targetPackage: String,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val sanitizedTarget: String = targetPackage.trim().trim('.')
        if (sanitizedTarget.isEmpty()) {
            return McpToolCallResult.error("targetPackage must be a non-empty package name (e.g. com.example.bar).")
        }
        if (!sanitizedTarget.split('.').all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }) {
            return McpToolCallResult.error("targetPackage '$targetPackage' is not a valid Java/Kotlin package name.")
        }

        val prep: MovePreparation = when (val r = prepareMove(project, className, sanitizedTarget)) {
            is PreparationResult.Failure -> return McpToolCallResult.error(r.message)
            is PreparationResult.Ok -> r.preparation
        }

        return performMove(project, prep)
    }

    @McpToolHints(readOnlyHint = FALSE, destructiveHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription(
        "Rename a class, method, field, parameter, or local variable and update every reference project-wide, " +
            "including string references " +
            "inside mixin config JSON, mods.toml, javadoc, etc. when the relevant IntelliJ language plugins " +
            "(Minecraft Development, Forge/Fabric support) contribute PSI references. Exists because the built-in " +
            "rename_refactoring discards conflict details on failure. Resolves the target by FQCN; pass memberName " +
            "to rename a method or field (use parameterTypes or methodDescriptor for overloaded methods; pass " +
            "memberKind='method' or 'field' when the class declares both under one name); without " +
            "memberName, renames the class itself (containing file renamed to match). " +
            "memberKind='parameter' or 'local' renames a variable inside a method: memberName then addresses the " +
            "containing method and variableName names the parameter or local. A parameter of an overriding method " +
            "targets the base declaration's parameter (library base refused); several same-named locals in " +
            "different scopes are refused, listed with their lines. Renaming a method that " +
            "overrides a source super method renames the super method and every overrider together; overriding a " +
            "library method is refused. On conflicts, each is reported " +
            "with its file and tagged [library] or [source]; [library] usually means a stale build-output jar. " +
            "ignoreConflicts=true proceeds despite conflicts, same as the IDE conflict dialog's Continue button. " +
            "dryRun=true reports usages and conflicts without modifying anything.",
    )
    @Suppress("unused")
    suspend fun mixin_rename(
        className: String,
        newName: String,
        memberName: String? = null,
        memberKind: String? = null,
        variableName: String? = null,
        parameterTypes: List<String>? = null,
        methodDescriptor: String? = null,
        ignoreConflicts: Boolean = false,
        dryRun: Boolean = false,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        if (!RefactorSupport.IDENTIFIER.matches(newName)) {
            return McpToolCallResult.error("newName '$newName' is not a valid Java identifier.")
        }
        if (memberName == null && (parameterTypes != null || methodDescriptor != null)) {
            return McpToolCallResult.error(
                "parameterTypes/methodDescriptor only apply together with memberName.",
            )
        }
        if (memberKind != null && memberKind !in setOf("method", "field", "parameter", "local")) {
            return McpToolCallResult.error("memberKind must be 'method', 'field', 'parameter', or 'local'.")
        }
        if (memberKind != null && memberName == null) {
            return McpToolCallResult.error("memberKind only applies together with memberName.")
        }
        val variableKind: Boolean = memberKind == "parameter" || memberKind == "local"
        if (variableKind && variableName == null) {
            return McpToolCallResult.error(
                "memberKind='$memberKind' requires variableName; memberName addresses the containing method.",
            )
        }
        if (variableName != null && !variableKind) {
            return McpToolCallResult.error("variableName only applies with memberKind='parameter' or 'local'.")
        }

        val prep: RenamePreparation = when (val r = prepareRename(
            project, className, newName, memberName, memberKind, variableName, parameterTypes, methodDescriptor,
        )) {
            is PreparationResult.Failure -> return McpToolCallResult.error(r.message)
            is PreparationResult.Ok -> r.preparation
        }

        if (dryRun) {
            return dryRunRename(project, prep, newName)
        }
        return performRename(project, prep, newName, ignoreConflicts)
    }

    // ───────────────────────────────────── Safe delete internals ─────────────────────────────────────

    private sealed class PreparationResult<out T> {
        data class Ok<T>(val preparation: T) : PreparationResult<T>()
        data class Failure(val message: String) : PreparationResult<Nothing>()
    }

    private data class SafeDeletePreparation(
        val element: PsiNamedElement,
        val displayName: String,
        val elementKind: String,
        val targetFilePath: String,
        val deletesWholeFile: Boolean,
        val usages: List<UsageRef>,
        val sameFileUsagesSkipped: Int,
    )

    private data class UsageRef(
        val file: String,
        val line: Int,
        val column: Int,
        val snippet: String,
        val tag: String?, // e.g. "override" for OverridingMethodsSearch hits
    )

    private suspend fun prepareSafeDelete(
        project: Project,
        className: String,
        methodName: String?,
        fieldName: String?,
        parameterTypes: List<String>?,
        methodDescriptor: String?,
    ): PreparationResult<SafeDeletePreparation> {
        return smartReadAction(project) {
            val psiClass: PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@smartReadAction PreparationResult.Failure(
                    "Class not found: $className. ${FqcnResolver.CLASS_NOT_FOUND_HINT}",
                )

            val target: PsiNamedElement
            val kind: String
            val displayName: String
            val skipUsageFile: VirtualFile?

            when {
                methodName != null -> {
                    val method: PsiMethod = when (val r = resolveMethod(
                        project, className, methodName, parameterTypes, methodDescriptor,
                    )) {
                        is PreparationResult.Failure -> return@smartReadAction r
                        is PreparationResult.Ok -> r.preparation
                    }
                    target = method
                    kind = "method"
                    displayName = RefactorSupport.methodDisplayName(method, className)
                    skipUsageFile = null
                }
                fieldName != null -> {
                    val field: PsiField = psiClass.findFieldByName(fieldName, true)
                        ?: return@smartReadAction PreparationResult.Failure(
                            "No field named '$fieldName' in ${psiClass.qualifiedName ?: className}.",
                        )
                    target = field
                    kind = "field"
                    val owner = field.containingClass?.qualifiedName ?: className
                    displayName = "$owner#${field.name}"
                    skipUsageFile = null
                }
                else -> {
                    target = psiClass
                    kind = "class"
                    displayName = psiClass.qualifiedName ?: className
                    // For a top-level class, refs from inside its own file aren't blocking.
                    skipUsageFile = psiClass.containingFile?.virtualFile?.takeIf {
                        psiClass.containingClass == null
                    }
                }
            }

            val deleteTarget: PsiNamedElement = unwrapLightTarget(target)
            if (deleteTarget !== target && deleteTarget.name != target.name) {
                return@smartReadAction PreparationResult.Failure(
                    "$displayName maps to source declaration '${deleteTarget.name}' (likely a Kotlin property " +
                        "accessor); deleting it would remove the whole property. Target '${deleteTarget.name}' directly instead.",
                )
            }
            val containingFile: PsiFile? = deleteTarget.containingFile
            val targetVirtualFile: VirtualFile? = containingFile?.virtualFile
            if (targetVirtualFile != null && ProjectFileIndex.getInstance(project).isInLibrary(targetVirtualFile)) {
                return@smartReadAction PreparationResult.Failure(
                    "$displayName is a library $kind inside ${containingJarName(targetVirtualFile)}; " +
                        "this tool only operates on project source.",
                )
            }
            val targetFilePath: String = targetVirtualFile?.let {
                RefactorSupport.projectRelative(project, it)
            } ?: "(no file)"

            val deletesWholeFile: Boolean = when {
                deleteTarget is PsiClass && deleteTarget.containingClass == null && containingFile != null -> {
                    val topLevel: List<PsiElement> = containingFile.children
                        .filter { it is PsiNamedElement && it.name != null }
                    topLevel.size == 1 && topLevel.first() === deleteTarget
                }
                else -> false
            }

            val usages: MutableList<UsageRef> = mutableListOf()
            val sameFileUsagesSkipped: Int = collectUsages(project, target, skipUsageFile, usages)
            if (target is PsiMethod) {
                collectOverrides(project, target, usages)
            }

            PreparationResult.Ok(
                SafeDeletePreparation(
                    element = deleteTarget,
                    displayName = displayName,
                    elementKind = kind,
                    targetFilePath = targetFilePath,
                    deletesWholeFile = deletesWholeFile,
                    usages = usages.toList(),
                    sameFileUsagesSkipped = sameFileUsagesSkipped,
                ),
            )
        }
    }

    private fun collectUsages(
        project: Project,
        element: PsiNamedElement,
        skipFile: VirtualFile?,
        sink: MutableList<UsageRef>,
    ): Int {
        var skipped = 0
        ReferencesSearch.search(element).forEach { reference ->
            val refElement: PsiElement = reference.element
            val refFile: VirtualFile = refElement.containingFile?.virtualFile ?: return@forEach
            if (skipFile != null && refFile == skipFile) {
                skipped++
                return@forEach
            }
            sink.add(toUsageRef(project, refElement, refFile, tag = null))
        }
        return skipped
    }

    private fun collectOverrides(
        project: Project,
        method: PsiMethod,
        sink: MutableList<UsageRef>,
    ) {
        OverridingMethodsSearch.search(method).forEach { overrider ->
            val file: VirtualFile = overrider.containingFile?.virtualFile ?: return@forEach
            sink.add(toUsageRef(project, overrider, file, tag = "override"))
        }
    }

    private fun toUsageRef(
        project: Project,
        element: PsiElement,
        file: VirtualFile,
        tag: String?,
    ): UsageRef {
        val containing: PsiFile? = element.containingFile
        val document = containing?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
        val (line, col, snippet) = if (document != null) {
            val lineIdx = document.getLineNumber(element.textOffset)
            val lineStart = document.getLineStartOffset(lineIdx)
            val lineEnd = document.getLineEndOffset(lineIdx)
            Triple(
                lineIdx + 1,
                element.textOffset - lineStart + 1,
                document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd)).trim(),
            )
        } else Triple(0, 0, "")
        return UsageRef(
            file = RefactorSupport.projectRelative(project, file),
            line = line,
            column = col,
            snippet = snippet,
            tag = tag,
        )
    }

    private fun formatBlocked(prep: SafeDeletePreparation, dryRun: Boolean, force: Boolean): String =
        buildString {
            val verb: String = when {
                dryRun -> "Dry run for safe-delete of"
                prep.usages.isEmpty() -> "Would delete"
                else -> "Cannot delete"
            }
            appendLine("$verb ${prep.elementKind} ${prep.displayName}")
            appendLine("  declared in: ${prep.targetFilePath}")
            if (prep.deletesWholeFile) {
                appendLine("  note: deleting this class would remove the file entirely.")
            }
            appendLine()
            if (prep.usages.isEmpty()) {
                if (prep.sameFileUsagesSkipped > 0) {
                    val fileNote: String = if (prep.deletesWholeFile) {
                        "the whole file is deleted with it"
                    } else {
                        "other declarations remaining in that file may be left broken"
                    }
                    appendLine(
                        "No usages found outside the deleted element's own file; " +
                            "${prep.sameFileUsagesSkipped} reference(s) inside ${prep.targetFilePath} " +
                            "are not counted ($fileNote).",
                    )
                } else {
                    appendLine("No usages found across project and dependencies.")
                }
                if (dryRun) appendLine("Re-run without dryRun=true to perform the deletion.")
            } else {
                appendLine("Found ${prep.usages.size} usage(s):")
                val shown = prep.usages.take(USAGE_DISPLAY_LIMIT)
                for (u: UsageRef in shown) {
                    val tagStr: String = u.tag?.let { " [$it]" } ?: ""
                    appendLine("  ${u.file}:${u.line}:${u.column}$tagStr  ${u.snippet}")
                }
                if (prep.usages.size > USAGE_DISPLAY_LIMIT) {
                    appendLine("  ... and ${prep.usages.size - USAGE_DISPLAY_LIMIT} more.")
                }
                appendLine()
                if (!dryRun && !force) {
                    appendLine("Re-run with force=true to delete anyway (this will leave broken references).")
                }
            }
        }

    private fun performDelete(
        project: Project,
        prep: SafeDeletePreparation,
        force: Boolean,
    ): McpToolCallResult {
        var error: String? = null

        ApplicationManager.getApplication().invokeAndWait {
            try {
                WriteCommandAction.writeCommandAction(project)
                    .withName("MixinMCP Safe Delete: ${prep.displayName}")
                    .withGroupId("MixinMCP")
                    .run<Throwable> {
                        if (!prep.element.isValid) {
                            error = "Target PSI element is no longer valid (was it modified concurrently?)."
                            return@run
                        }
                        prep.element.delete()
                        PsiDocumentManager.getInstance(project).commitAllDocuments()
                        FileDocumentManager.getInstance().saveAllDocuments()
                    }
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
        }

        if (error != null) {
            return McpToolCallResult.error("Delete failed: $error")
        }
        flushVfsToDisk()

        return McpToolCallResult.text(buildString {
            appendLine("Deleted ${prep.elementKind} ${prep.displayName}")
            appendLine("  from: ${prep.targetFilePath}")
            if (prep.deletesWholeFile) appendLine("  (file removed)")
            if (force && prep.usages.isNotEmpty()) {
                appendLine()
                appendLine(
                    "Force-deleted with ${prep.usages.size} prior usage(s); those references are now broken " +
                        "and the project may not compile.",
                )
            }
        })
    }

    // ───────────────────────────────────── Move file internals ─────────────────────────────────────

    private data class MovePreparation(
        val psiFile: PsiFile,
        val targetDir: PsiDirectory,
        val sourceFqn: String,
        val sourceRelative: String,
        val targetRelative: String,
        val moveMessage: String?,
    )

    private suspend fun prepareMove(
        project: Project,
        className: String,
        targetPackage: String,
    ): PreparationResult<MovePreparation> {
        // Phase 1: read action — resolve source file and source root.
        data class SourceInfo(
            val psiClass: PsiClass,
            val psiFile: PsiFile,
            val virtualFile: VirtualFile,
            val sourceRoot: VirtualFile,
            val currentPackage: String,
        )

        val srcInfo: PreparationResult<SourceInfo> = smartReadAction(project) {
            val psiClass: PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@smartReadAction PreparationResult.Failure(
                    "Class not found: $className. ${FqcnResolver.CLASS_NOT_FOUND_HINT}",
                )
            if (psiClass.containingClass != null) {
                return@smartReadAction PreparationResult.Failure(
                    "Cannot move inner class '$className' on its own. Move its enclosing top-level class instead.",
                )
            }
            val psiFile: PsiFile = unwrapLightTarget(psiClass).containingFile
                ?: return@smartReadAction PreparationResult.Failure("Class '$className' has no containing file.")
            val virtualFile: VirtualFile = psiFile.virtualFile
                ?: return@smartReadAction PreparationResult.Failure("Class '$className' is not backed by a physical file.")

            val sourceRoot: VirtualFile = ProjectRootManager.getInstance(project)
                .fileIndex.getSourceRootForFile(virtualFile)
                ?: return@smartReadAction PreparationResult.Failure(
                    "File ${RefactorSupport.projectRelative(project, virtualFile)} is not inside any source root.",
                )

            val currentPackage: String = run {
                val rel: String? = VfsUtil.getRelativePath(virtualFile.parent, sourceRoot, '/')
                rel?.replace('/', '.') ?: ""
            }

            PreparationResult.Ok(
                SourceInfo(psiClass, psiFile, virtualFile, sourceRoot, currentPackage),
            )
        }

        val info: SourceInfo = when (srcInfo) {
            is PreparationResult.Failure -> return PreparationResult.Failure(srcInfo.message)
            is PreparationResult.Ok -> srcInfo.preparation
        }

        if (info.currentPackage == targetPackage) {
            return PreparationResult.Failure(
                "Class '$className' is already in package '$targetPackage'.",
            )
        }

        // Phase 2: EDT write action — ensure destination directory exists, check for name conflict.
        val packageRelative: String = targetPackage.replace('.', '/')
        val sourceRelative: String = RefactorSupport.projectRelative(project, info.virtualFile)
        val fileName: String = info.virtualFile.name
        var targetDir: VirtualFile? = null
        var conflict: String? = null
        var ioError: String? = null

        ApplicationManager.getApplication().invokeAndWait {
            try {
                WriteCommandAction.writeCommandAction(project)
                    .withName("MixinMCP Prepare Move Target: $targetPackage")
                    .withGroupId("MixinMCP")
                    .run<Throwable> {
                        val dir: VirtualFile = VfsUtil.createDirectoryIfMissing(info.sourceRoot, packageRelative)
                            ?: throw IllegalStateException(
                                "Could not create target directory under source root ${info.sourceRoot.path}/$packageRelative",
                            )
                        targetDir = dir
                        val existing: VirtualFile? = dir.findChild(fileName)
                        if (existing != null) {
                            conflict = "Target package '$targetPackage' already contains a file named '$fileName' " +
                                "(${RefactorSupport.projectRelative(project, existing)}). Refusing to overwrite."
                        }
                    }
            } catch (t: Throwable) {
                ioError = t.message ?: t.javaClass.simpleName
            }
        }

        if (ioError != null) return PreparationResult.Failure("Move preparation failed: $ioError")
        if (conflict != null) return PreparationResult.Failure(conflict)
        val resolvedTargetDir: VirtualFile = targetDir
            ?: return PreparationResult.Failure("Failed to resolve target directory for package '$targetPackage'.")

        // Phase 3: read action — wrap target dir as PsiDirectory and re-validate source PSI.
        val finalPrep: PreparationResult<MovePreparation> = smartReadAction(project) {
            if (!info.psiFile.isValid) {
                return@smartReadAction PreparationResult.Failure("Source file became invalid before move could start.")
            }
            val targetPsiDir: PsiDirectory = PsiManager.getInstance(project).findDirectory(resolvedTargetDir)
                ?: return@smartReadAction PreparationResult.Failure(
                    "Could not get PsiDirectory for ${resolvedTargetDir.path}.",
                )

            val topLevelClasses: List<PsiClass> = info.psiFile.children
                .filterIsInstance<PsiClass>()
            val moveMessage: String? = if (topLevelClasses.size > 1) {
                "Note: source file contains ${topLevelClasses.size} top-level classes; all of them move together."
            } else null

            PreparationResult.Ok(
                MovePreparation(
                    psiFile = info.psiFile,
                    targetDir = targetPsiDir,
                    sourceFqn = info.psiClass.qualifiedName ?: className,
                    sourceRelative = sourceRelative,
                    targetRelative = "${RefactorSupport.projectRelative(project, resolvedTargetDir)}/$fileName",
                    moveMessage = moveMessage,
                ),
            )
        }

        return finalPrep
    }

    private fun performMove(project: Project, prep: MovePreparation): McpToolCallResult {
        var error: String? = null
        var pushedConflicts: List<RefactorSupport.ConflictRef> = emptyList()

        ApplicationManager.getApplication().invokeAndWait {
            try {
                if (!prep.psiFile.isValid || !prep.targetDir.isValid) {
                    error = "Source file or target directory became invalid before the move ran."
                    return@invokeAndWait
                }
                val processor = HeadlessMoveFilesProcessor(
                    project = project,
                    elements = arrayOf<PsiElement>(prep.psiFile),
                    newParent = prep.targetDir,
                    searchForReferences = true,
                    searchInComments = false,
                    searchInNonJavaFiles = true,
                    moveCallback = null,
                    prepareSuccessfulCallback = null,
                )
                @Suppress("UsePropertyAccessSyntax") // setter is public, getter is protected
                processor.setPreviewUsages(false)
                processor.run()
                pushedConflicts = processor.pushedConflicts
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                FileDocumentManager.getInstance().saveAllDocuments()
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
        }

        if (error != null) {
            return McpToolCallResult.error("Move failed: $error")
        }
        flushVfsToDisk()

        return McpToolCallResult.text(buildString {
            appendLine("Moved ${prep.sourceFqn}")
            appendLine("  from: ${prep.sourceRelative}")
            appendLine("  to:   ${prep.targetRelative}")
            prep.moveMessage?.let { appendLine("  $it") }
            appendLine()
            if (pushedConflicts.isNotEmpty()) {
                appendLine("Move completed; proceeded despite ${pushedConflicts.size} conflict(s):")
                for (c: RefactorSupport.ConflictRef in pushedConflicts) {
                    appendLine("  [${c.tag}] ${c.file}  ${c.message}")
                }
                appendLine()
            }
            appendLine(
                "Package declaration and project-wide imports updated by IntelliJ. References inside JSON, " +
                    "TOML, ServiceLoader files etc. are updated when their language plugins contribute PSI " +
                    "references; verify with `mixin_find_references` if in doubt.",
            )
        })
    }

    // ───────────────────────────────────── Rename internals ─────────────────────────────────────

    private data class RenamePreparation(
        val element: PsiNamedElement,
        val displayName: String,
        val elementKind: String,
        val targetFilePath: String,
        val note: String? = null,
    )

    private suspend fun prepareRename(
        project: Project,
        className: String,
        newName: String,
        memberName: String?,
        memberKind: String?,
        variableName: String?,
        parameterTypes: List<String>?,
        methodDescriptor: String?,
    ): PreparationResult<RenamePreparation> {
        return smartReadAction(project) {
            val psiClass: PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@smartReadAction PreparationResult.Failure(
                    "Class not found: $className. ${FqcnResolver.CLASS_NOT_FOUND_HINT}",
                )

            val resolved: PsiNamedElement
            val kind: String
            val displayName: String
            var note: String? = null

            when {
                memberName != null && variableName != null &&
                    (memberKind == "parameter" || memberKind == "local") -> {
                    val method: PsiMethod = when (val r = resolveMethod(
                        project, className, memberName, parameterTypes, methodDescriptor,
                    )) {
                        is PreparationResult.Failure -> return@smartReadAction r
                        is PreparationResult.Ok -> r.preparation
                    }
                    val varTarget: VariableTarget = when (val r = if (memberKind == "parameter") {
                        resolveParameterTarget(project, method, variableName, className)
                    } else {
                        resolveLocalTarget(project, method, variableName, className)
                    }) {
                        is PreparationResult.Failure -> return@smartReadAction r
                        is PreparationResult.Ok -> r.preparation
                    }
                    resolved = varTarget.element
                    kind = if (memberKind == "parameter") "parameter" else "local variable"
                    displayName = varTarget.displayName
                    note = varTarget.note
                }
                memberName != null -> {
                    val isMethod: Boolean = when (memberKind) {
                        "method" -> true
                        "field" -> false
                        else -> parameterTypes != null || methodDescriptor != null ||
                            psiClass.findMethodsByName(memberName, true).isNotEmpty()
                    }
                    if (isMethod) {
                        var method: PsiMethod = when (val r = resolveMethod(
                            project, className, memberName, parameterTypes, methodDescriptor,
                        )) {
                            is PreparationResult.Failure -> return@smartReadAction r
                            is PreparationResult.Ok -> r.preparation
                        }
                        // The IDE rename flow substitutes the base method before building
                        // RenameProcessor; RenameJavaMethodProcessor.prepareRenaming only adds
                        // overriders downward, so renaming the subclass method directly would
                        // silently detach it from its super hierarchy.
                        val supers: Array<PsiMethod> = method.findDeepestSuperMethods()
                        if (supers.isNotEmpty()) {
                            if (supers.size > 1) {
                                return@smartReadAction PreparationResult.Failure(
                                    "${RefactorSupport.methodDisplayName(method, className)} overrides multiple unrelated super " +
                                        "methods; rename each base declaration directly instead.",
                                )
                            }
                            val base: PsiMethod = supers.single()
                            val baseFile: VirtualFile? = base.containingFile?.virtualFile
                            if (base is PsiCompiledElement ||
                                (baseFile != null && ProjectFileIndex.getInstance(project).isInLibrary(baseFile))
                            ) {
                                return@smartReadAction PreparationResult.Failure(
                                    "${RefactorSupport.methodDisplayName(method, className)} overrides library method " +
                                        "${RefactorSupport.methodDisplayName(base, className)}; renaming it would detach the override.",
                                )
                            }
                            note = "Requested ${RefactorSupport.methodDisplayName(method, className)}; the rename targets the " +
                                "super method it overrides, and all overriders rename together."
                            method = base
                        }
                        resolved = method
                        kind = "method"
                        displayName = RefactorSupport.methodDisplayName(method, className)
                    } else {
                        val missingKind: String = if (memberKind == "field") "field" else "method or field"
                        val field: PsiField = psiClass.findFieldByName(memberName, true)
                            ?: return@smartReadAction PreparationResult.Failure(
                                "No $missingKind named '$memberName' in ${psiClass.qualifiedName ?: className}.",
                            )
                        resolved = field
                        kind = "field"
                        displayName = "${field.containingClass?.qualifiedName ?: className}#${field.name}"
                    }
                }
                else -> {
                    resolved = psiClass
                    kind = "class"
                    displayName = psiClass.qualifiedName ?: className
                }
            }

            if (resolved.name == newName) {
                return@smartReadAction PreparationResult.Failure(
                    "$displayName is already named '$newName'.",
                )
            }

            val target: PsiNamedElement = unwrapLightTarget(resolved)
            if (target !== resolved && target.name != resolved.name) {
                return@smartReadAction PreparationResult.Failure(
                    "$displayName maps to source declaration '${target.name}' (likely a Kotlin property " +
                        "accessor); rename '${target.name}' via memberName='${target.name}' instead.",
                )
            }
            val virtualFile: VirtualFile? = target.containingFile?.virtualFile
            if (virtualFile != null && ProjectFileIndex.getInstance(project).isInLibrary(virtualFile)) {
                return@smartReadAction PreparationResult.Failure(
                    "$displayName is a library $kind inside ${containingJarName(virtualFile)}; " +
                        "this tool only operates on project source.",
                )
            }
            if (target is PsiCompiledElement) {
                return@smartReadAction PreparationResult.Failure(
                    "$displayName is a compiled element, not project source; this tool only operates on project source.",
                )
            }
            if (virtualFile == null || !target.isWritable) {
                return@smartReadAction PreparationResult.Failure(
                    "$displayName is not backed by a writable physical file.",
                )
            }

            PreparationResult.Ok(
                RenamePreparation(
                    element = target,
                    displayName = displayName,
                    elementKind = kind,
                    targetFilePath = RefactorSupport.projectRelative(project, virtualFile),
                    note = note,
                ),
            )
        }
    }

    private data class VariableTarget(
        val element: PsiNamedElement,
        val displayName: String,
        val note: String?,
    )

    private fun resolveParameterTarget(
        project: Project,
        method: PsiMethod,
        variableName: String,
        className: String,
    ): PreparationResult<VariableTarget> {
        val parameters: Array<PsiParameter> = method.parameterList.parameters
        val index: Int = parameters.indexOfFirst { it.name == variableName }
        if (index < 0) {
            val available: String = parameters.joinToString(", ") { "'${it.name}'" }.ifEmpty { "(none)" }
            return PreparationResult.Failure(
                "No parameter named '$variableName' in ${RefactorSupport.methodDisplayName(method, className)}. " +
                    "Parameters: $available.",
            )
        }
        val supers: Array<PsiMethod> = method.findDeepestSuperMethods()
        if (supers.isEmpty()) {
            return PreparationResult.Ok(
                VariableTarget(
                    element = parameters[index],
                    displayName = "'$variableName' of ${RefactorSupport.methodDisplayName(method, className)}",
                    note = null,
                ),
            )
        }
        if (supers.size > 1) {
            return PreparationResult.Failure(
                "${RefactorSupport.methodDisplayName(method, className)} overrides multiple unrelated super methods; " +
                    "rename the parameter on each base declaration directly instead.",
            )
        }
        val base: PsiMethod = supers.single()
        val baseFile: VirtualFile? = base.containingFile?.virtualFile
        if (base is PsiCompiledElement ||
            (baseFile != null && ProjectFileIndex.getInstance(project).isInLibrary(baseFile))
        ) {
            return PreparationResult.Failure(
                "${RefactorSupport.methodDisplayName(method, className)} overrides library method " +
                    "${RefactorSupport.methodDisplayName(base, className)}; its parameters cannot be renamed.",
            )
        }
        val baseParam: PsiParameter = base.parameterList.parameters.getOrNull(index)
            ?: return PreparationResult.Failure(
                "${RefactorSupport.methodDisplayName(base, className)} has no parameter at index $index matching " +
                    "'$variableName' of ${RefactorSupport.methodDisplayName(method, className)}.",
            )
        return PreparationResult.Ok(
            VariableTarget(
                element = baseParam,
                displayName = "'${baseParam.name}' of ${RefactorSupport.methodDisplayName(base, className)}",
                note = "Requested parameter '$variableName' of ${RefactorSupport.methodDisplayName(method, className)}; " +
                    "the rename targets the base declaration's parameter, same rule as method renames.",
            ),
        )
    }

    private fun resolveLocalTarget(
        project: Project,
        method: PsiMethod,
        variableName: String,
        className: String,
    ): PreparationResult<VariableTarget> {
        val nav: PsiElement = method.navigationElement
        if (nav !== method && nav !is PsiMethod) {
            return PreparationResult.Failure(
                "${RefactorSupport.methodDisplayName(method, className)}: memberKind='local' supports " +
                    "Java sources only; Kotlin targets are not yet supported.",
            )
        }
        val body: PsiCodeBlock = method.body
            ?: return PreparationResult.Failure(
                "${RefactorSupport.methodDisplayName(method, className)} has no body; no locals to rename.",
            )
        val matches: MutableList<PsiLocalVariable> = mutableListOf()
        val allLocals: LinkedHashSet<String> = LinkedHashSet()
        body.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                ProgressManager.checkCanceled()
                if (element is PsiLocalVariable) {
                    allLocals.add(element.name)
                    if (element.name == variableName) matches.add(element)
                }
                super.visitElement(element)
            }
        })
        if (matches.isEmpty()) {
            val hint: String = if (allLocals.isEmpty()) "The method declares no locals."
            else "Locals: ${allLocals.joinToString(", ") { "'$it'" }}."
            return PreparationResult.Failure(
                "No local variable named '$variableName' in " +
                    "${RefactorSupport.methodDisplayName(method, className)}. $hint",
            )
        }
        if (matches.size > 1) {
            val document = method.containingFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
            return PreparationResult.Failure(buildString {
                appendLine(
                    "Found ${matches.size} local variables named '$variableName' in " +
                        "${RefactorSupport.methodDisplayName(method, className)}, in different scopes:",
                )
                for (variable: PsiLocalVariable in matches) {
                    val line: Int = document?.let { it.getLineNumber(variable.textOffset) + 1 } ?: 0
                    appendLine("  line $line: ${variable.text.lineSequence().first().trim()}")
                }
                append("This tool cannot disambiguate them; rename via the IDE instead.")
            })
        }
        return PreparationResult.Ok(
            VariableTarget(
                element = matches.single(),
                displayName = "'$variableName' in ${RefactorSupport.methodDisplayName(method, className)}",
                note = null,
            ),
        )
    }

    private suspend fun dryRunRename(
        project: Project,
        prep: RenamePreparation,
        newName: String,
    ): McpToolCallResult {
        return smartReadAction(project) {
            if (!prep.element.isValid) {
                return@smartReadAction McpToolCallResult.error(
                    "Target PSI element is no longer valid (was it modified concurrently?).",
                )
            }
            val processor = ConflictCapturingRenameProcessor(project, prep.element, newName, ignoreConflicts = true)
            // The real run expands myAllRenames via RenameJavaMethodProcessor.prepareRenaming
            // (all overriders); mirror that here without its synchronous-progress dialog.
            if (prep.element is PsiMethod) {
                OverridingMethodsSearch.search(prep.element).forEach { overrider ->
                    if (overrider !is PsiCompiledElement) processor.addElement(overrider, newName)
                }
            }
            val usages: Array<UsageInfo> = processor.computeUsages()
            val conflicts: List<RefactorSupport.ConflictRef> = RefactorSupport.renderConflicts(project, processor.collectConflicts(usages))
            val usagesByFile: Map<String, Int> = usages
                .mapNotNull { usage -> usage.virtualFile?.let { RefactorSupport.projectRelative(project, it) } }
                .groupingBy { it }
                .eachCount()

            McpToolCallResult.text(buildString {
                appendLine("Dry run for rename of ${prep.elementKind} ${prep.displayName} to '$newName'")
                appendLine("  declared in: ${prep.targetFilePath}")
                prep.note?.let { appendLine("  note: $it") }
                appendLine()
                if (usagesByFile.isEmpty()) {
                    appendLine("No usages found across project and dependencies.")
                } else {
                    appendLine("Found ${usages.size} usage(s) in ${usagesByFile.size} file(s):")
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
                appendLine("Re-run without dryRun=true to perform the rename.")
            })
        }
    }

    private fun performRename(
        project: Project,
        prep: RenamePreparation,
        newName: String,
        ignoreConflicts: Boolean,
    ): McpToolCallResult {
        var error: String? = null
        var conflicts: List<RefactorSupport.ConflictRef>? = null

        ApplicationManager.getApplication().invokeAndWait {
            try {
                if (!prep.element.isValid) {
                    error = "Target PSI element is no longer valid (was it modified concurrently?)."
                    return@invokeAndWait
                }
                val processor = ConflictCapturingRenameProcessor(project, prep.element, newName, ignoreConflicts)
                @Suppress("UsePropertyAccessSyntax") // setter is public, getter is protected
                processor.setPreviewUsages(false)
                processor.run()
                val captured: MultiMap<PsiElement, String>? = processor.capturedConflicts
                if (captured != null) {
                    conflicts = ApplicationManager.getApplication().runReadAction<List<RefactorSupport.ConflictRef>> {
                        RefactorSupport.renderConflicts(project, captured)
                    }
                } else {
                    // BaseRefactoringProcessor.doRun has silent bail-outs (preview escalation
                    // on forced-preview or read-only usages, dumb mode, canceled progress);
                    // verify the rename actually happened instead of inferring success.
                    val renamed: Boolean = ApplicationManager.getApplication().runReadAction<Boolean> {
                        prep.element.isValid && prep.element.name == newName
                    }
                    if (!renamed) {
                        error = "The platform refactoring bailed out without applying changes " +
                            "(preview escalation from read-only or non-code usages, dumb mode, or canceled progress). " +
                            "Check the IDE for an opened usage preview; no rename was performed."
                        return@invokeAndWait
                    }
                    PsiDocumentManager.getInstance(project).commitAllDocuments()
                    FileDocumentManager.getInstance().saveAllDocuments()
                }
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
        }

        if (error != null) {
            return McpToolCallResult.error("Rename failed: $error")
        }
        conflicts?.let {
            return McpToolCallResult.error(buildString {
                appendLine("Cannot rename ${prep.elementKind} ${prep.displayName} to '$newName'.")
                append(RefactorSupport.formatConflicts(it))
            })
        }
        flushVfsToDisk()

        return McpToolCallResult.text(buildString {
            appendLine("Renamed ${prep.elementKind} ${prep.displayName} to '$newName'")
            appendLine("  declared in: ${prep.targetFilePath}")
            prep.note?.let { appendLine("  note: $it") }
            appendLine()
            appendLine(
                "Project-wide references updated by IntelliJ. References inside mixin config JSON, TOML, " +
                    "javadoc etc. are updated when their language plugins contribute PSI references; verify " +
                    "with `mixin_find_references` if in doubt.",
            )
        })
    }

    // ───────────────────────────────────── Shared helpers ─────────────────────────────────────

    /**
     * Kotlin declarations resolve to non-physical light wrappers whose delete/rename/move
     * throws IncorrectOperationException; refactoring must mutate the underlying source
     * element. Resolved without Kotlin plugin types on the classpath: navigationElement
     * of a light element is the source declaration. Compiled elements are never
     * substituted; their navigationElement points into attached library sources,
     * which must not be modified.
     */
    private fun unwrapLightTarget(element: PsiNamedElement): PsiNamedElement {
        if (element is PsiCompiledElement || element.isPhysical) return element
        val nav: PsiElement = element.navigationElement
        if (nav !== element && nav is PsiNamedElement && nav.isPhysical && nav.isWritable) return nav
        return element
    }

    private fun resolveMethod(
        project: Project,
        className: String,
        methodName: String,
        parameterTypes: List<String>?,
        methodDescriptor: String?,
    ): PreparationResult<PsiMethod> = when (
        val resolution = MethodResolver.resolveDetailed(
            project, className, methodName,
            parameterTypes = parameterTypes,
            methodDescriptor = methodDescriptor,
        )
    ) {
        is MethodResolver.Resolution.Error -> PreparationResult.Failure(resolution.message)
        is MethodResolver.Resolution.Found -> PreparationResult.Ok(resolution.method)
    }

    private fun containingJarName(file: VirtualFile): String = VfsUtilCore.getRootFile(file).name

    /**
     * MoveFilesOrDirectoriesProcessor variant that suppresses the conflict dialog so
     * the move runs headlessly. Platform-detected conflicts are pushed through, but
     * rendered here while their PSI is still valid; performMove reports them in the
     * result text.
     */
    private class HeadlessMoveFilesProcessor(
        project: Project,
        elements: Array<PsiElement>,
        newParent: PsiDirectory,
        searchForReferences: Boolean,
        searchInComments: Boolean,
        searchInNonJavaFiles: Boolean,
        moveCallback: com.intellij.refactoring.move.MoveCallback?,
        prepareSuccessfulCallback: Runnable?,
    ) : MoveFilesOrDirectoriesProcessor(
        project, elements, newParent,
        searchForReferences, searchInComments, searchInNonJavaFiles,
        moveCallback, prepareSuccessfulCallback,
    ) {
        var pushedConflicts: List<RefactorSupport.ConflictRef> = emptyList()
            private set

        override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo>?): Boolean {
            if (!conflicts.isEmpty) {
                pushedConflicts = ApplicationManager.getApplication()
                    .runReadAction<List<RefactorSupport.ConflictRef>> {
                        RefactorSupport.renderConflicts(myProject, conflicts)
                    }
            }
            return true
        }
    }

    /**
     * RenameProcessor variant that runs headlessly: no automatic-rename dialog, and the
     * conflict dialog is replaced by a stub that captures the conflicts into
     * [capturedConflicts] and aborts, or continues when ignoreConflicts. Hooking the
     * dialog rather than overriding preprocessUsages keeps the rest of the platform
     * preprocessing identical to the IDE's Continue path: per-language new-name
     * validation (RenameUtil.checkRename) and unresolvable-collision filtering
     * (RenameUtil.removeConflictUsages) still run.
     */
    private class ConflictCapturingRenameProcessor(
        project: Project,
        private val target: PsiElement,
        private val targetNewName: String,
        private val ignoreConflicts: Boolean,
    ) : RenameProcessor(project, target, targetNewName, false, false) {

        var capturedConflicts: MultiMap<PsiElement, String>? = null
            private set

        /**
         * Dry-run usage search. Mirrors RenameProcessor.findUsages via the public
         * RenameUtil entry point it delegates to, minus the AutomaticRenamerFactory
         * pass, which only feeds the automatic-renaming dialog this processor suppresses.
         * Search-in-comments and text-occurrence search are off by construction.
         */
        fun computeUsages(): Array<UsageInfo> {
            val result: MutableList<UsageInfo> = mutableListOf()
            for ((element: PsiElement, newName: String) in LinkedHashMap(myAllRenames)) {
                result += RenameUtil.findUsages(
                    element,
                    newName,
                    myRefactoringScope,
                    /* searchInStringsAndComments = */ false,
                    /* searchForTextOccurrences = */ false,
                    myAllRenames,
                )
            }
            return UsageViewUtil.removeDuplicatedUsages(result.toTypedArray())
        }

        fun collectConflicts(usages: Array<UsageInfo>): MultiMap<PsiElement, String> {
            val conflicts: MultiMap<PsiElement, String> = MultiMap()
            RenameUtil.addConflictDescriptions(usages, conflicts)
            RenamePsiElementProcessor.forElement(target)
                .findExistingNameConflicts(target, targetNewName, conflicts, myAllRenames)
            return conflicts
        }

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

        override fun showAutomaticRenamingDialog(automaticVariableRenamer: AutomaticRenamer): Boolean = false
    }

    private companion object {
        private const val USAGE_DISPLAY_LIMIT: Int = 20
    }
}
