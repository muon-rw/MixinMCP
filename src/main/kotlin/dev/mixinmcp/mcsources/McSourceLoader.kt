package dev.mixinmcp.mcsources

enum class McSourceLoader(val id: String) {
    VANILLA("vanilla"),
    FORGE("forge"),
    NEOFORGE("neoforge"),
    ;

    companion object {
        val ALL_IDS: List<String> = entries.map { it.id }

        fun parse(raw: String): McSourceLoader {
            val normalized = raw.trim().lowercase()
            return when (normalized) {
                "vanilla" -> VANILLA
                "forge" -> FORGE
                "neoforge" -> NEOFORGE
                "fabric", "quilt" -> throw IllegalArgumentException(
                    "Loader '$raw' is not supported by NeoFormRuntime. Use loader=\"vanilla\" to fetch " +
                        "Mojmap vanilla sources for that Minecraft version, and mixin_mappings_lookup if you " +
                        "need Yarn or Intermediary names.",
                )
                else -> throw IllegalArgumentException(
                    "Unknown loader '$raw'. Expected one of: ${ALL_IDS.joinToString(", ")}.",
                )
            }
        }
    }
}
