package dev.mixinmcp.tools

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.mcpserver.impl.util.asTools
import com.intellij.openapi.diagnostic.Logger
import dev.mixinmcp.tools.mappings.MappingsToolset
import dev.mixinmcp.tools.refactor.ChangeSignatureToolset
import dev.mixinmcp.tools.refactor.ExtractToolset
import dev.mixinmcp.tools.refactor.InlineToolset
import dev.mixinmcp.tools.refactor.MemberMoveToolset
import dev.mixinmcp.tools.refactor.SymbolRefactorToolset
import dev.mixinmcp.tools.semantic.SemanticNavigationToolset
import dev.mixinmcp.tools.source.SourceNavigationToolset

/**
 * Registers every MixinMCP toolset through `mcpToolsProvider` instead of `mcpToolset`, so each reflected
 * tool can be wrapped in [UnknownParameterRejectingTool]. The built-in `ReflectionToolsProvider` offers no
 * hook between argument decoding and dispatch.
 */
class MixinMcpToolsProvider : McpToolsProvider {

    private val toolsets: List<McpToolset> = listOf(
        SourceNavigationToolset(),
        SemanticNavigationToolset(),
        BytecodeInspectionToolset(),
        ProjectManagementToolset(),
        MappingsToolset(),
        ChangeSignatureToolset(),
        ExtractToolset(),
        InlineToolset(),
        MemberMoveToolset(),
        SymbolRefactorToolset(),
    )

    private val reflectedTools: List<McpTool> by lazy {
        toolsets.flatMap { toolset ->
            try {
                toolset.asTools().map(::UnknownParameterRejectingTool)
            } catch (e: Exception) {
                LOG.warn("Failed to reflect MCP tools from ${toolset::class.java.name}", e)
                emptyList()
            }
        }
    }

    override fun getTools(): List<McpTool> = reflectedTools

    companion object {
        private val LOG = Logger.getInstance(MixinMcpToolsProvider::class.java)
    }
}
