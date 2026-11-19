package dev.mixinmcp.tools.mcsources

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue.FALSE
import com.intellij.mcpserver.annotations.McpToolHintValue.TRUE
import com.intellij.mcpserver.annotations.McpToolHints
import dev.mixinmcp.mcsources.CacheSummary
import dev.mixinmcp.mcsources.GenerationInProgressException
import dev.mixinmcp.mcsources.GenerationStatus
import dev.mixinmcp.mcsources.LoaderSourcesFetcher
import dev.mixinmcp.mcsources.LoaderSourcesRequest
import dev.mixinmcp.mcsources.LoaderVersionResolver
import dev.mixinmcp.mcsources.McSourceRequest
import dev.mixinmcp.mcsources.McSourcesOutcome
import dev.mixinmcp.mcsources.McSourcesCache
import dev.mixinmcp.mcsources.McSourcesFetchResult
import dev.mixinmcp.mcsources.McSourcesService
import dev.mixinmcp.mcsources.McSourceLoader
import dev.mixinmcp.mcsources.McVersionSpec
import dev.mixinmcp.mcsources.NeoFormRuntimeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.util.zip.ZipFile

private const val WORK_CACHE_CLEANUP_THRESHOLD_BYTES: Long = 250L * 1024L * 1024L

// MCP clients commonly abandon a call after 60s, so the default wait leaves room for the response.
private const val DEFAULT_WAIT_MS: Long = 40_000
private const val MAX_WAIT_MS: Long = 600_000

@Suppress("FunctionName") // @McpTool functions are snake_case by MCP convention
class McSourcesToolset : McpToolset {

    override fun isExperimental(): Boolean = false

