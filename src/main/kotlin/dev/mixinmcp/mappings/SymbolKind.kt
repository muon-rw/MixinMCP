package dev.mixinmcp.mappings

enum class SymbolKind(val id: String) {
    CLASS("class"),
    METHOD("method"),
    FIELD("field"),
    ;

    companion object {
        fun fromString(value: String): SymbolKind? =
            values().firstOrNull { it.id.equals(value, ignoreCase = true) }

        val ALL_IDS: List<String> = values().map { it.id }
    }
}

data class ParsedSymbol(
    val ownerInternalName: String,
    val memberName: String?,
    val descriptor: String?,
)

object SymbolParser {
    fun parse(raw: String, kind: SymbolKind): ParsedSymbol {
        val normalized = raw.replace('.', '/')
        return when (kind) {
            SymbolKind.CLASS -> ParsedSymbol(normalized, null, null)
            SymbolKind.METHOD -> parseMember(normalized, descriptorIntroducer = '(')
            SymbolKind.FIELD -> parseMember(normalized, descriptorIntroducer = ':')
        }
    }

    private fun parseMember(normalized: String, descriptorIntroducer: Char): ParsedSymbol {
        val descIdx = normalized.indexOf(descriptorIntroducer)
        val head: String
        val descriptor: String?
        if (descIdx >= 0) {
            head = normalized.substring(0, descIdx)
            descriptor = normalized.substring(descIdx + if (descriptorIntroducer == ':') 1 else 0)
        } else {
            head = normalized
            descriptor = null
        }
        val lastSlash = head.lastIndexOf('/')
        require(lastSlash >= 0) {
            "Symbol must be of the form owner/member; got '$normalized'"
        }
        val owner = head.substring(0, lastSlash)
        val member = head.substring(lastSlash + 1)
        require(member.isNotEmpty()) { "Empty member name in '$normalized'" }
        return ParsedSymbol(owner, member, descriptor)
    }
}
