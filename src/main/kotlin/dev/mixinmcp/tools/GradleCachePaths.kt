package dev.mixinmcp.tools

import java.nio.file.Files
import java.nio.file.Path

object GradleCachePaths {

    fun gradleUserHomeDir(): Path {
        val env: String? = System.getenv("GRADLE_USER_HOME")
        if (!env.isNullOrBlank()) {
            return Path.of(env)
        }
        return Path.of(System.getProperty("user.home"), ".gradle")
    }

    // Newest wins: changing versions (EAP snapshots) accumulate one hash dir per downloaded build.
    fun findNewestSourcesJarUnderVersionDir(versionDir: Path): Path? {
        if (!Files.isDirectory(versionDir)) return null
        return try {
            Files.list(versionDir).use { hashStream ->
                hashStream
                    .filter { Files.isDirectory(it) }
                    .flatMap { Files.list(it) }
                    .filter { it.fileName.toString().lowercase().endsWith("-sources.jar") }
                    .toList()
                    .maxByOrNull { path -> runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L) }
            }
        } catch (_: Exception) {
            null
        }
    }
}