    @McpToolHints(readOnlyHint = FALSE, openWorldHint = TRUE)
    @McpTool
    @McpDescription(
        "Fetches decompiled Minecraft (and optionally Forge or NeoForge) sources for an arbitrary " +
            "Minecraft version on demand using NeoFormRuntime, caching the resulting sources.jar under " +
            "~/.cache/mixinmcp/mc-sources/jars/. Use this when you need to inspect vanilla or loader " +
            "sources for a DIFFERENT Minecraft version than the project currently open in IntelliJ " +
            "(e.g. comparing 1.20.1 and 1.21.1 while porting a mod). For the open project's own version, " +
            "use mixin_find_class, mixin_search_in_deps, and mixin_get_dep_source instead.\n\n" +
            "Parameters:\n" +
            "- mcVersion: Minecraft version (e.g. '1.20.1', '1.21.1', '26.1', or a snapshot ID for vanilla).\n" +
            "- loader: 'vanilla' (default, 1.17+), 'forge' (1.17 through 1.20.1), or 'neoforge' (1.20.2+). " +
            "For Fabric/Quilt cross-version checks, use loader='vanilla' with mixin_mappings_lookup.\n" +
            "- loaderVersion: for 'forge' (e.g. '47.3.0') and 'neoforge' (e.g. '21.1.86' or '26.1.2.73'); " +
            "omit it to use the newest release for mcVersion. Omit for 'vanilla'.\n" +
            "- timeoutMs: how long to wait for a generation before returning (default 40000, max 600000; " +
            "0 starts it and returns at once).\n\n" +
            "Returns the jarPath of the cached sources.jar, which works directly with " +
            "mixin_list_jar_entries(jarPath=..., regexPattern=...) and " +
            "mixin_get_dep_source(jarPath=..., entry=...). Cached versions return immediately. A first-time " +
            "generation downloads and decompiles Minecraft via NeoFormRuntime, which typically takes 1-3 " +
            "minutes, longer than most MCP clients let one call run. It runs in the background: when " +
            "timeoutMs passes first, the call returns a 'still running' status with the latest NFRT step " +
            "instead of an error. Call again with the same arguments to keep waiting (the call rejoins the " +
            "same run), or poll mixin_ide_status. For a loader's own code without decompiling Minecraft " +
            "(NeoForge or Forge events and hooks, Fabric Loader, Fabric API), use mixin_get_loader_sources. " +
            "When finished with cross-version research, call mixin_manage_mc_sources_cache to reclaim disk space.",
    )
    @Suppress("unused")
    suspend fun mixin_get_mc_sources(
        mcVersion: String,
        loader: String = "vanilla",
        loaderVersion: String? = null,
        timeoutMs: Long = DEFAULT_WAIT_MS,
    ): McpToolCallResult {
        if (timeoutMs !in 0..MAX_WAIT_MS) {
            return McpToolCallResult.error("timeoutMs must be between 0 and $MAX_WAIT_MS (got $timeoutMs).")
        }
        val request = try {
            McSourceRequest.validated(mcVersion, loader, loaderVersion ?: newestGameLoaderVersion(mcVersion, loader))
        } catch (e: IllegalArgumentException) {
            return McpToolCallResult.error(e.message ?: "Invalid arguments.")
        } catch (e: IOException) {
            return McpToolCallResult.error("Could not look up the newest $loader version for $mcVersion: ${e.message}")
        }

        val outcome = try {
            McSourcesService.getInstance().getSources(request, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NeoFormRuntimeException) {
            return McpToolCallResult.error("Failed to generate Minecraft sources for ${request.cacheKey}: ${e.message}")
        } catch (e: Exception) {
            return McpToolCallResult.error("Failed to fetch Minecraft sources for ${request.cacheKey}: ${e.message}")
        }

        return when (outcome) {
            is McSourcesOutcome.Generating -> McpToolCallResult.text(
                formatRunning(outcome, timeoutMs, "Minecraft sources", "generating via NeoFormRuntime", "mixin_get_mc_sources"),
            )
            is McSourcesOutcome.Ready -> McpToolCallResult.text(
                formatReady(
                    outcome.result,
                    title = "Minecraft sources",
                    producedBy = "generated via NeoFormRuntime",
                    pathPrefix = "net/minecraft/world/entity/",
                    readExample = "net/minecraft/world/entity/LivingEntity.java",
                ),
            )
        }
    }

    @McpToolHints(readOnlyHint = FALSE, openWorldHint = TRUE)
    @McpTool
    @McpDescription(
        "Downloads the published sources of a mod loader or Fabric API for any version, without decompiling " +
            "Minecraft, and caches them as one sources.jar under ~/.cache/mixinmcp/mc-sources/jars/. Use it to " +
            "read NeoForge's or Forge's events and hooks, Fabric Loader, or Fabric API for a version other than " +
            "the open project's; for the project's own version, mixin_search_in_deps already covers them. These " +
            "jars hold only the loader's own code. For vanilla classes with a loader's patches applied, use " +
            "mixin_get_mc_sources.\n\n" +
            "Parameters:\n" +
            "- loader: 'neoforge', 'forge', 'fabric-loader', or 'fabric-api'.\n" +
            "- mcVersion: picks the newest loader version for that Minecraft version (e.g. '1.21.1'). Required " +
            "for 'forge' unless loaderVersion is a full '1.20.1-47.3.0'; ignored for 'fabric-loader'.\n" +
            "- loaderVersion: an exact version instead, e.g. '21.1.86', '47.3.0', '0.16.9', or '0.102.0+1.21.1' " +
            "(a Fabric API version without '+<mc>' takes it from mcVersion).\n" +
            "- timeoutMs: how long to wait before returning (default 40000, max 600000; 0 returns at once).\n\n" +
            "Fabric API publishes its code as about 40 module artifacts, which MixinMCP merges into one jar. " +
            "Before Minecraft 26.x those sources name game classes and members in intermediary (class_1657, " +
            "method_5643), so MixinMCP remaps them to Mojang names, mixin annotation strings included, to read " +
            "alongside mixin_get_mc_sources jars. The jar's MIXINMCP-SOURCES.txt records what was merged and " +
            "remapped. Returns the jarPath for mixin_list_jar_entries and mixin_get_dep_source. Like " +
            "mixin_get_mc_sources, a slow first fetch returns a 'still running' status; call again with the same " +
            "arguments to keep waiting.",
    )
    @Suppress("unused")
    suspend fun mixin_get_loader_sources(
        loader: String,
        mcVersion: String? = null,
        loaderVersion: String? = null,
        timeoutMs: Long = DEFAULT_WAIT_MS,
    ): McpToolCallResult {
        if (timeoutMs !in 0..MAX_WAIT_MS) {
            return McpToolCallResult.error("timeoutMs must be between 0 and $MAX_WAIT_MS (got $timeoutMs).")
        }
        val request = try {
            LoaderSourcesRequest.resolve(loader, mcVersion, loaderVersion)
        } catch (e: IllegalArgumentException) {
            return McpToolCallResult.error(e.message ?: "Invalid arguments.")
        } catch (e: IOException) {
            return McpToolCallResult.error("Could not look up the newest $loader version: ${e.message}")
        }
        val outcome = try {
            McSourcesService.getInstance().getLoaderSources(request, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return McpToolCallResult.error("Failed to fetch ${request.kind.id} sources for ${request.cacheKey}: ${e.message}")
        }
        return when (outcome) {
            is McSourcesOutcome.Generating -> McpToolCallResult.text(
                formatRunning(outcome, timeoutMs, "${request.kind.id} sources", "downloading", "mixin_get_loader_sources"),
            )
            is McSourcesOutcome.Ready -> McpToolCallResult.text(
                formatReady(
                    outcome.result,
                    title = "${request.kind.id} ${request.artifactVersion} sources",
                    producedBy = "downloaded",
                    pathPrefix = request.kind.sourcePathPrefix,
                    readExample = null,
                    jarNote = readJarNote(outcome.result.jarPath),
                ),
            )
        }
    }

    private suspend fun newestGameLoaderVersion(mcVersion: String, loader: String): String? {
        val kind = runCatching { McSourceLoader.parse(loader) }.getOrNull() ?: return null
        val release = runCatching { McVersionSpec.parse(mcVersion) }.getOrNull() as? McVersionSpec.Release ?: return null
        return when (kind) {
            McSourceLoader.NEOFORGE -> LoaderVersionResolver.newestNeoForge(release)
            McSourceLoader.FORGE -> LoaderVersionResolver.newestForge(release)
            McSourceLoader.VANILLA -> null
        }
    }

    private fun readJarNote(jar: Path): String? = runCatching {
        ZipFile(jar.toFile()).use { zip ->
            zip.getEntry(LoaderSourcesFetcher.NOTE_ENTRY)?.let { entry ->
                zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8).trim() }
            }
        }
    }.getOrNull()

