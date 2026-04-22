package dev.mixinmcp.mappings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SymbolParserTest {

    @Test
    fun classFormatDotSeparators() {
        val parsed = SymbolParser.parse("net.minecraft.world.level.Level", SymbolKind.CLASS)
        assertEquals("net/minecraft/world/level/Level", parsed.ownerInternalName)
        assertNull(parsed.memberName)
        assertNull(parsed.descriptor)
    }

    @Test
    fun classFormatSlashSeparators() {
        val parsed = SymbolParser.parse("net/minecraft/world/level/Level", SymbolKind.CLASS)
        assertEquals("net/minecraft/world/level/Level", parsed.ownerInternalName)
    }

    @Test
    fun classFormatNestedClassesKeepDollarSign() {
        val parsed = SymbolParser.parse("net.minecraft.Foo\$Bar", SymbolKind.CLASS)
        assertEquals("net/minecraft/Foo\$Bar", parsed.ownerInternalName)
    }

    @Test
    fun methodFormatWithDescriptor() {
        val parsed = SymbolParser.parse(
            "net.minecraft.world.level.Level.addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z",
            SymbolKind.METHOD,
        )
        assertEquals("net/minecraft/world/level/Level", parsed.ownerInternalName)
        assertEquals("addFreshEntity", parsed.memberName)
        assertEquals("(Lnet/minecraft/world/entity/Entity;)Z", parsed.descriptor)
    }

    @Test
    fun methodFormatWithoutDescriptor() {
        val parsed = SymbolParser.parse(
            "net.minecraft.world.level.Level.addFreshEntity",
            SymbolKind.METHOD,
        )
        assertEquals("net/minecraft/world/level/Level", parsed.ownerInternalName)
        assertEquals("addFreshEntity", parsed.memberName)
        assertNull(parsed.descriptor)
    }

    @Test
    fun methodFormatOnNestedClass() {
        val parsed = SymbolParser.parse(
            "net.minecraft.Foo\$Bar.doThing()V",
            SymbolKind.METHOD,
        )
        assertEquals("net/minecraft/Foo\$Bar", parsed.ownerInternalName)
        assertEquals("doThing", parsed.memberName)
        assertEquals("()V", parsed.descriptor)
    }

    @Test
    fun fieldFormatWithType() {
        val parsed = SymbolParser.parse(
            "net.minecraft.world.level.Level.entities:Lnet/minecraft/world/entity/Entity;",
            SymbolKind.FIELD,
        )
        assertEquals("net/minecraft/world/level/Level", parsed.ownerInternalName)
        assertEquals("entities", parsed.memberName)
        assertEquals("Lnet/minecraft/world/entity/Entity;", parsed.descriptor)
    }

    @Test
    fun fieldFormatWithoutType() {
        val parsed = SymbolParser.parse(
            "net.minecraft.world.level.Level.entities",
            SymbolKind.FIELD,
        )
        assertEquals("net/minecraft/world/level/Level", parsed.ownerInternalName)
        assertEquals("entities", parsed.memberName)
        assertNull(parsed.descriptor)
    }

    @Test
    fun fieldFormatWithPrimitiveType() {
        val parsed = SymbolParser.parse(
            "net.minecraft.world.level.Level.seed:J",
            SymbolKind.FIELD,
        )
        assertEquals("seed", parsed.memberName)
        assertEquals("J", parsed.descriptor)
    }

    @Test
    fun classWithoutSlashIsNotRequiredButMemberFormsAre() {
        // Single-segment class name is valid as a class kind
        val parsed = SymbolParser.parse("SimpleClass", SymbolKind.CLASS)
        assertEquals("SimpleClass", parsed.ownerInternalName)
    }

    @Test
    fun methodWithoutOwnerThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            SymbolParser.parse("doThing()V", SymbolKind.METHOD)
        }
    }

    @Test
    fun namespaceFromStringIsCaseInsensitive() {
        assertEquals(MappingNamespace.MOJMAP, MappingNamespace.fromString("mojmap"))
        assertEquals(MappingNamespace.MOJMAP, MappingNamespace.fromString("MojMap"))
        assertEquals(MappingNamespace.MOJMAP, MappingNamespace.fromString("MOJMAP"))
        assertNull(MappingNamespace.fromString("parchment"))
    }

    @Test
    fun symbolKindFromStringIsCaseInsensitive() {
        assertEquals(SymbolKind.METHOD, SymbolKind.fromString("method"))
        assertEquals(SymbolKind.METHOD, SymbolKind.fromString("Method"))
        assertNull(SymbolKind.fromString("func"))
    }
}
