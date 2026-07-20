package dev.mixinmcp.tools.refactor

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.extractMethod.newImpl.ExtractMethodPipeline
import com.intellij.refactoring.extractMethod.newImpl.ExtractSelector
import com.intellij.refactoring.extractMethod.newImpl.MethodExtractor
import com.intellij.refactoring.extractMethod.newImpl.structures.DataOutput
import com.intellij.refactoring.extractMethod.newImpl.structures.ExtractOptions
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/**
 * Guards `com.intellij.refactoring.extractMethod.newImpl`, which carries no `@ApiStatus` annotation at
 * any level and so is invisible to the plugin verifier: it reports "Compatible" whether or not this
 * surface still behaves. `mixin_extract_method` drives it directly, so these run the same calls the tool
 * makes against real PSI.
 *
 * The applying step, `DuplicatesMethodExtractor.extract()`, is deliberately not covered. It is a suspend
 * function, fixture tests run on the EDT, and wrapping it in `runBlocking` here deadlocks: a thread dump
 * shows AWT-EventQueue-0 parked in `BlockingCoroutine.joinBlocking` waiting on a coroutine that needs the
 * EDT to proceed. Covering it would need a harness that drives the test off the EDT while still giving
 * the fixture an EDT to dispatch to; until then that step is covered by dogfooding the tool against a
 * real project.
 */
class ExtractMethodPipelineTest : LightJavaCodeInsightFixtureTestCase() {

    fun testSelectorFindsStatementsAndPipelineDerivesParameters() {
        val file: PsiFile = myFixture.addFileToProject(
            "Sample.java",
            """
            class Sample {
                int run(int a, int b) {
                    int sum = a + b;
                    int scaled = sum * 2;
                    return scaled;
                }
            }
            """.trimIndent(),
        )
        val options: ExtractOptions = optionsFor(file, "int sum = a + b;", "int scaled = sum * 2;")

        val parameterNames: List<String> = options.inputParameters.map { it.name }
        assertTrue(
            "expected a and b as derived parameters, got $parameterNames",
            parameterNames.containsAll(listOf("a", "b")),
        )
        assertTrue(
            "expected a variable output, got ${options.dataOutput}",
            options.dataOutput is DataOutput.VariableOutput,
        )
        assertEquals("scaled", (options.dataOutput as DataOutput.VariableOutput).name)
    }

    fun testPreparedMethodCarriesRenamedSignature() {
        val file: PsiFile = myFixture.addFileToProject(
            "Renamed.java",
            """
            class Renamed {
                int run(int a) {
                    int doubled = a * 2;
                    return doubled;
                }
            }
            """.trimIndent(),
        )
        val options: ExtractOptions = optionsFor(file, "int doubled = a * 2;")
            .copy(methodName = "computeDoubled", visibility = "private")

        val prepared = MethodExtractor().prepareRefactoringElements(options)
        assertEquals("computeDoubled", prepared.method.name)
        assertEquals(listOf("a"), prepared.method.parameterList.parameters.map { it.name })
    }

    fun testFragmentWithoutOutputProducesEmptyDataOutput() {
        val file: PsiFile = myFixture.addFileToProject(
            "Logged.java",
            """
            class Logged {
                void run(String message) {
                    System.out.println(message);
                }
            }
            """.trimIndent(),
        )
        val options: ExtractOptions = optionsFor(file, "System.out.println(message);")

        assertTrue(
            "expected no output value, got ${options.dataOutput}",
            options.dataOutput is DataOutput.EmptyOutput,
        )
        assertEquals(listOf("message"), options.inputParameters.map { it.name })
    }

    /** Mirrors mixin_extract_method: select the fragment, then let the pipeline pick a target class. */
    private fun optionsFor(file: PsiFile, vararg fragments: String): ExtractOptions {
        val text: String = file.text
        val start: Int = text.indexOf(fragments.first())
        assertTrue("fragment not found: ${fragments.first()}", start >= 0)
        val lastFragment: String = fragments.last()
        val end: Int = text.indexOf(lastFragment) + lastFragment.length

        val elements: List<PsiElement> = ExtractSelector().suggestElementsToExtract(file, TextRange(start, end))
        assertTrue("ExtractSelector returned nothing for ${fragments.toList()}", elements.isNotEmpty())

        val all: List<ExtractOptions> = ExtractMethodPipeline.findAllOptionsToExtract(elements)
        assertTrue("findAllOptionsToExtract returned nothing", all.isNotEmpty())
        return all.firstOrNull { it.targetClass !is PsiAnonymousClass } ?: all.first()
    }
}
