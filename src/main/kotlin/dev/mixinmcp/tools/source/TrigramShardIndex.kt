package dev.mixinmcp.tools.source

import com.intellij.util.concurrency.AppExecutorUtil
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.lang.ref.SoftReference
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.zip.ZipFile

/**
 * Persistent trigram inverted index stored outside the VFS-watched
 * decompiled cache (`~/.cache/mixinmcp/trigrams/`).
 *
 * Indexes all text files (`.java`, `.kt`, `.json`, `.toml`, etc.) in both jar archives and
 * unpacked decompiled-cache directories. Needed because IntelliJ 2026.2's
 * `JavaLibrarySourcesExcludingIndexFilterExtension` excludes library `.java` files from
 * IntelliJ's built-in `TrigramIndex`, and `MixinDecompiledRootsProvider` excludes non-source
 * resources from indexing.
 *
 * Uses positional [FileChannel] reads rather than `MappedByteBuffer` so shard files never hold
 * lingering file locks on Windows.
 */
internal object TrigramShardIndex {

    private const val MAGIC: Int = 0x4D4D5432 // "MMT2"
    private const val VERSION: Int = 1
    private const val HEADER_BYTES: Int = 40
    private const val DICT_ENTRY_BYTES: Int = 12
    private const val MAX_INDEXED_FILE_BYTES: Long = 4L * 1024 * 1024

    val defaultShardDir: Path by lazy {
        Path.of(System.getProperty("user.home"), ".cache", "mixinmcp", "trigrams")
    }

    private class CachedShard(
        val fileCount: Int,
        val trigramCount: Int,
        val pathTableOffset: Long,
        val postingsOffset: Long,
        val dictBytes: ByteBuffer,
    )

    private val buildLocks = ConcurrentHashMap<Path, Any>()
    private val shardCache = ConcurrentHashMap<Path, SoftReference<CachedShard>>()

    // Builds run off the request path: a large jar can take longer to index than a search's timeout,
    // and a build abandoned at the deadline would restart from scratch on every search.
    private val buildExecutor: Executor =
        AppExecutorUtil.createBoundedApplicationPoolExecutor("MixinMCP trigram shard builder", 2)
    private val scheduledBuilds: MutableSet<Path> = ConcurrentHashMap.newKeySet()
    private val failedBuilds: MutableSet<Path> = ConcurrentHashMap.newKeySet()

    sealed interface Lookup {
        /** Root-relative forward-slash paths of the files that may match. */
        data class Candidates(val relPaths: List<String>) : Lookup

        /** No usable shard yet; one is building in the background, so scan the root directly this time. */
        data object Building : Lookup

        /** The root can't be indexed (unreadable, or its build failed); scan it directly. */
        data object Unavailable : Lookup
    }

    val pendingBuildCount: Int
        get() = scheduledBuilds.size

    /**
     * Looks up candidates in the shard for an unpacked directory [rootDir]. [contentStamp] must change
     * whenever the directory's contents may have changed, since the shard is never revalidated
     * against the files themselves.
     */
    fun lookupDirectory(
        rootDir: Path,
        contentStamp: String,
        query: RegexLiteralPlanner.TrigramQuery.Clauses,
        shardDir: Path = defaultShardDir,
    ): Lookup {
        val shardPath: Path = shardPathForDirectory(rootDir, contentStamp, shardDir)
        return lookup(shardPath, query) { buildDirectoryShard(rootDir, shardPath) }
    }

    /**
     * Looks up candidates in the shard for a jar/zip archive at [jarPath], optionally scoped to
     * [subPrefix] (e.g. `"java.base/"` inside JDK `src.zip`). Shards are keyed by
     * `(jarPath, size, mtime, subPrefix)`, so a rebuilt jar gets a fresh shard.
     */
    fun lookupJar(
        jarPath: String,
        subPrefix: String,
        query: RegexLiteralPlanner.TrigramQuery.Clauses,
        shardDir: Path = defaultShardDir,
    ): Lookup {
        val jarFile = File(jarPath)
        if (!jarFile.isFile) return Lookup.Unavailable
        val shardPath: Path = shardPathForJar(jarFile, subPrefix, shardDir)
        return lookup(shardPath, query) { buildJarShard(jarFile, subPrefix, shardPath) }
    }

