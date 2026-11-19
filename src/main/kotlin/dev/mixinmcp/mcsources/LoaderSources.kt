package dev.mixinmcp.mcsources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

enum class LoaderSourcesKind(val id: String, val sourcePathPrefix: String) {
    NEOFORGE("neoforge", "net/neoforged/neoforge/"),
    FORGE("forge", "net/minecraftforge/"),
    FABRIC_LOADER("fabric-loader", "net/fabricmc/loader/"),
    FABRIC_API("fabric-api", "net/fabricmc/fabric/"),
    ;

    companion object {
        val ALL_IDS: List<String> = entries.map { it.id }

        fun parse(raw: String): LoaderSourcesKind {
            val normalized = raw.trim().lowercase().replace('_', '-')
            return entries.firstOrNull { it.id == normalized }
                ?: throw IllegalArgumentException("Unknown loader '$raw'. Expected one of: ${ALL_IDS.joinToString(", ")}.")
        }
    }
}

data class LoaderSourcesRequest(val kind: LoaderSourcesKind, val artifactVersion: String) {
    // Game keys start with a Minecraft version, these with a loader name, so the two never collide.
    val cacheKey: String get() = "${kind.id}-$artifactVersion"

    // Fabric API versions end in "+<mc>", the game version its sources were written against.
    val fabricApiMcVersion: String?
        get() = if (kind == LoaderSourcesKind.FABRIC_API) artifactVersion.substringAfter('+', "").ifEmpty { null } else null

    companion object {
        suspend fun resolve(loaderRaw: String, mcVersionRaw: String?, loaderVersionRaw: String?): LoaderSourcesRequest {
            val kind = LoaderSourcesKind.parse(loaderRaw)
            val mc = mcVersionRaw?.trim()?.takeIf { it.isNotEmpty() }?.let(McVersionSpec::parse)
            val loaderVersion = loaderVersionRaw?.trim()?.takeIf { it.isNotEmpty() }?.also {
                require(McSourcesCache.SAFE_NAME_PATTERN.matches(it) && ".." !in it) { "Invalid loaderVersion '$it'." }
            }
            val artifactVersion = when (kind) {
                LoaderSourcesKind.NEOFORGE -> loaderVersion
                    ?: LoaderVersionResolver.newestNeoForge(requireRelease(mc, "NeoForge"))
                LoaderSourcesKind.FORGE -> forgeArtifactVersion(mc, loaderVersion)
                LoaderSourcesKind.FABRIC_LOADER -> loaderVersion ?: LoaderVersionResolver.newestFabricLoader()
                LoaderSourcesKind.FABRIC_API -> when {
                    loaderVersion != null && '+' in loaderVersion -> loaderVersion
                    loaderVersion != null -> "$loaderVersion+${requireMc(mc, "Fabric API without a '+<mc>' suffix").raw}"
                    else -> LoaderVersionResolver.newestFabricApi(requireMc(mc, "Fabric API"))
                }
            }
            return LoaderSourcesRequest(kind, artifactVersion)
        }

        // Forge coordinates embed the game version: net.minecraftforge:forge:1.20.1-47.3.0.
        private suspend fun forgeArtifactVersion(mc: McVersionSpec?, loaderVersion: String?): String {
            if (mc == null) {
                require(loaderVersion != null && '-' in loaderVersion) {
                    "Forge needs mcVersion, or a full loaderVersion such as '1.20.1-47.3.0'."
                }
                return loaderVersion
            }
            val release = requireRelease(mc, "Forge")
            val forgeVersion = loaderVersion?.removePrefix("${release.raw}-") ?: LoaderVersionResolver.newestForge(release)
            return "${release.raw}-$forgeVersion"
        }

        private fun requireMc(mc: McVersionSpec?, what: String): McVersionSpec =
            requireNotNull(mc) { "$what needs mcVersion (or pass loaderVersion)." }

        private fun requireRelease(mc: McVersionSpec?, what: String): McVersionSpec.Release {
            val spec = requireMc(mc, what)
            require(spec is McVersionSpec.Release) { "$what versions can only be resolved for release Minecraft versions (got ${spec.raw})." }
            return spec
        }
    }
}

data class FabricApiModule(val artifactId: String, val version: String)

// Pre-26 Fabric API sources use two namespaces: Loom remaps the Java code to intermediary on
// publish, but leaves mixin annotation strings in the Yarn names they were written in.
class FabricApiRemapper(
    private val intermediary: IntermediaryRemapper,
    private val yarn: YarnStringRemapper?,
    val yarnFailure: String?,
) {
    val unresolvedSelectors: Set<String> get() = yarn?.unresolved.orEmpty()

    fun remap(text: String): String = intermediary.remap(text).let { yarn?.remap(it) ?: it }
}

