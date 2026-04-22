package dev.mixinmcp.mappings

import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

object McVersionDetector {
    private val KEYS = listOf(
        "minecraft_version",
        "mc_version",
        "minecraftVersion",
        "mcVersion",
        "minecraft.version",
        "mc.version",
    )

    fun detect(project: Project): String? {
        val root = project.basePath?.let { Paths.get(it) } ?: return null
        readFrom(root)?.let { return it }
        return try {
            Files.list(root).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .filter { !it.fileName.toString().startsWith(".") }
                    .map(::readFrom)
                    .filter { it != null }
                    .findFirst()
                    .orElse(null)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readFrom(dir: Path): String? {
        val file = dir.resolve("gradle.properties")
        if (!Files.isRegularFile(file)) return null
        val props = Properties()
        Files.newInputStream(file).use(props::load)
        for (key in KEYS) {
            val v = props.getProperty(key)?.trim()
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }
}
