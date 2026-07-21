package dev.mixinmcp.cache

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * REQUIRED_GRADLE_PLUGIN_VERSION is a manual compatibility floor: it must rise exactly
 * when the IDE starts depending on manifest data only newer Gradle plugins write, and
 * must NOT rise otherwise. This guard fails on any manifest schema change so that
 * decision is made consciously instead of forgotten.
 */
@OptIn(ExperimentalSerializationApi::class)
class ManifestSchemaGuardTest {

    @Test
    fun manifestSchemaChangesForceAFloorDecision() {
        assertEquals(
            floorReminder,
            setOf("entries", "pluginVersion"),
            DecompilationManifest.serializer().descriptor.elementNames.toSet(),
        )
        assertEquals(
            floorReminder,
            setOf(
                "libraryName", "classesJarPath", "jarSize", "jarModified",
                "cachePath", "decompilerVersion", "createdAt", "classpathKind",
            ),
            CacheEntry.serializer().descriptor.elementNames.toSet(),
        )
        assertEquals(
            "the floor was reviewed against Gradle plugin 1.3.0 schema; update this assertion together with the field lists",
            "1.3.0",
            DecompilationCacheService.REQUIRED_GRADLE_PLUGIN_VERSION,
        )
    }

    private val floorReminder: String =
        "Manifest schema changed. If the IDE now reads data only newer Gradle plugins write, raise " +
            "DecompilationCacheService.REQUIRED_GRADLE_PLUGIN_VERSION to the first Gradle plugin version " +
            "writing it; if the change is backward-compatible, leave the floor alone. Then update this test."
}
