package dev.mixinmcp.tools.project

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
            ?: return McpToolCallResult.error("No project open")

        val basePath: String = project.basePath ?: return McpToolCallResult.error(
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

        return McpToolCallResult.text(
            "Project sync triggered for $externalPath. Dependencies will refresh in the background.",
        )
    }
}