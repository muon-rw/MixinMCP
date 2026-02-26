package dev.mixinmcp.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Gradle plugin that registers the mixinDecompile task.
 * Decompiles dependency JARs without -sources.jar into ~/.cache/mixinmcp/decompiled/.
 * See DESIGN.md Section 11.11.
 */
class MixinDecompilePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.tasks.register("mixinDecompile", MixinDecompileTask::class.java) {
            it.group = "mixinmcp"
            it.description = "Decompiles dependency JARs without sources into ~/.cache/mixinmcp/decompiled/"
        }
    }
}