    fun scheduleDirectoryBuild(rootDir: Path, contentStamp: String, shardDir: Path = defaultShardDir) {
        val shardPath: Path = shardPathForDirectory(rootDir, contentStamp, shardDir)
        if (!Files.isRegularFile(shardPath)) scheduleBuild(shardPath) { buildDirectoryShard(rootDir, shardPath) }
    }

    private fun lookup(
        shardPath: Path,
        query: RegexLiteralPlanner.TrigramQuery.Clauses,
        build: () -> Boolean,
    ): Lookup {
        if (shardPath in failedBuilds) return Lookup.Unavailable
        if (query.branches.any { extractBranchTrigrams(it).isEmpty() }) return Lookup.Unavailable
        if (shardPath !in scheduledBuilds) {
            queryShard(shardPath, query)?.let { return Lookup.Candidates(it) }
            // Missing or unreadable. Delete a corrupt file so the build doesn't accept its header as valid.
            shardCache.remove(shardPath)
            runCatching { Files.deleteIfExists(shardPath) }
        }
        scheduleBuild(shardPath, build)
        return if (shardPath in failedBuilds) Lookup.Unavailable else Lookup.Building
    }

    private fun scheduleBuild(shardPath: Path, build: () -> Boolean) {
        if (!scheduledBuilds.add(shardPath)) return
        try {
            buildExecutor.execute {
                try {
                    if (!build()) failedBuilds.add(shardPath)
                } catch (_: Exception) {
                    failedBuilds.add(shardPath)
                } finally {
                    scheduledBuilds.remove(shardPath)
                }
            }
        } catch (_: Exception) {
            scheduledBuilds.remove(shardPath)
        }
    }

    /**
     * Content stamp for a decompiled-cache directory. The Gradle plugin writes a cache directory in
     * place and records it in the manifest only once complete, keeping [manifestCreatedAt] across
     * later syncs and replacing it on every fresh write. The directory's creation time catches a
     * directory another project regenerated after this project's manifest entry was written.
     */
    fun cacheDirectoryStamp(rootDir: Path, manifestCreatedAt: Long): String? = try {
        val created: Long = Files.readAttributes(rootDir, BasicFileAttributes::class.java).creationTime().toMillis()
        "$manifestCreatedAt:$created"
    } catch (_: Exception) {
        null
    }

    fun shardPathForDirectory(rootDir: Path, contentStamp: String, shardDir: Path = defaultShardDir): Path {
        val sig = "${rootDir.toAbsolutePath().normalize()}|$contentStamp"
        return shardDir.resolve("dir-${sha256Hex(sig)}.idx")
    }

    fun shardPathForJar(jarFile: File, subPrefix: String, shardDir: Path = defaultShardDir): Path {
        val sig = "${jarFile.absolutePath}|${jarFile.length()}|${jarFile.lastModified()}|$subPrefix"
        return shardDir.resolve("jar-${sha256Hex(sig)}.idx")
    }

    fun buildDirectoryShard(rootDir: Path, shardPath: Path): Boolean {
        val lock: Any = buildLocks.computeIfAbsent(shardPath) { Any() }
        synchronized(lock) {
            if (isValidShardHeader(shardPath)) return true
            val paths: MutableList<String> = mutableListOf()
            val postings: MutableMap<Int, IntList> = HashMap()

            try {
                Files.walkFileTree(
                    rootDir,
                    object : SimpleFileVisitor<Path>() {
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            val fileName: String = file.fileName?.toString() ?: return FileVisitResult.CONTINUE
                            if (isBinaryEntryName(fileName)) return FileVisitResult.CONTINUE
                            if (attrs.size() > MAX_INDEXED_FILE_BYTES) return FileVisitResult.CONTINUE
                            val bytes: ByteArray = try {
                                Files.readAllBytes(file)
                            } catch (_: Exception) {
                                return FileVisitResult.CONTINUE
                            }
                            if (looksBinary(bytes)) return FileVisitResult.CONTINUE
                            val docId: Int = paths.size
                            val relPath: String = rootDir.relativize(file).toString().replace('\\', '/')
                            paths.add(relPath)
                            indexText(String(bytes, StandardCharsets.UTF_8), docId, postings)
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                            FileVisitResult.CONTINUE
                    },
                )
            } catch (_: Exception) {
                return false
            }

            return writeShardAtomically(shardPath, paths, postings)
        }
    }