    private fun formatReady(
        result: McSourcesFetchResult,
        title: String,
        producedBy: String,
        pathPrefix: String,
        readExample: String?,
        jarNote: String? = null,
    ): String {
        val jarPathStr = result.jarPath.toAbsolutePath().toString().replace('\\', '/')
        return buildString {
            appendLine("=== $title ready: ${result.key} ===")
            appendLine("jarPath: $jarPathStr")
            appendLine("cacheKey: ${result.key}")
            val status = if (result.cacheHit) {
                "cache hit (${McSourcesCache.formatBytes(result.sizeBytes)})"
            } else {
                val secs = String.format(java.util.Locale.ROOT, "%.1f", result.elapsedMs / 1000.0)
                "$producedBy in ${secs}s (${McSourcesCache.formatBytes(result.sizeBytes)})"
            }
            appendLine("Status: $status")
            jarNote?.let { appendLine(it) }
            if (result.evictedKeys.isNotEmpty()) {
                appendLine("Evicted stale (>30d) cache entries: ${result.evictedKeys.joinToString(", ")}")
            }
            if (result.evictedWorkCacheBytes > 0L) {
                appendLine(
                    "Evicted the NeoFormRuntime work cache, unused for 7+ days " +
                        "(freed ${McSourcesCache.formatBytes(result.evictedWorkCacheBytes)}).",
                )
            }
            appendLine()
            appendLine("How to inspect this jar:")
            appendLine("  - Grep source files: mixin_list_jar_entries(jarPath=\"$jarPathStr\", pathPrefix=\"$pathPrefix\", regexPattern=\"...\")")
            appendLine("  - List files in a package: mixin_list_jar_entries(jarPath=\"$jarPathStr\", pathPrefix=\"$pathPrefix\")")
            val entry = readExample ?: "<path from the listing>"
            appendLine("  - Read a file: mixin_get_dep_source(jarPath=\"$jarPathStr\", entry=\"$entry\", startLine=1, endLine=120)")
            appendLine()
            appendCacheFootprintAndCleanupHint(result.cacheSummary, result.key)
        }
    }

