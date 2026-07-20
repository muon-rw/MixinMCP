import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.kotlinSerialization) // @Serializable for MCP tool args
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(17)
}

// Configure project's dependencies
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// The IDE provides the Kotlin stdlib and org.jetbrains annotations at runtime; never ship our own
// copies even when transitive deps (mapping-io, kotlinx-serialization) pull them in.
// See https://plugins.jetbrains.com/docs/intellij/using-kotlin.html
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}

// Same reasoning for tests, and here it is load-bearing rather than tidiness: fixture tests run under
// the platform's coroutines debug javaagent, and our own stdlib's debug metadata is a version ahead of
// what that agent accepts ("Debug metadata version mismatch. Expected: 1, got 2"), which kills the
// fixture during plugin-descriptor loading.
configurations.testRuntimeClasspath {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/version_catalogs.html
dependencies {
    // ASM for bytecode analysis (IntelliJ bundles ASM but we need explicit access to asm-util for Textifier)
    implementation(libs.asm)
    implementation(libs.asm.util)

    // kotlinx.serialization for @Serializable MCP tool args
    implementation(libs.kotlinx.serialization.json)

    // mapping-io: parses tiny v1/v2, tsrg/tsrg2, ProGuard; composes namespaces
    implementation(libs.mapping.io)

    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        // Module Dependencies. Uses `platformBundledModules` property from the gradle.properties file for bundled IntelliJ Platform modules.
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(',') })

        testFramework(TestFrameworkType.Platform)
        // Java PSI fixtures: extractMethod.newImpl carries no @ApiStatus annotation, so the plugin
        // verifier cannot see it drift. Only running it against real PSI catches a behaviour change.
        testFramework(TestFrameworkType.Plugin.Java)
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html#specifying-a-release-channel
        channels = providers.gradleProperty("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = ""
}

// Configure Gradle Kover Plugin - read more: https://kotlin.github.io/kotlinx-kover/gradle-plugin/#configuration-details
kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }
}

// Dev sandbox: a throwaway IDE instance for dogfooding against a real mod project, so the production
// IDE never has to run a prerelease platform. Everything the tasks below reference is declared as a
// local, because a script-level val would be captured as a Gradle script object reference and the
// configuration cache cannot serialize those.
intellijPlatformTesting {
    runIde {
        register("runIdeDev") {
            task {
                val projectDir = layout.projectDirectory.dir(".sandbox/Chronicles-Leveling").asFile
                // IntelliJ opens the project passed as its first program argument; without one it stops
                // at the welcome screen and no MCP project tools resolve.
                if (projectDir.isDirectory) {
                    args(projectDir.absolutePath)
                }
                // The sandbox inherits this shell's JAVA_HOME, which is pinned to 17 for the plugin
                // build. Minecraft mod Gradle imports need 21+, so point the IDE at one.
                val gradleJvm = File("/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home")
                if (gradleJvm.isDirectory) {
                    environment("JAVA_HOME", gradleJvm.absolutePath)
                }
            }

            prepareSandboxTask {
                // Both IDEs are IDEA, so both derive the same default MCP port (64342) and would
                // collide; pin the sandbox to its own.
                val optionsDir = sandboxConfigDirectory.map { it.asFile.resolve("options") }
                val settingsXml = """
                    <application>
                      <component name="McpServerSettings">
                        <option name="enableMcpServer" value="true" />
                        <option name="mcpServerPort" value="64352" />
                        <option name="enableBraveMode" value="true" />
                      </component>
                    </application>
                """.trimIndent() + "\n"
                doLast {
                    val dir = optionsDir.get()
                    dir.mkdirs()
                    dir.resolve("mcpServer.xml").writeText(settingsXml)
                }
            }
        }

        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
            }

            plugins {
                robotServerPlugin()
            }
        }
    }
}
