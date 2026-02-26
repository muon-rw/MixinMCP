package dev.mixinmcp.cache

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.startup.ProjectActivity

/**
 * On project open: attach existing cached roots immediately.
 * The cache is populated by the Gradle plugin; no IDE-side decompilation.
 * See DESIGN.md Section 11.5 Step 5.
 */
class MixinDecompileCacheStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        LOG.info("MixinMCP: startup activity running for ${project.name}")
        val service = DecompilationCacheService.getInstance(project)

        service.refreshVfs()

        val existingRoots = service.getCachedRoots().map { it.root }

        if (existingRoots.isNotEmpty()) {
            LOG.info("MixinMCP: attaching ${existingRoots.size} decompiled source roots")
            ApplicationManager.getApplication().invokeAndWait {
                ApplicationManager.getApplication().runWriteAction {
                    AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                        project, null, emptyList(), existingRoots, "mixinmcp-decompiled",
                    )
                }
            }
            LOG.info("MixinMCP: fireAdditionalLibraryChanged completed")
        } else {
            LOG.info("MixinMCP: no cached roots found — run ./gradlew mixinDecompile in the project")
        }
    }

    companion object {
        private val LOG = Logger.getInstance(MixinDecompileCacheStartupActivity::class.java)
    }
}
