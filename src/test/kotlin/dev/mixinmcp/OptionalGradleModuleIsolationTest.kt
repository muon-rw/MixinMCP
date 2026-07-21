package dev.mixinmcp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The plugin must load with org.jetbrains.plugins.gradle disabled: everything Gradle-typed
 * lives in dev.mixinmcp.buildscript, registered only via the optional-dependency config file.
 * The test sandbox always bundles the Gradle plugin, so that startup path cannot be exercised
 * directly; instead this scans the constant pools of every always-loaded class for references
 * that would break class loading when the optional module is absent.
 */
class OptionalGradleModuleIsolationTest {

    @Test
    fun alwaysLoadedClassesNeverReferenceOptionalGradleTypes() {
        // Anchor on a known main class so only the main output is scanned, whatever
        // form the test runtime uses (instrumented jar or class directory); this
        // test's own constants would otherwise trip the scan.
        val anchor = javaClass.classLoader.getResource("dev/mixinmcp/resolve/FqcnResolver.class")
        assertTrue("main output not found on the test classpath", anchor != null)

        val offenders = mutableListOf<String>()
        fun check(name: String, bytes: ByteArray) {
            if (name.replace('\\', '/').contains("buildscript/")) return
            val pool = String(bytes, Charsets.ISO_8859_1)
            if ("org/jetbrains/plugins/gradle" in pool || "dev/mixinmcp/buildscript" in pool) {
                offenders += name
            }
        }

        when (anchor!!.protocol) {
            "jar" -> {
                // The platform test classloader serves jar resources through its own
                // connection type, so parse the jar path from the URL instead of
                // casting to JarURLConnection.
                val jarUri = java.net.URI(anchor.toString().removePrefix("jar:").substringBefore("!/"))
                java.util.zip.ZipFile(File(jarUri)).use { jar ->
                    jar.entries().asSequence()
                        .filter { !it.isDirectory && it.name.startsWith("dev/mixinmcp/") && it.name.endsWith(".class") }
                        .forEach { entry ->
                            check(entry.name, jar.getInputStream(entry).use { it.readBytes() })
                        }
                }
            }
            "file" -> {
                val mixinmcpDir: File = File(anchor.toURI()).parentFile.parentFile
                mixinmcpDir.walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .forEach { check(it.path, it.readBytes()) }
            }
            else -> assertTrue("unsupported main output protocol: ${anchor.protocol}", false)
        }

        assertTrue(
            "always-loaded classes reference optional Gradle-module types, so the plugin would fail " +
                "to load when org.jetbrains.plugins.gradle is disabled: $offenders",
            offenders.isEmpty(),
        )
    }
}
