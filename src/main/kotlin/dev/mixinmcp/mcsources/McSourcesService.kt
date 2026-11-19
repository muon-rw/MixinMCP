package dev.mixinmcp.mcsources

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import dev.mixinmcp.mappings.MappingNamespace
import dev.mixinmcp.mappings.MappingsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

data class McSourceRequest(
    val version: McVersionSpec,
    val loader: McSourceLoader,
    val loaderVersion: String?,
) {
    val cacheKey: String
        get() = buildString {
            append(version.raw)
            append('-')
            append(loader.id)
            if (!loaderVersion.isNullOrEmpty()) {
                append('-')
                append(loaderVersion)
            }
        }

    companion object {
        fun validated(mcVersionRaw: String, loaderRaw: String, loaderVersionRaw: String?): McSourceRequest {
            val version = McVersionSpec.parse(mcVersionRaw)
            val loader = McSourceLoader.parse(loaderRaw)

            var loaderVersion = loaderVersionRaw?.trim()?.takeIf { it.isNotEmpty() }
            if (loaderVersion != null) {
                // Strip "<mcVersion>-" prefix if the caller passed e.g. "1.20.1-47.3.0".
                val prefix = "${version.raw}-"
                if (loaderVersion.startsWith(prefix)) {
                    loaderVersion = loaderVersion.removePrefix(prefix)
                }
                require(McSourcesCache.SAFE_NAME_PATTERN.matches(loaderVersion) && ".." !in loaderVersion) {
                    "Invalid loaderVersion '$loaderVersion'."
                }
            }

            when (loader) {
                McSourceLoader.VANILLA -> {
                    require(loaderVersion == null) {
                        "loaderVersion should not be passed when loader=\"vanilla\" (got '$loaderVersion')."
                    }
                    if (version is McVersionSpec.Release && version < McVersionSpec.V1_17) {
                        throw IllegalArgumentException(
                            "Vanilla source generation requires Minecraft 1.17 or higher (got ${version.raw}). " +
                                "NeoFormRuntime only supports versions that ship official Mojang mappings.",
                        )
                    }
                }
                McSourceLoader.FORGE -> {
                    require(!loaderVersion.isNullOrEmpty()) {
                        "loaderVersion is required for Forge (e.g. \"47.3.0\" for MC 1.20.1)."
                    }
                    require(version is McVersionSpec.Release) {
                        "Forge source generation requires a release MC version between 1.17 and 1.20.1 (got ${version.raw})."
                    }
                    require(version >= McVersionSpec.V1_17) {
                        "Forge is only supported for Minecraft 1.17 through 1.20.1 (got ${version.raw}). " +
                            "NeoFormRuntime only supports versions that ship official Mojang mappings."
                    }
                    require(version <= McVersionSpec.V1_20_1) {
                        "Forge is only supported for Minecraft 1.20.1 and lower (got ${version.raw}). " +
                            "For 1.20.2+, use loader=\"neoforge\"."
                    }
                }
                McSourceLoader.NEOFORGE -> {
                    require(!loaderVersion.isNullOrEmpty()) {
                        "loaderVersion is required for NeoForge (e.g. \"21.1.86\" for MC 1.21.1 or \"26.1.2.73\" for MC 26.1.2)."
                    }
                    if (version is McVersionSpec.Release) {
                        require(version >= McVersionSpec.V1_20_2) {
                            "NeoForge is only supported for Minecraft 1.20.2 and higher (got ${version.raw}). " +
                                "For 1.20.1 and lower, use loader=\"forge\"."
                        }
                    }
                }
            }

            return McSourceRequest(version, loader, loaderVersion)
        }
    }
}

data class McSourcesFetchResult(
    val key: String,
    val jarPath: Path,
    val cacheHit: Boolean,
    val sizeBytes: Long,
    val elapsedMs: Long,
    val evictedKeys: List<String>,
    val evictedWorkCacheBytes: Long,
    val cacheSummary: CacheSummary,
)

