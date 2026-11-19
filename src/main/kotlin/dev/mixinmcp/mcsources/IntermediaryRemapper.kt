package dev.mixinmcp.mcsources

import net.fabricmc.mappingio.tree.MappingTreeView

class IntermediaryRemapper(
    // Internal names: "net/minecraft/class_2338$class_2339" -> "net/minecraft/core/BlockPos$MutableBlockPos"
    private val classes: Map<String, String>,
    // "method_5643" -> "hurt", "field_6002" -> "level"
    private val members: Map<String, String>,
) {
    // Keyed by the last intermediary segment ("class_2339"), so nested-class references written as
    // `class_2338.class_2339` remap one token at a time.
    private val simpleNames: Map<String, String> = classes.entries.associate { (intermediary, mojang) ->
        intermediary.substringAfterLast('/').substringAfterLast('$') to
            mojang.substringAfterLast('/').substringAfterLast('$')
    }

    val isEmpty: Boolean get() = classes.isEmpty() && members.isEmpty()

    // Intermediary names are unique across the whole game, so token replacement is exact without
    // parsing Java or resolving types.
    fun remap(text: String): String {
        if (!MAYBE_INTERMEDIARY.containsMatchIn(text)) return text
        // Mixin @At targets and descriptors spell classes as internal names inside strings.
        var out = INTERNAL_NAME.replace(text) { m -> classes[m.value] ?: m.value }
        // Imports and fully qualified references: only the top-level class carries the package.
        out = QUALIFIED_TOP_LEVEL.replace(out) { m ->
            classes["net/minecraft/${m.groupValues[1]}"]?.replace('/', '.')?.replace('$', '.') ?: m.value
        }
        out = BARE_CLASS.replace(out) { m -> simpleNames[m.value] ?: m.value }
        return MEMBER.replace(out) { m -> members[m.value] ?: m.value }
    }

    companion object {
        private val MAYBE_INTERMEDIARY = Regex("""\b(?:class|method|field|comp)_\d+\b""")
        private val INTERNAL_NAME = Regex("""net/minecraft/class_\d+(?:[$]class_\d+)*""")
        private val QUALIFIED_TOP_LEVEL = Regex("""\bnet\.minecraft\.(class_\d+)\b""")
        private val BARE_CLASS = Regex("""\bclass_\d+\b""")
        private val MEMBER = Regex("""\b(?:method|field|comp)_\d+\b""")

        private const val INTERMEDIARY_NS = "intermediary"
        private const val MOJMAP_NS = "mojmap"

        fun fromTree(tree: MappingTreeView): IntermediaryRemapper {
            val intermediaryNs = tree.dstNamespaces.indexOf(INTERMEDIARY_NS)
            val mojmapNs = tree.dstNamespaces.indexOf(MOJMAP_NS)
            require(intermediaryNs >= 0 && mojmapNs >= 0) {
                "Mappings tree lacks intermediary or mojmap (has ${tree.dstNamespaces})."
            }
            val classes = HashMap<String, String>()
            val members = HashMap<String, String>()
            for (cls in tree.classes) {
                val intermediary = cls.getDstName(intermediaryNs)
                val mojang = cls.getDstName(mojmapNs)
                if (intermediary != null && mojang != null && intermediary != mojang) classes[intermediary] = mojang
                for (method in cls.methods) {
                    addMember(members, method.getDstName(intermediaryNs), method.getDstName(mojmapNs))
                }
                for (field in cls.fields) {
                    addMember(members, field.getDstName(intermediaryNs), field.getDstName(mojmapNs))
                }
            }
            return IntermediaryRemapper(classes, members)
        }

        // An overriding method shares its intermediary name with the method it overrides, and so
        // does its Mojang name; the first mapping seen is as good as any.
        private fun addMember(members: MutableMap<String, String>, intermediary: String?, mojang: String?) {
            if (intermediary == null || mojang == null || intermediary == mojang) return
            if (MEMBER.matches(intermediary)) members.putIfAbsent(intermediary, mojang)
        }
    }
}
