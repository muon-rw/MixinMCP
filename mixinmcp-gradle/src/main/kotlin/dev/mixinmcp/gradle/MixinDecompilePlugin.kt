package dev.mixinmcp.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
/**
 * Gradle plugin that registers the mixinDecompile task.
 * Decompiles dependency JARs without -sources.jar into ~/.cache/mixinmcp/decompiled/.
 * See DESIGN.md Section 11.11.
 *
 * Configuration is resolved at configuration time and passed via Provider to avoid
 * Task.project access at execution time (required for Gradle configuration cache).
 * Supports runtimeClasspath, compileClasspath for Java/Neoforge MDG projects.
 */
class MixinDecompilePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.tasks.register("mixinDecompile", MixinDecompileTask::class.java) {
            it.group = "mixinmcp"
            it.description = "Decompiles dependency JARs without sources into ~/.cache/mixinmcp/decompiled/"
            it.projectDir = project.projectDir

            // Resolve configuration at configuration time (not execution) for configuration cache compatibility
            val config = findClasspathConfiguration(project)
            if (config != null) {
                val artifactCollection = config.incoming.artifacts
                it.resolvedArtifactsProvider = artifactCollection.resolvedArtifacts
            }
        }
    }

    private fun findClasspathConfiguration(project: Project): org.gradle.api.artifacts.Configuration? {
        return project.configurations.findByName("runtimeClasspath")
    }
}