    @McpToolHints(readOnlyHint = FALSE, destructiveHint = TRUE, openWorldHint = FALSE)
    @McpTool
    @McpDescription(
        "Inspects or cleans the on-demand Minecraft sources cache (~/.cache/mixinmcp/mc-sources/).\n\n" +
            "Actions:\n" +
            "- 'list' (default): shows every cached sources.jar (key, size, age, path) plus NeoFormRuntime's " +
            "work cache size and total disk usage.\n" +
            "- 'delete': deletes one cached sources.jar identified by `key` (e.g. '1.20.1-forge-47.3.0').\n" +
            "- 'clean_work': deletes NeoFormRuntime's internal download/work directory (nfrt-home) while " +
            "keeping all built sources.jar files. Useful right after generating sources to reclaim hundreds " +
            "of MB of intermediate files.\n" +
            "- 'clean_all': deletes all cached sources.jar files and NeoFormRuntime's work directory.\n" +
            "- 'cancel': stops the background generation for `key` and kills its NeoFormRuntime processes.\n\n" +
            "clean_work and clean_all return an error while any generation is running, since that run is " +
            "using the work directory; retry after it finishes or cancel it first.",
    )
    @Suppress("unused")
    suspend fun mixin_manage_mc_sources_cache(
        action: String = "list",
        key: String? = null,
    ): McpToolCallResult {
        val service = McSourcesService.getInstance()
        val cache = service.cache
        return withContext(Dispatchers.IO) {
            try {
                manageCache(service, cache, action, key)
            } catch (e: GenerationInProgressException) {
                McpToolCallResult.error(e.message ?: "A generation is in progress.")
            } catch (e: IOException) {
                // On Windows a jar the IDE still has open can't be deleted until the IDE releases it.
                McpToolCallResult.error("Cache operation '$action' failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun manageCache(
        service: McSourcesService,
        cache: McSourcesCache,
        action: String,
        key: String?,
    ): McpToolCallResult = when (action.trim().lowercase()) {
        "list" -> McpToolCallResult.text(formatCacheListing(cache.summary(), cache, service.runningGenerations()))
        "delete" -> deleteCachedJar(service, cache, key)
        "cancel" -> {
            val trimmedKey = key?.trim()?.takeIf { it.isNotEmpty() }
            val running = service.runningGenerations().map { it.key }
            when {
                trimmedKey == null -> McpToolCallResult.error(
                    "action=\"cancel\" requires `key`. Running generations: ${running.ifEmpty { listOf("(none)") }.joinToString(", ")}",
                )
                service.cancel(trimmedKey) -> McpToolCallResult.text(
                    "Cancelled the generation for '$trimmedKey'; its NeoFormRuntime processes are being stopped.",
                )
                else -> McpToolCallResult.error(
                    "No generation is running for '$trimmedKey'. Running generations: " +
                        running.ifEmpty { listOf("(none)") }.joinToString(", "),
                )
            }
        }
        "clean_work" -> {
            val cleaned = service.cleanWorkCache()
            val after = cache.summary()
            McpToolCallResult.text(
                "Cleaned NeoFormRuntime work cache (freed ${McSourcesCache.formatBytes(cleaned.freedBytes)}).\n" +
                    "Kept ${after.entries.size} cached sources.jar file(s) (${McSourcesCache.formatBytes(after.jarsTotalBytes)}).",
            )
        }
        "clean_all" -> {
            val cleaned = service.cleanAll()
            McpToolCallResult.text(
                "Cleared MC sources cache: deleted ${cleaned.deletedJars} jar(s) and NFRT work cache " +
                    "(freed ${McSourcesCache.formatBytes(cleaned.freedBytes)}).",
            )
        }
        else -> McpToolCallResult.error(
            "Unknown action '$action'. Expected one of: list, delete, clean_work, clean_all, cancel.",
        )
    }

    private fun deleteCachedJar(service: McSourcesService, cache: McSourcesCache, key: String?): McpToolCallResult {
        val trimmedKey = key?.trim()?.takeIf { it.isNotEmpty() }
            ?: return McpToolCallResult.error(
                "action=\"delete\" requires `key` (run action=\"list\" to see cached keys).",
            )
        try {
            McSourcesCache.validateKey(trimmedKey)
        } catch (e: IllegalArgumentException) {
            return McpToolCallResult.error(e.message ?: "Invalid key.")
        }
        val deleted = service.deleteEntry(trimmedKey)
        if (deleted.deletedJars == 0) {
            val existing = cache.listEntries().map { it.key }
            val available = if (existing.isEmpty()) "(cache is empty)" else existing.joinToString(", ")
            return McpToolCallResult.error(
                "No cached sources jar found for key '$trimmedKey'. Available keys: $available",
            )
        }
        val after = cache.summary()
        return McpToolCallResult.text(
            "Deleted '$trimmedKey' (freed ${McSourcesCache.formatBytes(deleted.freedBytes)}).\n" +
                "Remaining cache: ${after.entries.size} jar(s) (${McSourcesCache.formatBytes(after.jarsTotalBytes)}), " +
                "NFRT work cache ${McSourcesCache.formatBytes(after.workCacheBytes)}.",
        )
    }

    private fun formatRunning(
        outcome: McSourcesOutcome.Generating,
        timeoutMs: Long,
        title: String,
        activity: String,
        toolName: String,
    ): String {
        val status = outcome.status
        val elapsedS = (System.currentTimeMillis() - status.startedAtMs) / 1000
        return buildString {
            appendLine("=== $title still running: ${status.key} ===")
            val origin = if (outcome.startedByThisCall) "started by this call" else "joined a run already in progress"
            appendLine("Status: $activity, ${elapsedS}s elapsed ($origin).")
            appendLine("Latest step: ${status.latestOutput ?: "(none yet)"}")
            appendLine()
            appendLine(
                "The run continues in the background after this call returns" +
                    (if (timeoutMs > 0) " (waited ${timeoutMs / 1000}s)" else "") + ". Call $toolName " +
                    "again with the same arguments to keep " +
                    "waiting; it rejoins this run and returns the jarPath when it finishes. mixin_ide_status " +
                    "also reports progress. To stop it: mixin_manage_mc_sources_cache(action=\"cancel\", " +
                    "key=\"${status.key}\").",
            )
        }
    }

    private fun StringBuilder.appendCacheFootprintAndCleanupHint(summary: CacheSummary, currentKey: String) {
        appendLine(
            "Cache footprint: ${summary.entries.size} jar(s) (${McSourcesCache.formatBytes(summary.jarsTotalBytes)}), " +
                "NFRT work cache ${McSourcesCache.formatBytes(summary.workCacheBytes)}, " +
                "total ${McSourcesCache.formatBytes(summary.totalBytes)}.",
        )
        val cleanWork = if (summary.workCacheBytes > 0L) "action=\"clean_work\" to reclaim NFRT scratch space while keeping jars, " else ""
        if (summary.workCacheBytes >= WORK_CACHE_CLEANUP_THRESHOLD_BYTES || summary.entries.size >= 2) {
            appendLine(
                "Cleanup note: When you finish your cross-version research, call mixin_manage_mc_sources_cache with " +
                    "${cleanWork}action=\"delete\", key=\"$currentKey\", or action=\"clean_all\" if you no longer need " +
                    "the fetched sources.",
            )
        } else {
            appendLine(
                "Cleanup note: Jars untouched for 30 days and an NFRT work cache unused for 7 days are evicted " +
                    "automatically. To free disk space earlier, call mixin_manage_mc_sources_cache with " +
                    "${cleanWork}action=\"delete\", key=\"$currentKey\".",
            )
        }
    }

    private fun formatCacheListing(
        summary: CacheSummary,
        cache: McSourcesCache,
        running: List<GenerationStatus>,
    ): String {
        val now = System.currentTimeMillis()
        return buildString {
            appendLine("=== On-demand Minecraft sources cache ===")
            appendLine("Location: ${cache.cacheRoot.toAbsolutePath().toString().replace('\\', '/')}")
            appendLine(
                "Total: ${McSourcesCache.formatBytes(summary.totalBytes)} " +
                    "(${summary.entries.size} jar(s): ${McSourcesCache.formatBytes(summary.jarsTotalBytes)}; " +
                    "NFRT work cache: ${McSourcesCache.formatBytes(summary.workCacheBytes)})",
            )
            appendLine()
            if (summary.entries.isEmpty()) {
                appendLine("No cached sources jars.")
            } else {
                appendLine("Cached sources jars:")
                for (entry in summary.entries) {
                    val ageDays = Duration.ofMillis((now - entry.lastModifiedMs).coerceAtLeast(0L)).toDays()
                    val ageLabel = when (ageDays) {
                        0L -> "today"
                        1L -> "1 day ago"
                        else -> "$ageDays days ago"
                    }
                    val pathStr = entry.path.toAbsolutePath().toString().replace('\\', '/')
                    appendLine("  - ${entry.key} (${McSourcesCache.formatBytes(entry.sizeBytes)}, last used $ageLabel)")
                    appendLine("    $pathStr")
                }
            }
            if (running.isNotEmpty()) {
                appendLine()
                appendLine("Generating now:")
                running.forEach { appendLine("  - ${describeGeneration(it, now)}") }
            }
        }
    }

    companion object {
        fun describeGeneration(status: GenerationStatus, nowMs: Long = System.currentTimeMillis()): String =
            "${status.key} (${(nowMs - status.startedAtMs) / 1000}s elapsed; latest step: " +
                "${status.latestOutput ?: "(none yet)"})"
    }
}
