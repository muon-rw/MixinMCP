package dev.mixinmcp.tools

import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException
import java.util.zip.CRC32

internal const val VARIANT_GROUPING_FOOTER: String =
    "(same-name classpath variants grouped; use module= on mixin_find_class or bytecode tools to inspect one)"

/**
 * Groups results that share a key (usually an FQCN, optionally FQCN#member)
 * across classpath variants, tracking total copies and byte-distinct copies,
 * e.g. one class reachable through common/fabric/neoforge merged-jar variants.
 * Instantiate fresh inside each read-action lambda; instances are not
 * retry-safe across re-executed reads.
 */
class ClassContentDeduper {
    private class KeyStats {
        var total: Int = 0
        val hashes: MutableSet<Long> = mutableSetOf()
    }

    private val seen: MutableMap<String, KeyStats> = mutableMapOf()

    /** Returns true when [key] is first seen; every call updates variant counts. */
    fun record(key: String?, file: VirtualFile?): Boolean {
        if (key == null) return true
        val stats: KeyStats = seen.getOrPut(key) { KeyStats() }
        stats.total++
        contentHash(file)?.let { stats.hashes.add(it) }
        return stats.total == 1
    }

    /** " (3 variants, 2 distinct)" when more than one copy was recorded, else null. */
    fun annotationFor(key: String?): String? {
        val stats: KeyStats = seen[key] ?: return null
        if (stats.total <= 1) return null
        val distinct: Int = maxOf(1, stats.hashes.size)
        return if (distinct == 1) " (${stats.total} identical variants)"
        else " (${stats.total} variants, $distinct distinct)"
    }

    private fun contentHash(file: VirtualFile?): Long? {
        if (file == null) return null
        val bytes: ByteArray = try {
            file.contentsToByteArray()
        } catch (e: IOException) {
            return null
        }
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }
}
