package dev.mixinmcp.tools

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue.FALSE
import com.intellij.mcpserver.annotations.McpToolHintValue.TRUE
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.openapi.application.EDT
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import dev.mixinmcp.sync.IdeSyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.coroutineContext

/** Platform id string, not `GradleConstants.SYSTEM_ID`: the Gradle plugin is an optional dependency. */
private val GRADLE_SYSTEM_ID: ProjectSystemId = ProjectSystemId("GRADLE")

private const val NO_WAIT_START_BUDGET_MS: Long = 30_000
private const val MIN_START_BUDGET_MS: Long = 5_000
private const val MAX_SYNC_TIMEOUT_MS: Long = 600_000

internal fun normalizeDiskPath(path: String): String =
    FileUtil.toSystemIndependentName(FileUtil.toCanonicalPath(path.trim())).trimEnd('/')

internal fun linkedGradleRoots(project: Project): List<String> = runCatching {
    ExternalSystemApiUtil.getSettings(project, GRADLE_SYSTEM_ID)
        .linkedProjectsSettings
        .map { normalizeDiskPath(it.externalProjectPath) }
        .sorted()
}.getOrDefault(emptyList())

/**
 * The linked Gradle root that [requested] names: an exact match, the deepest linked root
 * containing it, the sole linked root under the IDE project directory, or null.
 */
internal fun resolveGradleRoot(requested: String, linked: List<String>, basePath: String): String? {
    linked.firstOrNull { FileUtil.pathsEqual(it, requested) }?.let { return it }
    linked.filter { FileUtil.isAncestor(it, requested, false) }.maxByOrNull { it.length }?.let { return it }
    if (FileUtil.pathsEqual(requested, basePath)) {
        linked.singleOrNull { FileUtil.isAncestor(requested, it, false) }?.let { return it }
    }
    return null
}

@Suppress("FunctionName") // @McpTool functions are snake_case by MCP convention
class ProjectManagementToolset : McpToolset {

    override fun isExperimental(): Boolean = false

