package dev.mixinmcp.tools.source

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.mixinmcp.cache.DecompilationCacheService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

private const val MAX_SCAN_FILE_BYTES: Long = 4L * 1024 * 1024
private const val SCAN_PARALLELISM: Int = 6

internal data class DepSearchExecution(
    val hits: List<DepSearchHit>,
    val preloadedContent: Map<String, String>,
    val timedOut: Boolean,
    val scannedFiles: Int,
    val sawMore: Boolean,
    val unreadableFilesByRoot: Map<String, Int>,
    val rootsScanned: Int,
    val rootsTotal: Int,
    val unfinishedRoots: List<String>,
    val usedIndex: Boolean,
    val patternHasTrigrams: Boolean,
    val indexBuildsPending: Int,
    val indexCandidatesFilteredOut: Int,
    val tiers: SearchTiers,
)

private data class RootSlot(
    val rootIdx: Int,
    val info: SourceRootInfo,
    val rootUrlTrimmed: String,
    val diskJarPath: String?,
    val jarSubPrefix: String,
    val diskDir: Path?,
    /** Null for directories that aren't decompiled-cache roots; only cache roots get a shard. */
    val dirShardStamp: String?,
)

private data class FileHits(
    val rootIdx: Int,
    val filePath: String,
    val url: String,
    val hits: List<DepSearchHit>,
    val content: String?,
)

private class RootScan(val files: List<FileHits>, val complete: Boolean)

private class ScanContext(
    val regex: CompiledRegex,
    val trigramQuery: RegexLiteralPlanner.TrigramQuery,
    val prefilter: RegexLiteralPlanner.LiteralPrefilter,
    val matchesMask: (String) -> Boolean,
    val normalizedPathPrefix: String?,
    val scanCap: Int,
    val startTime: Long,
    val timeout: Long,
    val captureContextContent: Boolean,
) {
    val timedOut = AtomicBoolean(false)
    val scannedFiles = AtomicInteger(0)
    val usedIndex = AtomicBoolean(false)
    val indexBuildsPending = AtomicInteger(0)
    val indexCandidatesFilteredOut = AtomicInteger(0)

    fun deadlinePassed(): Boolean {
        if (timedOut.get()) return true
        if (System.currentTimeMillis() - startTime > timeout) {
            timedOut.set(true)
            return true
        }
        return false
    }

    fun passesPathFilters(relPath: String): Boolean =
        (normalizedPathPrefix == null || relPath.startsWith(normalizedPathPrefix, ignoreCase = true)) &&
            matchesMask(relPath)

    /** Candidates that survive [skipPath] and the path filters, counting the ones the filters reject. */
    fun filterCandidates(candidates: List<String>, skipPath: (String) -> Boolean): List<String> {
        val kept: MutableList<String> = ArrayList(candidates.size)
        var rejected = 0
        for (relPath in candidates) {
            if (skipPath(relPath)) continue
            if (passesPathFilters(relPath)) kept.add(relPath) else rejected++
        }
        if (rejected > 0) indexCandidatesFilteredOut.addAndGet(rejected)
        return kept
    }

    fun recordLookup(lookup: TrigramShardIndex.Lookup?) {
        when (lookup) {
            is TrigramShardIndex.Lookup.Candidates -> usedIndex.set(true)
            TrigramShardIndex.Lookup.Building -> indexBuildsPending.incrementAndGet()
            TrigramShardIndex.Lookup.Unavailable, null -> {}
        }
    }
}

/** Hit cap and deadline for one root. Each root is capped on its own so parallel roots can't crowd out earlier ones. */
private class RootBudget(private val ctx: ScanContext) {
    var hits: Int = 0
    var stoppedByDeadline: Boolean = false
        private set

    fun canContinue(): Boolean {
        if (hits >= ctx.scanCap) return false
        if (ctx.deadlinePassed()) {
            stoppedByDeadline = true
            return false
        }
        return true
    }
}

