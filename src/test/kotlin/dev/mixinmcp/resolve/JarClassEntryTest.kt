package dev.mixinmcp.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class JarClassEntryTest {

    private val classMagic: ByteArray = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, 52)

    private fun jarWith(vararg entryNames: String): File {
        val jar: File = File.createTempFile("mixinmcp-jar-entry", ".jar")
        jar.deleteOnExit()
        JarOutputStream(FileOutputStream(jar)).use { out ->
            for (name in entryNames) {
                out.putNextEntry(JarEntry(name))
                out.write(classMagic)
                out.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun dottedNestedNameResolvesToDollarEntry() {
        val jar: File = jarWith("a/b/Outer.class", "a/b/Outer\$Inner.class")
        val lookup = ClassFileLocator.readClassEntry(jar, "a.b.Outer.Inner")
        assertTrue(lookup is ClassFileLocator.JarClassLookup.Found)
        assertEquals("a/b/Outer\$Inner.class", (lookup as ClassFileLocator.JarClassLookup.Found).entryName)
    }

    @Test
    fun slashAndDollarFormsResolve() {
        val jar: File = jarWith("a/b/Outer\$Inner.class")
        assertTrue(ClassFileLocator.readClassEntry(jar, "a/b/Outer\$Inner") is ClassFileLocator.JarClassLookup.Found)
        assertTrue(ClassFileLocator.readClassEntry(jar, "a.b.Outer\$Inner.class") is ClassFileLocator.JarClassLookup.Found)
    }

    @Test
    fun missingClassListsSimilarEntries() {
        val jar: File = jarWith("a/b/OuterHelper.class", "c/Unrelated.class")
        val lookup = ClassFileLocator.readClassEntry(jar, "a.b.Outer")
        assertTrue(lookup is ClassFileLocator.JarClassLookup.NotFound)
        assertEquals(listOf("a/b/OuterHelper.class"), (lookup as ClassFileLocator.JarClassLookup.NotFound).similarEntries)
    }

    @Test
    fun corruptJarIsReportedUnreadable() {
        val garbage: File = File.createTempFile("mixinmcp-garbage", ".jar")
        garbage.deleteOnExit()
        garbage.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(ClassFileLocator.readClassEntry(garbage, "a.B") is ClassFileLocator.JarClassLookup.Unreadable)
    }
}
