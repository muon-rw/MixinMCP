package dev.mixinmcp.resolve

import com.intellij.psi.PsiMethod
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class DescriptorOverloadTest : LightJavaCodeInsightFixtureTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addClass("package a; public class Slot {}")
        myFixture.addClass("package a; public class SlotGroup {}")
        myFixture.addClass("package a; public interface Sink<T> {}")
        myFixture.addClass(
            """
            package a;
            public class Stack {
                public void each(SlotGroup group, Sink<String> sink) {}
                public void each(Slot slot, Sink<String> sink) {}
            }
            """.trimIndent(),
        )
    }

    fun testDescriptorPicksTheOverloadWithGenericParameter() {
        val resolution = MethodResolver.resolveDetailed(project, "a.Stack", "each", methodDescriptor = "(La/Slot;La/Sink;)V")
        assertEquals("Slot", firstParamType(resolution))
    }

    fun testErasedSimpleParameterTypesMatchGenericParameter() {
        val resolution = MethodResolver.resolveDetailed(project, "a.Stack", "each", parameterTypes = listOf("Slot", "Sink"))
        assertEquals("Slot", firstParamType(resolution))
    }

    fun testResolveByDescriptorPicksTheExactOverload() {
        val method: PsiMethod? = MethodResolver.resolveByDescriptor(project, "a.Stack", "each", "(La/Slot;La/Sink;)V")
        assertEquals("Slot", method?.parameterList?.parameters?.first()?.type?.presentableText)
    }

    private fun firstParamType(resolution: MethodResolver.Resolution): String {
        assertTrue(resolution.toString(), resolution is MethodResolver.Resolution.Found)
        return (resolution as MethodResolver.Resolution.Found).method.parameterList.parameters.first().type.presentableText
    }
}
