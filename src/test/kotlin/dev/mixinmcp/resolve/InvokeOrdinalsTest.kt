package dev.mixinmcp.resolve

import dev.mixinmcp.resolve.BytecodeAnalyzer.InvokeSite
import dev.mixinmcp.resolve.BytecodeAnalyzer.InvokeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

class InvokeOrdinalsTest {

    private val utilA = InvokeTarget("t.Util", "a", "()V")
    private val utilB = InvokeTarget("t.Util", "b", "()V")
    private val subA = InvokeTarget("t.Sub", "a", "()V")
    private val lambdaName = "lambda\$run$0"

    private fun MethodVisitor.line(line: Int) {
        val label = Label()
        visitLabel(label)
        visitLineNumber(line, label)
    }

    private fun MethodVisitor.invokeStatic(owner: String, name: String) {
        visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, "()V", false)
    }

    private fun sampleClass(): ByteArray {
        val metafactory = Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                "Ljava/lang/invoke/CallSite;",
            false,
        )
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "t/Sample", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null).apply {
            visitCode()
            line(10)
            invokeStatic("t/Util", "a")
            line(11)
            invokeStatic("t/Util", "b")
            visitInvokeDynamicInsn(
                "run",
                "()Ljava/lang/Runnable;",
                metafactory,
                Type.getType("()V"),
                Handle(Opcodes.H_INVOKESTATIC, "t/Sample", lambdaName, "()V", false),
                Type.getType("()V"),
            )
            visitInsn(Opcodes.POP)
            line(12)
            invokeStatic("t/Util", "a")
            invokeStatic("t/Sub", "a")
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            lambdaName,
            "()V",
            null,
            null,
        ).apply {
            visitCode()
            invokeStatic("t/Util", "a")
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun ordinalsOf(method: String): Map<InvokeTarget, List<InvokeSite>> =
        BytecodeAnalyzer.invokeOrdinals(BytecodeAnalyzer.extractInvocations(sampleClass(), method, "()V")!!)

    @Test
    fun repeatedTargetIsNumberedInInstructionOrderWithLines() {
        val ordinals = ordinalsOf("run")
        assertEquals(listOf(InvokeSite(0, 10), InvokeSite(1, 12)), ordinals[utilA])
        assertEquals(listOf(InvokeSite(0, 11)), ordinals[utilB])
    }

    @Test
    fun ownerAsWrittenInTheInstructionIsASeparateTarget() {
        assertEquals(listOf(InvokeSite(0, 12)), ordinalsOf("run")[subA])
    }

    @Test
    fun invokeDynamicAndLambdaBodyCallsDoNotCountTowardTheEnclosingMethod() {
        val ordinals = ordinalsOf("run")
        assertEquals(setOf(utilA, utilB, subA), ordinals.keys)
        assertEquals(listOf(InvokeSite(0, null)), ordinalsOf(lambdaName)[utilA])
    }

    @Test
    fun missingMethodIsNull() {
        assertNull(BytecodeAnalyzer.extractInvocations(sampleClass(), "run", "(I)V"))
        assertNull(BytecodeAnalyzer.extractInvocations(sampleClass(), "absent", "()V"))
    }

    @Test
    fun groupingKeepsFirstSeenTargetOrder() {
        val invocations = listOf(
            BytecodeAnalyzer.Invocation(utilB, 1),
            BytecodeAnalyzer.Invocation(utilA, 2),
            BytecodeAnalyzer.Invocation(utilB, 3),
            BytecodeAnalyzer.Invocation(utilB, null),
        )
        val ordinals = BytecodeAnalyzer.invokeOrdinals(invocations)
        assertEquals(listOf(utilB, utilA), ordinals.keys.toList())
        assertEquals(listOf(InvokeSite(0, 1), InvokeSite(1, 3), InvokeSite(2, null)), ordinals[utilB])
        assertTrue(BytecodeAnalyzer.invokeOrdinals(emptyList()).isEmpty())
    }
}
