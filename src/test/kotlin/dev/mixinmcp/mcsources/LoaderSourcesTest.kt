package dev.mixinmcp.mcsources

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LoaderSourcesTest {

    private fun release(raw: String) = McVersionSpec.parse(raw) as McVersionSpec.Release

    @Test
    fun picksNewestForgeEvenWhenMetadataIsOutOfOrder() {
        // Forge's real metadata lists 47.4.5 before 47.4.4.
        val versions = listOf("1.20.1-47.4.5", "1.20.1-47.4.4", "1.20.1-47.4.29", "1.20.1-47.4.28", "1.19.2-43.3.0")
        assertEquals("47.4.29", LoaderVersionResolver.newestForge(versions, release("1.20.1")))
        assertNull(LoaderVersionResolver.newestForge(versions, release("1.18.2")))
    }

    @Test
    fun mapsMinecraftVersionsToNeoForgeLines() {
        val versions = listOf("21.0.0-beta", "21.0.5", "21.1.9", "21.1.267", "21.10.3", "26.3.0.112", "26.3.0.113")
        assertEquals("21.1.267", LoaderVersionResolver.newestNeoForge(versions, release("1.21.1")))
        assertEquals("21.0.5", LoaderVersionResolver.newestNeoForge(versions, release("1.21")))
        assertEquals("26.3.0.113", LoaderVersionResolver.newestNeoForge(versions, release("26.3")))
        assertEquals("21.0.0-beta", LoaderVersionResolver.newestNeoForge(listOf("21.0.0-beta"), release("1.21")))
    }

    @Test
    fun matchesFabricApiBySuffixAndLoaderByStability() {
        val api = listOf("0.101.2+1.21.1", "0.116.18+1.21.1", "0.116.9+1.21.1", "0.102.0+1.21", "0.166.3+26.4")
        assertEquals("0.116.18+1.21.1", LoaderVersionResolver.newestFabricApi(api, release("1.21.1")))
        assertEquals("0.102.0+1.21", LoaderVersionResolver.newestFabricApi(api, release("1.21")))
        assertEquals("0.166.3+26.4", LoaderVersionResolver.newestFabricApi(api, release("26.4")))

        val loader = listOf("0.1.0.48", "0.16.9", "0.20.1", "0.21.0-beta.1")
        assertEquals("0.20.1", LoaderVersionResolver.newestFabricLoader(loader))
    }

    @Test
    fun resolvesExplicitVersionsWithoutNetwork() = runBlocking {
        assertEquals("neoforge-21.1.86", LoaderSourcesRequest.resolve("neoforge", null, "21.1.86").cacheKey)
        assertEquals("forge-1.20.1-47.3.0", LoaderSourcesRequest.resolve("forge", "1.20.1", "47.3.0").cacheKey)
        assertEquals("forge-1.20.1-47.3.0", LoaderSourcesRequest.resolve("forge", null, "1.20.1-47.3.0").cacheKey)
        assertEquals("fabric-loader-0.16.9", LoaderSourcesRequest.resolve("fabric_loader", null, "0.16.9").cacheKey)

        val api = LoaderSourcesRequest.resolve("fabric-api", "1.21.1", "0.116.18")
        assertEquals("fabric-api-0.116.18+1.21.1", api.cacheKey)
        assertEquals("1.21.1", api.fabricApiMcVersion)
        McSourcesCache.validateKey(api.cacheKey)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { LoaderSourcesRequest.resolve("fabric-api", null, "0.116.18") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { LoaderSourcesRequest.resolve("quilt", null, "1.0.0") }
        }
        Unit
    }

    @Test
    fun parsesFabricApiModulesFromPom() {
        val pom = """
            <project>
              <dependencies>
                <dependency>
                  <groupId>net.fabricmc.fabric-api</groupId>
                  <artifactId>fabric-api-base</artifactId>
                  <version>0.4.42+6573ed8c19</version>
                </dependency>
                <dependency>
                  <groupId>net.fabricmc</groupId>
                  <artifactId>fabric-loader</artifactId>
                  <version>0.16.9</version>
                </dependency>
              </dependencies>
            </project>
        """.trimIndent()
        assertEquals(
            listOf(FabricApiModule("fabric-api-base", "0.4.42+6573ed8c19")),
            LoaderSourcesFetcher.parseFabricApiModules(pom),
        )
    }

    @Test
    fun remapsIntermediaryTokensInEveryForm() {
        val remapper = IntermediaryRemapper(
            classes = mapOf(
                "net/minecraft/class_1657" to "net/minecraft/world/entity/player/Player",
                "net/minecraft/class_2338" to "net/minecraft/core/BlockPos",
                "net/minecraft/class_2338\$class_2339" to "net/minecraft/core/BlockPos\$MutableBlockPos",
            ),
            members = mapOf("method_5643" to "hurt", "field_6002" to "level"),
        )
        val source = """
            import net.minecraft.class_1657;
            import net.minecraft.class_2338;
            @Inject(method = "method_5643", at = @At(value = "INVOKE", target = "Lnet/minecraft/class_2338${'$'}class_2339;method_5643()V"))
            void f(class_1657 player, class_2338.class_2339 pos) { player.field_6002.method_5643(); }
        """.trimIndent()
        val expected = """
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.core.BlockPos;
            @Inject(method = "hurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos${'$'}MutableBlockPos;hurt()V"))
            void f(Player player, BlockPos.MutableBlockPos pos) { player.level.hurt(); }
        """.trimIndent()
        assertEquals(expected, remapper.remap(source))
    }

    @Test
    fun remapsYarnMixinStringsUsingTheMixinTarget() {
        val gameMode = "net/minecraft/server/network/ServerPlayerInteractionManager"
        val block = "net/minecraft/block/Block"
        val world = "net/minecraft/world/World"
        val yarn = YarnStringRemapper(
            classes = mapOf(
                gameMode to "net/minecraft/server/level/ServerPlayerGameMode",
                block to "net/minecraft/world/level/block/Block",
                world to "net/minecraft/world/level/Level",
            ),
            methods = mapOf(
                gameMode to listOf(YarnStringRemapper.Member("interactBlock", "(L$world;)V", "useItemOn")),
                block to listOf(YarnStringRemapper.Member("onBreak", "(L$world;)V", "playerWillDestroy")),
            ),
            fields = mapOf(gameMode to listOf(YarnStringRemapper.Member("world", "L$world;", "level"))),
        )
        // The intermediary pass has already turned class literals and imports into Mojang names.
        val source = """
            import net.minecraft.server.level.ServerPlayerGameMode;
            @Mixin(ServerPlayerGameMode.class)
            class M {
                @Inject(method = "interactBlock", at = @At(value = "INVOKE", target = "L$block;onBreak(L$world;)V"))
                void a() {}
                @Inject(method = {"interactBlock(L$world;)V", "unknownMethod"}, at = @At("HEAD"))
                void b() {}
                @Accessor("world") abstract Object level();
                @Inject(method = "interactBlock", at = @At(value = "INVOKE", target = "$block.onBreak(L$world;)V")) void c() {}
                String plain = "interactBlock";
            }
        """.trimIndent()
        val remapped = yarn.remap(source)
        val level = "net/minecraft/world/level/Level"
        assertTrue(remapped, "method = \"useItemOn\"" in remapped)
        assertTrue(remapped, "target = \"Lnet/minecraft/world/level/block/Block;playerWillDestroy(L$level;)V\"" in remapped)
        assertTrue(remapped, "{\"useItemOn(L$level;)V\", \"unknownMethod\"}" in remapped)
        assertTrue(remapped, "@Accessor(\"level\")" in remapped)
        assertTrue(remapped, "target = \"net/minecraft/world/level/block/Block.playerWillDestroy(L$level;)V\"" in remapped)
        assertTrue(remapped, "plain = \"interactBlock\"" in remapped)
        // Yarn never lists unknownMethod, so it isn't a Yarn name and isn't reported.
        assertEquals(emptySet<String>(), yarn.unresolved)
    }

    @Test
    fun resolvesOverridesThroughTheClassHierarchy() {
        val entity = "net/minecraft/entity/Entity"
        val living = "net/minecraft/entity/LivingEntity"
        val item = "net/minecraft/item/ItemStack"
        val hierarchy = ClassHierarchy.parse("a b\nb\n")
        assertEquals(listOf("b"), hierarchy.ancestors("a"))
        assertEquals(emptyList<String>(), hierarchy.ancestors("b"))

        // Yarn lists damage on Entity only, and ItemStack's damage makes the bare name ambiguous
        // game-wide, so only the hierarchy can resolve LivingEntity's override.
        val yarn = YarnStringRemapper(
            classes = mapOf(
                entity to "net/minecraft/world/entity/Entity",
                living to "net/minecraft/world/entity/LivingEntity",
                item to "net/minecraft/world/item/ItemStack",
            ),
            methods = mapOf(
                // Two overloads with different Mojang names make the bare name "tickMovement" ambiguous.
                entity to listOf(
                    YarnStringRemapper.Member("damage", "()Z", "hurt"),
                    YarnStringRemapper.Member("tickMovement", "()V", "aiStep"),
                    YarnStringRemapper.Member("tickMovement", "(I)V", "aiStepFor"),
                ),
                item to listOf(YarnStringRemapper.Member("damage", "()V", "hurtAndBreak")),
            ),
            fields = emptyMap(),
            ancestorsOf = { if (it == living) listOf(entity) else emptyList() },
        )
        val source = """
            import net.minecraft.world.entity.LivingEntity;
            @Mixin(LivingEntity.class)
            class M {
                @Inject(method = "damage", at = @At("HEAD")) void a() {}
                @Inject(method = "tickMovement", at = @At("HEAD")) void b() {}
                @Inject(method = "close", at = @At("HEAD")) void c() {}
            }
        """.trimIndent()
        val lines = yarn.remap(source).lines()
        assertEquals("    @Inject(method = \"hurt\", at = @At(\"HEAD\")) void a() {}", lines[3])
        assertEquals(
            "    @Inject(method = \"tickMovement\", at = @At(\"HEAD\")) void b() {} // MixinMCP: \"tickMovement\" " +
                "still Yarn; mixin_mappings_lookup(from=\"yarn\", to=\"mojmap\") converts it.",
            lines[4],
        )
        // No Minecraft class in LivingEntity's ancestry declares close() (it comes from AutoCloseable),
        // so it keeps its name in every namespace and is left unmarked.
        assertEquals("    @Inject(method = \"close\", at = @At(\"HEAD\")) void c() {}", lines[5])
        assertEquals(setOf("tickMovement"), yarn.unresolved)
    }

    @Test
    fun nestedMixinsUseTheirOwnTargets() {
        val engine = "net/minecraft/client/particle/ParticleManager"
        val spriteSet = "net/minecraft/client/particle/ParticleManager\$SimpleSpriteProvider"
        val yarn = YarnStringRemapper(
            classes = mapOf(
                engine to "net/minecraft/client/particle/ParticleEngine",
                spriteSet to "net/minecraft/client/particle/ParticleEngine\$MutableSpriteSet",
            ),
            methods = emptyMap(),
            fields = mapOf(
                engine to listOf(YarnStringRemapper.Member("spriteAwareFactories", null, "spriteSets")),
                spriteSet to listOf(YarnStringRemapper.Member("sprites", null, "spriteList")),
            ),
        )
        val source = """
            import net.minecraft.client.particle.ParticleEngine;
            @Mixin(ParticleEngine.class)
            interface A {
                @Accessor("spriteAwareFactories") Object a();
                @Mixin(ParticleEngine.MutableSpriteSet.class)
                interface B { @Accessor("sprites") Object b(); }
            }
        """.trimIndent()
        val remapped = yarn.remap(source)
        assertTrue(remapped, "@Accessor(\"spriteSets\")" in remapped)
        assertTrue(remapped, "@Accessor(\"spriteList\")" in remapped)
        assertEquals(emptySet<String>(), yarn.unresolved)
    }

    @Test
    fun leavesUnmappedAndUnrelatedTextAlone() {
        val remapper = IntermediaryRemapper(mapOf(), mapOf("method_1" to "tick"))
        assertEquals("class_9999 x; myclass_1 y; tick();", remapper.remap("class_9999 x; myclass_1 y; method_1();"))
        assertEquals("no intermediary here", remapper.remap("no intermediary here"))
    }
}
