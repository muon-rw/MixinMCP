package dev.mixinmcp.gradle

import org.gradle.api.file.ConfigurableFileCollection

/**
 * `mixinmcp { extraJars.from(...) }`: jars to decompile that are on no classpath,
 * recorded in adhoc-manifest.json so they survive the per-run classpath prune.
 */
abstract class MixinMcpExtension {
    abstract val extraJars: ConfigurableFileCollection
}
