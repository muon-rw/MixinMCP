package dev.mixinmcp.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property

/**
 * `mixinmcp { extraJars.from(...) }`: jars to decompile that are on no classpath,
 * recorded in adhoc-manifest.json so they survive the per-run classpath prune.
 * `mixinmcp { decompilerHeap = "6g" }`: max heap of the forked decompiler JVM.
 */
abstract class MixinMcpExtension {
    abstract val extraJars: ConfigurableFileCollection
    abstract val decompilerHeap: Property<String>
}
