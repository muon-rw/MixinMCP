package dev.mixinmcp.mcsources

import net.fabricmc.mappingio.tree.MappingTreeView

/**
 * Remaps the Yarn names in mixin annotation strings to Mojang names. Fabric API writes those strings
 * in Yarn and Loom resolves them through a refmap at build time, so the published sources keep
 * them in Yarn even after the Java code is remapped to intermediary.
 */
class YarnStringRemapper(
    // Internal names, Yarn -> Mojang.
    private val classes: Map<String, String>,
    private val methods: Map<String, List<Member>>,
    private val fields: Map<String, List<Member>>,
    // Yarn internal name -> its Yarn-named superclasses and interfaces, nearest first. Null when the
    // game jar's hierarchy couldn't be loaded.
    private val ancestorsOf: ((String) -> List<String>)? = null,
) {
    data class Member(val yarnName: String, val yarnDesc: String?, val mojangName: String)

    private data class Remapped(val text: String, val unresolved: String? = null)

    private sealed interface Declaration {
        data class Found(val mojangName: String) : Declaration
        data object Ambiguous : Declaration
        data object None : Declaration
    }

    private val mojangToYarnClass: Map<String, String> = classes.entries.associate { (yarn, mojang) -> mojang to yarn }

    // Last resort when the owner's own hierarchy doesn't settle a name: a name (and descriptor, when
    // given) that maps to one Mojang name across the whole game is still unambiguous.
    private val methodsBySignature: Map<String, Set<String>> = indexBySignature(methods)
    private val fieldsBySignature: Map<String, Set<String>> = indexBySignature(fields)
    private val methodsByName: Map<String, Set<String>> = indexByName(methods)
    private val fieldsByName: Map<String, Set<String>> = indexByName(fields)

    private val unresolvedSelectors = LinkedHashSet<String>()

    val unresolved: Set<String> get() = unresolvedSelectors

    fun remap(source: String): String {
        if ("\"" !in source) return source
        val mixins = mixinTargets(source)
        val remapped = STRING_LITERAL.replace(source) { literal ->
            val content = literal.value.substring(1, literal.value.length - 1)
            val before = source.substring(maxOf(0, literal.range.first - CONTEXT_CHARS), literal.range.first)
            // A file can nest mixins (an accessor interface for an inner class), so each literal
            // belongs to the nearest @Mixin above it.
            val targets = mixins.lastOrNull { it.first < literal.range.first }?.second.orEmpty()
            val result = when {
                ACCESSOR_CONTEXT.containsMatchIn(before) -> remapAccessor(content, before, targets)
                METHOD_CONTEXT.containsMatchIn(before) -> remapSelector(content, targets)
                TARGETS_CONTEXT.containsMatchIn(before) -> Remapped(remapClassName(content))
                else -> remapQualified(content)
            }
            result.unresolved?.let { unresolvedSelectors += it }
            val marker = result.unresolved?.let { "$MARK$it$MARK" } ?: ""
            "\"" + result.text + "\"" + marker
        }
        return if (MARK in remapped) annotateUnresolved(remapped) else remapped
    }

    // An agent reads one mixin at a time and never sees the jar's note, so each name left in Yarn
    // says so on its own line.
    private fun annotateUnresolved(text: String): String = MARKED_LINE.replace(text) { line ->
        val names = MARKER.findAll(line.value).map { "\"${it.groupValues[1]}\"" }.distinct().joinToString(", ")
        MARKER.replace(line.value, "") +
            " // MixinMCP: $names still Yarn; mixin_mappings_lookup(from=\"yarn\", to=\"mojmap\") converts it."
    }

    // A name Yarn never lists (close() from AutoCloseable, a library method) is spelled the same in
    // every namespace and is already right, so only names Yarn does list are reported unresolved.
    private fun remapSelector(selector: String, targets: List<String>): Remapped {
        if (MEMBER_REF.matches(selector) || DOTTED_MEMBER_REF.matches(selector)) return remapQualified(selector)
        val paren = selector.indexOf('(')
        val name = if (paren >= 0) selector.substring(0, paren) else selector
        val desc = if (paren >= 0) selector.substring(paren) else null
        if (!isPlainIdentifier(name)) return Remapped(remapDescriptors(selector))
        val mapped = agreeOnTargets(targets) { owner -> lookupMethod(owner, name, desc) }
        val text = (mapped ?: name) + (desc?.let(::remapDescriptors) ?: "")
        // A mixin with no Minecraft target (remap = false on a library class) has nothing to remap.
        return Remapped(text, selector.takeIf { mapped == null && targets.isNotEmpty() && name in methodsByName })
    }

    private fun remapAccessor(name: String, before: String, targets: List<String>): Remapped {
        if (!isPlainIdentifier(name)) return Remapped(name)
        val isInvoker = before.substring(before.lastIndexOf('@')).startsWith("@Invoker")
        val mapped = agreeOnTargets(targets) { owner ->
            if (isInvoker) lookupMethod(owner, name, null) else lookupField(owner, name, null)
        }
        val isYarnName = name in (if (isInvoker) methodsByName else fieldsByName)
        return Remapped(mapped ?: name, name.takeIf { mapped == null && targets.isNotEmpty() && isYarnName })
    }

    // "Lowner;name(desc)ret", "Lowner;field:Ltype;", Mixin's equivalent "owner.name(desc)ret", or a
    // bare descriptor.
    private fun remapQualified(content: String): Remapped {
        val wrapped = MEMBER_REF.matchEntire(content)
        val match = wrapped ?: DOTTED_MEMBER_REF.matchEntire(content) ?: return Remapped(remapDescriptors(content))
        val (owner, name, rest) = match.destructured
        val mappedOwner = classes[owner] ?: owner
        val ownerPart = if (wrapped != null) "L$mappedOwner;" else "$mappedOwner."
        if (!isPlainIdentifier(name)) return Remapped("$ownerPart$name${remapDescriptors(rest)}")
        val mapped = when {
            rest.startsWith("(") -> lookupMethod(owner, name, rest)
            rest.startsWith(":") -> lookupField(owner, name, rest.substring(1))
            else -> lookupMethodOrField(owner, name)
        }
        // Unobfuscated classes (com.mojang.*) have the same member names in every namespace.
        val obfuscated = mappedOwner != owner
        val isYarnName = name in methodsByName || name in fieldsByName
        return Remapped(
            "$ownerPart${mapped ?: name}${remapDescriptors(rest)}",
            content.takeIf { mapped == null && obfuscated && isYarnName },
        )
    }

    // @Mixin(targets = "net.minecraft.client.render.WorldRenderer$1") spells binary names with dots.
    private fun remapClassName(name: String): String {
        val internal = name.replace('.', '/')
        return classes[internal]?.replace('/', '.') ?: remapDescriptors(name)
    }

    private fun remapDescriptors(text: String): String =
        CLASS_DESCRIPTOR.replace(text) { m -> "L" + (classes[m.groupValues[1]] ?: m.groupValues[1]) + ";" }

    private fun lookupMethod(owner: String, name: String, desc: String?): String? =
        resolve(declarationIn(methods, owner, name, desc), owner, name) {
            if (desc != null) methodsBySignature["$name$desc"]?.singleOrNull() else methodsByName[name]?.singleOrNull()
        }

    private fun lookupField(owner: String, name: String, desc: String?): String? =
        resolve(declarationIn(fields, owner, name, desc), owner, name) {
            if (desc != null) fieldsBySignature["$name:$desc"]?.singleOrNull() else fieldsByName[name]?.singleOrNull()
        }

    // "Lowner;name" without a descriptor can name either kind of member.
    private fun lookupMethodOrField(owner: String, name: String): String? {
        val method = declarationIn(methods, owner, name, null)
        val field = declarationIn(fields, owner, name, null)
        val declaration = listOf(method, field).firstOrNull { it is Declaration.Found }
            ?: listOf(method, field).firstOrNull { it is Declaration.Ambiguous }
            ?: Declaration.None
        return resolve(declaration, owner, name) {
            methodsByName[name]?.singleOrNull() ?: fieldsByName[name]?.singleOrNull()
        }
    }

    // Mappings list only obfuscated members, so when the owner's whole Minecraft ancestry declares no
    // such name, the member comes from the JDK or a library (close() from AutoCloseable) and keeps
    // its name in every namespace. Without the hierarchy we can't tell, so a name unique across the
    // game is the best remaining evidence.
    private fun resolve(declaration: Declaration, owner: String, name: String, gameWide: () -> String?): String? =
        when (declaration) {
            is Declaration.Found -> declaration.mojangName
            Declaration.Ambiguous -> null
            Declaration.None -> if (ancestorsOf != null && owner in classes) name else gameWide()
        }

    // Yarn lists a member only on the class that first declares it, while selectors usually name an
    // override or go through a subclass, so the nearest ancestor that declares the name decides.
    private fun declarationIn(members: Map<String, List<Member>>, owner: String, name: String, desc: String?): Declaration {
        for (cls in listOf(owner) + ancestorsOf?.invoke(owner).orEmpty()) {
            val candidates = members[cls].orEmpty().filter { it.yarnName == name && (desc == null || it.yarnDesc == desc) }
            if (candidates.isEmpty()) continue
            // Overloads that map to different names make a bare name ambiguous; an ancestor won't fix that.
            val mojang = candidates.map { it.mojangName }.distinct().singleOrNull()
            return if (mojang != null) Declaration.Found(mojang) else Declaration.Ambiguous
        }
        return Declaration.None
    }

    private fun agreeOnTargets(targets: List<String>, lookup: (String) -> String?): String? {
        if (targets.isEmpty()) return null
        return targets.map(lookup).distinct().singleOrNull()
    }

    // Each @Mixin in the file, by position, with the Yarn internal names of its targets. Runs after
    // the intermediary pass, so class literals and imports are already Mojang names.
    private fun mixinTargets(source: String): List<Pair<Int, List<String>>> {
        val imports = IMPORT.findAll(source).associate { it.groupValues[2] to it.groupValues[1] + "." + it.groupValues[2] }
        return MIXIN_ANNOTATION.findAll(source).map { mixin ->
            val annotation = mixin.groupValues[1]
            val fromLiterals = CLASS_LITERAL.findAll(annotation).mapNotNull { m ->
                val written = m.groupValues[1]
                val outer = written.substringBefore('.')
                val qualified = when {
                    written.first().isLowerCase() -> written
                    outer in imports -> imports.getValue(outer) + written.removePrefix(outer)
                    else -> return@mapNotNull null
                }
                mojangToYarnClass[toInternalName(qualified)]
            }
            val fromStrings = STRING_LITERAL.findAll(annotation).map { it.value.trim('"').replace('.', '/') }
                .filter { it in classes }
            mixin.range.first to (fromLiterals + fromStrings).distinct().toList()
        }.toList()
    }

    companion object {
        private const val CONTEXT_CHARS = 240
        private const val YARN_NS = "yarn"
        private const val MOJMAP_NS = "mojmap"

        // Brackets an unresolved name right after its literal until annotateUnresolved moves it into
        // a comment; Java source never contains this character.
        private const val MARK = "\u0001"
        private val MARKER = Regex("""$MARK([^$MARK]*)$MARK""")
        private val MARKED_LINE = Regex("""(?m)^.*$MARK.*$""")

        private val STRING_LITERAL = Regex(""""(?:[^"\\\n]|\\.)*"""")
        private val MEMBER_REF = Regex("""L([\w/$]+);([^(:]+)(.*)""")
        private val DOTTED_MEMBER_REF = Regex("""([\w$]+(?:/[\w$]+)+)\.([^(:./]+)(.*)""")
        private val CLASS_DESCRIPTOR = Regex("""L([\w/$]+);""")
        private val MIXIN_ANNOTATION = Regex("""@Mixin\s*\(([^)]*)\)""")
        private val CLASS_LITERAL = Regex("""([\w.]+)\.class\b""")
        private val IMPORT = Regex("""(?m)^\s*import\s+([\w.]+)\.(\w+)\s*;""")

        // The text just before a literal says which annotation attribute it belongs to; a literal
        // inside an array follows the attribute name and any earlier literals.
        private const val EARLIER_LITERALS = """\{?\s*(?:"(?:[^"\\\n]|\\.)*"\s*,\s*)*$"""
        private val METHOD_CONTEXT = Regex("""\bmethod\s*=\s*$EARLIER_LITERALS""")
        private val TARGETS_CONTEXT = Regex("""\btargets\s*=\s*$EARLIER_LITERALS""")
        private val ACCESSOR_CONTEXT = Regex("""@(?:Accessor|Invoker)\s*\(\s*(?:value\s*=\s*)?$""")

        private fun isPlainIdentifier(name: String): Boolean =
            name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' || it == '$' } && !name.startsWith("lambda$")

        // "net.minecraft.core.BlockPos.MutableBlockPos" -> "net/minecraft/core/BlockPos$MutableBlockPos"
        private fun toInternalName(qualified: String): String {
            val parts = qualified.split('.')
            val firstClass = parts.indexOfFirst { it.first().isUpperCase() }.takeIf { it >= 0 } ?: return qualified.replace('.', '/')
            return parts.take(firstClass).joinToString("/") + "/" + parts.drop(firstClass).joinToString("$")
        }

        private fun indexByName(byClass: Map<String, List<Member>>): Map<String, Set<String>> {
            val index = HashMap<String, MutableSet<String>>()
            for (member in byClass.values.flatten()) index.getOrPut(member.yarnName) { HashSet() }.add(member.mojangName)
            return index
        }

        private fun indexBySignature(byClass: Map<String, List<Member>>): Map<String, Set<String>> {
            val index = HashMap<String, MutableSet<String>>()
            for (member in byClass.values.flatten()) {
                val desc = member.yarnDesc ?: continue
                val key = if (desc.startsWith("(")) "${member.yarnName}$desc" else "${member.yarnName}:$desc"
                index.getOrPut(key) { HashSet() }.add(member.mojangName)
            }
            return index
        }

        // The tree's source namespace is the game jar's own obfuscated names, which is what
        // [hierarchy] is keyed by.
        fun fromTree(tree: MappingTreeView, hierarchy: ClassHierarchy?): YarnStringRemapper {
            val yarnNs = tree.dstNamespaces.indexOf(YARN_NS)
            val mojmapNs = tree.dstNamespaces.indexOf(MOJMAP_NS)
            require(yarnNs >= 0 && mojmapNs >= 0) { "Mappings tree lacks yarn or mojmap (has ${tree.dstNamespaces})." }
            val classes = HashMap<String, String>()
            val methods = HashMap<String, MutableList<Member>>()
            val fields = HashMap<String, MutableList<Member>>()
            val obfToYarn = HashMap<String, String>()
            for (cls in tree.classes) {
                val yarnClass = cls.getDstName(yarnNs) ?: continue
                obfToYarn[cls.srcName] = yarnClass
                cls.getDstName(mojmapNs)?.let { classes[yarnClass] = it }
                for (m in cls.methods) {
                    val yarn = m.getDstName(yarnNs) ?: continue
                    val mojang = m.getDstName(mojmapNs) ?: continue
                    methods.getOrPut(yarnClass) { ArrayList() }.add(Member(yarn, m.getDstDesc(yarnNs), mojang))
                }
                for (f in cls.fields) {
                    val yarn = f.getDstName(yarnNs) ?: continue
                    val mojang = f.getDstName(mojmapNs) ?: continue
                    fields.getOrPut(yarnClass) { ArrayList() }.add(Member(yarn, f.getDstDesc(yarnNs), mojang))
                }
            }
            if (hierarchy == null) return YarnStringRemapper(classes, methods, fields)
            val yarnToObf = obfToYarn.entries.associate { (obf, yarn) -> yarn to obf }
            val memo = HashMap<String, List<String>>()
            return YarnStringRemapper(classes, methods, fields) { yarn ->
                memo.getOrPut(yarn) {
                    val obf = yarnToObf[yarn] ?: return@getOrPut emptyList()
                    // Ancestors outside the mappings (java/*) have no Yarn members to look up.
                    hierarchy.ancestors(obf).mapNotNull { obfToYarn[it] }
                }
            }
        }
    }
}
