package dev.mixinmcp.cache

import dev.mixinmcp.rules.parseDeclaredDecompileVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GradlePluginVersionTest {

    @Test
    fun numericSegmentComparison() {
        assertTrue(compareGradlePluginVersions("1.3.0", "1.2.1") > 0)
        assertTrue(compareGradlePluginVersions("1.2.1", "1.3.0") < 0)
        assertEquals(0, compareGradlePluginVersions("1.3.0", "1.3.0"))
        assertTrue(compareGradlePluginVersions("1.10.0", "1.9.9") > 0)
        assertEquals(0, compareGradlePluginVersions("1.3", "1.3.0"))
        assertTrue(compareGradlePluginVersions("1.3.0-SNAPSHOT", "1.2.9") > 0)
    }

    @Test
    fun atLeastTreatsUnknownAsOutdated() {
        assertFalse(isGradlePluginVersionAtLeast(null, "1.3.0"))
        assertFalse(isGradlePluginVersionAtLeast("1.2.1", "1.3.0"))
        assertTrue(isGradlePluginVersionAtLeast("1.3.0", "1.3.0"))
        assertTrue(isGradlePluginVersionAtLeast("1.4.0", "1.3.0"))
    }

    @Test
    fun gatingIgnoresSuffixesAndTreatsNonNumericAsZero() {
        assertTrue(isGradlePluginVersionAtLeast("1.3.0-SNAPSHOT", "1.3.0"))
        assertFalse(isGradlePluginVersionAtLeast("1.3.0-rc1", "1.3.1"))
        assertFalse(isGradlePluginVersionAtLeast("garbage", "1.3.0"))
        assertEquals(0, compareGradlePluginVersions("garbage", "0.0.0"))
    }

    @Test
    fun declaredVersionParsing() {
        assertEquals(
            "1.2.0",
            parseDeclaredDecompileVersion("id 'dev.mixinmcp.decompile' version '1.2.0' apply false"),
        )
        assertEquals(
            "1.3.0",
            parseDeclaredDecompileVersion("""id("dev.mixinmcp.decompile") version "1.3.0" apply false"""),
        )
        assertEquals(
            "1.3.0",
            parseDeclaredDecompileVersion("""id("dev.mixinmcp.decompile") version("1.3.0")"""),
        )
        assertEquals(null, parseDeclaredDecompileVersion("""id("dev.mixinmcp.decompile")"""))
        assertEquals(null, parseDeclaredDecompileVersion("id 'net.fabricmc.fabric-loom' version '1.16-SNAPSHOT'"))
    }
}
