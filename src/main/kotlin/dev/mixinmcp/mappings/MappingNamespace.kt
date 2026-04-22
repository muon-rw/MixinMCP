package dev.mixinmcp.mappings

enum class MappingNamespace(val id: String) {
    OBF("obf"),
    MOJMAP("mojmap"),
    INTERMEDIARY("intermediary"),
    YARN("yarn"),
    SRG("srg"),
    ;

    companion object {
        fun fromString(value: String): MappingNamespace? =
            values().firstOrNull { it.id.equals(value, ignoreCase = true) }

        val ALL_IDS: List<String> = values().map { it.id }
    }
}
