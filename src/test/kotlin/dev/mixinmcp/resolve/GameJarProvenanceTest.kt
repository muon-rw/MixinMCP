package dev.mixinmcp.resolve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameJarProvenanceTest {

    private val projectPath = "/p"

    @Test
    fun loomVanillaMergedIsRemappedVanilla() {
        val path = "/p/.gradle/loom-cache/minecraftMaven/net/minecraft/" +
            "minecraft-merged-b34a2f0880/26.1.2/minecraft-merged-b34a2f0880-26.1.2.jar!/net/minecraft/world/entity/Mob.class"
        assertEquals(GameJarProvenance.LOOM_REMAPPED_VANILLA, classifyGameJarProvenance(path, projectPath))
    }

    @Test
    fun loomNeoforgeMergedIsLoaderPatched() {
        val path = "/p/.gradle/loom-cache/minecraftMaven/net/minecraft/" +
            "nfrt-net.neoforged.neoforge_26.1.2-minecraft-merged-deobf/26.1.2/" +
            "nfrt-net.neoforged.neoforge_26.1.2-minecraft-merged-deobf-26.1.2.jar"
        assertEquals(GameJarProvenance.LOADER_PATCHED, classifyGameJarProvenance(path, projectPath))
    }

    @Test
    fun mdgVanillaMergedIsRecompiledVanilla() {
        val path = "/p/common/build/moddev/artifacts/vanilla-26.1.2-1-merged.jar!/net/minecraft/world/entity/Mob.class"
        assertEquals(GameJarProvenance.RECOMPILED_VANILLA, classifyGameJarProvenance(path, projectPath))
    }

    @Test
    fun mdgPatchedMergedIsLoaderPatched() {
        val path = "/p/neoforge/build/moddev/artifacts/minecraft-patched-26.1.2.75-merged.jar"
        assertEquals(GameJarProvenance.LOADER_PATCHED, classifyGameJarProvenance(path, projectPath))
    }

    @Test
    fun decompileCachePathIsDecompileCache() {
        val path = "/Users/x/.cache/mixinmcp/decompiled/d5b2c1cf/net/minecraft/world/effect/PoisonMobEffect.java"
        assertEquals(GameJarProvenance.DECOMPILE_CACHE, classifyGameJarProvenance(path, projectPath))
    }

    @Test
    fun ordinaryModJarIsUnclassified() {
        val path = "/Users/x/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/" +
            "fabric-entity-events-v1/5.0.2/hash/fabric-entity-events-v1-5.0.2.jar!/net/fabricmc/fabric/mixin/MobMixin.class"
        assertNull(classifyGameJarProvenance(path, projectPath))
        assertNull(classifyGameJarProvenance(path, null))
    }

    @Test
    fun patchedNameClassifiesWithoutProjectPath() {
        val path = "/anywhere/minecraft-patched-26.1.2.75-merged.jar"
        assertEquals(GameJarProvenance.LOADER_PATCHED, classifyGameJarProvenance(path, null))
        assertNull(classifyGameJarProvenance("/anywhere/minecraft-merged-b34a2f0880-26.1.2.jar", null))
    }
}
