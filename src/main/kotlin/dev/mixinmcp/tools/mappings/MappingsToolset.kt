package dev.mixinmcp.tools.mappings

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue.FALSE
import com.intellij.mcpserver.annotations.McpToolHintValue.TRUE
import com.intellij.mcpserver.annotations.McpToolHints
import dev.mixinmcp.mappings.MappingNamespace
import dev.mixinmcp.mappings.MappingsResolver
import dev.mixinmcp.mappings.MappingsService
import dev.mixinmcp.mappings.McVersionDetector
import dev.mixinmcp.mappings.SymbolKind
import dev.mixinmcp.mappings.SymbolParser
import dev.mixinmcp.tools.softProject
import kotlin.coroutines.coroutineContext

@Suppress("FunctionName") // @McpTool functions are snake_case by MCP convention
class MappingsToolset : McpToolset {

    override fun isExperimental(): Boolean = false

    // openWorld: mappings are fetched over HTTP on first use and cached under ~/.cache/mixinmcp.
    @McpToolHints(readOnlyHint = TRUE, openWorldHint = TRUE)
    @McpTool
    @McpDescription(
        "Convert a Minecraft class/method/field name between mapping namespaces " +
            "(mojmap, yarn, intermediary, srg, obf). Downloads mappings on demand into " +
            "~/.cache/mixinmcp/mappings/. MC version is auto-detected from the open " +
            "project's gradle.properties if not given.\n\n" +
            "Symbol input uses internal (JVM) form but accepts '.' as a package separator. " +
            "Classes: 'net.minecraft.world.level.Level'. Methods: " +
            "'net.minecraft.world.level.Level.addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z' " +
            "(descriptor optional — all overloads listed if omitted). Fields: " +
            "'net.minecraft.world.level.Level.entities:Lnet/minecraft/world/entity/Entity;' " +
            "(type optional).",
    )
    @Suppress("unused")
    suspend fun mixin_mappings_lookup(
        symbol: String,
        kind: String,
        from: String,
        to: String,
        mcVersion: String? = null,
    ): McpToolCallResult {
        val kindEnum = SymbolKind.fromString(kind)
            ?: return McpToolCallResult.error(
                "Unknown kind '$kind'. Expected: ${SymbolKind.ALL_IDS.joinToString(", ")}.",
            )
        val fromNs = MappingNamespace.fromString(from)
            ?: return McpToolCallResult.error(
                "Unknown namespace '$from'. Expected: ${MappingNamespace.ALL_IDS.joinToString(", ")}.",
            )
        val toNs = MappingNamespace.fromString(to)
            ?: return McpToolCallResult.error(
                "Unknown namespace '$to'. Expected: ${MappingNamespace.ALL_IDS.joinToString(", ")}.",
            )

        val resolvedMcVersion = mcVersion?.takeIf { it.isNotBlank() }
            ?: coroutineContext.softProject()?.let(McVersionDetector::detect)
            ?: return McpToolCallResult.error(
                "Could not auto-detect Minecraft version from gradle.properties. " +
                    "Pass mcVersion explicitly (e.g., \"1.20.1\").",
            )

        val parsed = try {
            SymbolParser.parse(symbol, kindEnum)
        } catch (e: IllegalArgumentException) {
            return McpToolCallResult.error("Invalid symbol: ${e.message}")
        }

        if (fromNs == toNs) {
            return McpToolCallResult.text(
                "Source and target namespaces are both '${fromNs.id}'. Input unchanged: $symbol",
            )
        }

        val required = buildSet {
            add(fromNs)
            add(toNs)
        }

        val tree = try {
            MappingsService.getInstance().get(resolvedMcVersion, required)
        } catch (e: Exception) {
            return McpToolCallResult.error(
                "Failed to load mappings for $resolvedMcVersion: ${e.message}",
            )
        }

        val result = MappingsResolver.resolve(
            tree = tree,
            symbol = parsed,
            kind = kindEnum,
            from = fromNs,
            to = toNs,
            mcVersion = resolvedMcVersion,
        )

        return when (result) {
            is MappingsResolver.Result.Single -> McpToolCallResult.text(result.text)
            is MappingsResolver.Result.Ambiguous -> McpToolCallResult.text(result.text)
            is MappingsResolver.Result.NotFound -> McpToolCallResult.error(result.text)
        }
    }
}
