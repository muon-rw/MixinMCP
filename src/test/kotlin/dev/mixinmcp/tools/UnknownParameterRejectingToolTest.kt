package dev.mixinmcp.tools

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.McpToolCategory
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnknownParameterRejectingToolTest {

    private class RecordingTool : McpTool {
        var received: JsonObject? = null

        override val descriptor: McpToolDescriptor = McpToolDescriptor(
            name = "mixin_demo",
            description = "demo",
            category = McpToolCategory(
                shortName = "Demo",
                fullyQualifiedName = "dev.mixinmcp.Demo",
                isExperimental = false,
                alwaysIncluded = false,
            ),
            fullyQualifiedName = "dev.mixinmcp.Demo.mixin_demo",
            inputSchema = McpToolSchema(
                propertiesSchema = buildJsonObject {
                    put("path", buildJsonObject { put("type", "string") })
                    put("lineNumber", buildJsonObject { put("type", "integer") })
                },
                requiredProperties = emptySet(),
                definitions = emptyMap(),
            ),
        )

        override suspend fun call(args: JsonObject): McpToolCallResult {
            received = args
            return McpToolCallResult.text("ok")
        }
    }

    private fun text(result: McpToolCallResult): String =
        (result.content.single() as McpToolCallResultContent.Text).text

    @Test
    fun declaredParametersAndProjectPathPassThroughUntouched() {
        runBlocking {
            val delegate = RecordingTool()
            val args: JsonObject = buildJsonObject {
                put("path", "a/B.java")
                put("lineNumber", 3)
                put("projectPath", "C:/p")
            }
            val result: McpToolCallResult = UnknownParameterRejectingTool(delegate) { "projectPath" }.call(args)
            assertFalse(result.isError)
            assertEquals("ok", text(result))
            assertEquals(args, delegate.received)
        }
    }

    @Test
    fun unknownParameterRejectsBeforeDispatch() {
        runBlocking {
            val delegate = RecordingTool()
            val result: McpToolCallResult = UnknownParameterRejectingTool(delegate) { "projectPath" }.call(
                buildJsonObject {
                    put("path", "a/B.java")
                    put("startLine", 420)
                    put("endLine", 465)
                },
            )
            assertTrue(result.isError)
            assertNull(delegate.received)
            assertEquals(
                "Unknown parameters for mixin_demo: `startLine`, `endLine`. The call was not run. " +
                    "Accepted parameters: `path`, `lineNumber`, `projectPath`. Retry using only those names.",
                text(result),
            )
        }
    }

    @Test
    fun descriptorIsDelegatedUnchanged() {
        val delegate = RecordingTool()
        assertEquals(delegate.descriptor, UnknownParameterRejectingTool(delegate) { "projectPath" }.descriptor)
    }

    @Test
    fun nearMissSuggestsTheDeclaredName() {
        assertEquals(
            "Unknown parameter for mixin_demo: `line_number`. The call was not run. " +
                "Did you mean `lineNumber` instead of `line_number`? " +
                "Accepted parameters: `path`, `lineNumber`. Retry using only those names.",
            unknownParameterError("mixin_demo", listOf("line_number"), listOf("path", "lineNumber")),
        )
    }

    @Test
    fun caseOnlyMismatchSuggestsTheDeclaredName() {
        assertTrue(
            unknownParameterError("mixin_demo", listOf("linenumber"), listOf("path", "lineNumber"))
                .contains("Did you mean `lineNumber` instead of `linenumber`?"),
        )
    }

    @Test
    fun noSuggestionWithoutANearMiss() {
        assertFalse(
            unknownParameterError("mixin_demo", listOf("offset"), listOf("path", "lineNumber")).contains("Did you mean"),
        )
    }

    @Test
    fun unknownParametersKeepCallOrder() {
        assertEquals(listOf("b", "a"), unknownParameters(listOf("b", "path", "a"), setOf("path")))
    }

    @Test
    fun nothingIsUnknownWhenEveryNameIsDeclared() {
        assertTrue(unknownParameters(listOf("path", "lineNumber"), setOf("path", "lineNumber")).isEmpty())
    }
}
