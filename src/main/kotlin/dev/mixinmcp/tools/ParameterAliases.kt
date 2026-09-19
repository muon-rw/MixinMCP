package dev.mixinmcp.tools

/**
 * Former parameter names still accepted by [UnknownParameterRejectingTool], rewritten to the
 * canonical name before dispatch. Keeps calls written against older releases working.
 */
internal object ParameterAliases {

    private val byTool: Map<String, Map<String, String>> = mapOf(
        "mixin_refresh_vfs" to mapOf("path" to "filePath"),
        "mixin_safe_delete" to mapOf("force" to "ignoreConflicts"),
        "mixin_extract_method" to mapOf("methodName" to "newMethodName"),
    )

    fun forTool(toolName: String): Map<String, String> = byTool[toolName] ?: emptyMap()
}

internal fun normalizeParameterName(name: String): String =
    name.lowercase().filter { it != '_' && it != '-' }

/**
 * Names agents reach for that mean the same thing as a declared parameter, keyed by the
 * normalized (lowercase, no `_` or `-`) spelling.
 *
 * [autoAliases] are rewritten silently when exactly one of their targets is declared on the
 * tool, so the call runs as intended. [suggestOnly] entries could mean different things on
 * different tools (a disk path versus a classpath path, a target versus a new name), so they are
 * only ever proposed in the error message.
 */
internal object ParameterSynonyms {

    private val autoAliases: Map<String, List<String>> = mapOf(
        "pattern" to listOf("regexPattern"),
        "regex" to listOf("regexPattern"),
        "regexp" to listOf("regexPattern"),
        "search" to listOf("regexPattern"),
        "searchtext" to listOf("regexPattern"),
        "term" to listOf("regexPattern"),
        "symbol" to listOf("className"),
        "fqcn" to listOf("className"),
        "class" to listOf("className"),
        "clazz" to listOf("className"),
        "member" to listOf("memberName"),
        "method" to listOf("methodName", "memberName"),
        "function" to listOf("methodName", "memberName"),
        "field" to listOf("fieldName", "memberName"),
        "limit" to listOf("maxResults"),
        "max" to listOf("maxResults"),
        "count" to listOf("maxResults"),
        "maxcount" to listOf("maxResults"),
        "maxusagecount" to listOf("maxResults"),
        "file" to listOf("filePath"),
        "filename" to listOf("filePath"),
        "line" to listOf("lineNumber"),
        "lineno" to listOf("lineNumber"),
        "depth" to listOf("maxDepth"),
        "dir" to listOf("direction"),
        "jar" to listOf("jarPath"),
        "jarfile" to listOf("jarPath"),
        "entryname" to listOf("entry"),
        "entrypath" to listOf("entry"),
        "mask" to listOf("fileMask"),
        "glob" to listOf("fileMask"),
        "filefilter" to listOf("fileMask"),
        "prefix" to listOf("pathPrefix"),
        "descriptor" to listOf("methodDescriptor"),
        "params" to listOf("parameterTypes"),
        "args" to listOf("parameterTypes"),
        "version" to listOf("mcVersion"),
        "force" to listOf("ignoreConflicts"),
    )

    private val suggestOnly: Map<String, List<String>> = mapOf(
        "pattern" to listOf("query"),
        "regex" to listOf("query"),
        "search" to listOf("query"),
        "term" to listOf("query"),
        "text" to listOf("regexPattern", "query", "expression"),
        "type" to listOf("className"),
        "owner" to listOf("className"),
        "target" to listOf("targetClassName", "className"),
        "file" to listOf("path", "url"),
        "filename" to listOf("path", "url"),
        "sourcepath" to listOf("path", "filePath"),
        "source" to listOf("url", "path"),
        "offset" to listOf("lineNumber", "startLine"),
        "line" to listOf("startLine"),
        "name" to listOf("newName", "newMethodName"),
        "signature" to listOf("methodDescriptor", "parameterTypes"),
        "parameters" to listOf("parameterTypes", "parametersJson"),
        "root" to listOf("roots", "pathPrefix"),
        "package" to listOf("pathPrefix", "targetPackage"),
    )

    /** The declared name [normalizedName] can be rewritten to on this tool, or null when that is not unambiguous. */
    fun autoAlias(normalizedName: String, accepted: Collection<String>): String? {
        val candidates: List<String> = autoAliases[normalizedName] ?: return null
        val declared: List<String> = candidates.mapNotNull { candidate ->
            accepted.firstOrNull { normalizeParameterName(it) == normalizeParameterName(candidate) }
        }
        return declared.singleOrNull()
    }

    fun candidates(normalizedName: String): List<String> =
        (autoAliases[normalizedName] ?: emptyList()) + (suggestOnly[normalizedName] ?: emptyList())
}
