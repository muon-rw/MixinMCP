package dev.mixinmcp.sync

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tracks external-system resolve tasks and the project-data import that follows them, so tools
 * can tell "a sync is running" from "the IDE is wedged" and mixin_sync_project can await completion.
 * Fed by [IdeSyncStateListener] (resolve start and end) and the import listener subscribed below.
 */
@Service(Service.Level.PROJECT)
class IdeSyncState(project: Project) : Disposable {

    enum class Outcome { SUCCESS, FAILURE, CANCELLED }

    data class Snapshot(
        val generation: Long,
        val resolveInFlight: Int,
        val importInFlight: Boolean,
        val resolveStartedAt: Long?,
        val lastOutcome: Outcome?,
        val lastOutcomeAt: Long?,
        val lastError: String?,
        val triggerFailure: String?,
    ) {
        val busy: Boolean get() = resolveInFlight > 0 || importInFlight
    }

    private val state = MutableStateFlow(
        Snapshot(
            generation = 0,
            resolveInFlight = 0,
            importInFlight = false,
            resolveStartedAt = null,
            lastOutcome = null,
            lastOutcomeAt = null,
            lastError = null,
            triggerFailure = null,
        ),
    )

    val snapshot: Snapshot get() = state.value

    init {
        project.messageBus.connect(this).subscribe(
            ProjectDataImportListener.TOPIC,
            object : ProjectDataImportListener {
                override fun onImportStarted(projectPath: String?) {
                    state.update { it.copy(importInFlight = true) }
                }

                override fun onImportFinished(projectPath: String?) {
                    state.update { it.copy(importInFlight = false) }
                }

                override fun onImportFailed(projectPath: String?, t: Throwable) {
                    state.update { it.copy(importInFlight = false, lastError = t.message ?: it.lastError) }
                }

                override fun onFinalTasksFinished(projectPath: String?) {
                    state.update { it.copy(importInFlight = false) }
                }
            },
        )
    }

    fun resolveStarted() {
        state.update {
            it.copy(
                generation = it.generation + 1,
                resolveInFlight = it.resolveInFlight + 1,
                resolveStartedAt = System.currentTimeMillis(),
                triggerFailure = null,
            )
        }
    }

    fun resolveFinished(outcome: Outcome, error: String?) {
        state.update { it.copy(lastOutcome = outcome, lastOutcomeAt = System.currentTimeMillis(), lastError = error) }
    }

    fun resolveEnded() {
        state.update { it.copy(resolveInFlight = (it.resolveInFlight - 1).coerceAtLeast(0)) }
    }

    fun recordTriggerFailure(message: String) {
        state.update { it.copy(triggerFailure = message) }
    }

    /** Completes when a resolve newer than [generationBefore] has started, or the trigger itself failed. */
    suspend fun awaitStarted(generationBefore: Long): Snapshot =
        state.first { it.generation > generationBefore || it.triggerFailure != null }

    /**
     * Completes when no resolve is in flight and the data import that follows a successful
     * resolve has finished. The import starts a moment after the resolve ends, so a short grace
     * period waits for it to begin before concluding there is none.
     */
    suspend fun awaitIdle(): Snapshot {
        state.first { it.resolveInFlight == 0 }
        withTimeoutOrNull(IMPORT_START_GRACE_MS) { state.first { it.importInFlight } }
        return state.first { it.resolveInFlight == 0 && !it.importInFlight }
    }

    override fun dispose() {}

    companion object {
        private const val IMPORT_START_GRACE_MS: Long = 3_000

        fun getInstance(project: Project): IdeSyncState = project.service()
    }
}
