package dev.mixinmcp.tools.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolchainPathClassificationTest {

    private val projectPath = "/p"

    @Test
    fun loomCacheArtifactsAreLoomNotMerged() {
        val nfrtMerged =
            "/p/.gradle/loom-cache/minecraftMaven/net/minecraft/" +
                "nfrt-net.neoforged.neoforge_26.1.2-minecraft-merged-deobf/26.1.2/" +
                "nfrt-net.neoforged.neoforge_26.1.2-minecraft-merged-deobf-26.1.2.jar"
        val remappedMod = "/p/.gradle/loom-cache/remapped_mods/net_fabricmc_yarn/some/mod/mod-1.0.jar"
        assertTrue(isLoomCacheArtifactPath(nfrtMerged, projectPath))
        assertTrue(isLoomCacheArtifactPath(remappedMod, projectPath))
        assertFalse(isGradleToolchainMergedOrBinaryInBuild(nfrtMerged, projectPath))
        assertFalse(isGradleToolchainMergedOrBinaryInBuild(remappedMod, projectPath))
    }

    @Test
    fun mdgArtifactsAreNotLoom() {
        val mdg = "/p/build/moddev/artifacts/vanilla-26.1.2-1-merged.jar"
        assertFalse(isLoomCacheArtifactPath(mdg, projectPath))
        assertTrue(isGradleToolchainMergedOrBinaryInBuild(mdg, projectPath))
    }

    @Test
    fun zipTreeExpandedJarsUnderBuildTmpAreNotMerged() {
        val expanded = "/p/build/tmp/.cache/expanded/zip_abc/META-INF/jarjar/burnt_additions-0.1.5-neoforge-1.21.1.jar"
        assertFalse(isGradleToolchainMergedOrBinaryInBuild(expanded, projectPath))
    }

    @Test
    fun audioAndSerializedResourceExtensionsAreBinary() {
        assertTrue(isBinaryEntryName("WintersBreath.mp3"))
        assertTrue(isBinaryEntryName("ambient.flac"))
        assertTrue(isBinaryEntryName("sfd.ser"))
        assertTrue(isBinaryEntryName("CERT.RSA"))
        assertFalse(isBinaryEntryName("LivingEntity.java"))
    }
}