class LoaderSourcesFetcher(
    private val remapperFor: suspend (mcVersion: String) -> FabricApiRemapper,
) {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    suspend fun fetch(request: LoaderSourcesRequest, outputJar: Path, report: (String) -> Unit) = withContext(Dispatchers.IO) {
        val v = request.artifactVersion
        when (request.kind) {
            LoaderSourcesKind.NEOFORGE -> downloadSingle(
                "${LoaderVersionResolver.NEOFORGE_MAVEN}net/neoforged/neoforge/$v/neoforge-$v-sources.jar", outputJar, report,
            )
            LoaderSourcesKind.FORGE -> downloadSingle(
                "${LoaderVersionResolver.FORGE_MAVEN}net/minecraftforge/forge/$v/forge-$v-sources.jar", outputJar, report,
            )
            LoaderSourcesKind.FABRIC_LOADER -> downloadSingle(
                "${LoaderVersionResolver.FABRIC_MAVEN}net/fabricmc/fabric-loader/$v/fabric-loader-$v-sources.jar", outputJar, report,
            )
            LoaderSourcesKind.FABRIC_API -> assembleFabricApi(request, outputJar, report)
        }
    }

    private suspend fun downloadSingle(url: String, target: Path, report: (String) -> Unit) {
        report("Downloading ${url.substringAfterLast('/')}")
        download(url, target)
    }

    // The fabric-api artifact's own sources jar is an empty shell; the code is in the ~40 modules
    // its POM depends on, each with its own sources jar. We merge them so callers get one jarPath.
    private suspend fun assembleFabricApi(request: LoaderSourcesRequest, outputJar: Path, report: (String) -> Unit) {
        val v = request.artifactVersion
        report("Reading the fabric-api $v POM")
        val pomUrl = "${LoaderVersionResolver.FABRIC_MAVEN}net/fabricmc/fabric-api/fabric-api/$v/fabric-api-$v.pom"
        val modules = parseFabricApiModules(runInterruptible { LoaderVersionResolver.fetchString(pomUrl) })
        if (modules.isEmpty()) throw IOException("The fabric-api $v POM lists no Fabric API modules ($pomUrl).")

        val workDir = Files.createTempDirectory("mixinmcp-fabric-api-")
        try {
            val downloaded = downloadModules(modules, workDir, report)
            val remap = request.fabricApiMcVersion?.let { mc -> loadRemapper(mc, report) }
            report("Merging and remapping ${downloaded.count { it.second != null }} module jars")
            mergeModules(downloaded, outputJar, remap?.getOrNull()) { fabricApiNote(request, downloaded, remap) }
        } finally {
            McSourcesCache.deleteRecursively(workDir)
        }
    }

    // fabric-api-deprecated is itself an aggregate with no sources jar of its own, so a module
    // without one has its POM read for further modules.
    private suspend fun downloadModules(
        initial: List<FabricApiModule>,
        workDir: Path,
        report: (String) -> Unit,
    ): List<Pair<FabricApiModule, Path?>> {
        val results = ArrayList<Pair<FabricApiModule, Path?>>()
        val seen = initial.map { it.artifactId }.toMutableSet()
        var pending = initial
        val done = AtomicInteger()
        val permits = Semaphore(MAX_PARALLEL_DOWNLOADS)
        repeat(MAX_AGGREGATE_DEPTH) {
            if (pending.isEmpty()) return@repeat
            val total = seen.size
            val round = coroutineScope {
                pending.map { module ->
                    async {
                        permits.withPermit {
                            val jar = workDir.resolve("${module.artifactId}.jar")
                            val found = try {
                                download(moduleUrl(module, "-sources.jar"), jar)
                                true
                            } catch (_: MissingArtifactException) {
                                false
                            }
                            report("Downloaded Fabric API module sources ${done.incrementAndGet()}/$total")
                            module to jar.takeIf { found }
                        }
                    }
                }.awaitAll()
            }
            val next = ArrayList<FabricApiModule>()
            for ((module, jar) in round) {
                if (jar != null) {
                    results += module to jar
                    continue
                }
                val children = runCatching {
                    parseFabricApiModules(runInterruptible { LoaderVersionResolver.fetchString(moduleUrl(module, ".pom")) })
                }.getOrDefault(emptyList()).filter { seen.add(it.artifactId) }
                if (children.isEmpty()) results += module to null
                next += children
            }
            pending = next
        }
        return results
    }

    private fun moduleUrl(module: FabricApiModule, suffix: String): String =
        "${LoaderVersionResolver.FABRIC_MAVEN}net/fabricmc/fabric-api/${module.artifactId}/" +
            "${module.version}/${module.artifactId}-${module.version}$suffix"

    // Before 26.x, Fabric API's published sources name the game in intermediary (class_1657). From
    // 26.x the game ships unobfuscated, Fabric API uses Mojang names, and there is nothing to remap.
    private suspend fun loadRemapper(mcVersion: String, report: (String) -> Unit): Result<FabricApiRemapper>? {
        val spec = runCatching { McVersionSpec.parse(mcVersion) }.getOrNull()
        if (spec is McVersionSpec.Release && spec >= McVersionSpec.V26_1) return null
        report("Loading intermediary, Yarn, and Mojang mappings and the class hierarchy for $mcVersion")
        return runCatching { remapperFor(mcVersion) }
    }

    private fun mergeModules(
        modules: List<Pair<FabricApiModule, Path?>>,
        outputJar: Path,
        remapper: FabricApiRemapper?,
        note: () -> String,
    ) {
        val written = hashSetOf(NOTE_ENTRY)
        ZipOutputStream(Files.newOutputStream(outputJar)).use { out ->
            for ((module, jar) in modules) {
                if (jar == null) continue
                ZipFile(jar.toFile()).use { zip ->
                    for (entry in zip.entries()) {
                        if (entry.isDirectory) continue
                        // Every module ships its own fabric.mod.json, icon, and manifest.
                        val name = if (written.add(entry.name)) entry.name else "${module.artifactId}/${entry.name}"
                        if (name != entry.name && !written.add(name)) continue
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val content = if (remapper != null && isRemappable(name)) {
                            remapper.remap(bytes.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                        } else {
                            bytes
                        }
                        out.putNextEntry(ZipEntry(name))
                        out.write(content)
                        out.closeEntry()
                    }
                }
            }
            // Written last: it reports how many selectors the remap above couldn't resolve.
            out.putNextEntry(ZipEntry(NOTE_ENTRY))
            out.write(note().toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }
    }

    private fun fabricApiNote(
        request: LoaderSourcesRequest,
        modules: List<Pair<FabricApiModule, Path?>>,
        remap: Result<FabricApiRemapper>?,
    ): String = buildString {
        val merged = modules.count { it.second != null }
        appendLine("Merged by MixinMCP from $merged Fabric API module sources jars for fabric-api ${request.artifactVersion}.")
        val mc = request.fabricApiMcVersion
        val remapper = remap?.getOrNull()
        when {
            remap == null -> appendLine("Minecraft names are Mojang names as published.")
            remapper == null -> appendLine(
                "Minecraft names are still intermediary (class_1234, method_5678) in code and Yarn in mixin " +
                    "annotation strings: the $mc mappings could not be loaded (${remap.exceptionOrNull()?.message}). " +
                    "Convert names with mixin_mappings_lookup.",
            )
            remapper.yarnFailure != null -> appendLine(
                "Minecraft names in code were remapped from intermediary to Mojang names using the $mc mappings. " +
                    "Mixin annotation strings keep Fabric's Yarn names, because the Yarn mappings for $mc could " +
                    "not be loaded (${remapper.yarnFailure}); convert them with mixin_mappings_lookup(from=\"yarn\").",
            )
            else -> {
                appendLine(
                    "Minecraft names were remapped to Mojang names using the $mc mappings: intermediary names in " +
                        "code, and Fabric's Yarn names in mixin annotation strings (method selectors, @At targets, " +
                        "@Accessor and @Invoker names). They match a Mojang-named game jar, not the published bytecode.",
                )
                val unresolved = remapper.unresolvedSelectors
                if (unresolved.isNotEmpty()) {
                    val count = if (unresolved.size == 1) "1 mixin selector is" else "${unresolved.size} mixin selectors are"
                    appendLine(
                        "$count ambiguous and kept in Yarn, each marked with a // MixinMCP comment on its line; " +
                            "convert with mixin_mappings_lookup(from=\"yarn\"): " +
                            unresolved.take(MAX_LISTED_UNRESOLVED).joinToString(", "),
                    )
                }
            }
        }
        val missing = modules.filter { it.second == null }.map { "${it.first.artifactId}:${it.first.version}" }
        if (missing.isNotEmpty()) appendLine("Modules without a published sources jar: ${missing.joinToString(", ")}")
    }

    private suspend fun download(url: String, target: Path) = runInterruptible {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(120))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            when (response.statusCode()) {
                200 -> Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING)
                404 -> throw MissingArtifactException("Not published: $url")
                else -> throw IOException("HTTP ${response.statusCode()} downloading $url")
            }
        }
    }

    class MissingArtifactException(message: String) : IOException(message)

    companion object {
        const val NOTE_ENTRY = "MIXINMCP-SOURCES.txt"
        private const val USER_AGENT = "MixinMCP (+https://github.com/muon-rw/MixinMCP)"
        private const val MAX_PARALLEL_DOWNLOADS = 8
        private const val MAX_AGGREGATE_DEPTH = 3
        private const val MAX_LISTED_UNRESOLVED = 12

        private fun isRemappable(name: String): Boolean =
            name.endsWith(".java") || name.endsWith(".accesswidener") || name.endsWith(".classtweaker")

        internal fun parseFabricApiModules(pomXml: String): List<FabricApiModule> {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(pomXml)))
            val deps = doc.getElementsByTagName("dependency")
            return (0 until deps.length).mapNotNull { i ->
                val dep = deps.item(i) as? org.w3c.dom.Element ?: return@mapNotNull null
                fun child(tag: String) = dep.getElementsByTagName(tag).item(0)?.textContent?.trim()
                val group = child("groupId")
                val artifact = child("artifactId")
                val version = child("version")
                if (group == "net.fabricmc.fabric-api" && artifact != null && version != null) {
                    FabricApiModule(artifact, version)
                } else {
                    null
                }
            }
        }
    }
}
