package dev.mixinmcp.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.internal.DefaultTaskExecutionRequest
import org.gradle.jvm.JvmLibrary
import org.gradle.language.base.artifact.SourcesArtifact

/**
 * Gradle plugin that registers the genDependencySources task.
 * Decompiles dependency JARs without -sources.jar into ~/.cache/mixinmcp/decompiled/.
 * See DESIGN.md Section 11.11.
 *
 * Configuration is resolved at configuration time and passed via Provider to avoid
 * Task.project access at execution time (required for Gradle configuration cache).
 * Supports runtimeClasspath, compileClasspath for Java/Neoforge MDG projects.
 */
class MixinDecompilePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val taskProvider = project.tasks.register("genDependencySources", MixinDecompileTask::class.java) {
            it.group = "mixinmcp"
            it.description = "Decompiles dependency JARs without sources into ~/.cache/mixinmcp/decompiled/"
            it.projectDir = project.projectDir

            val config = findClasspathConfiguration(project)
            if (config != null) {
                // Use lenient resolution so that artifact transform failures (e.g.
                // ModDevGradle's RemappingTransform needing intermediateToNamed.zip
                // before it's been generated) don't crash the entire task. Failed
                // artifacts are captured via ArtifactCollection.getFailures() and
                // reported as a warning instead.
                val artifactView = config.incoming.artifactView { view ->
                    view.lenient(true)
                }
                it.artifactCollection = artifactView.artifacts

                // Query which dependencies have published sources.
                // This is done lazily via a Provider so it only resolves when the task runs.
                it.modulesWithSourcesProvider = project.provider {
                    findModulesWithSources(project, config)
                }
            }
        }

        project.tasks.register("cleanSourcesCache", CleanCacheTask::class.java) {
            it.group = "mixinmcp"
            it.description = "Deletes MixinMCP decompilation cache for this project (use --global for all projects)"
            it.projectDir = project.projectDir
        }

        // When IntelliJ is syncing the Gradle project, inject genDependencySources into the
        // task execution plan so it runs automatically as part of sync.
        // Technique borrowed from NeoForge MDG / Fabric Loom.
        if (java.lang.Boolean.getBoolean("idea.sync.active")) {
            project.afterEvaluate {
                val startParameter = project.gradle.startParameter
                val taskRequests = ArrayList(startParameter.taskRequests)
                taskRequests.add(DefaultTaskExecutionRequest(listOf(taskProvider.name)))
                startParameter.setTaskRequests(taskRequests)
            }
        }
    }

    private fun findClasspathConfiguration(project: Project): org.gradle.api.artifacts.Configuration? {
        return project.configurations.findByName("runtimeClasspath")
    }

    /**
     * Uses ArtifactResolutionQuery to find which modules on the classpath have
     * published -sources.jar artifacts. Returns a set of "group:module:version" strings.
     */
    private fun findModulesWithSources(
        project: Project,
        config: org.gradle.api.artifacts.Configuration
    ): Set<String> {
        val componentIds = config.incoming.resolutionResult.allDependencies
            .filterIsInstance<ResolvedDependencyResult>()
            .mapNotNull { it.selected.id as? ModuleComponentIdentifier }
            .toSet()

        if (componentIds.isEmpty()) return emptySet()

        val result = project.dependencies.createArtifactResolutionQuery()
            .forComponents(componentIds)
            .withArtifacts(JvmLibrary::class.java, SourcesArtifact::class.java)
            .execute()

        return result.resolvedComponents
            .filter { component ->
                component.getArtifacts(SourcesArtifact::class.java)
                    .any { it is ResolvedArtifactResult }
            }
            .mapNotNull { it.id as? ModuleComponentIdentifier }
            .map { "${it.group}:${it.module}:${it.version}" }
            .toSet()
    }
}
