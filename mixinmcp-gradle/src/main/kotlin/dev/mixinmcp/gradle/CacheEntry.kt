package dev.mixinmcp.gradle

import com.google.gson.annotations.SerializedName

/**
 * Cache entry for a single decompiled JAR.
 * JSON field names must match the IntelliJ plugin's DecompilationManifest format.
 *
 * @param libraryName Display name of the library (e.g. "org.example:foo:1.0")
 * @param classesJarPath Absolute path to the original classes JAR
 * @param jarSize Size in bytes of the JAR at decompilation time
 * @param jarModified Last modified timestamp of the JAR at decompilation time
 * @param cachePath Absolute path to the decompiled output directory
 * @param decompilerVersion Decompiler version used (e.g. "vineflower-1.11.2")
 * @param createdAt Timestamp when this entry was created
 */
data class CacheEntry(
    @SerializedName("libraryName") val libraryName: String,
    @SerializedName("classesJarPath") val classesJarPath: String,
    @SerializedName("jarSize") val jarSize: Long,
    @SerializedName("jarModified") val jarModified: Long,
    @SerializedName("cachePath") val cachePath: String,
    @SerializedName("decompilerVersion") val decompilerVersion: String,
    @SerializedName("createdAt") val createdAt: Long,
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
