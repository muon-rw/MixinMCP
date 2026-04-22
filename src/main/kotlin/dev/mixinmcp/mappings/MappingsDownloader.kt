package dev.mixinmcp.mappings

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * Downloads Minecraft mapping artifacts into ~/.cache/mixinmcp/mappings/.
 * Idempotent: re-downloads only if a cached file is missing or empty.
 */
object MappingsDownloader {
    private val LOG = Logger.getInstance(MappingsDownloader::class.java)
    private val JSON = Json { ignoreUnknownKeys = true }
    private const val USER_AGENT = "MixinMCP (+https://github.com/mixinmcp)"
    private const val LAUNCHER_MANIFEST_URL =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private const val LAUNCHER_MANIFEST_TTL_MS = 24L * 60L * 60L * 1000L

    val cacheRoot: Path
        get() = Paths.get(System.getProperty("user.home"), ".cache", "mixinmcp", "mappings")

    fun versionDir(mcVersion: String): Path = cacheRoot.resolve(mcVersion)

    suspend fun getMojmap(mcVersion: String): Path = withContext(Dispatchers.IO) {
        val dst = versionDir(mcVersion).resolve("mojmap.txt")
        if (fileExistsNonEmpty(dst)) return@withContext dst

        val mappingsUrl = when (val r = resolveMojmapUrl(mcVersion)) {
            is MojmapResolution.Url -> r.url
            MojmapResolution.VersionNotListed -> throw IOException(
                "Minecraft version '$mcVersion' is not in Mojang's launcher manifest " +
                    "($LAUNCHER_MANIFEST_URL). Check spelling — snapshot IDs look like '24w14a', " +
                    "release IDs look like '1.20.1'.",
            )
            MojmapResolution.NoClientMappings -> throw IOException(
                "Mojang mappings are not published for Minecraft $mcVersion " +
                    "(no downloads.client_mappings entry in the version JSON).",
            )
        }
        downloadTo(mappingsUrl, dst)
        dst
    }

    suspend fun getIntermediary(mcVersion: String): Path = withContext(Dispatchers.IO) {
        val dst = versionDir(mcVersion).resolve("intermediary.tiny")
        if (fileExistsNonEmpty(dst)) return@withContext dst

        val url =
            "https://maven.fabricmc.net/net/fabricmc/intermediary/$mcVersion/intermediary-$mcVersion-v2.jar"
        val tempJar = Files.createTempFile("mixinmcp-intermediary-", ".jar")
        try {
            val status = tryDownload(url, tempJar)
            when {
                status == 404 -> throw IOException(
                    "Fabric Intermediary is not published for Minecraft '$mcVersion'. " +
                        "Intermediary covers only versions Fabric supports (approximately 1.14+, " +
                        "plus some snapshots). Confirm the version is a real MC release and " +
                        "that Fabric supports it. Tried: $url",
                )
                status !in 200..299 -> throw IOException(
                    "Failed to fetch Intermediary: HTTP $status ($url)",
                )
            }
            extractZipEntry(tempJar, "mappings/mappings.tiny", dst)
        } finally {
            Files.deleteIfExists(tempJar)
        }
        dst
    }

    suspend fun getYarn(mcVersion: String): Path = withContext(Dispatchers.IO) {
        val dst = versionDir(mcVersion).resolve("yarn.tiny")
        if (fileExistsNonEmpty(dst)) return@withContext dst

        val builds = httpGetJsonAs<List<YarnBuildEntry>>(
            "https://meta.fabricmc.net/v2/versions/yarn/$mcVersion",
        )
        val build = builds.maxByOrNull { it.build }
            ?: throw IOException(
                "No Yarn builds published for MC $mcVersion " +
                    "(https://meta.fabricmc.net/v2/versions/yarn/$mcVersion returned empty).",
            )
        val mavenPath = mavenCoordToPath(build.maven)
        val v2Url = "https://maven.fabricmc.net/$mavenPath-v2.jar"
        val v1Url = "https://maven.fabricmc.net/$mavenPath.jar"

        val tempJar = Files.createTempFile("mixinmcp-yarn-", ".jar")
        try {
            val status = tryDownload(v2Url, tempJar)
            if (status == 404) {
                Files.deleteIfExists(tempJar)
                downloadTo(v1Url, tempJar)
            } else if (status !in 200..299) {
                throw IOException("Failed to fetch Yarn artifact: HTTP $status ($v2Url)")
            }
            extractZipEntry(tempJar, "mappings/mappings.tiny", dst)
        } finally {
            Files.deleteIfExists(tempJar)
        }
        LOG.info("MixinMCP: Yarn build ${build.version} cached at $dst")
        dst
    }

