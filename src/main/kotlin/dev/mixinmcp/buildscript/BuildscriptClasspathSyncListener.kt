package dev.mixinmcp.buildscript

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.vfs.VirtualFile

/**
 * After Gradle sync, re-announces buildscript roots so the workspace file index
 * re-queries [BuildscriptClasspathRootsProvider]; the platform reloads
 * GradleBuildClasspathManager itself during data import.
 */
class BuildscriptClasspathSyncListener : ExternalSystemTaskNotificationListener {

    override fun onSuccess(projectPath: String, id: ExternalSystemTaskId) {
        if (id.type != ExternalSystemTaskType.RESOLVE_PROJECT) return
        val project: Project = ProjectManager.getInstance().openProjects
            .find { it.basePath == projectPath } ?: return

        // Enumeration stays on this background thread; the write action wraps only the
        // cheap fire, or every read action in the IDE stalls behind the classpath scan.
        val newRoots: List<VirtualFile> = runReadActionBlocking {
            if (project.isDisposed) return@runReadActionBlocking emptyList()
            BuildscriptClasspathRoots.collectEntries(project)
                .filterNot { it.kotlinDslOnly }
                .flatMap { listOfNotNull(it.classesRoot, it.sourcesRoot) }
        }

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            ApplicationManager.getApplication().runWriteAction {
                AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                    project, null, emptyList(), newRoots, "mixinmcp-buildscript",
                )
            }
        }
    }
}
