package dev.mixinmcp.tools

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.impl.util.projectPathParameterName
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The MCP framework binds arguments with `ignoreUnknownKeys`, so a misspelled or invented parameter
 * (`line_number`, `offset`) is dropped and the tool runs on defaults. To the caller that looks like a
 * wrong answer, not a wrong call. This wrapper rewrites renamed parameters and unambiguous synonyms
 * to their canonical names, rejects any other undeclared name before dispatch, and suggests the
 * declared parameter the caller most likely meant.
 *
 * [projectPathParameter] is read per call: the framework resolves that name through an extension point,
 * so it needs a running application and may be customised by other plugins.
 */
class UnknownParameterRejectingTool(
    private val delegate: McpTool,
    private val aliases: Map<String, String> = ParameterAliases.forTool(delegate.descriptor.name),
    private val projectPathParameter: () -> String = { projectPathParameterName },
) : McpTool {

    override val descriptor: McpToolDescriptor
        get() = delegate.descriptor

    override suspend fun call(args: JsonObject): McpToolCallResult {
        val accepted: Set<String> = descriptor.inputSchema.propertiesSchema.keys + projectPathParameter()
        val rewritten: JsonObject = when (val r = applyParameterAliases(args, aliases, accepted)) {
            is AliasResult.Rewritten -> r.args
            is AliasResult.Conflict -> return McpToolCallResult.error(
                "Parameters `${r.alias}` and `${r.canonical}` mean the same thing on ${descriptor.name}; " +
                    "pass only `${r.canonical}`. The call was not run.",
            )
        }
        val unknown: List<String> = unknownParameters(rewritten.keys, accepted)
        if (unknown.isNotEmpty()) {
            return McpToolCallResult.error(unknownParameterError(descriptor.name, unknown, accepted))
        }
        return delegate.call(rewritten)
    }
}

internal sealed class AliasResult {
    class Rewritten(val args: JsonObject) : AliasResult()
    class Conflict(val alias: String, val canonical: String) : AliasResult()
}

/**
 * Rewrites per-tool [aliases] and, for names not in [accepted], unambiguous synonyms from
 * [ParameterSynonyms]; a declared name is never rewritten.
 */
internal fun applyParameterAliases(
    args: JsonObject,
    aliases: Map<String, String>,
    accepted: Set<String> = emptySet(),
): AliasResult {
    fun canonicalOf(key: String): String {
        if (key in accepted) return key
        aliases[key]?.let { return it }
        return ParameterSynonyms.autoAlias(normalizeParameterName(key), accepted) ?: key
    }
    if (args.keys.all { canonicalOf(it) == it }) return AliasResult.Rewritten(args)
    val out = LinkedHashMap<String, JsonElement>()
    for ((key, value) in args) {
        val canonical: String = canonicalOf(key)
        if (canonical != key && (args.containsKey(canonical) || out.containsKey(canonical))) {
            return AliasResult.Conflict(key, canonical)
        }
        out[canonical] = value
    }
    return AliasResult.Rewritten(JsonObject(out))
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

/**
 * Spelling variants first (`line_number` for `lineNumber`), then synonyms that were not
 * unambiguous enough to apply silently (`file` for `path`), then a unique declared name that
 * starts or ends with the wanted word (`context` for `contextLines`).
 */
internal fun closestAcceptedName(name: String, accepted: Collection<String>): String? {
    val wanted: String = normalizeParameterName(name)
    if (wanted.isEmpty()) return null
    accepted.singleOrNull { normalizeParameterName(it) == wanted }?.let { return it }
    for (candidate in ParameterSynonyms.candidates(wanted)) {
        accepted.firstOrNull { normalizeParameterName(it) == normalizeParameterName(candidate) }?.let { return it }
    }
    return accepted.singleOrNull {
        val declared: String = normalizeParameterName(it)
        declared != wanted && (declared.startsWith(wanted) || declared.endsWith(wanted))
    }
}
