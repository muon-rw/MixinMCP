package dev.mixinmcp.resolve

import com.intellij.openapi.roots.DependencyScope
import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleScopeTagTest {

    @Test
    fun compileVisibleScopesStayUntagged() {
        assertEquals("", ClassVariants.scopeTag(DependencyScope.COMPILE))
        assertEquals("", ClassVariants.scopeTag(DependencyScope.PROVIDED))
        assertEquals("", ClassVariants.scopeTag(null))
    }

    @Test
    fun runtimeAndTestScopesAreTagged() {
        assertEquals(" (RUNTIME)", ClassVariants.scopeTag(DependencyScope.RUNTIME))
        assertEquals(" (TEST)", ClassVariants.scopeTag(DependencyScope.TEST))
    }

    @Test
    fun explanationReplacesTheShortTagWithoutRepeatingIt() {
        assertEquals(
            listOf("MixinMCP.main", "MixinMCP.test"),
            ClassVariants.explainScopeTags(listOf("MixinMCP.main", "MixinMCP.test")),
        )
        assertEquals(
            listOf("core.main", "core.test (RUNTIME, cannot compile against this class)", "b (TEST, test sources only)"),
            ClassVariants.explainScopeTags(listOf("core.main", "core.test (RUNTIME)", "b (TEST)")),
        )
    }
}
