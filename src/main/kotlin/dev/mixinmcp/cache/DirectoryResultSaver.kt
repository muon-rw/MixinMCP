package dev.mixinmcp.cache

import org.jetbrains.java.decompiler.main.extern.IResultSaver
import java.io.File
import java.util.jar.Manifest

/**
 * IResultSaver implementation that writes decompiled .java files to a directory,
 * preserving package structure. Used with Vineflower for JAR decompilation.
 *
 * Vineflower's built-in DirectoryResultSaver may be in a different artifact;
 * this implementation provides the same behavior per DESIGN.md Section 11.7.
 */
class DirectoryResultSaver(private val rootDir: File) : IResultSaver {

    override fun saveFolder(path: String) {
        val dir = File(rootDir, path)
        dir.mkdirs()
    }

    override fun saveClassFile(path: String, qualifiedName: String, entryName: String, content: String, mapping: IntArray?) {
        // qualifiedName uses internal form (slashes) e.g. "com/example/Foo"
        val relativePath = qualifiedName.replace('/', File.separatorChar) + ".java"
        val file = File(rootDir, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    override fun copyFile(source: String, path: String, entryName: String) {
        // Non-class resources; skip for decompilation-only use
    }

    override fun createArchive(path: String, archiveName: String, manifest: Manifest?) {
        // JAR output; not used for directory decompilation
    }

    override fun saveDirEntry(path: String, archiveName: String, entryName: String) {
        // JAR output; not used for directory decompilation
    }

    override fun copyEntry(source: String, path: String, archiveName: String, entry: String) {
        // JAR output; not used for directory decompilation
    }

    override fun saveClassEntry(path: String, archiveName: String, qualifiedName: String, entryName: String, content: String) {
        // JAR output; for directory output the sink may call this
        val relativePath = qualifiedName.replace('/', File.separatorChar) + ".java"
        val file = File(rootDir, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    override fun closeArchive(path: String, archiveName: String) {
        // JAR output; not used for directory decompilation
    }
}
