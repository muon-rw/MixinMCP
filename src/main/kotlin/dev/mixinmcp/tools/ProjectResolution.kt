package dev.mixinmcp.tools

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlin.coroutines.CoroutineContext

/**
 * Project-resolution helpers shared by every MCP toolset.
 *
 * The IntelliJ MCP framework auto-injects a top-level `projectPath` argument
 * into every tool's JSON schema and resolves it before dispatch. When the
 * caller omits that argument and more than one IDE window is open, the
 * framework's `coroutineContext.projectOrNull` does NOT return null — it
 * raises [McpExpectedError] with the bare message "No exact project is
 * specified while multiple projects are opened." That throw bypasses the
 * usual `?: return McpToolCallResult.error(...)` Elvis pattern, so the agent
 * sees an opaque framework error with no hint about how to recover.
 *
 * [requireProject] catches that throw and turns it into a structured
 * [McpToolCallResult.error] that lists the open project paths and reminds
 * the agent to retry with `projectPath` set. [softProject] is the
 * fallback-friendly variant for tools whose work has a non-project path
 * (currently only `mixin_mappings_lookup`, which can take an explicit
 * `mcVersion`).
 */
internal inline fun CoroutineContext.requireProject(
    onError: (McpToolCallResult) -> Nothing,
): Project {
    val resolved: Project? = try {
        this.projectOrNull
    } catch (_: McpExpectedError) {
        onError(multiProjectError())
    }
    return resolved ?: onError(McpToolCallResult.error(NO_PROJECT_OPEN_MESSAGE))
}

internal fun CoroutineContext.softProject(): Project? = try {
    this.projectOrNull
} catch (_: McpExpectedError) {
    null
}

private fun multiProjectError(): McpToolCallResult {
    val open: List<Project> = ProjectManager.getInstance().openProjects.toList()
    val paths: String = open.joinToString("\n") { p ->
        "  - ${p.basePath ?: "(no base path)"}"
    }
    return McpToolCallResult.error(
        buildString {
            appendLine("Multiple IntelliJ projects are open and no project was selected for this call.")
            appendLine("Retry with the auto-injected `projectPath` argument set to one of:")
            appendLine(paths)
            append("Pick the project that contains the class or file you're targeting. ")
            append("`projectPath` is a top-level parameter the IntelliJ MCP framework adds to every tool — ")
            append("pass it alongside the tool's other arguments.")
        },
    )
}

private const val NO_PROJECT_OPEN_MESSAGE: String =
    "No IntelliJ project is open. Open the Minecraft mod project in the IDE, then retry."
