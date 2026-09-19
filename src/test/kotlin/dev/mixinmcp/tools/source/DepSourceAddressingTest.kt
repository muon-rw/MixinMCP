package dev.mixinmcp.tools.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepSourceAddressingTest {

    @Test
    fun bareJarPathBecomesJarUrl() {
        assertEquals(
            "jar://C:/pack/mods/x.jar!/META-INF/mods.toml",
            normalizeSourceUrl("C:\\pack\\mods\\x.jar!/META-INF/mods.toml"),
        )
    }

    @Test
    fun schemeUrlsPassThrough() {
        assertEquals("jar://a/b.jar!/x/Y.java", normalizeSourceUrl("jar://a/b.jar!/x/Y.java"))
        assertEquals("file://C:/x/y.json", normalizeSourceUrl(" file://C:/x/y.json "))
    }

    @Test
    fun bareFilePathBecomesFileUrl() {
        assertEquals("file://C:/x/y.json", normalizeSourceUrl("C:\\x\\y.json"))
    }

    @Test
    fun relativeDiskPartsResolveAgainstTheProject() {
        assertEquals(
            "jar://C:/p/mod/libs/x.jar!/META-INF/mods.toml",
            normalizeSourceUrl("jar://libs/x.jar!/META-INF/mods.toml", "C:/p/mod"),
        )
        assertEquals("jar://C:/p/mod/libs/x.jar!/a.json", normalizeSourceUrl("libs\\x.jar!/a.json", "C:\\p\\mod\\"))
        assertEquals("jar://C:/abs/x.jar!/a.json", normalizeSourceUrl("jar://C:/abs/x.jar!/a.json", "C:/p/mod"))
        assertEquals("file://C:/p/mod/build.gradle", normalizeSourceUrl("./build.gradle", "C:/p/mod"))
    }

    @Test
    fun resolveAgainstBaseKeepsAbsolutePaths() {
        assertEquals("C:/p/mod/src/A.java", dev.mixinmcp.tools.resolveAgainstBase("C:/p/mod", "src\\A.java"))
        assertEquals("D:/x/y.jar", dev.mixinmcp.tools.resolveAgainstBase("C:/p/mod", "D:\\x\\y.jar"))
        assertEquals("/opt/y.jar", dev.mixinmcp.tools.resolveAgainstBase("C:/p/mod", "/opt/y.jar"))
        assertEquals("rel/y.jar", dev.mixinmcp.tools.resolveAgainstBase(null, "rel\\y.jar"))
    }

    @Test
    fun jarEntryUrlJoinsWithOneSeparator() {
        assertEquals("jar://C:/m/x.jar!/META-INF/mods.toml", jarEntryUrl("C:\\m\\x.jar", "/META-INF/mods.toml"))
        assertEquals("jar://C:/m/x.jar!/assets/a/lang/en_us.json", jarEntryUrl("C:/m/x.jar/", "assets\\a\\lang\\en_us.json"))
    }

    @Test
    fun urlAndDiskShapedPrefixesAreDetected() {
        assertTrue(looksLikeUrlOrDiskPath("jar://x.jar!/net/"))
        assertTrue(looksLikeUrlOrDiskPath("x-sources.jar!/net/minecraft/"))
        assertTrue(looksLikeUrlOrDiskPath("C:/Users/x/.cache/"))
        assertFalse(looksLikeUrlOrDiskPath("net/minecraft/"))
        assertFalse(looksLikeUrlOrDiskPath("java/util/"))
    }

    @Test
    fun binaryContentIsDetectedByNulBytes() {
        assertTrue(looksBinary(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0, 1)))
        assertFalse(looksBinary("plain text\n".toByteArray()))
        assertFalse(looksBinary(ByteArray(0)))
    }

    @Test
    fun directoriesOutsideThePrefixArePruned() {
        assertTrue(directoryMayContainPrefix("net/", "net/minecraft/"))
        assertTrue(directoryMayContainPrefix("net/minecraft/world/", "net/minecraft/"))
        assertTrue(directoryMayContainPrefix("NET/Minecraft/", "net/minecraft/"))
        assertFalse(directoryMayContainPrefix("java/", "net/minecraft/"))
        assertFalse(directoryMayContainPrefix("net/minecraftforge/", "net/minecraft/"))
    }

    @Test
    fun binaryEntryNamesAreRecognized() {
        assertTrue(isBinaryEntryName("a/Foo.class"))
        assertTrue(isBinaryEntryName("icon.PNG"))
        assertFalse(isBinaryEntryName("META-INF/mods.toml"))
        assertFalse(isBinaryEntryName("en_us.json"))
        assertFalse(isBinaryEntryName("Makefile"))
    }
}