/**
 * Runs a parallel dependency regex search outside the IntelliJ read lock. When the regex has usable
 * trigrams and a root's persistent shard ([TrigramShardIndex]) is built, we open only the shard's
 * candidate files; otherwise we scan the root directly with whole-file literal pre-filtering, and a
 * missing shard is built in the background for later searches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun scanDependencies(
    project: Project,
    regex: CompiledRegex,
    regexPattern: String,
    caseSensitive: Boolean,
    fileMask: String?,
    matchesMask: (String) -> Boolean,
    normalizedPathPrefix: String?,
    rootsMode: String,
    maxResults: Int,
    timeout: Long,
    captureContextContent: Boolean,
): DepSearchExecution {
    val searchPlan: RegexLiteralPlanner.Plan = RegexLiteralPlanner.plan(regexPattern, caseSensitive)
    val ctx = ScanContext(
        regex = regex,
        trigramQuery = searchPlan.trigramQuery,
        prefilter = searchPlan.prefilter,
        matchesMask = matchesMask,
        normalizedPathPrefix = normalizedPathPrefix,
        scanCap = if (maxResults == Int.MAX_VALUE) maxResults else maxResults + 1,
        startTime = System.currentTimeMillis(),
        timeout = timeout,
        captureContextContent = captureContextContent,
    )

    val (tiers: SearchTiers, slotsByTier: List<List<RootSlot>>) = readAction {
        val t: SearchTiers = searchTiers(project, collectSourceRootsWithMetadata(project))
        val cacheService: DecompilationCacheService = DecompilationCacheService.getInstance(project)
        val slots: List<List<RootSlot>> = t.tiersFor(rootsMode).map { tierRoots ->
            tierRoots.mapIndexed { rootIdx, info -> buildRootSlot(rootIdx, info, cacheService) }
        }
        t to slots
    }

    val rootsTotal: Int = slotsByTier.sumOf { it.size }
    val allHits: MutableList<DepSearchHit> = mutableListOf()
    val preloadedContent: MutableMap<String, String> = HashMap()
    val unreadableByRoot: MutableMap<String, Int> = linkedMapOf()
    var rootsScanned = 0
    val unfinishedRoots: MutableList<String> = mutableListOf()
    val pathsHitEarlier: MutableSet<String> = HashSet()

    withContext(Dispatchers.IO.limitedParallelism(SCAN_PARALLELISM)) {
        for (tierSlots in slotsByTier) {
            if (allHits.size >= ctx.scanCap) break
            if (ctx.deadlinePassed()) {
                tierSlots.mapTo(unfinishedRoots) { it.info.typeLabel }
                continue
            }

            val tierUnreadable = ConcurrentHashMap<String, AtomicInteger>()
            // Later tiers skip paths already matched so a class present both as -sources.jar
            // and in the decompiled cache is reported once, from the better root.
            val skipEarlier: Set<String> = HashSet(pathsHitEarlier)
            val skipPath: (String) -> Boolean = { it in skipEarlier }

            val tierResults: List<Pair<RootSlot, RootScan>> = coroutineScope {
                tierSlots.map { slot ->
                    async {
                        if (ctx.deadlinePassed()) return@async slot to RootScan(emptyList(), complete = false)
                        slot to scanRoot(slot, ctx, skipPath, tierUnreadable)
                    }
                }.awaitAll()
            }

            val merged: MutableList<FileHits> = mutableListOf()
            for ((slot, scan) in tierResults) {
                merged.addAll(scan.files)
                if (scan.complete) rootsScanned++ else unfinishedRoots.add(slot.info.typeLabel)
            }
            appendSortedFileHits(merged, allHits, preloadedContent, ctx.scanCap)

            for ((label, count) in tierUnreadable) {
                unreadableByRoot.merge(label, count.get(), Int::plus)
            }
            for (hit in allHits) {
                pathsHitEarlier.add(hit.filePath)
            }
        }
    }

    return DepSearchExecution(
        hits = allHits.take(maxResults),
        preloadedContent = preloadedContent,
        timedOut = ctx.timedOut.get() || System.currentTimeMillis() - ctx.startTime > timeout,
        scannedFiles = ctx.scannedFiles.get(),
        sawMore = allHits.size > maxResults,
        unreadableFilesByRoot = unreadableByRoot,
        rootsScanned = rootsScanned,
        rootsTotal = rootsTotal,
        unfinishedRoots = unfinishedRoots,
        usedIndex = ctx.usedIndex.get(),
        patternHasTrigrams = searchPlan.trigramQuery is RegexLiteralPlanner.TrigramQuery.Clauses,
        indexBuildsPending = ctx.indexBuildsPending.get(),
        indexCandidatesFilteredOut = ctx.indexCandidatesFilteredOut.get(),
        tiers = tiers,
    )
}

private fun appendSortedFileHits(
    results: List<FileHits>,
    allHits: MutableList<DepSearchHit>,
    preloadedContent: MutableMap<String, String>,
    scanCap: Int,
) {
    if (results.isEmpty() || allHits.size >= scanCap) return
    val sorted: List<FileHits> = results.sortedWith(compareBy<FileHits> { it.rootIdx }.thenBy { it.filePath })
    for (fh in sorted) {
        if (allHits.size >= scanCap) break
        val remaining: Int = scanCap - allHits.size
        val toAdd: List<DepSearchHit> = if (fh.hits.size <= remaining) fh.hits else fh.hits.take(remaining)
        allHits.addAll(toAdd)
        if (fh.content != null && toAdd.isNotEmpty()) {
            preloadedContent.putIfAbsent(fh.url, fh.content)
        }
    }
}

private fun buildRootSlot(rootIdx: Int, info: SourceRootInfo, cacheService: DecompilationCacheService): RootSlot {
    val root: VirtualFile = info.root
    val url: String = root.url

    var diskJarPath: String? = null
    var jarSubPrefix = ""
    var diskDir: Path? = null
    var dirShardStamp: String? = null

    if (url.startsWith("jar://")) {
        val rawPath: String = root.path
        val jarPart: String = rawPath.substringBefore("!/")
        if (File(jarPart).isFile) {
            diskJarPath = jarPart
            val sub: String = rawPath.substringAfter("!/", "").trim('/')
            jarSubPrefix = if (sub.isEmpty()) "" else "$sub/"
        }
    } else if (root.isInLocalFileSystem) {
        val p: Path = Path.of(root.path)
        if (Files.isDirectory(p)) {
            diskDir = p
            // Other directories can change at any time, and a shard is never revalidated against its files.
            dirShardStamp = cacheService.cachedRootInfo(root)?.let {
                TrigramShardIndex.cacheDirectoryStamp(p, it.manifestCreatedAt)
            }
        }
    }

    return RootSlot(
        rootIdx = rootIdx,
        info = info,
        rootUrlTrimmed = url.trimEnd('/'),
        diskJarPath = diskJarPath,
        jarSubPrefix = jarSubPrefix,
        diskDir = diskDir,
        dirShardStamp = dirShardStamp,
    )
}

private suspend fun scanRoot(
    slot: RootSlot,
    ctx: ScanContext,
    skipPath: (String) -> Boolean,
    unreadable: ConcurrentHashMap<String, AtomicInteger>,
): RootScan {
    val countUnreadable: (Int) -> Unit = { n ->
        unreadable.computeIfAbsent(slot.info.typeLabel) { AtomicInteger() }.addAndGet(n)
    }
    if (slot.diskJarPath != null) return scanJarRoot(slot, slot.diskJarPath, ctx, skipPath, countUnreadable)
    if (slot.diskDir != null) return scanDirRoot(slot, slot.diskDir, ctx, skipPath, countUnreadable)

    // Synthetic or in-memory VFS root (e.g. test fixtures).
    return readAction {
        val hits: MutableList<DepSearchHit> = mutableListOf()
        val localScanned = IntArray(1)
        val localUnreadable = IntArray(1)
        collectRegexHits(
            vf = slot.info.root,
            root = slot.info.root,
            regex = ctx.regex,
            matchesMask = ctx.matchesMask,
            hits = hits,
            maxResults = ctx.scanCap,
            startTime = ctx.startTime,
            timeout = ctx.timeout,
            rootLabel = slot.info.typeLabel,
            pathPrefix = ctx.normalizedPathPrefix,
            skipPath = skipPath,
            scannedFiles = localScanned,
            unreadableFiles = localUnreadable,
            prefilter = ctx.prefilter,
        )
        ctx.scannedFiles.addAndGet(localScanned[0])
        if (localUnreadable[0] > 0) countUnreadable(localUnreadable[0])
        val files: List<FileHits> = hits.groupBy { it.filePath }.map { (fp, list) ->
            FileHits(slot.rootIdx, fp, list.first().url, list, content = null)
        }
        RootScan(files, complete = hits.size >= ctx.scanCap || !ctx.deadlinePassed())
    }
}

private fun scanJarRoot(
    slot: RootSlot,
    jarPath: String,
    ctx: ScanContext,
    skipPath: (String) -> Boolean,
    countUnreadable: (Int) -> Unit,
): RootScan {
    val out: MutableList<FileHits> = mutableListOf()
    val budget = RootBudget(ctx)
    val session: CompiledRegex.LineSession = ctx.regex.newLineSession()
    val subPrefix: String = slot.jarSubPrefix

    val lookup: TrigramShardIndex.Lookup? = (ctx.trigramQuery as? RegexLiteralPlanner.TrigramQuery.Clauses)
        ?.let { TrigramShardIndex.lookupJar(jarPath, subPrefix, it) }
    ctx.recordLookup(lookup)
    val candidates: List<String>? = (lookup as? TrigramShardIndex.Lookup.Candidates)
        ?.let { ctx.filterCandidates(it.relPaths, skipPath) }
    if (candidates != null && candidates.isEmpty()) return RootScan(out, complete = true)

    val zip: ZipFile = try {
        ZipFile(File(jarPath))
    } catch (_: Exception) {
        countUnreadable(1)
        return RootScan(out, complete = true)
    }
    zip.use { zf ->
        if (candidates != null) {
            for (relPath in candidates) {
                if (!budget.canContinue()) break
                val entry = zf.getEntry(subPrefix + relPath) ?: continue
                val bytes: ByteArray = try {
                    zf.getInputStream(entry).use { it.readAllBytes() }
                } catch (_: Exception) {
                    countUnreadable(1)
                    continue
                }
                processBytesIntoHits(bytes, slot, relPath, session, ctx, budget, out)
            }
            return RootScan(out, complete = !budget.stoppedByDeadline)
        }

        val entries = zf.entries()
        while (entries.hasMoreElements()) {
            if (!budget.canContinue()) break
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val rawName: String = entry.name
            val relPath: String = if (subPrefix.isNotEmpty()) {
                if (!rawName.startsWith(subPrefix)) continue
                rawName.removePrefix(subPrefix)
            } else {
                rawName
            }
            if (isBinaryEntryName(relPath.substringAfterLast('/'))) continue
            if (entry.size > MAX_SCAN_FILE_BYTES) continue
            if (skipPath(relPath) || !ctx.passesPathFilters(relPath)) continue

            val bytes: ByteArray = try {
                zf.getInputStream(entry).use { it.readAllBytes() }
            } catch (_: Exception) {
                countUnreadable(1)
                continue
            }
            processBytesIntoHits(bytes, slot, relPath, session, ctx, budget, out)
        }
    }
    return RootScan(out, complete = !budget.stoppedByDeadline)
}

private fun scanDirRoot(
    slot: RootSlot,
    rootDir: Path,
    ctx: ScanContext,
    skipPath: (String) -> Boolean,
    countUnreadable: (Int) -> Unit,
): RootScan {
    val out: MutableList<FileHits> = mutableListOf()
    val budget = RootBudget(ctx)
    val session: CompiledRegex.LineSession = ctx.regex.newLineSession()

    val query: RegexLiteralPlanner.TrigramQuery.Clauses? = ctx.trigramQuery as? RegexLiteralPlanner.TrigramQuery.Clauses
    val lookup: TrigramShardIndex.Lookup? = if (query != null && slot.dirShardStamp != null) {
        TrigramShardIndex.lookupDirectory(rootDir, slot.dirShardStamp, query)
    } else {
        null
    }
    ctx.recordLookup(lookup)

    if (lookup is TrigramShardIndex.Lookup.Candidates) {
        for (relPath in ctx.filterCandidates(lookup.relPaths, skipPath)) {
            if (!budget.canContinue()) break
            val bytes: ByteArray = try {
                Files.readAllBytes(rootDir.resolve(relPath))
            } catch (_: Exception) {
                countUnreadable(1)
                continue
            }
            processBytesIntoHits(bytes, slot, relPath, session, ctx, budget, out)
        }
        return RootScan(out, complete = !budget.stoppedByDeadline)
    }

    val prefix: String? = ctx.normalizedPathPrefix
    try {
        Files.walkFileTree(
            rootDir,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!budget.canContinue()) return FileVisitResult.TERMINATE
                    if (prefix != null && dir != rootDir) {
                        val dirRel: String = rootDir.relativize(dir).toString().replace('\\', '/') + "/"
                        if (!directoryMayContainPrefix(dirRel, prefix)) return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!budget.canContinue()) return FileVisitResult.TERMINATE
                    val fileName: String = file.fileName?.toString() ?: return FileVisitResult.CONTINUE
                    if (isBinaryEntryName(fileName)) return FileVisitResult.CONTINUE
                    if (attrs.size() > MAX_SCAN_FILE_BYTES) return FileVisitResult.CONTINUE
                    val relPath: String = rootDir.relativize(file).toString().replace('\\', '/')
                    if (skipPath(relPath) || !ctx.passesPathFilters(relPath)) return FileVisitResult.CONTINUE

                    val bytes: ByteArray = try {
                        Files.readAllBytes(file)
                    } catch (_: Exception) {
                        countUnreadable(1)
                        return FileVisitResult.CONTINUE
                    }
                    processBytesIntoHits(bytes, slot, relPath, session, ctx, budget, out)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    countUnreadable(1)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    } catch (_: Exception) {
        countUnreadable(1)
    }
    return RootScan(out, complete = !budget.stoppedByDeadline)
}

private fun processBytesIntoHits(
    bytes: ByteArray,
    slot: RootSlot,
    relPath: String,
    session: CompiledRegex.LineSession,
    ctx: ScanContext,
    budget: RootBudget,
    out: MutableList<FileHits>,
) {
    if (looksBinary(bytes)) return
    val content = String(bytes, StandardCharsets.UTF_8)
    ctx.scannedFiles.incrementAndGet()
    val remaining: Int = ctx.scanCap - budget.hits
    if (remaining <= 0) return
    val url = "${slot.rootUrlTrimmed}/$relPath"
    val hits: List<DepSearchHit> = matchContentLines(
        content = content,
        url = url,
        filePath = relPath,
        rootLabel = slot.info.typeLabel,
        session = session,
        prefilter = ctx.prefilter,
        maxHits = remaining,
    )
    if (hits.isNotEmpty()) {
        budget.hits += hits.size
        out.add(
            FileHits(
                rootIdx = slot.rootIdx,
                filePath = relPath,
                url = url,
                hits = hits,
                content = if (ctx.captureContextContent) content else null,
            ),
        )
    }
}

/**
 * Matches [session] line-by-line against [content] without splitting the whole file into a list.
 * Runs [prefilter] first; when a single required [RegexLiteralPlanner.LiteralPrefilter.jumpLiteral]
 * exists, jumps directly between candidate lines via `indexOf` instead of testing every line.
 */
