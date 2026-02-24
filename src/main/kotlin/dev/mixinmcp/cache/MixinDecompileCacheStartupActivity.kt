package dev.mixinmcp.cache

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.startup.ProjectActivity

/**
 * On project open: attach existing cached roots immediately, then queue
 * background refresh for any new/changed JARs.
 * See DESIGN.md Section 11.5 Step 5.
 */
class MixinDecompileCacheStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val service = DecompilationCacheService.getInstance(project)
        val existingRoots = service.getCachedRoots().map { it.root }

        if (existingRoots.isNotEmpty()) {
            ApplicationManager.getApplication().invokeAndWait {
                ApplicationManager.getApplication().runWriteAction {
                    AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                        project,
                        null,
                        emptyList(),
                        existingRoots,
                        "mixinmcp-decompiled",
                    )
                }
            }
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            service.refreshCache(project)
            val newRoots = service.getCachedRoots().map { it.root }
            ApplicationManager.getApplication().invokeLater {
                ApplicationManager.getApplication().runWriteAction {
                    AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                        project,
                        null,
                        existingRoots,
                        newRoots,
                        "mixinmcp-decompiled",
                    )
                }
            }
        }
    }
}
