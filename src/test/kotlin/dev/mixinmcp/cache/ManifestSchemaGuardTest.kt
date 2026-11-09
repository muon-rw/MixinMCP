package dev.mixinmcp.cache

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * REQUIRED_GRADLE_PLUGIN_VERSION is a manual floor that drives the IDE's "Gradle plugin outdated"
 * warning. It must rise when the IDE starts depending on manifest data only newer Gradle plugins
 * write, and may also rise when older Gradle plugins have a bug worth warning users about
 * (1.6.0: decompiling inside the Gradle daemon could run an IDE sync out of memory). This guard
 * fails on any manifest schema change so that decision is made consciously instead of forgotten.
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
            "the floor was last reviewed at Gradle plugin 1.6.0 (schema unchanged since 1.5.0); update this assertion " +
                "together with the field lists",
            "1.6.0",
            DecompilationCacheService.REQUIRED_GRADLE_PLUGIN_VERSION,
        )
    }

    private val floorReminder: String =
        "Manifest schema changed. If the IDE now reads data only newer Gradle plugins write, raise " +
            "DecompilationCacheService.REQUIRED_GRADLE_PLUGIN_VERSION to the first Gradle plugin version " +
            "writing it; if the change is backward-compatible, leave the floor alone. Then update this test."
}
