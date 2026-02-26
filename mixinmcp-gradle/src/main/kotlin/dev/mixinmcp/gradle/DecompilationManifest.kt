package dev.mixinmcp.gradle

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Cache manifest for decompiled library JARs.
 * Maps artifact hash (SHA-256 of jarPath|jarSize|jarModified) to cache entry metadata.
 * JSON structure must match the IntelliJ plugin's DecompilationManifest (entries wrapper).
 * See DESIGN.md Section 11.5 Step 1.
 */
class DecompilationManifest(
    val entries: Map<String, CacheEntry> = emptyMap(),
) {
    /**
     * Load manifest from cacheRoot/manifest.json.
     * Returns empty manifest if file does not exist or is invalid.
     */
    fun load(cacheRoot: Path): DecompilationManifest {
        val manifestPath = cacheRoot.resolve(MANIFEST_FILE)
        if (!Files.exists(manifestPath)) return DecompilationManifest()
        return try {
            val content = Files.readString(manifestPath)
            val type = object : TypeToken<ManifestJson>() {}.type
            val wrapper = gson.fromJson<ManifestJson>(content, type)
            DecompilationManifest(entries = wrapper?.entries ?: emptyMap())
        } catch (_: Exception) {
            DecompilationManifest()
        }
    }

    /**
     * Save manifest to cacheRoot/manifest.json.
     */
    fun save(cacheRoot: Path) {
        Files.createDirectories(cacheRoot)
        val manifestPath = cacheRoot.resolve(MANIFEST_FILE)
        val wrapper = ManifestJson(entries)
        val content = gson.toJson(wrapper)
        Files.writeString(manifestPath, content)
    }

    private data class ManifestJson(val entries: Map<String, CacheEntry> = emptyMap())

    companion object {
        private const val MANIFEST_FILE = "manifest.json"

        private val gson = GsonBuilder().setPrettyPrinting().create()

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
