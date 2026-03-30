package dev.mixinmcp.gradle

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Cache manifest for decompiled library JARs.
 * Maps content-based artifact hash (SHA-256 of JAR bytes) to cache entry metadata.
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
        private const val HASH_MEMO_FILE = "hash-memo.json"

        private val gson = GsonBuilder().setPrettyPrinting().create()

        /**
         * Compute artifact hash: SHA-256 of the JAR file's actual bytes.
         * Streaming read with an 8KB buffer — negligible memory overhead.
         */
        fun computeArtifactHash(jarFile: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            jarFile.inputStream().buffered().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * Content-hash with a fast-path memo keyed on path+size+mtime.
         * If the JAR's filesystem metadata hasn't changed since the last run,
         * the previously computed content hash is reused — avoiding a full read.
         */
        fun computeArtifactHashMemoized(jarFile: File, memo: MutableMap<String, String>): String {
            val memoKey = "${jarFile.absolutePath}|${jarFile.length()}|${jarFile.lastModified()}"
            memo[memoKey]?.let { return it }
            val hash = computeArtifactHash(jarFile)
            memo[memoKey] = hash
            return hash
        }

        fun loadHashMemo(cacheRoot: Path): MutableMap<String, String> {
            val memoPath = cacheRoot.resolve(HASH_MEMO_FILE)
            if (!Files.exists(memoPath)) return mutableMapOf()
            return try {
                val type = object : TypeToken<MutableMap<String, String>>() {}.type
                gson.fromJson<MutableMap<String, String>>(Files.readString(memoPath), type) ?: mutableMapOf()
            } catch (_: Exception) {
                mutableMapOf()
            }
        }

        fun saveHashMemo(cacheRoot: Path, memo: Map<String, String>) {
            Files.createDirectories(cacheRoot)
            Files.writeString(cacheRoot.resolve(HASH_MEMO_FILE), gson.toJson(memo))
        }
    }
}
