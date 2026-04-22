package dev.mixinmcp.mappings

import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MappingTreeView.ClassMappingView
import net.fabricmc.mappingio.tree.MappingTreeView.FieldMappingView
import net.fabricmc.mappingio.tree.MappingTreeView.MethodMappingView

object MappingsResolver {

    sealed class Result {
        data class Single(val text: String) : Result()
        data class Ambiguous(val text: String) : Result()
        data class NotFound(val text: String) : Result()
    }

    fun resolve(
        tree: MappingTreeView,
        symbol: ParsedSymbol,
        kind: SymbolKind,
        from: MappingNamespace,
        to: MappingNamespace,
        mcVersion: String,
    ): Result {
        val fromNsId = nsIdOf(tree, from)
            ?: return Result.NotFound("Namespace '${from.id}' not present in loaded mappings for $mcVersion.")
        val toNsId = nsIdOf(tree, to)
            ?: return Result.NotFound("Namespace '${to.id}' not present in loaded mappings for $mcVersion.")

        val cls = findClass(tree, symbol.ownerInternalName, fromNsId)
            ?: return Result.NotFound(
                "Class '${symbol.ownerInternalName}' not found in ${from.id} mappings for $mcVersion.",
            )

        val classTo = nameInNs(cls, toNsId)
            ?: return Result.NotFound(
                "Class '${symbol.ownerInternalName}' has no ${to.id} mapping.",
            )

        return when (kind) {
            SymbolKind.CLASS -> Result.Single(formatClassLine(cls, tree, from, to, fromNsId, toNsId))
            SymbolKind.METHOD -> resolveMethod(cls, tree, symbol, from, to, fromNsId, toNsId, mcVersion, classTo)
            SymbolKind.FIELD -> resolveField(cls, tree, symbol, from, to, fromNsId, toNsId, mcVersion, classTo)
        }
    }

    private fun resolveMethod(
        cls: ClassMappingView,
        tree: MappingTreeView,
        symbol: ParsedSymbol,
        from: MappingNamespace,
        to: MappingNamespace,
        fromNsId: Int,
        toNsId: Int,
        mcVersion: String,
        classTo: String,
    ): Result {
        val memberName = symbol.memberName!!
        val descriptor = symbol.descriptor
        val matches = cls.methods.filter { m ->
            val name = nameInNs(m, fromNsId)
            name == memberName && (descriptor == null || descInNs(m, fromNsId) == descriptor)
        }
        if (matches.isEmpty()) {
            val available = cls.methods.mapNotNull { nameInNs(it, fromNsId) }.distinct().sorted()
            return Result.NotFound(
                "Method '$memberName' not found on class in ${from.id} for $mcVersion. " +
                    "Available in ${from.id}: ${available.take(40).joinToString(", ")}" +
                    if (available.size > 40) " (+${available.size - 40} more)" else "",
            )
        }
        val lines = buildString {
            appendLine(formatClassLine(cls, tree, from, to, fromNsId, toNsId))
            matches.forEachIndexed { i, m ->
                if (i > 0) appendLine()
                append(formatMethodBlock(m, from, to, fromNsId, toNsId))
            }
        }
        return if (matches.size == 1) Result.Single(lines) else Result.Ambiguous(lines)
    }

    private fun resolveField(
        cls: ClassMappingView,
        tree: MappingTreeView,
        symbol: ParsedSymbol,
        from: MappingNamespace,
        to: MappingNamespace,
        fromNsId: Int,
        toNsId: Int,
        mcVersion: String,
        classTo: String,
    ): Result {
        val memberName = symbol.memberName!!
        val descriptor = symbol.descriptor
        val matches = cls.fields.filter { f ->
            val name = nameInNs(f, fromNsId)
            name == memberName && (descriptor == null || descInNs(f, fromNsId) == descriptor)
        }
        if (matches.isEmpty()) {
            val available = cls.fields.mapNotNull { nameInNs(it, fromNsId) }.distinct().sorted()
            return Result.NotFound(
                "Field '$memberName' not found on class in ${from.id} for $mcVersion. " +
                    "Available in ${from.id}: ${available.take(40).joinToString(", ")}" +
                    if (available.size > 40) " (+${available.size - 40} more)" else "",
            )
        }
        val lines = buildString {
            appendLine(formatClassLine(cls, tree, from, to, fromNsId, toNsId))
            matches.forEachIndexed { i, f ->
                if (i > 0) appendLine()
                append(formatFieldBlock(f, from, to, fromNsId, toNsId))
            }
        }
        return if (matches.size == 1) Result.Single(lines) else Result.Ambiguous(lines)
    }

