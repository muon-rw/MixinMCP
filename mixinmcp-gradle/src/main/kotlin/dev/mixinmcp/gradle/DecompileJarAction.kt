package dev.mixinmcp.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.jetbrains.java.decompiler.api.Decompiler
import org.jetbrains.java.decompiler.main.decompiler.DirectoryResultSaver
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences

interface DecompileJarParameters : WorkParameters {
    val inputJar: RegularFileProperty
    val outputDir: DirectoryProperty
    val threads: Property<Int>
}

/** Runs Vineflower on one jar inside a forked worker JVM, so its heap use cannot exhaust the Gradle daemon. */
abstract class DecompileJarAction : WorkAction<DecompileJarParameters> {
    override fun execute() {
        Decompiler.builder()
            .inputs(parameters.inputJar.get().asFile)
            .output(DirectoryResultSaver(parameters.outputDir.get().asFile))
            .option(IFernflowerPreferences.THREADS, parameters.threads.get().toString())
            .option(IFernflowerPreferences.REMOVE_SYNTHETIC, "0")
            .logger(IFernflowerLogger.NO_OP)
            .build()
            .decompile()
    }
}
