package dev.mixinmcp.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAutoAttacherTest {

    @Test
    fun acceptsVanillaModeMergedJar() {
        assertTrue(
            SourceAutoAttacher.isMdgMergedJarForAttach(
                "/p/common/build/moddev/artifacts/vanilla-26.1.2-1-merged.jar!/",
            ),
        )
    }

    @Test
    fun acceptsNeoForgePatchedMergedJar() {
        assertTrue(
            SourceAutoAttacher.isMdgMergedJarForAttach(
                "/p/neoforge/build/moddev/artifacts/minecraft-patched-26.1.2.75-merged.jar!/",
            ),
        )
    }

    @Test
    fun acceptsMergedJarWithWindowsSeparators() {
        assertTrue(
            SourceAutoAttacher.isMdgMergedJarForAttach(
                "C:\\p\\forge\\build\\moddev\\artifacts\\forge-1.20.1-47.2.0-merged.jar!/",
            ),
        )
    }

    @Test
    fun rejectsMergedJarOutsideModdevArtifacts() {
        assertFalse(SourceAutoAttacher.isMdgMergedJarForAttach("/p/build/libs/mymod-merged.jar"))
    }

    @Test
    fun rejectsNonMergedModdevArtifacts() {
        assertFalse(
            SourceAutoAttacher.isMdgMergedJarForAttach(
                "/p/common/build/moddev/artifacts/vanilla-26.1.2-1-sources.jar",
            ),
        )
        assertFalse(
            SourceAutoAttacher.isMdgMergedJarForAttach(
                "/p/common/build/moddev/artifacts/vanilla-26.1.2-1.jar",
            ),
        )
        assertFalse(
            SourceAutoAttacher.isMdgMergedJarForAttach(
                "/p/neoforge/build/moddev/artifacts/client-extra-26.1.2.jar",
            ),
        )
    }

    @Test
    fun siblingSourcesNameForMergedJar() {
        assertEquals(
            "vanilla-26.1.2-1-sources.jar",
            SourceAutoAttacher.siblingSourcesJarName("vanilla-26.1.2-1-merged.jar"),
        )
        assertEquals(
            "minecraft-patched-26.1.2.75-sources.jar",
            SourceAutoAttacher.siblingSourcesJarName("minecraft-patched-26.1.2.75-merged.jar"),
        )
    }

    @Test
    fun siblingSourcesNameMatchesCaseInsensitively() {
        assertEquals(
            "Forge-1.20.1-sources.jar",
            SourceAutoAttacher.siblingSourcesJarName("Forge-1.20.1-MERGED.JAR"),
        )
    }

    @Test
    fun siblingSourcesNameNullForNonMergedJar() {
        assertNull(SourceAutoAttacher.siblingSourcesJarName("vanilla-26.1.2-1.jar"))
        assertNull(SourceAutoAttacher.siblingSourcesJarName("vanilla-26.1.2-1-sources.jar"))
        assertNull(SourceAutoAttacher.siblingSourcesJarName("merged.jar"))
    }

    @Test
    fun rejectsLoomCacheMinecraftJars() {
        assertFalse(
            SourceAutoAttacher.isMdgMergedJarForAttach(
                "/p/.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-ff8a080d8e/26.1.2/minecraft-merged-ff8a080d8e-26.1.2.jar",
            ),
        )
        assertFalse(
            SourceAutoAttacher.isMdgMergedJarForAttach(
                "/p/.gradle/loom-cache/minecraftMaven/net/minecraft/" +
                    "nfrt-net.neoforged.neoforge_26.1.2-minecraft-merged-deobf/26.1.2/" +
                    "nfrt-net.neoforged.neoforge_26.1.2-minecraft-merged-deobf-26.1.2.jar",
            ),
        )
    }
}