sealed interface McSourcesOutcome {
    data class Ready(val result: McSourcesFetchResult) : McSourcesOutcome
    data class Generating(val status: GenerationStatus, val startedByThisCall: Boolean) : McSourcesOutcome
}

data class GenerationStatus(val key: String, val startedAtMs: Long, val latestOutput: String?)

data class GenerationFailure(val key: String, val message: String, val failedAtMs: Long)

class SourcesJobException(message: String) : RuntimeException(message)

class GenerationInProgressException(keys: Collection<String>) : IllegalStateException(
    "Sources are still being generated for ${keys.sorted().joinToString(", ")}, and that run uses " +
        "NeoFormRuntime's work cache. Check progress with mixin_ide_status, wait for it with " +
        "mixin_get_mc_sources, or stop it with mixin_manage_mc_sources_cache(action=\"cancel\", key=...), " +
        "then retry the cleanup.",
)

@Service(Service.Level.APP)
class McSourcesService(serviceScope: CoroutineScope) {

    val cache: McSourcesCache = McSourcesCache()
    private val runner: NeoFormRuntimeRunner = NeoFormRuntimeRunner()
    private val loaderFetcher = LoaderSourcesFetcher(::fabricApiRemapper)

    // MixinMCP downloads these mappings itself, so remapping works whatever the open project's build
    // uses, including 26.x Loom projects that ship no remapper.
    private suspend fun fabricApiRemapper(mcVersion: String): FabricApiRemapper {
        val mappings = MappingsService.getInstance()
        val withYarn = runCatching { mappings.get(mcVersion, setOf(MappingNamespace.YARN, MappingNamespace.MOJMAP)) }
        withYarn.getOrNull()?.let { tree ->
            // Without the hierarchy, selectors naming overrides fall back to name-only lookup, and
            // the ones that stay ambiguous are marked in the source.
            val hierarchy = runCatching { ClassHierarchy.load(mcVersion) }.getOrNull()
            return FabricApiRemapper(
                IntermediaryRemapper.fromTree(tree),
                YarnStringRemapper.fromTree(tree, hierarchy),
                yarnFailure = null,
            )
        }
        val tree = mappings.get(mcVersion, setOf(MappingNamespace.INTERMEDIARY, MappingNamespace.MOJMAP))
        return FabricApiRemapper(IntermediaryRemapper.fromTree(tree), yarn = null, yarnFailure = withYarn.exceptionOrNull()?.message)
    }

    // Generations outlive the tool call that started them: MCP clients give up on a call after
    // about 60s, and a first-time decompile takes minutes. A supervisor keeps one failed
    // generation from cancelling the others; IDE shutdown still cancels them all.
    private val generationScope = CoroutineScope(
        serviceScope.coroutineContext + SupervisorJob(serviceScope.coroutineContext[Job]) + Dispatchers.IO,
    )
    private val generations = ConcurrentHashMap<String, Generation>()

    // A generation that fails while no call is waiting on it would otherwise vanish silently.
    // The failure is kept until one call reports it, so the call after that retries.
    private val unreportedFailures = ConcurrentHashMap<String, GenerationFailure>()

    // Every generation shares nfrt-home, so work-cache deletion must wait until none is running.
    // A plain monitor rather than a Mutex: registration has to survive cancellation in `finally`.
    private val workCacheLock = Any()
    private val activeGenerations = mutableSetOf<String>()

    private class Generation(val key: String, val startedAtMs: Long, val usesWorkCache: Boolean) {
        @Volatile
        var latestOutput: String? = null
        lateinit var job: Deferred<Path>

        fun status() = GenerationStatus(key, startedAtMs, latestOutput)

        fun report(line: String) {
            summarizeOutputLine(line)?.let { latestOutput = it }
        }
    }

