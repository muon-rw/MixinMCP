package dev.mixinmcp.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.internal.DefaultTaskExecutionRequest
import org.gradle.jvm.JvmLibrary
import org.gradle.language.base.artifact.SourcesArtifact
import java.io.File

/**
 * Gradle plugin that registers the genDependencySources task.
 * Decompiles dependency JARs without `-sources.jar` and mirrors published source JARs
 * into ~/.cache/mixinmcp/decompiled/ (for IDE indexing when Gradle uses transformed jars).
 * See DESIGN.md Section 11.11.
 *
 * Configuration is resolved at configuration time and passed via Provider to avoid
 * Task.project access at execution time (required for Gradle configuration cache).
 * Resolves compileClasspath to cover all compile-visible dependency scopes
 * (implementation, api, compileOnly, compileOnlyApi). runtimeOnly deps are
 * excluded — they can't be referenced in code or targeted by mixins.
 */
class MixinDecompilePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val taskProvider = project.tasks.register("genDependencySources", MixinDecompileTask::class.java) {
            it.group = "mixinmcp"
            it.description = "Decompile jars without sources and mirror published -sources.jar into ~/.cache/mixinmcp/decompiled/"
            it.projectDir = project.projectDir

            val configs = findClasspathConfigurations(project)
            if (configs.isNotEmpty()) {
                // Use lenient resolution so that artifact transform failures (e.g.
                // ModDevGradle's RemappingTransform needing intermediateToNamed.zip
                // before it's been generated) don't crash the entire task. Failed
                // artifacts are captured via ArtifactCollection.getFailures() and
                // reported as a warning instead.
                it.artifactCollections = configs.map { config ->
                    config.incoming.artifactView { view -> view.lenient(true) }.artifacts
                }

                // Map "group:module:version" -> resolved -sources.jar file (Gradle cache).
                // Lazily resolved when the task runs (configuration-cache safe).
                it.publishedSourcesJarsProvider = project.provider {
                    findPublishedSourcesJars(project, configs)
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

    private fun findClasspathConfigurations(project: Project): List<org.gradle.api.artifacts.Configuration> {
        return listOfNotNull(
            project.configurations.findByName("compileClasspath"),
        )
    }

    /**
     * Resolves published `-sources.jar` per module across all classpath configurations.
     * IntelliJ often attaches the transformed/remapped classes JAR without linking
     * sources; we mirror these jars into the MixinMCP cache so SyntheticLibrary roots
     * see real sources.
     */
    private fun findPublishedSourcesJars(
        project: Project,
        configs: List<org.gradle.api.artifacts.Configuration>
    ): Map<String, File> {
        val componentIds = configs.flatMap { config ->
            config.incoming.resolutionResult.allDependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .mapNotNull { it.selected.id as? ModuleComponentIdentifier }
        }.toSet()

        if (componentIds.isEmpty()) return emptyMap()

        val result = project.dependencies.createArtifactResolutionQuery()
            .forComponents(componentIds)
            .withArtifacts(JvmLibrary::class.java, SourcesArtifact::class.java)
            .execute()

        return result.resolvedComponents.mapNotNull { component ->
            val id = component.id as? ModuleComponentIdentifier ?: return@mapNotNull null
            val sourcesFile = component.getArtifacts(SourcesArtifact::class.java)
                .filterIsInstance<ResolvedArtifactResult>()
                .firstOrNull()
                ?.file
                ?: return@mapNotNull null
            "${id.group}:${id.module}:${id.version}" to sourcesFile
        }.toMap()
    }
}
