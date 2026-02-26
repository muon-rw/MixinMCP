package dev.mixinmcp.cache

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Cache manifest for decompiled library JARs.
 *
 * Maps artifact hash (SHA-256 of jarPath|jarSize|jarModified) to cache entry metadata.
 * See DESIGN.md Section 11.5 Step 1.
 */
@Serializable
data class DecompilationManifest(
    val entries: Map<String, CacheEntry> = emptyMap(),
) {
    /**
     * Load manifest from cacheRoot/manifest.json.
     * Returns empty manifest if file does not exist or is invalid.
     */
    fun load(cacheRoot: Path): DecompilationManifest {
        val manifestPath = cacheRoot.resolve(MANIFEST_FILE)
        if (!Files.exists(manifestPath)) {
            LOG.info("MixinMCP: manifest not found at $manifestPath")
            return DecompilationManifest()
        }
        return try {
            val content = Files.readString(manifestPath)
            val result = json.decodeFromString<DecompilationManifest>(content)
            LOG.info("MixinMCP: loaded manifest with ${result.entries.size} entries from $manifestPath")
            result
        } catch (e: Exception) {
            LOG.warn("MixinMCP: failed to parse manifest at $manifestPath", e)
            DecompilationManifest()
        }
    }

    /**
     * Save manifest to cacheRoot/manifest.json.
     */
    fun save(cacheRoot: Path) {
        Files.createDirectories(cacheRoot)
        val manifestPath = cacheRoot.resolve(MANIFEST_FILE)
        val content = json.encodeToString(serializer(), this)
        Files.writeString(manifestPath, content)
    }

    companion object {
        private val LOG = Logger.getInstance(DecompilationManifest::class.java)
        private const val MANIFEST_FILE = "manifest.json"

        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

        /**
         * Compute artifact hash: SHA-256 of "$jarPath|$jarSize|$jarLastModified".
         * Fast to compute (no content hashing), sufficient for invalidation.
         */
        fun computeArtifactHash(jarPath: String, jarSize: Long, jarModified: Long): String {
            val input = "$jarPath|$jarSize|$jarModified"
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Cache entry for a single decompiled JAR.
 *
 * @param libraryName Display name of the library (e.g. "org.example:foo:1.0")
 * @param classesJarPath Absolute path to the original classes JAR
 * @param jarSize Size in bytes of the JAR at decompilation time
 * @param jarModified Last modified timestamp of the JAR at decompilation time
 * @param cachePath Absolute path to the decompiled output directory
 * @param decompilerVersion Decompiler version used (e.g. "vineflower-1.11.2")
 * @param createdAt Timestamp when this entry was created
 */
@Serializable
data class CacheEntry(
    val libraryName: String,
    val classesJarPath: String,
    val jarSize: Long,
    val jarModified: Long,
    val cachePath: String,
    val decompilerVersion: String,
    val createdAt: Long,
) {
    /**
     * Check if this entry is still valid for the given JAR file.
     * Valid when the JAR exists, has the same size and last-modified time.
     */
    fun isValid(jarFile: java.io.File): Boolean {
        if (!jarFile.exists()) return false
        return jarFile.length() == jarSize && jarFile.lastModified() == jarModified
    }
}