    suspend fun getSrg(mcVersion: String): Path = withContext(Dispatchers.IO) {
        val dst = versionDir(mcVersion).resolve("srg.tsrg")
        if (fileExistsNonEmpty(dst)) return@withContext dst

        val forgeUrl =
            "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_config/$mcVersion/mcp_config-$mcVersion.zip"
        val tempZip = Files.createTempFile("mixinmcp-srg-", ".zip")
        try {
            var status = tryDownload(forgeUrl, tempZip)
            if (status == 404) {
                Files.deleteIfExists(tempZip)
                val neoformVersion = resolveLatestNeoformVersion(mcVersion)
                    ?: throw IOException(
                        "SRG mappings not found for $mcVersion. " +
                            "Tried: $forgeUrl and latest net.neoforged:neoform:$mcVersion-*. " +
                            "Forge/NeoForge may not have published SRG for this version yet.",
                    )
                val neoformUrl =
                    "https://maven.neoforged.net/releases/net/neoforged/neoform/" +
                        "$neoformVersion/neoform-$neoformVersion.zip"
                status = tryDownload(neoformUrl, tempZip)
                if (status !in 200..299) {
                    throw IOException("Failed to fetch NeoForm: HTTP $status ($neoformUrl)")
                }
            } else if (status !in 200..299) {
                throw IOException("Failed to fetch mcp_config: HTTP $status ($forgeUrl)")
            }
            extractZipEntry(tempZip, "config/joined.tsrg", dst)
        } finally {
            Files.deleteIfExists(tempZip)
        }
        dst
    }

    private sealed class MojmapResolution {
        data class Url(val url: String) : MojmapResolution()
        object VersionNotListed : MojmapResolution()
        object NoClientMappings : MojmapResolution()
    }

    private suspend fun resolveMojmapUrl(mcVersion: String): MojmapResolution {
        val manifestBytes = readLauncherManifest()
        val manifest = JSON.decodeFromString(VersionManifestV2.serializer(), String(manifestBytes))
        val entry = manifest.versions.firstOrNull { it.id == mcVersion }
            ?: return MojmapResolution.VersionNotListed
        val versionJsonBytes = httpGet(entry.url)
        val versionJson =
            JSON.decodeFromString(VersionJson.serializer(), String(versionJsonBytes))
        val url = versionJson.downloads.client_mappings?.url
            ?: return MojmapResolution.NoClientMappings
        return MojmapResolution.Url(url)
    }

    private fun readLauncherManifest(): ByteArray {
        val path = cacheRoot.resolve("launcher-manifest.json")
        if (fileExistsNonEmpty(path)) {
            val age = System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis()
            if (age < LAUNCHER_MANIFEST_TTL_MS) {
                return Files.readAllBytes(path)
            }
        }
        val bytes = httpGet(LAUNCHER_MANIFEST_URL)
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
        return bytes
    }

    private fun resolveLatestNeoformVersion(mcVersion: String): String? {
        val metaUrl =
            "https://maven.neoforged.net/releases/net/neoforged/neoform/maven-metadata.xml"
        val xml = try {
            String(httpGet(metaUrl))
        } catch (_: IOException) {
            return null
        }
        val regex = Regex("<version>([^<]+)</version>")
        val prefix = "$mcVersion-"
        return regex.findAll(xml)
            .map { it.groupValues[1] }
            .filter { it.startsWith(prefix) }
            .sortedDescending()
            .firstOrNull()
    }

    private fun mavenCoordToPath(coord: String): String {
        val parts = coord.split(":")
        require(parts.size == 3) { "Bad maven coord: $coord" }
        val groupPath = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        return "$groupPath/$artifact/$version/$artifact-$version"
    }

    private fun httpGet(url: String): ByteArray {
        val conn = openConnection(url)
        try {
            val status = conn.responseCode
            if (status !in 200..299) {
                throw IOException("HTTP $status for $url")
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private inline fun <reified T> httpGetJsonAs(url: String): T {
        val bytes = httpGet(url)
        return JSON.decodeFromString(String(bytes))
    }

    private fun downloadTo(url: String, dst: Path) {
        val status = tryDownload(url, dst)
        if (status !in 200..299) {
            throw IOException("HTTP $status for $url")
        }
    }

    /** Streams url → dst atomically. Returns HTTP status; dst is left untouched on non-2xx. */
    private fun tryDownload(url: String, dst: Path): Int {
        val conn = openConnection(url)
        try {
            val status = conn.responseCode
            if (status !in 200..299) return status
            Files.createDirectories(dst.parent)
            val tmp = Files.createTempFile(dst.parent, dst.fileName.toString(), ".part")
            try {
                conn.inputStream.use { input ->
                    Files.newOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (t: Throwable) {
                Files.deleteIfExists(tmp)
                throw t
            }
            return status
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "*/*")
        return conn
    }

    private fun extractZipEntry(zipPath: Path, entryName: String, dst: Path) {
        ZipFile(zipPath.toFile()).use { zip ->
            val entry = zip.getEntry(entryName)
                ?: throw IOException("Zip entry '$entryName' not found in $zipPath")
            Files.createDirectories(dst.parent)
            val tmp = Files.createTempFile(dst.parent, dst.fileName.toString(), ".part")
            try {
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (t: Throwable) {
                Files.deleteIfExists(tmp)
                throw t
            }
        }
    }

    private fun fileExistsNonEmpty(p: Path): Boolean =
        Files.isRegularFile(p) && Files.size(p) > 0

    @Serializable
    private data class VersionManifestV2(val versions: List<VersionManifestEntry> = emptyList())

    @Serializable
    private data class VersionManifestEntry(val id: String, val url: String)

    @Serializable
    private data class VersionJson(val downloads: Downloads)

    @Serializable
    private data class Downloads(val client_mappings: DownloadInfo? = null)

    @Serializable
    private data class DownloadInfo(val url: String)

    @Serializable
    private data class YarnBuildEntry(
        val gameVersion: String = "",
        val separator: String = "",
        val build: Int = 0,
        val maven: String = "",
        val version: String = "",
        val stable: Boolean = false,
    )
}
