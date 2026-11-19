package dev.mixinmcp.mcsources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

object LoaderVersionResolver {
    const val NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/"
    const val FORGE_MAVEN = "https://maven.minecraftforge.net/"
    const val FABRIC_MAVEN = "https://maven.fabricmc.net/"

    private const val USER_AGENT = "MixinMCP (+https://github.com/muon-rw/MixinMCP)"
    private const val CACHE_TTL_MS = 20L * 60L * 1000L

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private data class CachedVersions(val versions: List<String>, val fetchedAtMs: Long)

    private val metadataCache = ConcurrentHashMap<String, CachedVersions>()

    suspend fun newestNeoForge(mc: McVersionSpec.Release): String =
        newestNeoForge(versions(NEOFORGE_MAVEN + "net/neoforged/neoforge/maven-metadata.xml"), mc)
            ?: throw IllegalArgumentException("NeoForge has no published version for Minecraft ${mc.raw}.")

    suspend fun newestForge(mc: McVersionSpec.Release): String =
        newestForge(versions(FORGE_MAVEN + "net/minecraftforge/forge/maven-metadata.xml"), mc)
            ?: throw IllegalArgumentException("Forge has no published version for Minecraft ${mc.raw}.")

    suspend fun newestFabricLoader(): String =
        newestFabricLoader(versions(FABRIC_MAVEN + "net/fabricmc/fabric-loader/maven-metadata.xml"))
            ?: throw IOException("Fabric Loader's maven-metadata.xml lists no stable version.")

    suspend fun newestFabricApi(mc: McVersionSpec): String =
        newestFabricApi(versions(FABRIC_MAVEN + "net/fabricmc/fabric-api/fabric-api/maven-metadata.xml"), mc)
            ?: throw IllegalArgumentException("Fabric API has no published version for Minecraft ${mc.raw}.")

    // NeoForge drops the leading "1." (MC 1.21.1 -> 21.1.x); from 26.x on it keeps the full
    // MC version, patch included (MC 26.3 -> 26.3.0.x).
    fun newestNeoForge(versions: List<String>, mc: McVersionSpec.Release): String? {
        val prefix = if (mc.major >= 26) "${mc.major}.${mc.minor}.${mc.patch}." else "${mc.minor}.${mc.patch}."
        return newest(versions.filter { it.startsWith(prefix) })
    }

    /** Returns the Forge version without its "<mc>-" prefix, e.g. "47.4.29". */
    fun newestForge(versions: List<String>, mc: McVersionSpec.Release): String? {
        val prefix = "${mc.raw}-"
        return newest(versions.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) })
    }

    fun newestFabricLoader(versions: List<String>): String? = newest(versions.filter(::isStable), stableOnly = true)

    fun newestFabricApi(versions: List<String>, mc: McVersionSpec): String? {
        val suffix = "+${mc.raw}"
        return newest(versions.filter { it.endsWith(suffix) })
    }

    // Prefers stable versions but falls back to betas when a line has nothing else yet.
    private fun newest(candidates: List<String>, stableOnly: Boolean = false): String? {
        val stable = candidates.filter(::isStable)
        val pool = if (stable.isNotEmpty() || stableOnly) stable else candidates
        return pool.maxWithOrNull(::compareVersions)
    }

    fun isStable(version: String): Boolean = version.substringBefore('+').none { it.isLetter() }

    // Maven metadata is not reliably in publish order (Forge's lists 47.4.5 before 47.4.4).
    fun compareVersions(a: String, b: String): Int {
        val partsA = a.split('.', '-', '+')
        val partsB = b.split('.', '-', '+')
        for (i in 0 until maxOf(partsA.size, partsB.size)) {
            val x = partsA.getOrNull(i) ?: return -1
            val y = partsB.getOrNull(i) ?: return 1
            val xNum = x.toLongOrNull()
            val yNum = y.toLongOrNull()
            val cmp = if (xNum != null && yNum != null) xNum.compareTo(yNum) else x.compareTo(y)
            if (cmp != 0) return cmp
        }
        return 0
    }

    private suspend fun versions(metadataUrl: String): List<String> {
        val now = System.currentTimeMillis()
        metadataCache[metadataUrl]?.takeIf { now - it.fetchedAtMs < CACHE_TTL_MS }?.let { return it.versions }
        val fetched = withContext(Dispatchers.IO) {
            NeoFormVersionResolver.parseVersionsXml(fetchString(metadataUrl))
        }
        metadataCache[metadataUrl] = CachedVersions(fetched, System.currentTimeMillis())
        return fetched
    }

    internal fun fetchString(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while fetching $url", e)
        }
        if (response.statusCode() != 200) throw IOException("HTTP ${response.statusCode()} fetching $url")
        return response.body()
    }
}
