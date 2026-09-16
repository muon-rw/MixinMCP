package dev.mixinmcp.tools

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import dev.mixinmcp.sync.IdeSyncState
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.coroutineContext

/**
 * Every tool below reads PSI under `smartReadAction`, which waits out indexing with no deadline;
 * during a Gradle sync followed by re-indexing a call can hang for minutes with no signal to the
 * caller. This wrapper waits a bounded time for the IDE to become idle and then returns an error
 * that says what it is waiting on, so the agent can retry or block explicitly with mixin_sync_project.
 */
class IdeBusyGuardingTool(private val delegate: McpTool) : McpTool {

    override val descriptor: McpToolDescriptor
        get() = delegate.descriptor

    override suspend fun call(args: JsonObject): McpToolCallResult {
        if (descriptor.name in EXEMPT_TOOLS) return delegate.call(args)
        val project: Project = coroutineContext.softProject() ?: return delegate.call(args)
        val waitedMs: Long? = awaitIdeIdle(project, MAX_WAIT_MS)
        if (waitedMs == null) return McpToolCallResult.error(busyMessage(project, MAX_WAIT_MS))
        return delegate.call(args)
    }

    companion object {
        const val MAX_WAIT_MS: Long = 30_000
        private const val POLL_MS: Long = 250

        val EXEMPT_TOOLS: Set<String> = setOf(
            "mixin_sync_project",
            "mixin_refresh_vfs",
            "mixin_ide_status",
            "mixin_mappings_lookup",
        )

        fun isBusy(project: Project): Boolean =
            !project.isDisposed && (DumbService.isDumb(project) || IdeSyncState.getInstance(project).snapshot.busy)

        /** Milliseconds waited until the IDE was idle, or null when [maxWaitMs] elapsed first. */
        suspend fun awaitIdeIdle(project: Project, maxWaitMs: Long): Long? {
            val start: Long = System.currentTimeMillis()
            while (isBusy(project)) {
                val elapsed: Long = System.currentTimeMillis() - start
                if (elapsed >= maxWaitMs) return null
                delay(POLL_MS)
            }
            return System.currentTimeMillis() - start
        }

        fun describeBusyState(project: Project): String {
            val snapshot: IdeSyncState.Snapshot = IdeSyncState.getInstance(project).snapshot
            val parts: MutableList<String> = mutableListOf()
            if (DumbService.isDumb(project)) parts.add("indexing (dumb mode)")
            if (snapshot.resolveInFlight > 0) {
                val since: String = snapshot.resolveStartedAt?.let { " started ${(System.currentTimeMillis() - it) / 1000}s ago" } ?: ""
                parts.add("Gradle/Maven resolve in progress$since")
            }
            if (snapshot.importInFlight) parts.add("project-data import in progress")
            return if (parts.isEmpty()) "idle" else parts.joinToString(", ")
        }

        fun busyMessage(project: Project, waitedMs: Long): String =
            "IDE is busy: ${describeBusyState(project)}. Waited ${waitedMs / 1000}s; the call was not run. " +
                "Retry in a moment, call mixin_ide_status to watch it, or mixin_sync_project(wait=true) to block " +
                "until the current sync and its import finish."
    }
}
