package dev.mixinmcp.mcsources

import dev.mixinmcp.mappings.MappingsDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.objectweb.asm.ClassReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

// Internal class name -> its superclass followed by its interfaces, in the game jar's own names
// (obfuscated before 26.x, which is also the mapping tree's source namespace).
class ClassHierarchy(private val supertypes: Map<String, List<String>>) {

    /** Returns every superclass and superinterface of [cls], nearest first. */
    fun ancestors(cls: String): List<String> {
        val result = LinkedHashSet<String>()
        val queue = ArrayDeque(supertypes[cls].orEmpty())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next == cls || !result.add(next)) continue
            queue.addAll(supertypes[next].orEmpty())
        }
        return result.toList()
    }

    companion object {
        private const val FILE_NAME = "class-hierarchy.txt"

        // Kept beside the mappings for the same version. Only the extracted hierarchy (a few hundred
        // KB) is stored; the client jar it comes from is deleted after reading.
        suspend fun load(mcVersion: String): ClassHierarchy = withContext(Dispatchers.IO) {
            val file = MappingsDownloader.versionDir(mcVersion).resolve(FILE_NAME)
            if (!Files.isRegularFile(file) || Files.size(file) == 0L) {
                val jar = Files.createTempFile("mixinmcp-client-$mcVersion-", ".jar")
                try {
                    MappingsDownloader.downloadClientJar(mcVersion, jar)
                    Files.createDirectories(file.parent)
                    val temp = Files.createTempFile(file.parent, FILE_NAME, ".part")
                    Files.writeString(temp, extract(jar))
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(jar)
                }
            }
            parse(Files.readString(file))
        }

        internal fun parse(text: String): ClassHierarchy = ClassHierarchy(
            text.lineSequence().filter { it.isNotBlank() }.associate { line ->
                val parts = line.split(' ')
                parts[0] to parts.drop(1)
            },
        )

        // One line per class: "<name> <super> <interface>...". java/lang/Object is dropped as noise.
        internal fun extract(jar: Path): String = buildString {
            ZipFile(jar.toFile()).use { zip ->
                for (entry in zip.entries()) {
                    if (!entry.name.endsWith(".class") || entry.name.startsWith("META-INF/")) continue
                    val reader = zip.getInputStream(entry).use { ClassReader(it) }
                    val supers = listOfNotNull(reader.superName?.takeIf { it != "java/lang/Object" }) +
                        reader.interfaces
                    if (supers.isEmpty()) continue
                    append(reader.className)
                    supers.forEach { append(' ').append(it) }
                    append('\n')
                }
            }
        }
    }
}
