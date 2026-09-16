package dev.mixinmcp.sync

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.project.Project

class IdeSyncStateListener : ExternalSystemTaskNotificationListener {

    override fun onStart(projectPath: String, id: ExternalSystemTaskId) {
        resolveProject(id)?.let { IdeSyncState.getInstance(it).resolveStarted() }
    }

    override fun onSuccess(projectPath: String, id: ExternalSystemTaskId) {
        resolveProject(id)?.let { IdeSyncState.getInstance(it).resolveFinished(IdeSyncState.Outcome.SUCCESS, null) }
    }

    override fun onFailure(projectPath: String, id: ExternalSystemTaskId, exception: Exception) {
        resolveProject(id)?.let {
            IdeSyncState.getInstance(it).resolveFinished(IdeSyncState.Outcome.FAILURE, exception.message ?: exception.toString())
        }
    }

    override fun onCancel(projectPath: String, id: ExternalSystemTaskId) {
        resolveProject(id)?.let { IdeSyncState.getInstance(it).resolveFinished(IdeSyncState.Outcome.CANCELLED, null) }
    }

    override fun onEnd(projectPath: String, id: ExternalSystemTaskId) {
        resolveProject(id)?.let { IdeSyncState.getInstance(it).resolveEnded() }
    }

    private fun resolveProject(id: ExternalSystemTaskId): Project? {
        if (id.type != ExternalSystemTaskType.RESOLVE_PROJECT) return null
        return id.findProject()?.takeUnless { it.isDisposed }
    }
}
