package dev.mixinmcp.buildscript

import dev.mixinmcp.tools.source.BUILDSCRIPT_LABEL_PREFIX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class BuildscriptClasspathHelpersTest {

    @Test
    fun pairKeyStripsSourcesAndExtension() {
        assertEquals("fabric-loom-1.16.3", pairKey("fabric-loom-1.16.3.jar"))
        assertEquals("fabric-loom-1.16.3", pairKey("fabric-loom-1.16.3-sources.jar"))
        assertEquals("mod-publish-plugin-2.1.1", pairKey("mod-publish-plugin-2.1.1-sources.jar"))
    }

    @Test
    fun midNameSourcesIsNotASourcesJar() {
        assertFalse(isSourcesJarName("kotlin-sources-plugin-1.0.jar"))
        assertEquals("kotlin-sources-plugin-1.0", pairKey("kotlin-sources-plugin-1.0.jar"))
        assertEquals("buildSrc", pairKey("buildSrc.jar"))
    }

    @Test
    fun pairScopeKeyPairsAcrossCacheHashDirsButNotAcrossArtifacts() {
        val classes = "/u/.gradle/caches/modules-2/files-2.1/g.one/lib/1.0/aa11/lib-1.0.jar"
        val sources = "/u/.gradle/caches/modules-2/files-2.1/g.one/lib/1.0/bb22/lib-1.0-sources.jar"
        val otherGroup = "/u/.gradle/caches/modules-2/files-2.1/g.two/lib/1.0/cc33/lib-1.0.jar"
        assertEquals(pairScopeKey(classes, "lib-1.0.jar"), pairScopeKey(sources, "lib-1.0-sources.jar"))
        assertFalse(pairScopeKey(classes, "lib-1.0.jar") == pairScopeKey(otherGroup, "lib-1.0.jar"))
    }

    @Test
    fun sourcesJarNameDetection() {
        assertTrue(isSourcesJarName("moddev-gradle-2.0.141-sources.jar"))
        assertFalse(isSourcesJarName("moddev-gradle-2.0.141.jar"))
        assertFalse(isSourcesJarName("moddev-gradle-2.0.141-javadoc.jar"))
    }

    @Test
    fun gradleDistributionClassification() {
        val distLib =
            "/Users/x/.gradle/wrapper/dists/gradle-9.4.1-bin/abc/gradle-9.4.1/lib/gradle-core-api-9.4.1.jar"
        val generatedApi = "/Users/x/.gradle/caches/9.4.1/generated-gradle-jars/gradle-api-9.4.1.jar"
        val plugin =
            "/Users/x/.gradle/caches/modules-2/files-2.1/net.fabricmc/fabric-loom/1.16.3/aa/fabric-loom-1.16.3.jar"
        assertTrue(isGradleDistLibPath(distLib))
        assertFalse(isGeneratedGradleApiFatJar(distLib))
        assertTrue(isGeneratedGradleApiFatJar(generatedApi))
        assertFalse(isGradleDistLibPath(generatedApi))
        assertTrue(isGradleDistributionPath(distLib))
        assertTrue(isGradleDistributionPath(generatedApi))
        assertFalse(isGradleDistributionPath(plugin))
    }

    @Test
    fun labelsSplitDistributionFromPlugins() {
        assertEquals(
            "$BUILDSCRIPT_LABEL_PREFIX: fabric-loom-1.16.3",
            labelForJar("fabric-loom-1.16.3.jar", "/x/caches/modules-2/files-2.1/a/fabric-loom-1.16.3.jar"),
        )
        assertEquals(
            "$BUILDSCRIPT_LABEL_PREFIX (Gradle distribution): gradle-core-api-9.4.1",
            labelForJar(
                "gradle-core-api-9.4.1.jar",
                "/x/wrapper/dists/gradle-9.4.1-bin/abc/gradle-9.4.1/lib/gradle-core-api-9.4.1.jar",
            ),
        )
    }

    @Test
    fun modulesCacheVersionDirDerivation() {
        val jar = "/Users/x/.gradle/caches/modules-2/files-2.1/net.fabricmc/fabric-loom/1.16.3/28c6/fabric-loom-1.16.3.jar"
        assertEquals(
            Path.of("/Users/x/.gradle/caches/modules-2/files-2.1/net.fabricmc/fabric-loom/1.16.3"),
            modulesCacheVersionDir(jar),
        )
        val distJar = "/Users/x/.gradle/wrapper/dists/gradle-9.4.1-bin/abc/gradle-9.4.1/lib/gradle-core-api-9.4.1.jar"
        assertEquals(null, modulesCacheVersionDir(distJar))
    }

    @Test
    fun sourcesJarLabelCollapsesToArtifactName() {
        assertEquals(
            "$BUILDSCRIPT_LABEL_PREFIX: moddev-gradle-2.0.141",
            labelForJar("moddev-gradle-2.0.141-sources.jar", "/x/caches/modules-2/files-2.1/a/moddev-gradle-2.0.141-sources.jar"),
        )
    }
}