    private fun findClass(tree: MappingTreeView, name: String, nsId: Int): ClassMappingView? {
        if (nsId == MappingTreeView.SRC_NAMESPACE_ID) {
            return tree.getClass(name)
        }
        return tree.classes.firstOrNull { it.getDstName(nsId) == name }
    }

    private fun nsIdOf(tree: MappingTreeView, ns: MappingNamespace): Int? {
        if (tree.srcNamespace == ns.id) return MappingTreeView.SRC_NAMESPACE_ID
        val idx = tree.dstNamespaces.indexOf(ns.id)
        return if (idx >= 0) idx else null
    }

    private fun nameInNs(cls: ClassMappingView, nsId: Int): String? =
        if (nsId == MappingTreeView.SRC_NAMESPACE_ID) cls.srcName else cls.getDstName(nsId)

    private fun nameInNs(m: MethodMappingView, nsId: Int): String? =
        if (nsId == MappingTreeView.SRC_NAMESPACE_ID) m.srcName else m.getDstName(nsId)

    private fun nameInNs(f: FieldMappingView, nsId: Int): String? =
        if (nsId == MappingTreeView.SRC_NAMESPACE_ID) f.srcName else f.getDstName(nsId)

    private fun descInNs(m: MethodMappingView, nsId: Int): String? =
        if (nsId == MappingTreeView.SRC_NAMESPACE_ID) m.srcDesc else m.getDstDesc(nsId)

    private fun descInNs(f: FieldMappingView, nsId: Int): String? =
        if (nsId == MappingTreeView.SRC_NAMESPACE_ID) f.srcDesc else f.getDstDesc(nsId)

    private fun formatClassLine(
        cls: ClassMappingView,
        tree: MappingTreeView,
        from: MappingNamespace,
        to: MappingNamespace,
        fromNsId: Int,
        toNsId: Int,
    ): String {
        val fromName = nameInNs(cls, fromNsId) ?: "?"
        val toName = nameInNs(cls, toNsId) ?: "?"
        val others = listOfNotNull(
            tree.srcNamespace.takeIf { it !in setOf(from.id, to.id) }?.let { ns -> ns to cls.srcName },
        ) + tree.dstNamespaces.mapIndexedNotNull { i, ns ->
            if (ns == from.id || ns == to.id) null else cls.getDstName(i)?.let { ns to it }
        }
        val otherStr =
            if (others.isEmpty()) ""
            else others.joinToString(", ", prefix = "  (also: ", postfix = ")") { "${it.first}=${it.second}" }
        return "Class: $fromName [${from.id}] → $toName [${to.id}]$otherStr"
    }

    private fun formatMethodBlock(
        m: MethodMappingView,
        from: MappingNamespace,
        to: MappingNamespace,
        fromNsId: Int,
        toNsId: Int,
    ): String {
        val fromName = nameInNs(m, fromNsId) ?: "?"
        val fromDesc = descInNs(m, fromNsId) ?: ""
        val toName = nameInNs(m, toNsId) ?: "?"
        val toDesc = descInNs(m, toNsId) ?: ""
        return "  method: $fromName$fromDesc [${from.id}] → $toName$toDesc [${to.id}]"
    }

    private fun formatFieldBlock(
        f: FieldMappingView,
        from: MappingNamespace,
        to: MappingNamespace,
        fromNsId: Int,
        toNsId: Int,
    ): String {
        val fromName = nameInNs(f, fromNsId) ?: "?"
        val fromDesc = descInNs(f, fromNsId)
        val toName = nameInNs(f, toNsId) ?: "?"
        val toDesc = descInNs(f, toNsId)
        val fromPart = if (fromDesc != null) "$fromName:$fromDesc" else fromName
        val toPart = if (toDesc != null) "$toName:$toDesc" else toName
        return "  field: $fromPart [${from.id}] → $toPart [${to.id}]"
    }
}
