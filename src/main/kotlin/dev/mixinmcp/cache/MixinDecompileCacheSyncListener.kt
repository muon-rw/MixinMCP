package dev.mixinmcp.cache

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task

/**
 * Triggers decompilation cache refresh after Gradle/Maven project sync.
 * See DESIGN.md Section 11.5 Step 5.
 */
class MixinDecompileCacheSyncListener : ExternalSystemTaskNotificationListener {

    override fun onSuccess(projectPath: String, id: ExternalSystemTaskId) {
        if (id.type != ExternalSystemTaskType.RESOLVE_PROJECT) return

        val project = resolveProject(projectPath) ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "MixinMCP: Decompiling dependencies...",
            true,
        ) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                DecompilationCacheService.getInstance(project).refreshCache(project)
                val newRoots = DecompilationCacheService.getInstance(project)
                    .getCachedRoots()
                    .map { it.root }

                ApplicationManager.getApplication().invokeLater {
                    ApplicationManager.getApplication().runWriteAction {
                        AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                            project,
                            null,
                            emptyList(),
                            newRoots,
                            "mixinmcp-decompiled",
                        )
                    }
                }
            }
        })
    }

    private fun resolveProject(projectPath: String): Project? {
        return com.intellij.openapi.project.ProjectManager.getInstance()
            .openProjects
            .find { it.basePath == projectPath }
    }
}