    fun buildJarShard(jarFile: File, subPrefix: String, shardPath: Path): Boolean {
        val lock: Any = buildLocks.computeIfAbsent(shardPath) { Any() }
        synchronized(lock) {
            if (isValidShardHeader(shardPath)) return true
            val paths: MutableList<String> = mutableListOf()
            val postings: MutableMap<Int, IntList> = HashMap()

            try {
                ZipFile(jarFile).use { zf ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        val rawName: String = entry.name
                        val relPath: String = if (subPrefix.isNotEmpty()) {
                            if (!rawName.startsWith(subPrefix)) continue
                            rawName.removePrefix(subPrefix)
                        } else {
                            rawName
                        }
                        val fileName: String = relPath.substringAfterLast('/')
                        if (isBinaryEntryName(fileName)) continue
                        if (entry.size > MAX_INDEXED_FILE_BYTES) continue
                        val bytes: ByteArray = try {
                            zf.getInputStream(entry).use { it.readAllBytes() }
                        } catch (_: Exception) {
                            continue
                        }
                        if (looksBinary(bytes)) continue
                        val docId: Int = paths.size
                        paths.add(relPath)
                        indexText(String(bytes, StandardCharsets.UTF_8), docId, postings)
                    }
                }
            } catch (_: Exception) {
                return false
            }

            return writeShardAtomically(shardPath, paths, postings)
        }
    }

    private fun indexText(content: String, docId: Int, postings: MutableMap<Int, IntList>) {
        val trigrams: IntArray = extractUniqueTrigrams(content)
        for (tg in trigrams) {
            postings.computeIfAbsent(tg) { IntList() }.add(docId)
        }
    }

    internal fun queryShard(
        shardPath: Path,
        query: RegexLiteralPlanner.TrigramQuery.Clauses,
    ): List<String>? {
        val cached: CachedShard = loadShardHeader(shardPath) ?: return null
        if (cached.fileCount == 0 || cached.trigramCount == 0) return emptyList()

        // Binary-search all branches against the in-memory dictionary first;
        // if no branch matches, we return without opening the shard file on disk.
        val branchEntries: MutableList<List<Pair<Int, Int>>> = ArrayList(query.branches.size)
        for (branch in query.branches) {
            val branchTrigrams: IntArray = extractBranchTrigrams(branch)
            if (branchTrigrams.isEmpty()) return null
            val entries = ArrayList<Pair<Int, Int>>(branchTrigrams.size)
            var branchMissed = false
            for (tg in branchTrigrams) {
                val idx: Int = binarySearchTrigram(cached.dictBytes, cached.trigramCount, tg)
                if (idx < 0) {
                    branchMissed = true
                    break
                }
                val off: Int = cached.dictBytes.getInt(idx * DICT_ENTRY_BYTES + 4)
                val len: Int = cached.dictBytes.getInt(idx * DICT_ENTRY_BYTES + 8)
                entries.add(off to len)
            }
            if (!branchMissed) {
                entries.sortBy { it.second }
                branchEntries.add(entries)
            }
        }
        if (branchEntries.isEmpty()) return emptyList()

        return try {
            FileChannel.open(shardPath, StandardOpenOption.READ).use { ch ->
                val matchedDocs = LinkedHashSet<Int>()
                for (entries in branchEntries) {
                    var current: IntArray = readPostingList(ch, cached.postingsOffset + entries[0].first, entries[0].second)
                    for (i in 1 until entries.size) {
                        if (current.isEmpty()) break
                        val next: IntArray = readPostingList(ch, cached.postingsOffset + entries[i].first, entries[i].second)
                        current = intersectSorted(current, next)
                    }
                    for (id in current) matchedDocs.add(id)
                }
                if (matchedDocs.isEmpty()) return emptyList()

                val sortedDocs: IntArray = matchedDocs.toIntArray()
                Arrays.sort(sortedDocs)
                readPathsForDocIds(ch, cached.pathTableOffset, cached.fileCount, sortedDocs)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadShardHeader(shardPath: Path): CachedShard? {
        shardCache[shardPath]?.get()?.let { return it }
        return try {
            FileChannel.open(shardPath, StandardOpenOption.READ).use { ch ->
                val headerBuf = ByteBuffer.allocate(HEADER_BYTES)
                if (!readFully(ch, headerBuf, 0L)) return null
                headerBuf.flip()
                val magic: Int = headerBuf.int
                val version: Int = headerBuf.int
                if (magic != MAGIC || version != VERSION) return null
                val fileCount: Int = headerBuf.int
                val trigramCount: Int = headerBuf.int
                val pathTableOffset: Long = headerBuf.long
                val trigramDictOffset: Long = headerBuf.long
                val postingsOffset: Long = headerBuf.long

                val dictBytes: ByteBuffer = if (trigramCount > 0) {
                    val buf = ByteBuffer.allocate(trigramCount * DICT_ENTRY_BYTES)
                    if (!readFully(ch, buf, trigramDictOffset)) return null
                    buf.flip()
                    buf
                } else {
                    ByteBuffer.allocate(0)
                }
                val loaded = CachedShard(fileCount, trigramCount, pathTableOffset, postingsOffset, dictBytes)
                shardCache[shardPath] = SoftReference(loaded)
                loaded
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun binarySearchTrigram(dictBytes: ByteBuffer, count: Int, key: Int): Int {
        var lo = 0
        var hi: Int = count - 1
        while (lo <= hi) {
            val mid: Int = (lo + hi) ushr 1
            val midVal: Int = dictBytes.getInt(mid * DICT_ENTRY_BYTES)
            when {
                midVal < key -> lo = mid + 1
                midVal > key -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun readPostingList(ch: FileChannel, offset: Long, byteLen: Int): IntArray {
        val buf = ByteBuffer.allocate(byteLen)
        if (!readFully(ch, buf, offset)) return IntArray(0)
        val arr: ByteArray = buf.array()
        val out = IntList()
        var pos = 0
        var prevDoc = 0
        while (pos < byteLen) {
            var shift = 0
            var delta = 0
            while (pos < byteLen) {
                val b: Int = arr[pos++].toInt()
                delta = delta or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            prevDoc += delta
            out.add(prevDoc)
        }
        return out.toIntArray()
    }

    private fun intersectSorted(a: IntArray, b: IntArray): IntArray {
        val out = IntList(minOf(a.size, b.size))
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            val va: Int = a[i]
            val vb: Int = b[j]
            when {
                va == vb -> {
                    out.add(va)
                    i++
                    j++
                }
                va < vb -> i++
                else -> j++
            }
        }
        return out.toIntArray()
    }

    private fun readPathsForDocIds(
        ch: FileChannel,
        pathTableOffset: Long,
        fileCount: Int,
        sortedDocs: IntArray,
    ): List<String> {
        val offsetsBytes: Long = (fileCount + 1).toLong() * 4L
        val pathDataStart: Long = pathTableOffset + offsetsBytes
        val out: MutableList<String> = ArrayList(sortedDocs.size)
        val pairBuf = ByteBuffer.allocate(8)
        for (docId in sortedDocs) {
            if (docId < 0 || docId >= fileCount) continue
            pairBuf.clear()
            if (!readFully(ch, pairBuf, pathTableOffset + docId.toLong() * 4L)) continue
            pairBuf.flip()
            val start: Int = pairBuf.int
            val end: Int = pairBuf.int
            val len: Int = end - start
            if (len <= 0) continue
            val strBuf = ByteBuffer.allocate(len)
            if (!readFully(ch, strBuf, pathDataStart + start.toLong())) continue
            out.add(String(strBuf.array(), StandardCharsets.UTF_8))
        }
        return out
    }

    private fun writeShardAtomically(
        shardPath: Path,
        paths: List<String>,
        postings: Map<Int, IntList>,
    ): Boolean {
        try {
            Files.createDirectories(shardPath.parent)
            val tmp: Path = shardPath.resolveSibling("${shardPath.fileName}.tmp.${Thread.currentThread().id}")
            try {
                val pathBytes: List<ByteArray> = paths.map { it.toByteArray(StandardCharsets.UTF_8) }
                val sortedTrigrams: IntArray = postings.keys.toIntArray()
                Arrays.sort(sortedTrigrams)

                val encodedPostings = Array<ByteArray>(sortedTrigrams.size) { i ->
                    encodeDeltaVarint(postings.getValue(sortedTrigrams[i]))
                }

                val pathOffsetsBytes: Long = (paths.size + 1).toLong() * 4L
                val totalPathBytes: Long = pathBytes.sumOf { it.size.toLong() }
                val pathTableOffset: Long = HEADER_BYTES.toLong()
                val trigramDictOffset: Long = pathTableOffset + pathOffsetsBytes + totalPathBytes
                val postingsOffset: Long = trigramDictOffset + sortedTrigrams.size.toLong() * DICT_ENTRY_BYTES

                DataOutputStream(BufferedOutputStream(Files.newOutputStream(tmp))).use { out ->
                    out.writeInt(MAGIC)
                    out.writeInt(VERSION)
                    out.writeInt(paths.size)
                    out.writeInt(sortedTrigrams.size)
                    out.writeLong(pathTableOffset)
                    out.writeLong(trigramDictOffset)
                    out.writeLong(postingsOffset)

                    var curPathOff = 0
                    for (pb in pathBytes) {
                        out.writeInt(curPathOff)
                        curPathOff += pb.size
                    }
                    out.writeInt(curPathOff)
                    for (pb in pathBytes) {
                        out.write(pb)
                    }

                    var curPostOff = 0
                    for (i in sortedTrigrams.indices) {
                        val len: Int = encodedPostings[i].size
                        out.writeInt(sortedTrigrams[i])
                        out.writeInt(curPostOff)
                        out.writeInt(len)
                        curPostOff += len
                    }

                    for (ep in encodedPostings) {
                        out.write(ep)
                    }
                }

                try {
                    Files.move(tmp, shardPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(tmp, shardPath, StandardCopyOption.REPLACE_EXISTING)
                }
                return true
            } catch (_: Exception) {
                runCatching { Files.deleteIfExists(tmp) }
                return false
            }
        } catch (_: Exception) {
            return false
        }
    }

    private fun isValidShardHeader(shardPath: Path): Boolean {
        if (!Files.isRegularFile(shardPath)) return false
        return try {
            FileChannel.open(shardPath, StandardOpenOption.READ).use { ch ->
                val buf = ByteBuffer.allocate(8)
                if (!readFully(ch, buf, 0L)) return false
                buf.flip()
                buf.int == MAGIC && buf.int == VERSION
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun encodeDeltaVarint(docIds: IntList): ByteArray {
        val out = ByteArray(docIds.size * 5)
        var pos = 0
        var prev = 0
        for (i in 0 until docIds.size) {
            val doc: Int = docIds[i]
            var delta: Int = doc - prev
            prev = doc
            while ((delta and 0x7F.inv()) != 0) {
                out[pos++] = ((delta and 0x7F) or 0x80).toByte()
                delta = delta ushr 7
            }
            out[pos++] = delta.toByte()
        }
        return out.copyOf(pos)
    }

    internal fun extractBranchTrigrams(words: List<String>): IntArray {
        val set = HashSet<Int>()
        for (word in words) {
            collectTrigramsInto(word, set)
        }
        return set.toIntArray()
    }

    internal fun extractUniqueTrigrams(text: CharSequence): IntArray {
        val set = HashSet<Int>()
        collectTrigramsInto(text, set)
        return set.toIntArray()
    }

    private fun collectTrigramsInto(text: CharSequence, out: MutableSet<Int>) {
        var c0 = 0
        var c1 = 0
        var runLen = 0
        for (i in 0 until text.length) {
            val ch: Char = text[i]
            if (Character.isJavaIdentifierPart(ch)) {
                val lower: Int = Character.toLowerCase(ch).code and 0xFF
                runLen++
                if (runLen >= 3) {
                    out.add((c0 shl 16) or (c1 shl 8) or lower)
                }
                c0 = c1
                c1 = lower
            } else {
                runLen = 0
            }
        }
    }

    private fun readFully(ch: FileChannel, buf: ByteBuffer, startPos: Long): Boolean {
        var pos: Long = startPos
        while (buf.hasRemaining()) {
            val n: Int = ch.read(buf, pos)
            if (n < 0) return false
            pos += n
        }
        return true
    }

    private fun sha256Hex(s: String): String {
        val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(StandardCharsets.UTF_8))
        return buildString(64) {
            for (b in digest) {
                val v: Int = b.toInt() and 0xFF
                append(HEX_CHARS[v ushr 4])
                append(HEX_CHARS[v and 0x0F])
            }
        }
    }

    private val HEX_CHARS: CharArray = "0123456789abcdef".toCharArray()

    private class IntList(initialCapacity: Int = 8) {
        private var data: IntArray = IntArray(initialCapacity.coerceAtLeast(1))
        var size: Int = 0
            private set

        fun add(v: Int) {
            if (size == data.size) {
                data = data.copyOf(data.size * 2)
            }
            data[size++] = v
        }

        operator fun get(index: Int): Int = data[index]

        fun toIntArray(): IntArray = data.copyOf(size)
    }
}
