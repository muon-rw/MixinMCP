package dev.mixinmcp.tools

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Project-management tools: Gradle/Maven sync trigger. Gradle RESOLVE success
 * cascades into MixinDecompileCacheSyncListener, which schedules
 * SourceAutoAttacher so MDG merged jars flow back into Library SOURCES.
 */
class ProjectManagementToolset : McpToolset {

    @McpTool
    @McpDescription("Trigger Gradle/Maven project sync to refresh dependencies and decompilation cache. Call after changing build.gradle or pom.xml. Runs in background.")
    @Suppress("unused")
    suspend fun mixin_sync_project(
        projectPath: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.Companion.error("No project open")

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
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.Companion.error("No project open")

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
}