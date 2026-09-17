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
    fun noteExplainsOnlyTheTagsPresent() {
        assertEquals("", ClassVariants.scopeNote(listOf("MixinMCP.main", "MixinMCP.test")))
        assertEquals(
            " (RUNTIME: that module cannot compile against this class)",
            ClassVariants.scopeNote(listOf("neoforge.main", "common.main (RUNTIME)")),
        )
        assertEquals(
            " (RUNTIME: that module cannot compile against this class; TEST: only that module's test sources can)",
            ClassVariants.scopeNote(listOf("a.main (RUNTIME)", "b (TEST)")),
        )
    }
}
