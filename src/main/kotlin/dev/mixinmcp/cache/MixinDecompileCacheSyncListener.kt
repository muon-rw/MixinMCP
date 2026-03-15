package dev.mixinmcp.cache

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import java.nio.file.Files
import java.nio.file.Path

/**
 * After Gradle/Maven project sync: notify IntelliJ to re-read the roots provider.
 * The cache was populated by the Gradle task that ran before/during sync.
 * See DESIGN.md Section 11.5 Step 5.
 */
class MixinDecompileCacheSyncListener : ExternalSystemTaskNotificationListener {

    override fun onSuccess(projectPath: String, id: ExternalSystemTaskId) {
        if (id.type != ExternalSystemTaskType.RESOLVE_PROJECT) return
        val project = resolveProject(projectPath) ?: return

        val service = DecompilationCacheService.getInstance(project)
        service.refreshVfs()

        val newRoots = service.getCachedRoots().map { it.root }

        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().runWriteAction {
                AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                    project, null, emptyList(), newRoots, "mixinmcp-decompiled",
                )
            }
        }

        checkForUnresolvedArtifacts(project, projectPath)
    }

    private fun checkForUnresolvedArtifacts(project: Project, projectPath: String) {
        val root = Path.of(projectPath)
        val markerFiles = mutableListOf<Path>()

        fun checkDir(dir: Path) {
            val marker = dir.resolve(".gradle/mixinmcp/$UNRESOLVED_MARKER_FILE")
            if (Files.exists(marker)) markerFiles.add(marker)
        }

        checkDir(root)
        try {
            Files.list(root).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .filter { !it.fileName.toString().startsWith(".") }
                    .forEach { checkDir(it) }
            }
        } catch (_: Exception) {}

        if (markerFiles.isEmpty()) return

        val totalUnresolved = markerFiles.sumOf { path ->
            try { Files.readString(path).trim().toInt() } catch (_: Exception) { 0 }
        }
        if (totalUnresolved == 0) return

        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("MixinMCP")
                .createNotification(
                    "MixinMCP",
                    "$totalUnresolved dependency artifact(s) couldn't be resolved during sync " +
                        "(likely missing mapping data on first load). " +
                        "Run <code>./gradlew genDependencySources</code> to decompile them.",
                    NotificationType.WARNING,
                )
                .notify(project)
        }
    }

    private fun resolveProject(projectPath: String): Project? {
        return com.intellij.openapi.project.ProjectManager.getInstance()
            .openProjects
            .find { it.basePath == projectPath }
    }

    companion object {
        private const val UNRESOLVED_MARKER_FILE = "unresolved.txt"
    }
}
