package dev.mixinmcp.tools

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import dev.mixinmcp.resolve.FqcnResolver
import dev.mixinmcp.resolve.MethodResolver
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Project-management and refactoring tools.
 *
 * Lifecycle: Gradle/Maven sync, VFS refresh.
 *
 * Refactoring: safe-delete and move-file. Both use IntelliJ's PSI/refactoring
 * infrastructure so cross-language references are picked up — including string
 * references inside mixin config JSON, mods.toml, service-loader files, etc.,
 * provided the relevant language plugins (Minecraft Development, Forge/Fabric
 * support) are installed and contribute PSI references for those formats.
 */
class ProjectManagementToolset : McpToolset {

    @McpTool
    @McpDescription("Trigger Gradle/Maven project sync to refresh dependencies and decompilation cache. Call after changing build.gradle or pom.xml. Runs in background.")
    @Suppress("unused")
    suspend fun mixin_sync_project(
        projectPath: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val basePath: String = project.basePath ?: return McpToolCallResult.Companion.error(
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
        }

        return McpToolCallResult.Companion.text(
            "Project sync triggered for $externalPath. Dependencies will refresh in the background.",
        )
    }

    @McpTool
    @McpDescription(
        "Force-refresh IntelliJ's Virtual File System (VFS) so on-disk changes made by external tools " +
            "(Gradle, shell scripts, code generators, etc.) become visible to the IDE and to subsequent " +
            "MCP tool calls. Optional `path` scopes the refresh; if omitted, the project root is refreshed " +
            "recursively. When `path` is a file, its parent directory is refreshed so content changes, " +
            "sibling creates, and deletes are all detected in one call. When `path` no longer exists on " +
            "disk, the nearest existing ancestor is refreshed so the deletion is picked up. Returns only " +
            "after the refresh finishes.",
    )
    @Suppress("unused")
    suspend fun mixin_refresh_vfs(
        path: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val requestedPath: String = path ?: project.basePath ?: return McpToolCallResult.Companion.error(
            "Project has no base path",
        )
        val requested = File(requestedPath)

        // Walk up to the nearest entry that still exists on disk — handles the case where
        // the caller's path was just deleted externally and VFS still has a stale entry.
        var existing: File? = requested
        while (existing != null && !existing.exists()) {
            existing = existing.parentFile
        }
        val resolvedExisting = existing ?: return McpToolCallResult.Companion.error(
            "Neither $requestedPath nor any ancestor exists on disk.",
        )

        // For a file target, refresh the parent directory with reloadChildren=true: that
        // single call covers edits to the file, newly created siblings, and sibling
        // deletions, and doesn't rely on VFS already knowing about a just-created child.
        // Directory targets refresh themselves. Recurse only when the caller explicitly
        // asked for an existing directory — widening scope beyond that would surprise
        // callers passing a single file path.
        val refreshTarget: File
        val recursive: Boolean
        when {
            resolvedExisting == requested && resolvedExisting.isDirectory -> {
                refreshTarget = resolvedExisting
                recursive = true
            }
            resolvedExisting == requested && resolvedExisting.isFile -> {
                refreshTarget = resolvedExisting.parentFile ?: resolvedExisting
                recursive = false
            }
            else -> {
                refreshTarget = resolvedExisting
                recursive = false
            }
        }

        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(refreshTarget)
            ?: return McpToolCallResult.Companion.error(
                "VFS could not locate ${refreshTarget.absolutePath}.",
            )

        VfsUtil.markDirtyAndRefresh(false, recursive, true, vf)

        val scope = when {
            recursive -> "directory, recursive"
            refreshTarget == requested -> "file"
            resolvedExisting == requested -> "parent of file: ${refreshTarget.absolutePath}"
            else -> "nearest existing ancestor: ${refreshTarget.absolutePath}"
        }
        return McpToolCallResult.Companion.text(
            "VFS refresh completed for $requestedPath [$scope].",
        )
    }

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
            return McpToolCallResult.Companion.error(
                "Pass either methodName or fieldName, not both.",
            )
        }
        if (fieldName != null && (parameterTypes != null || methodDescriptor != null)) {
            return McpToolCallResult.Companion.error(
                "parameterTypes/methodDescriptor only apply to methodName, not fieldName.",
            )
        }

        val prep: SafeDeletePreparation = when (val r = prepareSafeDelete(
            project, className, methodName, fieldName, parameterTypes, methodDescriptor,
        )) {
            is PreparationResult.Failure -> return McpToolCallResult.Companion.error(r.message)
            is PreparationResult.Ok -> r.preparation
        }

        if (dryRun || (prep.usages.isNotEmpty() && !force)) {
            return McpToolCallResult.Companion.text(formatBlocked(prep, dryRun, force))
        }

        return performDelete(project, prep, force)
    }

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
            return McpToolCallResult.Companion.error("targetPackage must be a non-empty package name (e.g. com.example.bar).")
        }
        if (!sanitizedTarget.split('.').all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }) {
            return McpToolCallResult.Companion.error("targetPackage '$targetPackage' is not a valid Java/Kotlin package name.")
        }

        val prep: MovePreparation = when (val r = prepareMove(project, className, sanitizedTarget)) {
            is PreparationResult.Failure -> return McpToolCallResult.Companion.error(r.message)
            is PreparationResult.Ok -> r.preparation
        }

        return performMove(project, prep)
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
    )

    private data class UsageRef(
        val file: String,
        val line: Int,
        val column: Int,
        val snippet: String,
        val tag: String?, // e.g. "override" for OverridingMethodsSearch hits
    )

    private fun prepareSafeDelete(
        project: Project,
        className: String,
        methodName: String?,
        fieldName: String?,
        parameterTypes: List<String>?,
        methodDescriptor: String?,
    ): PreparationResult<SafeDeletePreparation> {
        return ReadAction.nonBlocking<PreparationResult<SafeDeletePreparation>> {
            val psiClass: PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@nonBlocking PreparationResult.Failure("Class not found: $className")

            val target: PsiNamedElement
            val kind: String
            val displayName: String
            val skipUsageFile: VirtualFile?

            when {
                methodName != null -> {
                    val resolution = MethodResolver.resolveDetailed(
                        project, className, methodName,
                        parameterTypes = parameterTypes,
                        methodDescriptor = methodDescriptor,
                    )
                    when (resolution) {
                        is MethodResolver.Resolution.Error ->
                            return@nonBlocking PreparationResult.Failure(resolution.message)
                        is MethodResolver.Resolution.Found -> {
                            target = resolution.method
                            kind = "method"
                            val owner = resolution.method.containingClass?.qualifiedName ?: className
                            val params = resolution.method.parameterList.parameters
                                .joinToString(", ") { it.type.presentableText }
                            displayName = "$owner#${resolution.method.name}($params)"
                            skipUsageFile = null
                        }
                    }
                }
                fieldName != null -> {
                    val field: PsiField = psiClass.findFieldByName(fieldName, true)
                        ?: return@nonBlocking PreparationResult.Failure(
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

            val containingFile: PsiFile? = target.containingFile
            val targetFilePath: String = containingFile?.virtualFile?.let {
                projectRelative(project, it)
            } ?: "(no file)"

            val deletesWholeFile: Boolean = when {
                target is PsiClass && target.containingClass == null && containingFile != null -> {
                    val topLevel: List<PsiElement> = containingFile.children
                        .filter { it is PsiNamedElement && it.name != null }
                    topLevel.size == 1 && topLevel.first() === target
                }
                else -> false
            }

            val usages: MutableList<UsageRef> = mutableListOf()
            collectUsages(project, target, skipUsageFile, usages)
            if (target is PsiMethod) {
                collectOverrides(project, target, usages)
            }

            PreparationResult.Ok(
                SafeDeletePreparation(
                    element = target,
                    displayName = displayName,
                    elementKind = kind,
                    targetFilePath = targetFilePath,
                    deletesWholeFile = deletesWholeFile,
                    usages = usages.toList(),
                ),
            )
        }.inSmartMode(project).executeSynchronously()
    }

    private fun collectUsages(
        project: Project,
        element: PsiNamedElement,
        skipFile: VirtualFile?,
        sink: MutableList<UsageRef>,
    ) {
        ReferencesSearch.search(element).forEach { reference ->
            val refElement: PsiElement = reference.element
            val refFile: VirtualFile = refElement.containingFile?.virtualFile ?: return@forEach
            if (skipFile != null && refFile == skipFile) return@forEach
            sink.add(toUsageRef(project, refElement, refFile, tag = null))
        }
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
            file = projectRelative(project, file),
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
                appendLine("No usages found across project and dependencies.")
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
            return McpToolCallResult.Companion.error("Delete failed: $error")
        }

        return McpToolCallResult.Companion.text(buildString {
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

    private fun prepareMove(
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

        val srcInfo: PreparationResult<SourceInfo> = ReadAction.nonBlocking<PreparationResult<SourceInfo>> {
            val psiClass: PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@nonBlocking PreparationResult.Failure("Class not found: $className")
            if (psiClass.containingClass != null) {
                return@nonBlocking PreparationResult.Failure(
                    "Cannot move inner class '$className' on its own. Move its enclosing top-level class instead.",
                )
            }
            val psiFile: PsiFile = psiClass.containingFile
                ?: return@nonBlocking PreparationResult.Failure("Class '$className' has no containing file.")
            val virtualFile: VirtualFile = psiFile.virtualFile
                ?: return@nonBlocking PreparationResult.Failure("Class '$className' is not backed by a physical file.")

            val sourceRoot: VirtualFile = ProjectRootManager.getInstance(project)
                .fileIndex.getSourceRootForFile(virtualFile)
                ?: return@nonBlocking PreparationResult.Failure(
                    "File ${projectRelative(project, virtualFile)} is not inside any source root.",
                )

            val currentPackage: String = run {
                val rel: String? = VfsUtil.getRelativePath(virtualFile.parent, sourceRoot, '/')
                rel?.replace('/', '.') ?: ""
            }

            PreparationResult.Ok(
                SourceInfo(psiClass, psiFile, virtualFile, sourceRoot, currentPackage),
            )
        }.inSmartMode(project).executeSynchronously()

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
        val sourceRelative: String = projectRelative(project, info.virtualFile)
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
                                "(${projectRelative(project, existing)}). Refusing to overwrite."
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
        val finalPrep: PreparationResult<MovePreparation> = ReadAction.nonBlocking<PreparationResult<MovePreparation>> {
            if (!info.psiFile.isValid) {
                return@nonBlocking PreparationResult.Failure("Source file became invalid before move could start.")
            }
            val targetPsiDir: PsiDirectory = PsiManager.getInstance(project).findDirectory(resolvedTargetDir)
                ?: return@nonBlocking PreparationResult.Failure(
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
                    targetRelative = "${projectRelative(project, resolvedTargetDir)}/$fileName",
                    moveMessage = moveMessage,
                ),
            )
        }.inSmartMode(project).executeSynchronously()

        return finalPrep
    }

    private fun performMove(project: Project, prep: MovePreparation): McpToolCallResult {
        var error: String? = null

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
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                FileDocumentManager.getInstance().saveAllDocuments()
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }
        }

        if (error != null) {
            return McpToolCallResult.Companion.error("Move failed: $error")
        }

        return McpToolCallResult.Companion.text(buildString {
            appendLine("Moved ${prep.sourceFqn}")
            appendLine("  from: ${prep.sourceRelative}")
            appendLine("  to:   ${prep.targetRelative}")
            prep.moveMessage?.let { appendLine("  $it") }
            appendLine()
            appendLine(
                "Package declaration and project-wide imports updated by IntelliJ. References inside JSON, " +
                    "TOML, ServiceLoader files etc. are updated when their language plugins contribute PSI " +
                    "references; verify with `mixin_find_references` if in doubt.",
            )
        })
    }

    // ───────────────────────────────────── Shared helpers ─────────────────────────────────────

    private fun projectRelative(project: Project, file: VirtualFile): String {
        val basePath: String? = project.basePath
        val absolute: String = file.path
        if (basePath != null && absolute.startsWith("$basePath/")) {
            return absolute.removePrefix("$basePath/")
        }
        return absolute
    }

    /**
     * MoveFilesOrDirectoriesProcessor variant that suppresses the conflict dialog so
     * the move runs headlessly. Conflicts surface via the processor's normal
     * exception path instead of a modal dialog that would deadlock the MCP call.
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
        override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo>?): Boolean {
            // Returning true means "proceed regardless"; we already surfaced any
            // blocking state via prepareMove, so any remaining conflicts are
            // ones the IDE flagged late and the agent has decided to push through.
            return true
        }
    }

    private companion object {
        private const val USAGE_DISPLAY_LIMIT: Int = 20
    }
}
