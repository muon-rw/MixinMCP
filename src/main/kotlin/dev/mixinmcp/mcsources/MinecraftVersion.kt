package dev.mixinmcp.mcsources

sealed interface McVersionSpec {
    val raw: String

    data class Release(val major: Int, val minor: Int, val patch: Int) : McVersionSpec, Comparable<Release> {
        // Mojang never ships an "x.y.0": the first release of a line is "1.21" or "26.1", and
        // NeoForm, MCPConfig, and Forge all publish under that two-part name.
        override val raw: String
            get() = if (patch == 0) "$major.$minor" else "$major.$minor.$patch"

        override fun compareTo(other: Release): Int {
            major.compareTo(other.major).let { if (it != 0) return it }
            minor.compareTo(other.minor).let { if (it != 0) return it }
            return patch.compareTo(other.patch)
        }

        override fun toString(): String = raw
    }

    data class NonRelease(override val raw: String) : McVersionSpec {
        override fun toString(): String = raw
    }

    companion object {
        private val RELEASE_PATTERN = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?$""")
        private val SAFE_VERSION_PATTERN = Regex("""^[A-Za-z0-9._-]+$""")

        val V1_17 = Release(1, 17, 0)
        val V1_20_1 = Release(1, 20, 1)
        val V1_20_2 = Release(1, 20, 2)
        val V26_1 = Release(26, 1, 0)

        fun parse(input: String): McVersionSpec {
            val trimmed = input.trim()
            require(trimmed.isNotEmpty()) { "mcVersion must not be blank." }
            require(SAFE_VERSION_PATTERN.matches(trimmed) && ".." !in trimmed) {
                "Invalid mcVersion '$trimmed'. Expected a Minecraft version like '1.20.1', '1.21.1', '26.1', or a snapshot ID."
            }

            val match = RELEASE_PATTERN.matchEntire(trimmed)
            if (match != null) {
                val a = match.groupValues[1].toInt()
                val b = match.groupValues[2].toInt()
                val hasThird = match.groupValues[3].isNotEmpty()
                val c = if (hasThird) match.groupValues[3].toInt() else 0

                // Two-component shorthand in [2, 25] maps to 1.<major>.<minor> (e.g. 20.1 -> 1.20.1).
                // Versions 26+ use calendar versioning (26.1 stays 26.1).
                return if (!hasThird && a in 2..25) {
                    Release(1, a, b)
                } else {
                    Release(a, b, c)
                }
            }

            return NonRelease(trimmed)
        }
    }
}