internal fun matchContentLines(
    content: String,
    url: String,
    filePath: String,
    rootLabel: String,
    session: CompiledRegex.LineSession,
    prefilter: RegexLiteralPlanner.LiteralPrefilter,
    maxHits: Int,
): List<DepSearchHit> {
    if (maxHits <= 0) return emptyList()
    if (!prefilter.matches(content)) return emptyList()

    val jumpLit: String? = prefilter.jumpLiteral
    return if (jumpLit != null) {
        matchLinesViaJumpLiteral(content, url, filePath, rootLabel, session, jumpLit, prefilter.ignoreCase, maxHits)
    } else {
        matchAllLinesWithSession(content, url, filePath, rootLabel, session, maxHits)
    }
}

private fun matchLinesViaJumpLiteral(
    content: String,
    url: String,
    filePath: String,
    rootLabel: String,
    session: CompiledRegex.LineSession,
    jumpLit: String,
    ignoreCase: Boolean,
    maxHits: Int,
): List<DepSearchHit> {
    val hits: MutableList<DepSearchHit> = mutableListOf()
    val len: Int = content.length
    var searchFrom = 0
    var currentLineNum = 1
    var countedUpTo = 0
    var lastTestedLineStart = -1

    while (searchFrom < len && hits.size < maxHits) {
        val idx: Int = content.indexOf(jumpLit, searchFrom, ignoreCase = ignoreCase)
        if (idx < 0) break

        var lineStart: Int = idx
        while (lineStart > 0 && content[lineStart - 1] != '\n' && content[lineStart - 1] != '\r') {
            lineStart--
        }
        var lineEnd: Int = idx + jumpLit.length
        while (lineEnd < len && content[lineEnd] != '\n' && content[lineEnd] != '\r') {
            lineEnd++
        }

        if (lineStart != lastTestedLineStart) {
            currentLineNum += countLineBreaks(content, countedUpTo, lineStart)
            countedUpTo = lineStart
            lastTestedLineStart = lineStart

            val highlighted: String? = session.matchAndHighlight(content, lineStart, lineEnd)
            if (highlighted != null) {
                hits.add(
                    DepSearchHit(
                        url = url,
                        filePath = filePath,
                        rootLabel = rootLabel,
                        lineNum = currentLineNum,
                        highlighted = highlighted,
                    ),
                )
            }
        }

        searchFrom = if (lineEnd < len && content[lineEnd] == '\r' && lineEnd + 1 < len && content[lineEnd + 1] == '\n') {
            lineEnd + 2
        } else if (lineEnd < len) {
            lineEnd + 1
        } else {
            len
        }
    }
    return hits
}