    private fun workCacheJobs(): Set<String> =
        activeGenerations + generations.filterValues { it.usesWorkCache }.keys

    suspend fun getSources(request: McSourceRequest, waitMs: Long): McSourcesOutcome =
        startOrJoin(request.cacheKey, waitMs, usesWorkCache = true) { generation, tempJar ->
            runner.run(cache.nfrtHomeDir, buildNfrtArgs(request, tempJar), generation::report)
        }

    suspend fun getLoaderSources(request: LoaderSourcesRequest, waitMs: Long): McSourcesOutcome =
        startOrJoin(request.cacheKey, waitMs, usesWorkCache = false) { generation, tempJar ->
            loaderFetcher.fetch(request, tempJar, generation::report)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun startOrJoin(
        key: String,
        waitMs: Long,
        usesWorkCache: Boolean,
        produce: suspend (Generation, Path) -> Unit,
    ): McSourcesOutcome = withContext(Dispatchers.IO) {
        val evicted = cache.evictStale()
        cache.deleteOrphanTempJars()
        val evictedWorkBytes = synchronized(workCacheLock) {
            if (workCacheJobs().isEmpty()) cache.evictStaleWorkCache() else 0L
        }

        fun ready(jar: Path, cacheHit: Boolean, elapsedMs: Long) = McSourcesOutcome.Ready(
            McSourcesFetchResult(
                key = key,
                jarPath = jar,
                cacheHit = cacheHit,
                sizeBytes = Files.size(jar),
                elapsedMs = elapsedMs,
                evictedKeys = evicted,
                evictedWorkCacheBytes = evictedWorkBytes,
                cacheSummary = cache.summary(),
            ),
        )

        if (!generations.containsKey(key)) {
            cache.getIfPresent(key)?.let { return@withContext ready(it, cacheHit = true, elapsedMs = 0L) }
            unreportedFailures.remove(key)?.let { failure ->
                val agoS = (System.currentTimeMillis() - failure.failedAtMs) / 1000
                throw SourcesJobException(
                    "The background run failed ${agoS}s ago (call again to retry): ${failure.message}",
                )
            }
        }

        var startedByThisCall = false
        val generation = generations.computeIfAbsent(key) {
            startedByThisCall = true
            newGeneration(key, usesWorkCache, produce)
        }
        // Started outside computeIfAbsent: the completion handler removes the map entry, which
        // would be a recursive update if the job could finish while the map is still computing.
        generation.job.start()

        // join rather than await: await rethrows the job's cancellation, which would be
        // indistinguishable from this call itself being cancelled.
        val finished = withTimeoutOrNull(waitMs.milliseconds) { generation.job.join() } != null
        if (!finished) return@withContext McSourcesOutcome.Generating(generation.status(), startedByThisCall)
        val jar = when (val failure = generation.job.getCompletionExceptionOrNull()) {
            null -> generation.job.getCompleted()
            is CancellationException -> throw SourcesJobException("The run for $key was cancelled.")
            else -> {
                unreportedFailures.remove(key)
                throw failure
            }
        }
        ready(jar, cacheHit = false, elapsedMs = System.currentTimeMillis() - generation.startedAtMs)
    }

    fun runningGenerations(): List<GenerationStatus> = generations.values.map { it.status() }.sortedBy { it.startedAtMs }

    fun unreportedFailures(): List<GenerationFailure> = unreportedFailures.values.sortedBy { it.failedAtMs }

    fun cancel(key: String): Boolean {
        val generation = generations[key] ?: return false
        generation.job.cancel()
        return true
    }

    private fun newGeneration(key: String, usesWorkCache: Boolean, produce: suspend (Generation, Path) -> Unit): Generation {
        val generation = Generation(key, System.currentTimeMillis(), usesWorkCache)
        unreportedFailures.remove(key)
        generation.job = generationScope.async(start = CoroutineStart.LAZY) { generate(generation, produce) }
        generation.job.invokeOnCompletion { generations.remove(key, generation) }
        return generation
    }

    private suspend fun generate(generation: Generation, produce: suspend (Generation, Path) -> Unit): Path {
        val key = generation.key
        cache.getIfPresent(key)?.let { return it }
        if (generation.usesWorkCache) {
            synchronized(workCacheLock) {
                cache.markWorkCacheUsed()
                activeGenerations.add(key)
            }
        }
        var tempJar: Path? = null
        try {
            tempJar = cache.createTempJarPath(key)
            produce(generation, tempJar)
            if (!Files.isRegularFile(tempJar) || Files.size(tempJar) == 0L) {
                throw SourcesJobException("The run for $key finished without writing a sources jar.")
            }
            return cache.publishTempJar(key, tempJar)
        } catch (e: Throwable) {
            tempJar?.let { runCatching { Files.deleteIfExists(it) } }
            if (e !is CancellationException) {
                unreportedFailures[key] = GenerationFailure(key, e.message ?: e.javaClass.simpleName, System.currentTimeMillis())
            }
            throw e
        } finally {
            if (generation.usesWorkCache) synchronized(workCacheLock) { activeGenerations.remove(key) }
        }
    }

    fun deleteEntry(key: String): CleanupResult = cache.deleteEntry(key)

    fun cleanWorkCache(): CleanupResult = synchronized(workCacheLock) {
        requireNoActiveGeneration()
        cache.cleanWorkCache()
    }

    fun cleanAll(): CleanupResult = synchronized(workCacheLock) {
        requireNoActiveGeneration()
        cache.cleanAll()
    }

    private fun requireNoActiveGeneration() {
        val inFlight = workCacheJobs()
        if (inFlight.isNotEmpty()) throw GenerationInProgressException(inFlight)
    }

    private suspend fun buildNfrtArgs(request: McSourceRequest, outputJar: Path): List<String> {
        val args = mutableListOf("run", "--dist", "joined")
        val resultKey: String
        when (request.loader) {
            McSourceLoader.NEOFORGE -> {
                args.add("--neoforge")
                args.add("net.neoforged:neoforge:${request.loaderVersion}:userdev")
                resultKey = "gameSourcesWithNeoForge"
            }
            McSourceLoader.FORGE -> {
                args.add("--add-repository")
                args.add("https://maven.minecraftforge.net")
                args.add("--neoforge")
                args.add("net.minecraftforge:forge:${request.version.raw}-${request.loaderVersion}:userdev")
                resultKey = "gameSourcesWithNeoForge"
            }
            McSourceLoader.VANILLA -> {
                val neoFormVersion = NeoFormVersionResolver.resolve(request.version.raw)
                if (neoFormVersion != null) {
                    args.add("--neoform")
                    args.add("net.neoforged:neoform:$neoFormVersion@zip")
                } else {
                    // Older versions (<= 1.20.1) have no NeoForm build; fall back to MCPConfig on Forge Maven.
                    args.add("--add-repository")
                    args.add("https://maven.minecraftforge.net")
                    args.add("--neoform")
                    args.add("de.oceanlabs.mcp:mcp_config:${request.version.raw}@zip")
                }
                resultKey = "gameSources"
            }
        }
        args.add("--write-result=$resultKey:${outputJar.toAbsolutePath()}")
        return args
    }

    companion object {
        private const val MAX_STATUS_LINE_CHARS = 200

        private fun summarizeOutputLine(line: String): String? {
            val cleaned = NeoFormRuntimeRunner.stripAnsiAndControlChars(line).trim()
            if (cleaned.isEmpty()) return null
            return if (cleaned.length <= MAX_STATUS_LINE_CHARS) cleaned else cleaned.take(MAX_STATUS_LINE_CHARS) + "..."
        }

        fun getInstance(): McSourcesService =
            ApplicationManager.getApplication().getService(McSourcesService::class.java)
    }
}
