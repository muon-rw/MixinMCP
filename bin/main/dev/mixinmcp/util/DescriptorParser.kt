package dev.mixinmcp.util

/**
 * Parses JVM method descriptors for use with method resolution.
 * Mixin developers write descriptors in @Inject(method = "...") — this utility
 * lets tools accept descriptors directly instead of requiring parameterTypes arrays.
 *
 * Descriptor format: (params)returnType
 * - Object: L internalName ;  (e.g. Lnet/minecraft/world/entity/Entity;)
 * - Array: [ componentType  (e.g. [I, [[Ljava/lang/String;)
 * - Primitives: Z B C S I J F D (boolean byte char short int long float double)
 */
object DescriptorParser {

    private val PRIMITIVE_NAMES: Map<Char, String> = mapOf(
        'Z' to "boolean",
        'B' to "byte",
        'C' to "char",
        'S' to "short",
        'I' to "int",
        'J' to "long",
        'F' to "float",
        'D' to "double",
    )

    /**
     * Parses a JVM method descriptor and returns parameter types as canonical
     * Java type names (e.g. "net.minecraft.world.entity.Entity", "int").
     * Suitable for matching against PsiParameter.type.canonicalText.
     *
     * @param descriptor JVM descriptor (e.g. "(Lnet/minecraft/world/entity/Entity;)Z")
     * @return List of canonical parameter type names, or empty list if parsing fails
     */
    fun parseParameterTypes(descriptor: String): List<String> {
        val trimmed: String = descriptor.trim()
        if (!trimmed.startsWith("(") || !trimmed.contains(")")) return emptyList()
        val endParen: Int = trimmed.indexOf(')')
        val paramsPart: String = trimmed.substring(1, endParen)
        val internalNames: MutableList<String> = mutableListOf()
        var i: Int = 0
        while (i < paramsPart.length) {
            val parsed: Pair<Int, String> = parseFieldDescriptor(paramsPart, i)
                ?: return emptyList()
            i += parsed.first
            internalNames.add(parsed.second)
        }
        return internalNames.map { toCanonicalName(it) }
    }

    /**
     * Converts internal type names (e.g. "net/minecraft/world/entity/Entity")
     * to simple names (e.g. "Entity") for display and parameterTypes suggestions.
     */
    fun toSimpleNames(internalNames: List<String>): List<String> {
        return internalNames.map { internal ->
            val canonical: String = toCanonicalName(internal)
            canonical.substringAfterLast('.', canonical)
        }
    }

    /**
     * Converts internal name to canonical Java name (slashes to dots).
     * Handles primitives and array prefixes.
     */
    fun toCanonicalName(internalOrPrimitive: String): String {
        var s: String = internalOrPrimitive
        val arrayDepth: Int = s.takeWhile { it == '[' }.length
        s = s.drop(arrayDepth)
        val base: String = when {
            s.startsWith("L") && s.endsWith(";") ->
                s.drop(1).dropLast(1).replace('/', '.')
            s.length == 1 && s[0] in PRIMITIVE_NAMES ->
                PRIMITIVE_NAMES[s[0]]!!
            else -> s
        }
        return base + "[]".repeat(arrayDepth)
    }

    /**
     * Parses a single field descriptor from the given position.
     * Returns (chars consumed, internal/primitive type string) or null on error.
     */
    private fun parseFieldDescriptor(input: String, start: Int): Pair<Int, String>? {
        if (start >= input.length) return null
        return when (val c: Char = input[start]) {
            'L' -> {
                val end: Int = input.indexOf(';', start)
                if (end < 0) return null
                Pair(end - start + 1, input.substring(start, end + 1))
            }
            '[' -> {
                val parsed: Pair<Int, String>? = parseFieldDescriptor(input, start + 1)
                if (parsed == null) return null
                Pair(1 + parsed.first, "[" + parsed.second)
            }
            in PRIMITIVE_NAMES -> Pair(1, c.toString())
            else -> null
        }
    }

    /**
     * Converts parsed canonical parameter types to the parameterTypes array format
     * used by tools (simple names for display in error messages).
     */
    fun toParameterTypesFormat(canonicalTypes: List<String>): List<String> {
        return canonicalTypes.map { it.substringAfterLast('.', it) }
    }
}
