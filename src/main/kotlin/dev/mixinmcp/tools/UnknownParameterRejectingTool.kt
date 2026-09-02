package dev.mixinmcp.tools

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.impl.util.projectPathParameterName
import kotlinx.serialization.json.JsonObject

/**
 * The MCP framework binds arguments with `ignoreUnknownKeys`, so a misspelled or invented parameter
 * (`line_number`, `offset`) is dropped and the tool runs on defaults. To the caller that looks like a
 * wrong answer, not a wrong call. This wrapper rejects such calls before dispatch and names the
 * accepted parameters.
 *
 * [projectPathParameter] is read per call: the framework resolves that name through an extension point,
 * so it needs a running application and may be customised by other plugins.
 */
class UnknownParameterRejectingTool(
    private val delegate: McpTool,
    private val projectPathParameter: () -> String = { projectPathParameterName },
) : McpTool {

    override val descriptor: McpToolDescriptor
        get() = delegate.descriptor

    override suspend fun call(args: JsonObject): McpToolCallResult {
        val accepted: Set<String> = descriptor.inputSchema.propertiesSchema.keys + projectPathParameter()
        val unknown: List<String> = unknownParameters(args.keys, accepted)
        if (unknown.isNotEmpty()) {
            return McpToolCallResult.error(unknownParameterError(descriptor.name, unknown, accepted))
        }
        return delegate.call(args)
    }
}

internal fun unknownParameters(argumentNames: Collection<String>, accepted: Set<String>): List<String> =
    argumentNames.filter { it !in accepted }

internal fun unknownParameterError(toolName: String, unknown: List<String>, accepted: Collection<String>): String =
    buildString {
        append("Unknown parameter")
        if (unknown.size > 1) append("s")
        append(" for $toolName: ")
        append(unknown.joinToString(", ") { "`$it`" })
        append(". The call was not run. ")
        val suggestions: List<String> = unknown.mapNotNull { name ->
            closestAcceptedName(name, accepted)?.let { "`$it` instead of `$name`" }
        }
        if (suggestions.isNotEmpty()) {
            append("Did you mean ${suggestions.joinToString(", ")}? ")
        }
        append("Accepted parameters: ${accepted.joinToString(", ") { "`$it`" }}. Retry using only those names.")
    }

private fun closestAcceptedName(name: String, accepted: Collection<String>): String? {
    val wanted: String = normalizeParameterName(name)
    return accepted.singleOrNull { normalizeParameterName(it) == wanted }
}

private fun normalizeParameterName(name: String): String =
    name.lowercase().filter { it != '_' && it != '-' }
