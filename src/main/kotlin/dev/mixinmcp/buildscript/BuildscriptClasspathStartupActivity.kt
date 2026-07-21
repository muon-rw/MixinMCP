package dev.mixinmcp.buildscript

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.startup.ProjectActivity

/**
 * On project open: announce buildscript roots persisted by the previous sync
 * (GradleBuildClasspathManager restores them from the workspace model) so they
 * are indexed without waiting for a fresh sync.
 */
class BuildscriptClasspathStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val roots = readAction {
            BuildscriptClasspathRoots.collectEntries(project)
                .filterNot { it.kotlinDslOnly }
                .flatMap { listOfNotNull(it.classesRoot, it.sourcesRoot) }
        }
        if (roots.isEmpty()) return
        edtWriteAction {
            AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                project, null, emptyList(), roots, "mixinmcp-buildscript",
            )
        }
    }
}
