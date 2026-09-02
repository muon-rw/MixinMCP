package dev.mixinmcp.buildscript

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * On project open: snapshot the buildscript roots persisted by the previous sync
 * (GradleBuildClasspathManager restores them from the workspace model) so they
 * are indexed without waiting for a fresh sync.
 */
class BuildscriptClasspathStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        BuildscriptClasspathSnapshot.getInstance(project).refresh("startup")
    }
}
