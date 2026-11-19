package dev.mixinmcp.tools.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TrigramShardIndexTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val stamp = "1:1"

    @Test
    fun buildsAndQueriesDirectoryShardAcrossJavaAndResourcesSkippingBinaries() {
        val root: Path = tempFolder.newFolder("cache-root").toPath()
        val shardDir: Path = tempFolder.newFolder("shards").toPath()

        writeFile(root.resolve("com/example/Mod.java"), "class Mod { void addEffect() {} }")
        writeFile(root.resolve("assets/example/icon.png"), "minecraft:diamond_sword")
        writeFile(root.resolve("data/example/recipes/blade.json"), """{"result": "minecraft:diamond_sword"}""")
        writeFile(root.resolve("META-INF/neoforge.mods.toml"), """modId="apotheosis"""")
        assertTrue(TrigramShardIndex.buildDirectoryShard(root, TrigramShardIndex.shardPathForDirectory(root, stamp, shardDir)))

        assertEquals(listOf("com/example/Mod.java"), candidates(root, """addEffect\(""", shardDir))
        assertEquals(listOf("data/example/recipes/blade.json"), candidates(root, "minecraft.*diamond_sword", shardDir))
        assertEquals(
            setOf("com/example/Mod.java", "META-INF/neoforge.mods.toml"),
            candidates(root, "apotheosis|addEffect", shardDir)?.toSet(),
        )
        assertEquals(emptyList<String>(), candidates(root, "zzz_nonexistent", shardDir))
    }

    @Test
    fun buildsAndQueriesJarShardWithAndWithoutSubPrefix() {
        val jarFile: File = tempFolder.newFile("sample-sources.jar")
        val shardDir: Path = tempFolder.newFolder("jar-shards").toPath()

        ZipOutputStream(FileOutputStream(jarFile)).use { zos ->
            putZipEntry(zos, "java.base/java/util/concurrent/ConcurrentHashMap.java", "public class ConcurrentHashMap {}")
            putZipEntry(zos, "java.desktop/java/awt/Color.java", "public class Color {}")
            putZipEntry(zos, "java.base/icon.png", "ConcurrentHashMap")
        }
        for (sub in listOf("java.base/", "java.desktop/")) {
            assertTrue(TrigramShardIndex.buildJarShard(jarFile, sub, TrigramShardIndex.shardPathForJar(jarFile, sub, shardDir)))
        }

        val query = clauses("ConcurrentHashMap")
        assertEquals(
            TrigramShardIndex.Lookup.Candidates(listOf("java/util/concurrent/ConcurrentHashMap.java")),
            TrigramShardIndex.lookupJar(jarFile.absolutePath, "java.base/", query, shardDir),
        )
        assertEquals(
            TrigramShardIndex.Lookup.Candidates(emptyList()),
            TrigramShardIndex.lookupJar(jarFile.absolutePath, "java.desktop/", query, shardDir),
        )
    }

    @Test
    fun missingShardBuildsInBackgroundInsteadOfBlockingTheLookup() {
        val root: Path = tempFolder.newFolder("background-root").toPath()
        val shardDir: Path = tempFolder.newFolder("background-shards").toPath()
        writeFile(root.resolve("com/example/Foo.java"), "class Foo { void tickFoo() {} }")

        val query = clauses("tickFoo")
        assertEquals(TrigramShardIndex.Lookup.Building, TrigramShardIndex.lookupDirectory(root, stamp, query, shardDir))
        assertEquals(TrigramShardIndex.Lookup.Candidates(listOf("com/example/Foo.java")), awaitCandidates(root, stamp, query, shardDir))
    }

    @Test
    fun corruptShardIsDeletedAndRebuiltInBackground() {
        val root: Path = tempFolder.newFolder("corrupt-root").toPath()
        val shardDir: Path = tempFolder.newFolder("corrupt-shards").toPath()

        writeFile(root.resolve("META-INF/mods.toml"), """modId="otherworld"""")
        Files.write(TrigramShardIndex.shardPathForDirectory(root, stamp, shardDir), byteArrayOf(0x01, 0x02, 0x03, 0x04))

        val query = clauses("otherworld")
        assertEquals(TrigramShardIndex.Lookup.Building, TrigramShardIndex.lookupDirectory(root, stamp, query, shardDir))
        assertEquals(TrigramShardIndex.Lookup.Candidates(listOf("META-INF/mods.toml")), awaitCandidates(root, stamp, query, shardDir))
    }

    @Test
    fun changedContentStampNeverReusesTheOldShard() {
        val root: Path = tempFolder.newFolder("restamped-root").toPath()
        val shardDir: Path = tempFolder.newFolder("restamped-shards").toPath()
        writeFile(root.resolve("a/Old.java"), "class Old {}")
        assertTrue(TrigramShardIndex.buildDirectoryShard(root, TrigramShardIndex.shardPathForDirectory(root, stamp, shardDir)))

        writeFile(root.resolve("a/Added.java"), "class Added { void freshMethod() {} }")
        val query = clauses("freshMethod")
        assertEquals(TrigramShardIndex.Lookup.Candidates(emptyList()), TrigramShardIndex.lookupDirectory(root, stamp, query, shardDir))
        assertEquals(TrigramShardIndex.Lookup.Building, TrigramShardIndex.lookupDirectory(root, "2:2", query, shardDir))
        assertEquals(TrigramShardIndex.Lookup.Candidates(listOf("a/Added.java")), awaitCandidates(root, "2:2", query, shardDir))
    }

    @Test
    fun handlesDirectoryWithNoTextFiles() {
        val root: Path = tempFolder.newFolder("bin-only-root").toPath()
        val shardDir: Path = tempFolder.newFolder("bin-only-shards").toPath()

        writeFile(root.resolve("assets/icon.png"), "not text")
        assertTrue(TrigramShardIndex.buildDirectoryShard(root, TrigramShardIndex.shardPathForDirectory(root, stamp, shardDir)))
        assertEquals(emptyList<String>(), candidates(root, "text", shardDir))
    }

    private fun clauses(regex: String): RegexLiteralPlanner.TrigramQuery.Clauses =
        RegexLiteralPlanner.plan(regex, caseSensitive = true).trigramQuery as RegexLiteralPlanner.TrigramQuery.Clauses

    private fun candidates(root: Path, regex: String, shardDir: Path): List<String>? =
        (TrigramShardIndex.lookupDirectory(root, stamp, clauses(regex), shardDir) as? TrigramShardIndex.Lookup.Candidates)?.relPaths

    private fun awaitCandidates(
        root: Path,
        contentStamp: String,
        query: RegexLiteralPlanner.TrigramQuery.Clauses,
        shardDir: Path,
    ): TrigramShardIndex.Lookup {
        val deadline: Long = System.currentTimeMillis() + 10_000
        var lookup: TrigramShardIndex.Lookup = TrigramShardIndex.lookupDirectory(root, contentStamp, query, shardDir)
        while (lookup == TrigramShardIndex.Lookup.Building && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            lookup = TrigramShardIndex.lookupDirectory(root, contentStamp, query, shardDir)
        }
        return lookup
    }

    private fun writeFile(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    private fun putZipEntry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(StandardCharsets.UTF_8))
        zos.closeEntry()
    }
}
