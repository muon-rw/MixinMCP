package dev.mixinmcp.tools.semantic

import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class CallHierarchyOrdinalsTest : LightJavaCodeInsightFixtureTestCase() {

    private fun sample(): Map<String, PsiMethod> {
        val file = myFixture.addFileToProject(
            "p/Target.java",
            """
            package p;
            class Target {
                static void t() {}
                void caller() {
                    t();
                    Runnable r = () -> t();
                    t();
                }
                void once() { t(); }
            }
            """.trimIndent(),
        ) as PsiJavaFile
        return file.classes.single().methods.associateBy { it.name }
    }

    private fun callers(method: PsiMethod, maxResults: Int = 50): Pair<List<String>, Boolean> {
        val budget = CallHierarchyExpander.Budget(maxResults)
        val out = StringBuilder()
        CallHierarchyExpander.expandCallers(
            project, method, 0, 1, GlobalSearchScope.allScope(project),
            mutableSetOf(CallHierarchyExpander.cycleKeyOf(method)), budget, out,
        )
        return out.lines().filter { it.isNotBlank() } to budget.truncated
    }

    fun testUnbuiltCalleeGetsSourceOrderOrdinalsExcludingLambdaBody() {
        val caller: PsiMethod = sample().getValue("caller")
        val out = StringBuilder()
        CallHierarchyExpander.expandCallees(
            project, CallHierarchyExpander.targetFor(caller, null), 0, 1,
            mutableSetOf(CallHierarchyExpander.cycleKeyOf(caller)), CallHierarchyExpander.Budget(50), out,
        )
        val line: String = out.lines().single { "p.Target#t()V" in it }
        assertTrue(line, line.endsWith("p.Target#t()V  [x2, source order: ordinal 0 line 5, ordinal 1 line 7]"))
    }

    fun testCallerIsListedOnceWithOrdinalsAndLambdaReference() {
        val (lines: List<String>, truncated: Boolean) = callers(sample().getValue("t"))
        assertEquals(lines.joinToString("\n"), 2, lines.size)
        val caller: String = lines.single { "p.Target#caller()" in it }
        assertTrue(caller, caller.endsWith("[x2, source order: ordinal 0 line 5, ordinal 1 line 7]  (also line 6)"))
        val once: String = lines.single { "p.Target#once()" in it }
        assertFalse(once, "[x" in once || "also" in once)
        assertFalse(truncated)
    }

    fun testCallersExcludeOtherOverloads() {
        val file = myFixture.addFileToProject(
            "q/Over.java",
            """
            package q;
            class Over {
                static void f(int a) {}
                static void f(String s) {}
                void usesInt() { f(1); }
                void usesString() { f("x"); }
            }
            """.trimIndent(),
        ) as PsiJavaFile
        val intOverload: PsiMethod = file.classes.single().methods.single { it.name == "f" && "int" in it.parameterList.text }
        val (lines: List<String>, _) = callers(intOverload)
        assertEquals(lines.joinToString("\n"), 1, lines.size)
        assertTrue(lines.single(), "q.Over#usesInt()" in lines.single())
    }

    fun testCallerGroupsShareTheResultBudget() {
        val (lines: List<String>, truncated: Boolean) = callers(sample().getValue("t"), maxResults = 1)
        assertEquals(lines.joinToString("\n"), 1, lines.size)
        assertTrue(truncated)
    }

    fun testBinaryCalleeGetsBytecodeOrdinalsPerOwnerAsWritten() {
        val dir: File = FileUtil.createTempDirectory("mixinmcp-ordinals", null)
        JarOutputStream(FileOutputStream(File(dir, "lib.jar"))).use { jar ->
            for ((name, bytes) in libraryClasses()) {
                jar.putNextEntry(JarEntry("$name.class"))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
        PsiTestUtil.addLibrary(testRootDisposable, module, "ordinals-lib", dir.path, "lib.jar")
        val use: PsiMethod = JavaPsiFacade.getInstance(project)
            .findClass("lib.User", GlobalSearchScope.allScope(project))!!
            .findMethodsByName("use", false).single()
        val out = StringBuilder()
        CallHierarchyExpander.expandCallees(
            project, CallHierarchyExpander.targetFor(use, null), 0, 1,
            mutableSetOf(CallHierarchyExpander.cycleKeyOf(use)), CallHierarchyExpander.Budget(50), out,
        )
        assertEquals(
            out.toString(),
            listOf(
                "[L1] lib.Sub#foo()V  [x2: ordinal 0 line 20, ordinal 1 line 22]",
                "[L1] lib.Base#foo()V  [x2: ordinal 0 line 21, ordinal 1 line 22]",
                "[L1] lib.Base#once()V",
            ),
            out.lines().filter { it.startsWith("[L1]") },
        )
    }

    private fun libraryClasses(): Map<String, ByteArray> {
        fun libClass(name: String, superName: String): ByteArray {
            val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null)
            if (name == "lib/Base") {
                val methods: List<Pair<String, Int>> =
                    listOf("foo" to Opcodes.ACC_PUBLIC, "once" to (Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC))
                for ((method, access) in methods) {
                    writer.visitMethod(access, method, "()V", null, null).apply {
                        visitCode()
                        visitInsn(Opcodes.RETURN)
                        visitMaxs(0, 0)
                        visitEnd()
                    }
                }
            }
            writer.visitEnd()
            return writer.toByteArray()
        }

        val user = ClassWriter(ClassWriter.COMPUTE_MAXS)
        user.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "lib/User", null, "java/lang/Object", null)
        user.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "use", "(Llib/Sub;Llib/Base;)V", null, null).apply {
            fun line(line: Int) {
                val label = Label()
                visitLabel(label)
                visitLineNumber(line, label)
            }
            fun invokeFoo(slot: Int, owner: String) {
                visitVarInsn(Opcodes.ALOAD, slot)
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "foo", "()V", false)
            }
            visitCode()
            line(20)
            invokeFoo(0, "lib/Sub")
            line(21)
            invokeFoo(1, "lib/Base")
            visitMethodInsn(Opcodes.INVOKESTATIC, "lib/Base", "once", "()V", false)
            line(22)
            invokeFoo(0, "lib/Sub")
            invokeFoo(1, "lib/Base")
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        user.visitEnd()

        return mapOf(
            "lib/Base" to libClass("lib/Base", "java/lang/Object"),
            "lib/Sub" to libClass("lib/Sub", "lib/Base"),
            "lib/User" to user.toByteArray(),
        )
    }
}