    @McpToolHints(readOnlyHint = FALSE, destructiveHint = FALSE, idempotentHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription(
        "Trigger a Gradle project sync (re-import) so dependency, source-root, and decompilation-cache changes reach " +
            "the IDE; call it after editing build files or running genDependencySources. projectPath: the Gradle root " +
            "to sync; defaults to the IDE project directory, accepts either separator form, and must be a linked " +
            "Gradle root or a directory inside one (the error lists the linked roots). wait (default true) blocks " +
            "until the resolve and the project-data import that follows it finish, up to timeoutMs (default 90000, " +
            "max 600000; the resolve can take several seconds to start), then reports success, failure with the error " +
            "text, cancellation, or timeout. When a sync is already running (IntelliJ auto-reloads after build-file " +
            "edits), wait=true waits for it inside the same budget and then syncs again; wait=false returns without " +
            "queueing a second one. wait=false returns as soon as the resolve has started, or after 30s if it " +
            "has not, and the sync continues in the background (poll " +
            "mixin_ide_status). Maven projects are not supported by this tool; use the IDE's Maven reload.",
    )
    @Suppress("unused")
    suspend fun mixin_sync_project(
        projectPath: String? = null,
        wait: Boolean = true,
        timeoutMs: Long = 90_000,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val basePath: String = project.basePath ?: return McpToolCallResult.error("Project has no base path")
        if (timeoutMs < 1000 || timeoutMs > MAX_SYNC_TIMEOUT_MS) {
            return McpToolCallResult.error("timeoutMs must be between 1000 and $MAX_SYNC_TIMEOUT_MS (got $timeoutMs)")
        }

        val requested: String = normalizeDiskPath(projectPath ?: basePath)
        val linked: List<String> = linkedGradleRoots(project)
        val gradleRoot: String = resolveGradleRoot(requested, linked, normalizeDiskPath(basePath))
            ?: return McpToolCallResult.error(
                buildString {
                    append("No linked Gradle project matches `$requested`. ")
                    if (linked.isEmpty()) {
                        append("This IDE project has no linked Gradle root")
                        if (File(basePath, "pom.xml").isFile) {
                            append("; it looks like a Maven project, which this tool cannot sync (use the IDE's Maven reload)")
                        }
                        append(".")
                    } else {
                        append("Linked Gradle roots: ${linked.joinToString(", ")}. Pass one of them as projectPath.")
                    }
                },
            )

        val syncState: IdeSyncState = IdeSyncState.getInstance(project)
        val requestedAt: Long = System.currentTimeMillis()

        // IntelliJ drops a refresh request for a project whose resolve is already running (an
        // auto-reload after a build-file edit, or an earlier call), so no new generation would
        // ever start. Wait that resolve out, then sync again so the import sees current build files.
        if (syncState.snapshot.resolveInFlight > 0) {
            if (!wait) {
                return McpToolCallResult.text(
                    "A sync is already running for this project (${IdeBusyGuardingTool.describeBusyState(project)}); " +
                        "IntelliJ ignores new sync requests until it ends, so none was queued. Poll mixin_ide_status, " +
                        "or call mixin_sync_project with wait=true to wait for it and then sync again.",
                )
            }
            withTimeoutOrNull(timeoutMs) { syncState.awaitIdle() } ?: return McpToolCallResult.error(
                "An earlier sync is still running after ${timeoutMs / 1000}s (${IdeBusyGuardingTool.describeBusyState(project)}), " +
                    "so no new sync was requested for $gradleRoot. Poll mixin_ide_status or call again with a larger timeoutMs.",
            )
        }
        val budgetLeftMs: Long = timeoutMs - (System.currentTimeMillis() - requestedAt)
        if (budgetLeftMs < MIN_START_BUDGET_MS) {
            return McpToolCallResult.error(
                "Waiting for the earlier sync used the ${timeoutMs / 1000}s budget, so no new sync was requested for " +
                    "$gradleRoot. Call again (the IDE is idle now) or pass a larger timeoutMs.",
            )
        }

        // External System refresh must run on EDT; the resolve itself runs in the background.
        // The generation is read on the EDT right before the refresh, so a resolve that started
        // after the wait above is either counted in it or adopted by awaitStarted.
        val generationBefore: Long = try {
            withContext(Dispatchers.EDT) {
                val generation: Long = syncState.snapshot.generation
                if (!project.isDisposed && syncState.snapshot.resolveInFlight == 0) {
                    FileDocumentManager.getInstance().saveAllDocuments()
                    val spec = ImportSpecBuilder(project, GRADLE_SYSTEM_ID).use(ProgressExecutionMode.START_IN_FOREGROUND_ASYNC)
                    ExternalSystemUtil.refreshProject(gradleRoot, spec.build())
                }
                generation
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return McpToolCallResult.error("Sync could not be started for $gradleRoot: ${e.message ?: e}")
        }

        val startBudgetMs: Long = (timeoutMs - (System.currentTimeMillis() - requestedAt)).coerceAtLeast(1).let {
            if (wait) it else minOf(it, NO_WAIT_START_BUDGET_MS)
        }
        val started: IdeSyncState.Snapshot = withTimeoutOrNull(startBudgetMs) { syncState.awaitStarted(generationBefore) }
            ?: return if (wait) {
                McpToolCallResult.error(
                    "Sync requested for $gradleRoot but no Gradle resolve started within ${startBudgetMs / 1000}s. " +
                        "The IDE may have refused the import (untrusted project, Gradle plugin disabled) or queued it " +
                        "behind another sync; check mixin_ide_status and the Build tool window.",
                )
            } else {
                McpToolCallResult.text(
                    "Sync requested for $gradleRoot; the resolve had not started after ${startBudgetMs / 1000}s, so it " +
                        "is queued or was refused. Tools called now may still see the old project model. Poll " +
                        "mixin_ide_status, or call mixin_sync_project with wait=true to block until it finishes.",
                )
            }
        if (!wait) {
            return McpToolCallResult.text(
                "Sync started for $gradleRoot (generation ${started.generation}); it continues in the background. " +
                    "Poll mixin_ide_status, or call mixin_sync_project again with wait=true to block until it finishes.",
            )
        }

        val remainingMs: Long = (timeoutMs - (System.currentTimeMillis() - requestedAt)).coerceAtLeast(1)
        val finished: IdeSyncState.Snapshot? = withTimeoutOrNull(remainingMs) { syncState.awaitIdle() }
        val current: IdeSyncState.Snapshot = finished ?: syncState.snapshot
        val elapsedS: Long = current.resolveStartedAt?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0
        if (finished == null) {
            return McpToolCallResult.error(
                "Sync for $gradleRoot is still running after ${timeoutMs / 1000}s (${IdeBusyGuardingTool.describeBusyState(project)}). " +
                    "It continues in the background; poll mixin_ide_status or call again with a larger timeoutMs.",
            )
        }
        return when (current.lastOutcome) {
            IdeSyncState.Outcome.SUCCESS -> McpToolCallResult.text(
                "Sync finished for $gradleRoot in ${elapsedS}s: resolve succeeded and the project-data import completed. " +
                    "Source roots, dependencies, and the decompiled cache are current; indexing may still be running " +
                    "(other tools wait for it).",
            )
            IdeSyncState.Outcome.FAILURE -> McpToolCallResult.error(
                "Sync FAILED for $gradleRoot after ${elapsedS}s: ${current.lastError ?: "no error text"}. " +
                    "The IDE model was not updated; fix the build script and retry.",
            )
            IdeSyncState.Outcome.CANCELLED -> McpToolCallResult.error("Sync for $gradleRoot was cancelled after ${elapsedS}s.")
            null -> McpToolCallResult.text("Sync for $gradleRoot ended after ${elapsedS}s without a recorded outcome.")
        }
    }

    @McpToolHints(readOnlyHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription(
        "Reports whether the IDE can answer classpath questions right now: dumb mode (indexing), a Gradle resolve or " +
            "project-data import in flight and how long ago it started, the last sync outcome with its error text, and " +
            "the linked Gradle roots that mixin_sync_project accepts as projectPath. Other mixin_* tools wait up to " +
            "30s for a busy IDE and then return an error asking you to retry; call this to see what they are waiting on.",
    )
    @Suppress("unused")
    suspend fun mixin_ide_status(): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }
        val snapshot: IdeSyncState.Snapshot = IdeSyncState.getInstance(project).snapshot
        val now: Long = System.currentTimeMillis()
        fun ago(epochMs: Long?): String = epochMs?.let { "${(now - it) / 1000}s ago" } ?: "never"
        val text: String = buildString {
            appendLine("=== IDE status: ${project.name} ===")
            appendLine("Project directory: ${project.basePath}")
            appendLine("Busy: ${IdeBusyGuardingTool.describeBusyState(project)}")
            appendLine("Indexing (dumb mode): ${DumbService.isDumb(project)}")
            appendLine("Resolve tasks in flight: ${snapshot.resolveInFlight}; last resolve started ${ago(snapshot.resolveStartedAt)}")
            appendLine("Project-data import in flight: ${snapshot.importInFlight}")
            appendLine("Last sync outcome: ${snapshot.lastOutcome ?: "none recorded since the IDE opened"} (${ago(snapshot.lastOutcomeAt)})")
            snapshot.lastError?.let { appendLine("Last sync error: $it") }
            val linked: List<String> = linkedGradleRoots(project)
            appendLine("Linked Gradle roots (${linked.size}): ${if (linked.isEmpty()) "(none)" else linked.joinToString(", ")}")
        }
        return McpToolCallResult.text(text)
    }

    @McpToolHints(readOnlyHint = FALSE, destructiveHint = FALSE, idempotentHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription(
        "Force-refresh IntelliJ's Virtual File System (VFS) so on-disk changes made by external tools " +
            "(Gradle, shell scripts, code generators, etc.) become visible to the IDE and to subsequent " +
            "MCP tool calls. Optional `filePath` (absolute, or relative to the project directory) scopes the " +
            "refresh; if omitted, the project root is refreshed " +
            "recursively. When `filePath` is a file, its parent directory is refreshed so content changes, " +
            "sibling creates, and deletes are all detected in one call. When `filePath` no longer exists on " +
            "disk, the nearest existing ancestor is refreshed so the deletion is picked up. Returns only " +
            "after the refresh finishes. (`path` is accepted as an alias of `filePath`.)",
    )
    @Suppress("unused")
    suspend fun mixin_refresh_vfs(
        filePath: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val requestedPath: String = filePath?.let { resolveAgainstBase(project.basePath, it) }
            ?: project.basePath
            ?: return McpToolCallResult.error("Project has no base path")
        val requested = File(requestedPath)

        // Walk up to the nearest entry that still exists on disk — handles the case where
        // the caller's path was just deleted externally and VFS still has a stale entry.
        var existing: File? = requested
        while (existing != null && !existing.exists()) {
            existing = existing.parentFile
        }
        val resolvedExisting = existing ?: return McpToolCallResult.error(
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
            FileUtil.filesEqual(resolvedExisting, requested) && resolvedExisting.isDirectory -> {
                refreshTarget = resolvedExisting
                recursive = true
            }
            FileUtil.filesEqual(resolvedExisting, requested) && resolvedExisting.isFile -> {
                refreshTarget = resolvedExisting.parentFile ?: resolvedExisting
                recursive = false
            }
            else -> {
                refreshTarget = resolvedExisting
                recursive = false
            }
        }

        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(refreshTarget)
            ?: return McpToolCallResult.error(
                "VFS could not locate ${refreshTarget.absolutePath}.",
            )

        VfsUtil.markDirtyAndRefresh(false, recursive, true, vf)

        val scope = when {
            recursive -> "directory, recursive"
            FileUtil.filesEqual(refreshTarget, requested) -> "file"
            FileUtil.filesEqual(resolvedExisting, requested) -> "parent of file: ${refreshTarget.absolutePath}"
            else -> "nearest existing ancestor: ${refreshTarget.absolutePath}"
        }
        return McpToolCallResult.text(
            "VFS refresh completed for $requestedPath [$scope].",
        )
    }
}
