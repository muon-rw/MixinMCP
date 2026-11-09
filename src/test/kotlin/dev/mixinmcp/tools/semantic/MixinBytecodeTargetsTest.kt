package dev.mixinmcp.tools.semantic

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation
import dev.mixinmcp.tools.source.BytecodeScanStats
import dev.mixinmcp.tools.source.ClassFileView
import dev.mixinmcp.tools.source.walkJarClasses
import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Overwrite
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Suppress("unused")
open class MixinFixtureTarget {
    fun tick() {}

    class Inner
}

@Suppress("unused")
@Mixin(value = [MixinFixtureTarget::class, MixinFixtureTarget.Inner::class], priority = 900)
abstract class MixinFixtureTargetMixin {
    @Inject(method = ["tick"], at = [At("HEAD")], cancellable = true)
    fun cancelTick() {}

    @WrapOperation(method = ["render(F)V"], at = [At(value = "INVOKE", target = "Lfoo/Bar;baz()V")])
    fun wrapRender() {}

    @Overwrite
    fun update() {}

    fun helper() {}
}

@Suppress("unused")
@Mixin(targets = ["net.minecraft.class_2794"])
abstract class IntermediaryFixtureMixin {
    @Inject(method = ["method_12103"], at = [At("TAIL")])
    fun afterLocate() {}
}

class MixinBytecodeTargetsTest {

    private fun classBytes(type: Class<*>): ByteArray =
        type.getResourceAsStream("/${type.name.replace('.', '/')}.class")!!.use { it.readBytes() }

    private fun mixinOf(type: Class<*>): BytecodeMixin? = ClassFileView.parse(classBytes(type))?.let(::readBytecodeMixin)

    private val targetName: String = MixinFixtureTarget::class.java.name.replace('.', '/')

    @Test
    fun readsClassLiteralTargetsIncludingNestedClasses() {
        val mixin: BytecodeMixin = mixinOf(MixinFixtureTargetMixin::class.java)!!
        assertEquals(listOf(targetName, "$targetName\$Inner"), mixin.targets)
        assertEquals(MixinFixtureTargetMixin::class.java.name, mixin.className)
    }

    @Test
    fun readsInjectorsFromBothPackagesAndOverwrite() {
        val injectors: List<BytecodeInjector> = mixinOf(MixinFixtureTargetMixin::class.java)!!.injectors
        assertEquals(listOf("cancelTick", "wrapRender", "update"), injectors.map { it.handlerName })
        val inject: String = injectors[0].render()
        assertTrue(inject, inject.startsWith("@Inject("))
        assertTrue(inject, "method = \"tick\"" in inject && "at = @At(\"HEAD\")" in inject && "cancellable = true" in inject)
        assertTrue(inject, inject.endsWith("-> cancelTick"))
        assertTrue(injectors[1].render(), "@At(value = \"INVOKE\", target = \"Lfoo/Bar;baz()V\")" in injectors[1].render())
        assertEquals(listOf("update"), injectors[2].methodSelectors)
    }

    @Test
    fun stringTargetsAreNormalizedToInternalNames() {
        assertEquals(listOf("net/minecraft/class_2794"), mixinOf(IntermediaryFixtureMixin::class.java)!!.targets)
    }

    @Test
    fun nonMixinClassesAreIgnored() {
        assertNull(mixinOf(MixinFixtureTarget::class.java))
    }

    @Test
    fun targetNamesMatchInternalDottedAndNestedForms() {
        val names = TargetNames(setOf("a/b/Outer\$Inner"), setOf("tick"), null)
        assertTrue(names.matchesClass("a/b/Outer\$Inner"))
        assertTrue(names.matchesClass("a.b.Outer.Inner"))
        assertFalse(names.matchesClass("a/b/Outer"))
        assertTrue(names.matchesMethod("tick"))
        assertTrue(names.matchesMethod("tick()V"))
        assertTrue(names.matchesMethod("La/b/Outer;tick()V"))
        assertFalse(names.matchesMethod("ticker"))
        assertEquals(listOf("Inner"), names.simpleNames)
    }

