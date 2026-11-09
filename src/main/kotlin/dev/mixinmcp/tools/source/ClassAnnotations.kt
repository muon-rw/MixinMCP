package dev.mixinmcp.tools.source

private const val MAX_RENDERED_STRING: Int = 300

/** An annotation read from a class file's RuntimeVisibleAnnotations / RuntimeInvisibleAnnotations attribute. */
internal data class BytecodeAnnotation(val descriptor: String, val values: Map<String, AnnotationValue>) {
    val internalName: String get() = descriptor.removePrefix("L").removeSuffix(";")
    val shortName: String get() = internalName.substringAfterLast('/').substringAfterLast('$')

    /** Every string in [element], whether given as a single value or an array. */
    fun strings(element: String): List<String> = values[element]?.flatten()?.mapNotNull { (it as? AnnotationValue.Str)?.value }.orEmpty()

    /** Every nested annotation in [element], whether given as a single value or an array. */
    fun annotations(element: String): List<BytecodeAnnotation> =
        values[element]?.flatten()?.mapNotNull { (it as? AnnotationValue.Nested)?.annotation }.orEmpty()

    /** Every class literal in [element], as internal names. */
    fun classes(element: String): List<String> =
        values[element]?.flatten()?.mapNotNull { (it as? AnnotationValue.ClassLiteral)?.internalName }.orEmpty()

    /** Java-like source text, e.g. `@Inject(method = "tick", at = @At("HEAD"), cancellable = true)`. */
    fun render(): String {
        if (values.isEmpty()) return "@$shortName"
        val only: Map.Entry<String, AnnotationValue>? = values.entries.singleOrNull()
        if (only != null && only.key == "value") return "@$shortName(${only.value.render()})"
        return "@$shortName(${values.entries.joinToString(", ") { (name, value) -> "$name = ${value.render()}" }})"
    }
}

internal sealed interface AnnotationValue {
    fun render(): String

    fun flatten(): List<AnnotationValue> = if (this is Array) values.flatMap { it.flatten() } else listOf(this)

    data class Str(val value: String) : AnnotationValue {
        override fun render(): String = "\"" + value.take(MAX_RENDERED_STRING) + (if (value.length > MAX_RENDERED_STRING) "…" else "") + "\""
    }

    data class Constant(val text: String) : AnnotationValue {
        override fun render(): String = text
    }

    data class EnumConstant(val typeDescriptor: String, val name: String) : AnnotationValue {
        override fun render(): String = "${typeDescriptor.removePrefix("L").removeSuffix(";").substringAfterLast('/')}.$name"
    }

    data class ClassLiteral(val descriptor: String) : AnnotationValue {
        val internalName: String get() = descriptor.removePrefix("L").removeSuffix(";")
        override fun render(): String = "${internalName.replace('/', '.')}.class"
    }

    data class Nested(val annotation: BytecodeAnnotation) : AnnotationValue {
        override fun render(): String = annotation.render()
    }

    data class Array(val values: List<AnnotationValue>) : AnnotationValue {
        override fun render(): String = values.singleOrNull()?.render() ?: values.joinToString(", ", "{", "}") { it.render() }
    }
}

private val ANNOTATION_ATTRIBUTES: Set<String> = setOf("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations")

/**
 * Annotations in [attributes] (a class's, field's, or method's), from both the visible
 * (RUNTIME retention) and invisible (CLASS retention, e.g. `@Mixin`) attributes. An attribute
 * that fails to parse is skipped rather than failing the class.
 */
internal fun ClassFileView.annotationsIn(attributes: List<ClassFileView.Attribute>): List<BytecodeAnnotation> {
    val out: MutableList<BytecodeAnnotation> = mutableListOf()
    for (attribute in attributes) {
        if (!isUtf8(attribute.nameIndex) || utf8(attribute.nameIndex) !in ANNOTATION_ATTRIBUTES) continue
        runCatching {
            val reader = AnnotationReader(this, attribute.offset, attribute.offset + attribute.length)
            repeat(reader.u2()) { out.add(reader.annotation()) }
        }
    }
    return out
}

/** Reads JVMS 4.7.16 `annotation` and `element_value` structures within [end]. */
private class AnnotationReader(private val view: ClassFileView, private var p: Int, private val end: Int) {
    private val bytes: ByteArray = view.bytes

    fun u1(): Int {
        check(p + 1 <= end)
        return (bytes[p++].toInt() and 0xFF)
    }

    fun u2(): Int {
        check(p + 2 <= end)
        return u2(bytes, p).also { p += 2 }
    }

    private fun utf8(index: Int): String {
        check(view.isUtf8(index))
        return view.utf8(index)
    }

    fun annotation(): BytecodeAnnotation {
        val descriptor: String = utf8(u2())
        val pairs: Int = u2()
        val values = LinkedHashMap<String, AnnotationValue>()
        repeat(pairs) {
            val name: String = utf8(u2())
            values[name] = elementValue()
        }
        return BytecodeAnnotation(descriptor, values)
    }

    private fun elementValue(): AnnotationValue {
        return when (val tag: Char = u1().toChar()) {
            's' -> AnnotationValue.Str(utf8(u2()))
            'e' -> {
                val type: String = utf8(u2())
                AnnotationValue.EnumConstant(type, utf8(u2()))
            }
            'c' -> AnnotationValue.ClassLiteral(utf8(u2()))
            '@' -> AnnotationValue.Nested(annotation())
            '[' -> AnnotationValue.Array(List(u2()) { elementValue() })
            'B', 'C', 'I', 'S', 'Z', 'D', 'F', 'J' -> AnnotationValue.Constant(constantText(tag, u2()))
            else -> error("unknown element_value tag $tag")
        }
    }

    private fun constantText(tag: Char, index: Int): String {
        val at: Int = view.offset(index)
        return when (tag) {
            'Z' -> (u4(bytes, at) != 0).toString()
            'C' -> "'${u4(bytes, at).toChar()}'"
            'F' -> java.lang.Float.intBitsToFloat(u4(bytes, at)).toString() + "f"
            'J' -> ((u4(bytes, at).toLong() shl 32) or (u4(bytes, at + 4).toLong() and 0xFFFFFFFFL)).toString() + "L"
            'D' -> java.lang.Double.longBitsToDouble((u4(bytes, at).toLong() shl 32) or (u4(bytes, at + 4).toLong() and 0xFFFFFFFFL)).toString()
            else -> u4(bytes, at).toString()
        }
    }
}
