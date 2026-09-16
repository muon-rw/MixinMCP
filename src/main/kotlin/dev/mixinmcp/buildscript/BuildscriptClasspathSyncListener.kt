package dev.mixinmcp.buildscript

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.project.Project

/**
 * After Gradle sync, recompute the buildscript snapshot; the platform reloads
 * GradleBuildClasspathManager itself during data import.
 */
class BuildscriptClasspathSyncListener : ExternalSystemTaskNotificationListener {

    override fun onSuccess(projectPath: String, id: ExternalSystemTaskId) {
        if (id.type != ExternalSystemTaskType.RESOLVE_PROJECT) return
        val project: Project = id.findProject()?.takeUnless { it.isDisposed } ?: return
        BuildscriptClasspathSnapshot.getInstance(project).scheduleRefresh("gradle-sync")
    }
}
