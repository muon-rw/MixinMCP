package dev.mixinmcp.mcsources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeoFormVersionResolverTest {

    private val sampleVersions = listOf(
        "1.20.2-20231019.002635",
        "1.21.1-20240808.132153",
        "1.21.1-20240808.144430",
        "1.21.4-rc1-20241128.120000",
        "1.21.4-20241203.161809",
        "26.1-snapshot-1-20260115.100000",
        "26.1-1",
        "26.1-2",
    )

    @Test
    fun picksNewestBuildForRelease() {
        assertEquals("1.21.1-20240808.144430", NeoFormVersionResolver.resolve("1.21.1", sampleVersions))
        assertEquals("26.1-2", NeoFormVersionResolver.resolve("26.1", sampleVersions))
    }

    @Test
    fun ignoresPreReleaseWhenReleaseRequested() {
        val onlyRc = listOf("1.21.4-rc1-20241128.120000", "26.1-snapshot-1-20260115.100000")
        assertNull(NeoFormVersionResolver.resolve("1.21.4", onlyRc))
        assertNull(NeoFormVersionResolver.resolve("26.1", onlyRc))
        assertEquals("1.21.4-rc1-20241128.120000", NeoFormVersionResolver.resolve("1.21.4-rc1", onlyRc))
    }

    @Test
    fun returnsNullForPreNeoFormVersions() {
        assertNull(NeoFormVersionResolver.resolve("1.20.1", sampleVersions))
    }

    @Test
    fun parsesMetadataXml() {
        val xml = """
            <metadata>
              <versioning>
                <versions>
                  <version>1.21.1-20240808.132153</version>
                  <version>1.21.1-20240808.144430</version>
                </versions>
              </versioning>
            </metadata>
        """.trimIndent()
        assertEquals(
            listOf("1.21.1-20240808.132153", "1.21.1-20240808.144430"),
            NeoFormVersionResolver.parseVersionsXml(xml),
        )
    }
}
