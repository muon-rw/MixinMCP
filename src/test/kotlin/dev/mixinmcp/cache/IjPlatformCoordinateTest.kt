package dev.mixinmcp.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IjPlatformCoordinateTest {

    @Test
    fun installerDistWithClassifier() {
        assertEquals(
            "idea" to "2026.1.4",
            SourceAutoAttacher.parseIjPlatformDistCoordinate("Gradle: idea:idea:aarch64:2026.1.4"),
        )
    }

    @Test
    fun installerDistWithoutClassifier() {
        assertEquals(
            "ideaIC" to "2024.3",
            SourceAutoAttacher.parseIjPlatformDistCoordinate("Gradle: idea:ideaIC:2024.3"),
        )
    }

    @Test
    fun mavenDist() {
        assertEquals(
            "ideaIC" to "2023.2.7",
            SourceAutoAttacher.parseIjPlatformDistCoordinate("Gradle: com.jetbrains.intellij.idea:ideaIC:2023.2.7"),
        )
    }

    @Test
    fun buildNumberVersionAccepted() {
        assertEquals(
            "ideaIC" to "243.21565.193",
            SourceAutoAttacher.parseIjPlatformDistCoordinate("Gradle: idea:ideaIC:243.21565.193"),
        )
    }

    @Test
    fun unprefixedLibraryNameAccepted() {
        assertEquals(
            "idea" to "2026.1.4",
            SourceAutoAttacher.parseIjPlatformDistCoordinate("idea:idea:2026.1.4"),
        )
    }

    @Test
    fun ordinaryLibrariesRejected() {
        assertNull(SourceAutoAttacher.parseIjPlatformDistCoordinate("Gradle: org.ow2.asm:asm:9.9.1"))
        assertNull(
            SourceAutoAttacher.parseIjPlatformDistCoordinate(
                "Gradle: com.jetbrains.intellij.platform:test-framework:261.26222.65",
            ),
        )
        assertNull(
            SourceAutoAttacher.parseIjPlatformDistCoordinate(
                "Gradle: bundledPlugin:com.intellij.java:IU-261.26222.65",
            ),
        )
    }

    @Test
    fun tooFewSegmentsRejected() {
        assertNull(SourceAutoAttacher.parseIjPlatformDistCoordinate("Gradle: idea:idea"))
    }

    @Test
    fun nonNumericVersionRejected() {
        assertNull(SourceAutoAttacher.parseIjPlatformDistCoordinate("Gradle: idea:idea:aarch64:latest"))
    }

    @Test
    fun bundledLibrariesDetected() {
        assertTrue(
            SourceAutoAttacher.isIjPlatformBundledLibrary("Gradle: bundledPlugin:com.intellij.java:IU-261.26222.65"),
        )
        assertTrue(
            SourceAutoAttacher.isIjPlatformBundledLibrary(
                "Gradle: bundledModule:intellij.platform.coverage:IC-243.21565.193",
            ),
        )
        assertFalse(SourceAutoAttacher.isIjPlatformBundledLibrary("Gradle: org.ow2.asm:asm:9.9.1"))
    }
}
