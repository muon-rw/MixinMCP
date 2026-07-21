package dev.mixinmcp.resolve

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.impl.compiled.ClsFileImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.ClassUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Locates raw .class file bytes for a given FQCN.
 * Returns bytes that MUST start with 0xCAFEBABE (Java class magic).
 * If VirtualFile.contentsToByteArray() returns decompiled source, falls back
 * to reading directly from the JAR. Project-source classes resolve through the
 * module's compiler output; missing or out-of-date output is reported so tools
 * can ask for a build instead of claiming the class does not exist.
 */
object ClassFileLocator {

    private const val CLASS_MAGIC: Int = 0xCAFEBABE.toInt()

    sealed class LocateResult {
        /**
         * [maybeStale]: the source file is newer than the .class (or has unsaved editor
         * changes), so the bytes may not reflect current source. mtime-based; a content-unchanged
         * touch (git checkout, formatter no-op) also trips it, so it is a warning, not an error.
         */
        class Found(val bytes: ByteArray, val maybeStale: Boolean = false) : LocateResult()
        data object NotFound : LocateResult()
        data object NotBuilt : LocateResult()
    }

    @RequiresReadLock
    fun locate(
        project: Project,
        fqcn: String,
        scope: GlobalSearchScope = GlobalSearchScope.everythingScope(project),
    ): ByteArray? =
        (locateDetailed(project, fqcn, scope) as? LocateResult.Found)?.bytes

    @RequiresReadLock
    fun locateDetailed(
        project: Project,
        fqcn: String,
        scope: GlobalSearchScope = GlobalSearchScope.everythingScope(project),
    ): LocateResult {
        val psiClass: PsiClass = FqcnResolver.resolveNested(project, fqcn, scope)
            ?: return LocateResult.NotFound

        return locateForClass(psiClass)
    }

    /**
     * Locates bytes for an already-resolved PsiClass variant, reading from that
     * variant's own origin (its jar entry or its module's compiler output).
     */
    @RequiresReadLock
    fun locateForClass(psiClass: PsiClass): LocateResult {
        val containingFile = psiClass.containingFile ?: return LocateResult.NotFound

        if (containingFile is ClsFileImpl) {
            val virtualFile = containingFile.virtualFile
            if (!virtualFile.isValid) return LocateResult.NotFound

            val bytes: ByteArray = try {
                virtualFile.contentsToByteArray()
            } catch (_: Exception) {
                return LocateResult.NotFound
            }

            if (isValidClassBytes(bytes)) return LocateResult.Found(bytes)

            // contentsToByteArray returned decompiled source; try JAR fallback
            readFromJar(virtualFile.url)?.let { return LocateResult.Found(it) }
        }

        // Strategy 2: PSI already points at a .class file
        val virtualFile = psiClass.containingFile?.virtualFile ?: return LocateResult.NotFound
        if (virtualFile.extension == "class") {
            val bytes: ByteArray = try {
                virtualFile.contentsToByteArray()
            } catch (_: Exception) {
                return LocateResult.NotFound
            }

            if (isValidClassBytes(bytes)) return LocateResult.Found(bytes)
            return LocateResult.NotFound
        }

        // Strategy 3: project source; read the .class from the module's compiler output
        return locateFromCompilerOutput(psiClass, virtualFile)
    }

    private fun locateFromCompilerOutput(psiClass: PsiClass, sourceVf: VirtualFile): LocateResult {
        val module: Module = ModuleUtilCore.findModuleForPsiElement(psiClass) ?: return LocateResult.NotFound
        val jvmName: String = ClassUtil.getJVMClassName(psiClass) ?: return LocateResult.NotFound
        val relativePath: String = jvmName.replace('.', '/') + ".class"

        val classFile: Path = compilerOutputDirs(module)
            .map { it.resolve(relativePath) }
            .filter { Files.isRegularFile(it) }
            .maxByOrNull { fileMtime(it) }
            ?: return LocateResult.NotBuilt

        val maybeStale: Boolean = FileDocumentManager.getInstance().isFileModified(sourceVf) ||
            fileMtime(Path.of(sourceVf.path)) > fileMtime(classFile)

        val bytes: ByteArray = runCatching { Files.readAllBytes(classFile) }.getOrNull()
            ?: return LocateResult.NotFound
        return if (isValidClassBytes(bytes)) LocateResult.Found(bytes, maybeStale) else LocateResult.NotFound
    }

    /**
     * Gradle-imported modules register the java output dir; Kotlin and Groovy classes land in
     * sibling dirs under the same source-set name, so those are probed as well.
     */
    private fun compilerOutputDirs(module: Module): List<Path> {
        val extension = CompilerModuleExtension.getInstance(module) ?: return emptyList()
        val urls: List<String> = listOfNotNull(extension.compilerOutputUrl, extension.compilerOutputUrlForTests)
        return urls
            .map { VfsUtilCore.urlToPath(it).replace('\\', '/') }
            .flatMap { path ->
                if ("/classes/java/" in path) {
                    listOf(
                        path,
                        path.replace("/classes/java/", "/classes/kotlin/"),
                        path.replace("/classes/java/", "/classes/groovy/"),
                    )
                } else {
                    listOf(path)
                }
            }
            .distinct()
            .map { Path.of(it) }
    }

    private fun fileMtime(path: Path): Long =
        runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L)

    private fun isValidClassBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val magic: Int = ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        return magic == CLASS_MAGIC
    }

    /**
     * Fallback: read raw bytes from JAR when VirtualFile points to decompiled content.
     * Parses jar:///path/to.jar!/entry/Class.class URL.
     */
    private fun readFromJar(url: String): ByteArray? {
        if (!url.startsWith("jar:")) return null

        val afterJar: String = url.removePrefix("jar:")
        val sep: Int = afterJar.indexOf("!/")
        if (sep < 0) return null

        val jarPath: String = afterJar.substring(0, sep).removePrefix("file:").trimStart('/')
        val entryPath: String = afterJar.substring(sep + 2).trimStart('/')

        return try {
            val file: File = File(jarPath)
            if (!file.exists()) return null

            val rawBytes: ByteArray = JarFile(file).use { jar ->
                val entry = jar.getJarEntry(entryPath) ?: jar.getJarEntry(entryPath.removePrefix("/"))
                    ?: return@use null
                jar.getInputStream(entry).readBytes()
            } ?: return null
            if (isValidClassBytes(rawBytes)) rawBytes else null
        } catch (_: Exception) {
            null
        }
    }
}
