package dev.mixinmcp.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class AntigravityPluginTest {

    @Test
    fun presenceDetection() {
        val temp = Files.createTempDirectory("mixinmcp-agy-presence")
        try {
            assertFalse(isAntigravityPresent(temp))

            val cliDir = temp.resolve("antigravity-cli")
            Files.createDirectories(cliDir)
            assertTrue(isAntigravityPresent(temp))
            Files.delete(cliDir)

            val configDir = temp.resolve("config")
            Files.createDirectories(configDir)
            assertTrue(isAntigravityPresent(temp))
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    @Test
    fun scanDetectsGlobalAndWorkspaceInstalls() {
        val geminiHome = Files.createTempDirectory("mixinmcp-agy-home")
        val projectRoot = Files.createTempDirectory("mixinmcp-agy-project")
        try {
            val emptyScan = scanAntigravityPluginInstall(geminiHome, projectRoot)
            assertFalse(emptyScan.installed)
            assertTrue(emptyScan.versions.isEmpty())

            val globalPlugin = geminiHome.resolve("config/plugins/mixinmcp/plugin.json")
            Files.createDirectories(globalPlugin.parent)
            Files.writeString(globalPlugin, """{"name": "mixinmcp", "version": "1.5.0"}""")

            val globalScan = scanAntigravityPluginInstall(geminiHome, projectRoot)
            assertTrue(globalScan.installed)
            assertEquals(listOf("1.5.0"), globalScan.versions)

            val wsPlugin = projectRoot.resolve(".agents/plugins/mixinmcp/.antigravity-plugin/plugin.json")
            Files.createDirectories(wsPlugin.parent)
            Files.writeString(wsPlugin, """{"name": "mixinmcp", "version": "1.5.2"}""")

            val bothScan = scanAntigravityPluginInstall(geminiHome, projectRoot)
            assertTrue(bothScan.installed)
            assertEquals(listOf("1.5.0", "1.5.2"), bothScan.versions)
        } finally {
            geminiHome.toFile().deleteRecursively()
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun scanDetectsSkillsAndPluginsJson() {
        val geminiHome = Files.createTempDirectory("mixinmcp-agy-home-skills")
        val projectRoot = Files.createTempDirectory("mixinmcp-agy-project-skills")
        try {
            val skillFile = geminiHome.resolve("config/skills/mixinmcp-tools/SKILL.md")
            Files.createDirectories(skillFile.parent)
            Files.writeString(skillFile, "---\nname: mixinmcp-tools\n---\n")

            val skillScan = scanAntigravityPluginInstall(geminiHome, projectRoot)
            assertTrue(skillScan.installed)
            assertTrue(skillScan.versions.isEmpty())

            Files.delete(skillFile)
            assertFalse(scanAntigravityPluginInstall(geminiHome, projectRoot).installed)

            val pluginsJson = projectRoot.resolve(".agents/plugins.json")
            Files.createDirectories(pluginsJson.parent)
            Files.writeString(pluginsJson, """{"entries": [{"path": "../antigravity-plugin"}]}""")

            val jsonScan = scanAntigravityPluginInstall(geminiHome, projectRoot)
            assertTrue(jsonScan.installed)
        } finally {
            geminiHome.toFile().deleteRecursively()
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun parsePluginVersionHelper() {
        assertEquals("1.5.2", parsePluginJsonVersion("""{"name":"mixinmcp","version":"1.5.2"}"""))
        assertNull(parsePluginJsonVersion("""{"name":"mixinmcp"}"""))
    }

    @Test
    fun antigravityPluginBundleParityAndContent() {
        val root = Path.of("").toAbsolutePath()
        val gradleProps = root.resolve("gradle.properties")
        val claudeManifest = root.resolve("claude-plugin/.claude-plugin/plugin.json")
        val agyManifest = root.resolve("antigravity-plugin/plugin.json")
        assertTrue("gradle.properties should exist", Files.isRegularFile(gradleProps))
        assertTrue("claude-plugin manifest should exist", Files.isRegularFile(claudeManifest))
        assertTrue("antigravity-plugin manifest should exist", Files.isRegularFile(agyManifest))

        val propsVersion = Regex("""(?m)^\s*pluginVersion\s*=\s*(\S+)""")
            .find(Files.readString(gradleProps))?.groupValues?.get(1)
        val claudeVersion = parsePluginJsonVersion(Files.readString(claudeManifest))
        val agyVersion = parsePluginJsonVersion(Files.readString(agyManifest))
        assertNotNull("pluginVersion must be set in gradle.properties", propsVersion)
        assertEquals("Claude plugin version must match gradle.properties", propsVersion, claudeVersion)
        assertEquals("Antigravity plugin version must match gradle.properties", propsVersion, agyVersion)

        val rulesFile = root.resolve("antigravity-plugin/rules/AGENTS.md")
        assertTrue("antigravity-plugin/rules/AGENTS.md should exist", Files.isRegularFile(rulesFile))
        assertTrue("AGENTS.md must be under 24KB limit", Files.size(rulesFile) in 1..24_000)
        val rulesText = Files.readString(rulesFile)
        assertTrue("AGENTS.md should forbid shell Gradle builds", "get_run_configurations" in rulesText)
        assertTrue("AGENTS.md should require get_file_problems for mixins", "get_file_problems" in rulesText)
        assertTrue("AGENTS.md should cover VFS refresh after disk edits", "mixin_refresh_vfs" in rulesText)
        assertTrue("AGENTS.md should steer refactors to mixin_rename", "mixin_rename" in rulesText)

        val expectedSkillFiles = listOf(
            "skills/mixinmcp-tools/SKILL.md",
            "skills/mixinmcp-tools/references/toolchains.md",
            "skills/mixin-writing/SKILL.md",
            "skills/mixin-writing/references/at-reference.md",
            "skills/mixin-writing/references/expressions-language.md",
        )
        for (rel in expectedSkillFiles) {
            assertTrue("Missing antigravity-plugin/$rel", Files.isRegularFile(root.resolve("antigravity-plugin").resolve(rel)))
        }

        val agyToolsSkill = Files.readString(root.resolve("antigravity-plugin/skills/mixinmcp-tools/SKILL.md"))
        assertTrue("Antigravity skill should mention grep_search", "grep_search" in agyToolsSkill)
        assertTrue("Antigravity skill should mention read_file", "read_file" in agyToolsSkill)
        assertTrue("Antigravity skill should mention agy mcp", "agy mcp" in agyToolsSkill)
    }
}