    @Test
    fun injectorMatchesAtTargetOnMixinTargetClassOnly() {
        val injectors: List<BytecodeInjector> = mixinOf(MixinFixtureTargetMixin::class.java)!!.injectors
        val wrapRender: BytecodeInjector = injectors.single { it.handlerName == "wrapRender" }
        val fooBarBaz = TargetNames(setOf("foo/Bar"), setOf("baz"), null)
        val otherOwnerBaz = TargetNames(setOf(targetName), setOf("baz"), null)
        assertTrue(wrapRender.targetsMethod(fooBarBaz))
        assertFalse(wrapRender.targetsMethod(otherOwnerBaz))
    }

    @Test
    fun walksNestedJarsAndFindsMixins() {
        val inner = ByteArrayOutputStream()
        ZipOutputStream(inner).use { zip ->
            for (type in listOf(MixinFixtureTargetMixin::class.java, MixinFixtureTarget::class.java)) {
                zip.putNextEntry(ZipEntry("${type.name.replace('.', '/')}.class"))
                zip.write(classBytes(type))
                zip.closeEntry()
            }
        }
        val outer: File = File.createTempFile("mixinmcp-mixins", ".jar").apply { deleteOnExit() }
        ZipOutputStream(FileOutputStream(outer)).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/jarjar/inner.jar"))
            zip.write(inner.toByteArray())
            zip.closeEntry()
        }
        val names = TargetNames(setOf(targetName), emptySet(), null)
        val found: MutableList<Pair<String, String>> = mutableListOf()
        walkJarClasses(outer, null, BytecodeScanStats(), { false }) { container, bytes ->
            val mixin: BytecodeMixin = ClassFileView.parse(bytes)?.let(::readBytecodeMixin) ?: return@walkJarClasses
            if (mixin.targets.any(names::matchesClass)) found.add(container to mixin.className)
        }
        assertEquals(listOf("${outer.name} !/META-INF/jarjar/inner.jar" to MixinFixtureTargetMixin::class.java.name), found)
    }

    @Test
    fun expandsClassAndMethodAcrossNamespaces() {
        val tree = MemoryMappingTree()
        tree.visitHeader()
        tree.visitNamespaces("obf", listOf("mojmap", "intermediary", "yarn"))
        tree.visitContent()
        tree.visitClass("abc")
        tree.visitDstName(MappedElementKind.CLASS, 0, "net/minecraft/world/level/chunk/ChunkGenerator")
        tree.visitDstName(MappedElementKind.CLASS, 1, "net/minecraft/class_2794")
        tree.visitDstName(MappedElementKind.CLASS, 2, "net/minecraft/world/gen/chunk/ChunkGenerator")
        tree.visitElementContent(MappedElementKind.CLASS)
        tree.visitMethod("a", "()V")
        tree.visitDstName(MappedElementKind.METHOD, 0, "getNearestGeneratedStructure")
        tree.visitDstName(MappedElementKind.METHOD, 1, "method_12103")
        tree.visitDstName(MappedElementKind.METHOD, 2, "locateStructure")
        tree.visitEnd()

        val names: TargetNames = expandWithTree(tree, "net/minecraft/world/level/chunk/ChunkGenerator", "getNearestGeneratedStructure")!!
        assertEquals(
            setOf("net/minecraft/world/level/chunk/ChunkGenerator", "net/minecraft/class_2794", "net/minecraft/world/gen/chunk/ChunkGenerator"),
            names.classNames,
        )
        assertEquals(setOf("getNearestGeneratedStructure", "method_12103", "locateStructure"), names.methodNames)
        assertTrue(names.matchesClass("net.minecraft.class_2794"))
        assertNull(expandWithTree(tree, "net/minecraft/Unknown", null))
    }

    @Test
    fun majorVersionParsesOldAndNewSchemes() {
        assertEquals(1, majorVersionOf("1.21.1"))
        assertEquals(26, majorVersionOf("26.1.2"))
        assertNull(majorVersionOf("latest"))
    }
}
