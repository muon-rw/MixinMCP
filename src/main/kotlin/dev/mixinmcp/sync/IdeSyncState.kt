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
        /** Monotonic: conflated StateFlow collectors can miss a short importInFlight=true window, never a count. */
        val importsFinished: Long = 0,
        val importsFinishedAtResolveStart: Long = 0,
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
                    state.update { it.copy(importInFlight = false, importsFinished = it.importsFinished + 1) }
                }

                override fun onImportFailed(projectPath: String?, t: Throwable) {
                    state.update {
                        it.copy(importInFlight = false, importsFinished = it.importsFinished + 1, lastError = t.message ?: it.lastError)
                    }
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
                importsFinishedAtResolveStart = it.importsFinished,
            )
        }
    }

    fun resolveFinished(outcome: Outcome, error: String?) {
        state.update { it.copy(lastOutcome = outcome, lastOutcomeAt = System.currentTimeMillis(), lastError = error) }
    }

    fun resolveEnded() {
        state.update { it.copy(resolveInFlight = (it.resolveInFlight - 1).coerceAtLeast(0)) }
    }

    /**
     * Completes when a resolve newer than [generationBefore] has started, a resolve is in flight
     * (IntelliJ drops a refresh request while one runs, so that resolve is the one to wait for).
     */
    suspend fun awaitStarted(generationBefore: Long): Snapshot =
        state.first { it.generation > generationBefore || it.resolveInFlight > 0 }

    /**
     * Completes when no resolve is in flight and, after a successful resolve, the project-data
     * import that follows it has finished. The import starts some time after the resolve ends
     * (later on a busy EDT), so we wait on the import counter rather than a fixed grace period,
     * falling back to [IMPORT_WAIT_FALLBACK_MS] in case no import is ever reported.
     */
    suspend fun awaitIdle(): Snapshot {
        val resolved: Snapshot = state.first { it.resolveInFlight == 0 }
        if (resolved.lastOutcome == Outcome.SUCCESS) {
            withTimeoutOrNull(IMPORT_WAIT_FALLBACK_MS) {
                state.first { it.importsFinished > it.importsFinishedAtResolveStart }
            }
        }
        return state.first { it.resolveInFlight == 0 && !it.importInFlight }
    }

    override fun dispose() {}

    companion object {
        private const val IMPORT_WAIT_FALLBACK_MS: Long = 30_000

        fun getInstance(project: Project): IdeSyncState = project.service()
    }
}
