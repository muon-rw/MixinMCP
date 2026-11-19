package dev.mixinmcp.cache

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import dev.mixinmcp.settings.MixinMcpAppSettings
import dev.mixinmcp.tools.source.TrigramShardIndex
import dev.mixinmcp.tools.source.collectSourceRootsWithMetadata
import dev.mixinmcp.tools.source.shardPathForSourceRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Marks every open project's decompiled directories and trigram shards as used, then runs
 * [CacheLruEvictor] when due. Being open counts as use, so we also re-mark while the IDE stays up.
 */
@Service(Service.Level.APP)
class CacheMaintenanceService(private val scope: CoroutineScope) {

    private val evictionLock = Mutex()
    private var pendingRun: Job? = null

    // Light test fixtures run our startup activity, and maintenance works on the real ~/.cache/mixinmcp.
    private val enabled: Boolean = !ApplicationManager.getApplication().isUnitTestMode

    init {
        if (enabled) startPeriodicRuns()
    }

    private fun startPeriodicRuns() {
        scope.launch(CoroutineName("MixinMCP cache maintenance") + Dispatchers.IO) {
            while (true) {
                delay(CacheLruEvictor.TOUCH_THROTTLE_MS.milliseconds)
                runMaintenance()
            }
        }
    }

    /** Debounced; call after a project's decompiled roots are (re)loaded, on open and after each sync. */
    fun schedule() {
        if (!enabled) return
        synchronized(this) {
            pendingRun?.cancel()
            pendingRun = scope.launch(CoroutineName("MixinMCP cache maintenance") + Dispatchers.IO) {
                delay(DEBOUNCE)
                runMaintenance()
            }
        }
    }

    private class OpenProjectUsage(val cacheDirs: Set<Path>, val cacheDirNames: Set<String>, val shards: Set<Path>)

    private suspend fun runMaintenance() {
        val nowMs: Long = System.currentTimeMillis()
        val usage: OpenProjectUsage = collectOpenProjectUsage()
        for (dir in usage.cacheDirs) CacheLruEvictor.touchIfStale(dir, nowMs)
        for (shard in usage.shards) CacheLruEvictor.touchIfStale(shard, nowMs)

        if (!isEvictionDue(nowMs) || !evictionLock.tryLock()) return
        try {
            markEvictionRun(nowMs)
            val settings: MixinMcpAppSettings = MixinMcpAppSettings.getInstance()
            // Coerced because mixinmcp.xml can be edited by hand, bypassing the settings UI's validation.
            val decompiledMaxGiB: Int = settings.decompiledCacheMaxGiB.coerceIn(MixinMcpAppSettings.DECOMPILED_CACHE_MAX_GIB_RANGE)
            val trigramMaxMiB: Int = settings.trigramCacheMaxMiB.coerceIn(MixinMcpAppSettings.TRIGRAM_CACHE_MAX_MIB_RANGE)
            val sizes: MutableMap<String, Long> = CacheLruEvictor.loadSizeMemo(SIZE_MEMO_FILE)
            CacheLruEvictor.evictDecompiledCache(
                cacheRoot = DecompilationCacheService.globalCacheRoot,
                protectedDirNames = usage.cacheDirNames,
                maxUnprotectedBytes = decompiledMaxGiB.toLong() shl 30,
                nowMs = nowMs,
                knownSizes = sizes,
            )
            CacheLruEvictor.saveSizeMemo(SIZE_MEMO_FILE, sizes)
            CacheLruEvictor.evictTrigramShards(
                shardDir = TrigramShardIndex.defaultShardDir,
                protectedShardNames = usage.shards.mapTo(HashSet()) { it.fileName.toString() },
                maxUnprotectedBytes = trigramMaxMiB.toLong() shl 20,
                nowMs = nowMs,
            )
        } finally {
            evictionLock.unlock()
        }
    }

    private suspend fun collectOpenProjectUsage(): OpenProjectUsage {
        val cacheDirs: MutableSet<Path> = HashSet()
        val cacheDirNames: MutableSet<String> = HashSet()
        val shards: MutableSet<Path> = HashSet()
        for (project: Project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            try {
                // Manifests rather than getCachedRoots(), which drops directories the VFS hasn't seen yet.
                for ((hash, dir) in DecompilationCacheService.getInstance(project).manifestCacheDirs()) {
                    cacheDirNames.add(hash)
                    dir.fileName?.let { cacheDirNames.add(it.toString()) }
                    cacheDirs.add(dir)
                }
                shards += readAction {
                    if (project.isDisposed) return@readAction emptyList()
                    val cacheService: DecompilationCacheService = DecompilationCacheService.getInstance(project)
                    collectSourceRootsWithMetadata(project).mapNotNull { shardPathForSourceRoot(it.root, cacheService) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("MixinMCP: couldn't collect cache usage for ${project.name}", e)
            }
        }
        return OpenProjectUsage(cacheDirs, cacheDirNames, shards)
    }

    // Shared across IDE processes (the main IDE and a sandbox IDE, for example) through the marker's mtime.
    private fun isEvictionDue(nowMs: Long): Boolean {
        val lastRunMs: Long = runCatching { Files.getLastModifiedTime(EVICTION_MARKER_FILE).toMillis() }.getOrNull()
            ?: return true
        return nowMs - lastRunMs >= EVICTION_INTERVAL_MS
    }

    private fun markEvictionRun(nowMs: Long) {
        try {
            Files.createDirectories(EVICTION_MARKER_FILE.parent)
            if (!Files.exists(EVICTION_MARKER_FILE)) Files.createFile(EVICTION_MARKER_FILE)
            Files.setLastModifiedTime(EVICTION_MARKER_FILE, FileTime.fromMillis(nowMs))
        } catch (_: Exception) {
        }
    }

    companion object {
        private val LOG = Logger.getInstance(CacheMaintenanceService::class.java)
        private val DEBOUNCE = 5.seconds
        private val EVICTION_INTERVAL_MS: Long = TimeUnit.HOURS.toMillis(6)
        private val MAINTENANCE_DIR: Path = Paths.get(System.getProperty("user.home"), ".cache", "mixinmcp")
        private val EVICTION_MARKER_FILE: Path = MAINTENANCE_DIR.resolve("last-eviction")
        private val SIZE_MEMO_FILE: Path = MAINTENANCE_DIR.resolve("decompiled-sizes.properties")

        fun getInstance(): CacheMaintenanceService = service()
    }
}
