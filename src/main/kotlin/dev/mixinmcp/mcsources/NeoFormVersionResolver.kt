package dev.mixinmcp.mcsources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Resolves a Minecraft version to the newest matching `net.neoforged:neoform` artifact version
 * from NeoForged's maven-metadata.xml. NeoForm starts at 23w31a (first snapshot after 1.20.1);
 * older versions return null so callers fall back to MCPConfig.
 */
object NeoFormVersionResolver {
    private const val METADATA_URL =
        "https://maven.neoforged.net/releases/net/neoforged/neoform/maven-metadata.xml"
    private const val USER_AGENT = "MixinMCP (+https://github.com/muon-rw/MixinMCP)"
    private const val CACHE_TTL_MS = 20L * 60L * 1000L

    // Build suffix after "<mcVersion>-": digits, dots, and dashes only (e.g. "20240808.144430" or "1").
    // Rejects suffixes containing letters (like "rc1", "pre2", "snapshot-1") so requesting "1.21.4"
    // never matches "1.21.4-rc1-20241128.120000".
    private val BUILD_SUFFIX = Regex("""^\d+(?:[.-]\d+)*$""")

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Volatile
    private var cachedVersions: List<String>? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    suspend fun resolve(requestedVersion: String): String? {
        val versions = getAllVersions()
        return resolve(requestedVersion, versions)
    }

    fun resolve(requestedVersion: String, availableVersions: List<String>): String? {
        val prefix = "$requestedVersion-"
        var best: String? = null
        for (id in availableVersions) {
            val matches = when {
                id == requestedVersion -> true
                id.startsWith(prefix) -> BUILD_SUFFIX.matches(id.substring(prefix.length))
                else -> false
            }
            if (matches) {
                // maven-metadata.xml lists versions in publish order (oldest first).
                best = id
            }
        }
        return best
    }

    private suspend fun getAllVersions(): List<String> {
        val now = System.currentTimeMillis()
        val existing = cachedVersions
        if (existing != null && now - cachedAtMs < CACHE_TTL_MS) {
            return existing
        }
        val fetched = withContext(Dispatchers.IO) { fetchVersions() }
        cachedVersions = fetched
        cachedAtMs = System.currentTimeMillis()
        return fetched
    }

    private fun fetchVersions(): List<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(METADATA_URL))
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()
        val response: HttpResponse<String> = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while fetching NeoForm metadata", e)
        }
        if (response.statusCode() != 200) {
            throw IOException("Failed to fetch NeoForm metadata: HTTP ${response.statusCode()} ($METADATA_URL)")
        }
        return parseVersionsXml(response.body())
    }

    internal fun parseVersionsXml(xml: String): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val nodes = doc.getElementsByTagName("version")
        val result = ArrayList<String>(nodes.length)
        for (i in 0 until nodes.length) {
            val text = nodes.item(i).textContent?.trim()
            if (!text.isNullOrEmpty()) {
                result.add(text)
            }
        }
        return result
    }
}
