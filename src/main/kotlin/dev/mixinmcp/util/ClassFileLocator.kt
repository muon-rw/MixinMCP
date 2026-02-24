package dev.mixinmcp.util

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.impl.compiled.ClsFileImpl
import java.io.File
import java.util.jar.JarFile

/**
 * Locates raw .class file bytes for a given FQCN.
 * Returns bytes that MUST start with 0xCAFEBABE (Java class magic).
 * If VirtualFile.contentsToByteArray() returns decompiled source, falls back
 * to reading directly from the JAR.
 */
object ClassFileLocator {

    private const val CLASS_MAGIC: Int = 0xCAFEBABE.toInt()

    /**
     * Locates the raw .class file bytes for a given FQCN.
     * Uses FqcnResolver for consistency. Validates magic number; falls back
     * to JAR-based read if contentsToByteArray returns wrong content.
     */
    fun locate(project: Project, fqcn: String): ByteArray? {
        return ReadAction.compute<ByteArray?, Throwable> {
            val psiClass: PsiClass = FqcnResolver.resolveNested(project, fqcn)
                ?: return@compute null

            locateFromPsiClass(psiClass)
        }
    }

    private fun locateFromPsiClass(psiClass: PsiClass): ByteArray? {
        val containingFile = psiClass.containingFile ?: return null

        if (containingFile is ClsFileImpl) {
            val virtualFile = containingFile.virtualFile
            if (!virtualFile.isValid) return null

            val bytes: ByteArray = try {
                virtualFile.contentsToByteArray()
            } catch (e: Exception) {
                return null
            }

            if (isValidClassBytes(bytes)) return bytes

            // contentsToByteArray returned decompiled source; try JAR fallback
            readFromJar(virtualFile.url)?.let { return it }
        }

        // Strategy 2: Project source — .class in output dir
        val virtualFile = psiClass.containingFile?.virtualFile ?: return null
        if (virtualFile.extension == "class") {
            val bytes: ByteArray = try {
                virtualFile.contentsToByteArray()
            } catch (e: Exception) {
                return null
            }

            if (isValidClassBytes(bytes)) return bytes
        }

        return null
    }

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
        } catch (e: Exception) {
            null
        }
    }
}
