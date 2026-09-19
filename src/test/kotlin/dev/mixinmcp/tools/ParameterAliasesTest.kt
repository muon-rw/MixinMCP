package dev.mixinmcp.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ParameterAliasesTest {

    @Test
    fun aliasIsRewrittenToCanonicalName() {
        val args: JsonObject = buildJsonObject {
            put("path", "x")
            put("projectPath", "p")
        }
        val result = applyParameterAliases(args, mapOf("path" to "filePath"))
        assertTrue(result is AliasResult.Rewritten)
        val rewritten: JsonObject = (result as AliasResult.Rewritten).args
        assertEquals(setOf("filePath", "projectPath"), rewritten.keys)
        assertEquals("x", rewritten["filePath"]!!.jsonPrimitive.content)
    }

    @Test
    fun aliasAndCanonicalTogetherConflict() {
        val args: JsonObject = buildJsonObject {
            put("path", "x")
            put("filePath", "y")
        }
        val result = applyParameterAliases(args, mapOf("path" to "filePath"))
        assertTrue(result is AliasResult.Conflict)
        assertEquals("path", (result as AliasResult.Conflict).alias)
        assertEquals("filePath", result.canonical)
    }

    @Test
    fun argsWithoutAliasesPassThroughUntouched() {
        val args: JsonObject = buildJsonObject { put("filePath", "x") }
        val result = applyParameterAliases(args, mapOf("path" to "filePath"))
        assertSame(args, (result as AliasResult.Rewritten).args)
    }

    @Test
    fun renamedParametersKeepTheirOldNames() {
        assertEquals("ignoreConflicts", ParameterAliases.forTool("mixin_safe_delete")["force"])
        assertEquals("newMethodName", ParameterAliases.forTool("mixin_extract_method")["methodName"])
        assertEquals("filePath", ParameterAliases.forTool("mixin_refresh_vfs")["path"])
        assertTrue(ParameterAliases.forTool("mixin_find_class").isEmpty())
    }

    @Test
    fun unambiguousSynonymIsRewrittenSilently() {
        val args: JsonObject = buildJsonObject {
            put("limit", 5)
            put("class", "a.B")
        }
        val result = applyParameterAliases(args, emptyMap(), setOf("className", "maxResults", "projectPath"))
        val rewritten: JsonObject = (result as AliasResult.Rewritten).args
        assertEquals(setOf("maxResults", "className"), rewritten.keys)
        assertEquals("a.B", rewritten["className"]!!.jsonPrimitive.content)
    }

    @Test
    fun fileFilterIsRewrittenToFileMask() {
        val args: JsonObject = buildJsonObject { put("fileFilter", "*.java") }
        val result = applyParameterAliases(args, emptyMap(), setOf("regexPattern", "fileMask"))
        assertEquals(setOf("fileMask"), (result as AliasResult.Rewritten).args.keys)
    }

    @Test
    fun synonymThatMatchesTwoDeclaredNamesIsLeftAlone() {
        val args: JsonObject = buildJsonObject { put("method", "tick") }
        val result = applyParameterAliases(args, emptyMap(), setOf("methodName", "memberName", "kind"))
        assertSame(args, (result as AliasResult.Rewritten).args)
    }

    @Test
    fun declaredNameIsNeverRewritten() {
        val args: JsonObject = buildJsonObject { put("jar", "jade") }
        val result = applyParameterAliases(args, emptyMap(), setOf("jar", "jarPath"))
        assertSame(args, (result as AliasResult.Rewritten).args)
    }

    @Test
    fun suggestOnlySynonymIsNotRewritten() {
        val args: JsonObject = buildJsonObject { put("file", "a/B.java") }
        val result = applyParameterAliases(args, emptyMap(), setOf("url", "path"))
        assertSame(args, (result as AliasResult.Rewritten).args)
        assertEquals("path", closestAcceptedName("file", listOf("url", "path")))
    }

    @Test
    fun synonymConflictsWithAnExplicitCanonicalValue() {
        val args: JsonObject = buildJsonObject {
            put("limit", 5)
            put("maxResults", 10)
        }
        val result = applyParameterAliases(args, emptyMap(), setOf("maxResults"))
        assertTrue(result is AliasResult.Conflict)
    }

    @Test
    fun synonymsSuggestTheDeclaredName() {
        assertEquals("regexPattern", closestAcceptedName("pattern", listOf("regexPattern", "fileMask")))
        assertEquals("className", closestAcceptedName("symbol", listOf("className", "methodName")))
        assertEquals("maxResults", closestAcceptedName("limit", listOf("className", "maxResults")))
        assertEquals("path", closestAcceptedName("file", listOf("url", "path")))
    }

    @Test
    fun uniqueSubstringSuggestsTheDeclaredName() {
        assertEquals("className", closestAcceptedName("class", listOf("className", "methodName")))
        assertEquals("contextLines", closestAcceptedName("context", listOf("regexPattern", "contextLines")))
    }

    @Test
    fun ambiguousSubstringSuggestsNothing() {
        assertNull(closestAcceptedName("name", listOf("className", "methodName")))
    }

    @Test
    fun unrelatedNameSuggestsNothing() {
        assertNull(closestAcceptedName("colour", listOf("path", "lineNumber")))
    }
}