private fun matchAllLinesWithSession(
    content: String,
    url: String,
    filePath: String,
    rootLabel: String,
    session: CompiledRegex.LineSession,
    maxHits: Int,
): List<DepSearchHit> {
    val hits: MutableList<DepSearchHit> = mutableListOf()
    val len: Int = content.length
    var lineStart = 0
    var lineNum = 1

    while (lineStart <= len && hits.size < maxHits) {
        var lineEnd: Int = lineStart
        while (lineEnd < len && content[lineEnd] != '\n' && content[lineEnd] != '\r') {
            lineEnd++
        }
        val highlighted: String? = session.matchAndHighlight(content, lineStart, lineEnd)
        if (highlighted != null) {
            hits.add(
                DepSearchHit(
                    url = url,
                    filePath = filePath,
                    rootLabel = rootLabel,
                    lineNum = lineNum,
                    highlighted = highlighted,
                ),
            )
        }
        if (lineEnd >= len) break
        lineStart = if (content[lineEnd] == '\r' && lineEnd + 1 < len && content[lineEnd + 1] == '\n') {
            lineEnd + 2
        } else {
            lineEnd + 1
        }
        lineNum++
    }
    return hits
}

private fun countLineBreaks(s: String, from: Int, to: Int): Int {
    var count = 0
    var i: Int = from
    while (i < to) {
        val c: Char = s[i]
        if (c == '\n') {
            count++
        } else if (c == '\r') {
            count++
            if (i + 1 < to && s[i + 1] == '\n') i++
        }
        i++
    }
    return count
}
