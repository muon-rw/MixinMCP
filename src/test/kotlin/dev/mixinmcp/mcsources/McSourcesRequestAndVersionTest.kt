package dev.mixinmcp.mcsources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McSourcesRequestAndVersionTest {

    @Test
    fun parsesReleaseAndCalendarVersions() {
        assertEquals(McVersionSpec.Release(1, 20, 1), McVersionSpec.parse("1.20.1"))
        assertEquals("1.20.1", McVersionSpec.parse("20.1").raw)
        assertEquals(McVersionSpec.Release(26, 1, 0), McVersionSpec.parse("26.1"))
        assertEquals("26.1", McVersionSpec.parse("26.1").raw)
        assertEquals("26.1.2", McVersionSpec.parse("26.1.2").raw)
    }

    @Test
    fun keepsTwoPartReleaseNames() {
        assertEquals("1.21", McVersionSpec.parse("1.21").raw)
        assertEquals("1.21", McVersionSpec.parse("1.21.0").raw)
        assertEquals("1.20", McVersionSpec.parse("20.0").raw)
        assertEquals("1.21-vanilla", McSourceRequest.validated("1.21", "vanilla", null).cacheKey)
        assertEquals(
            "1.20-forge-46.0.14",
            McSourceRequest.validated("1.20", "forge", "1.20-46.0.14").cacheKey,
        )
    }

    @Test
    fun parsesNonReleaseSnapshots() {
        val spec = McVersionSpec.parse("24w14a")
        assertTrue(spec is McVersionSpec.NonRelease)
        assertEquals("24w14a", spec.raw)
    }

    @Test
    fun rejectsUnsafeVersionStrings() {
        assertThrows(IllegalArgumentException::class.java) { McVersionSpec.parse("") }
        assertThrows(IllegalArgumentException::class.java) { McVersionSpec.parse("../1.20.1") }
        assertThrows(IllegalArgumentException::class.java) { McVersionSpec.parse("1.20.1/evil") }
    }

    @Test
    fun fabricLoaderGivesVanillaHint() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            McSourceLoader.parse("fabric")
        }
        assertTrue(ex.message!!.contains("vanilla"))
        assertTrue(ex.message!!.contains("mixin_mappings_lookup"))
    }

    @Test
    fun validatesForgeAndStripsMcPrefix() {
        val req = McSourceRequest.validated("1.20.1", "forge", "1.20.1-47.3.0")
        assertEquals("47.3.0", req.loaderVersion)
        assertEquals("1.20.1-forge-47.3.0", req.cacheKey)
    }

    @Test
    fun rejectsForgeOnModernMcVersions() {
        assertThrows(IllegalArgumentException::class.java) {
            McSourceRequest.validated("1.21.1", "forge", "52.0.0")
        }
        assertThrows(IllegalArgumentException::class.java) {
            McSourceRequest.validated("1.12.2", "forge", "14.23.5.2860")
        }
    }

    @Test
    fun validatesNeoForgeAndRejectsLegacy() {
        val req = McSourceRequest.validated("1.21.1", "neoforge", "21.1.86")
        assertEquals("1.21.1-neoforge-21.1.86", req.cacheKey)

        val cal = McSourceRequest.validated("26.1", "neoforge", "26.1.0.10")
        assertEquals("26.1-neoforge-26.1.0.10", cal.cacheKey)

        assertThrows(IllegalArgumentException::class.java) {
            McSourceRequest.validated("1.20.1", "neoforge", "47.1.106")
        }
    }

    @Test
    fun vanillaRejectsLoaderVersionAndPre113() {
        val req = McSourceRequest.validated("1.21.4", "vanilla", null)
        assertEquals("1.21.4-vanilla", req.cacheKey)

        assertThrows(IllegalArgumentException::class.java) {
            McSourceRequest.validated("1.21.4", "vanilla", "21.4.1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            McSourceRequest.validated("1.12.2", "vanilla", null)
        }
    }

    // NFRT rejects MCPConfig versions that predate official Mojang mappings.
    @Test
    fun rejectsPreMojmapVersions() {
        assertThrows(IllegalArgumentException::class.java) {
            McSourceRequest.validated("1.16.5", "vanilla", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            McSourceRequest.validated("1.16.5", "forge", "36.2.39")
        }
        assertEquals("1.17-vanilla", McSourceRequest.validated("1.17", "vanilla", null).cacheKey)
        assertEquals("1.18.2-forge-40.2.0", McSourceRequest.validated("1.18.2", "forge", "40.2.0").cacheKey)
    }
}
