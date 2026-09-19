package dev.mixinmcp.tools.source

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class JarGrepTest {

    private fun jarWith(entries: Map<String, ByteArray>): File {
        val jar: File = File.createTempFile("mixinmcp-grep", ".jar")
        jar.deleteOnExit()
        ZipOutputStream(FileOutputStream(jar)).use { out ->
            for ((name, bytes) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jar
    }

    private val jar: File = jarWith(
        linkedMapOf(
            "META-INF/neoforge.mods.toml" to "[[dependencies.x]]\nmodId=\"fzzy_config\"\n".toByteArray(),
            "data/x/recipe/rune.json" to "{\"item\": \"wind_spellbooks:wind_rune\"}".toByteArray(),
            "a/B.class" to "wind_rune in a constant pool".toByteArray(),
            "assets/x/icon.png" to "wind_rune".toByteArray(),
            "data/x/blob.bin" to byteArrayOf(0, 1, 2),
        ),
    )

    @Test
    fun matchingLinesCarryEntryAndLineNumber() {
        assertEquals(
            listOf("  META-INF/neoforge.mods.toml:2: modId=\"fzzy_config\""),
            grepJar(jar, null, buildFileMaskMatcher("mods.toml"), Pattern.compile("(?i)FZZY"), 100),
        )
    }

    @Test
    fun binaryEntriesAreSkipped() {
        assertEquals(
            listOf("  data/x/recipe/rune.json:1: {\"item\": \"wind_spellbooks:wind_rune\"}"),
            grepJar(jar, null, buildFileMaskMatcher(null), Pattern.compile("wind_rune"), 100),
        )
    }

    @Test
    fun prefixAndLimitApply() {
        assertEquals(0, grepJar(jar, "assets/", buildFileMaskMatcher(null), Pattern.compile("wind_rune"), 100).size)
        assertEquals(1, grepJar(jar, null, buildFileMaskMatcher(null), Pattern.compile("."), 1).size)
    }
}
