package dev.mixinmcp.util

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceMethodVisitor
import java.io.PrintWriter
import java.io.StringWriter

/**
 * ASM-based bytecode analysis. Identifies synthetic methods, lambdas,
 * and parses lambda source method names for mixin target resolution.
 */
object BytecodeAnalyzer {

    data class MethodInfo(
        val access: Int,
        val name: String,
        val descriptor: String,
        val signature: String?,
        var instructions: String? = null,
    ) {
        val isSynthetic: Boolean get() = access and Opcodes.ACC_SYNTHETIC != 0
        val isBridge: Boolean get() = access and Opcodes.ACC_BRIDGE != 0
        val isLambda: Boolean get() = isSynthetic && name.startsWith("lambda$")

        /**
         * For synthetic lambdas like "lambda$tick$0", extracts the source method name.
         */
        val lambdaSourceMethod: String?
            get() {
                if (!isLambda) return null
                val stripped: String = name.removePrefix("lambda$")
                val dollarIndex: Int = stripped.lastIndexOf('$')
                return if (dollarIndex > 0) stripped.substring(0, dollarIndex) else stripped
            }
    }

    data class FieldInfo(
        val access: Int,
        val name: String,
        val descriptor: String,
        val value: Any?,
    ) {
        val isSynthetic: Boolean get() = access and Opcodes.ACC_SYNTHETIC != 0
    }

    data class ClassAnalysis(
        val version: Int,
        val access: Int,
        val name: String,
        val superName: String?,
        val interfaces: List<String>,
        val methods: List<MethodInfo>,
        val fields: List<FieldInfo>,
    )

    /**
     * Performs full class analysis. Set includeInstructions=true to also
     * capture bytecode instructions for each method (expensive).
     */
    fun analyze(classBytes: ByteArray, includeInstructions: Boolean = false): ClassAnalysis {
        val reader = ClassReader(classBytes)
        val methods = mutableListOf<MethodInfo>()
        val fields = mutableListOf<FieldInfo>()

        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    val info = MethodInfo(access, name, descriptor, signature)
                    methods.add(info)

                    if (includeInstructions) {
                        val sw = StringWriter()
                        val printer = Textifier()
                        val tmv = TraceMethodVisitor(printer)
                        return object : MethodVisitor(Opcodes.ASM9, tmv) {
                            override fun visitEnd() {
                                super.visitEnd()
                                printer.print(PrintWriter(sw))
                                info.instructions = sw.toString().trimEnd()
                            }
                        }
                    }
                    return null
                }

                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor? {
                    fields.add(FieldInfo(access, name, descriptor, value))
                    return null
                }
            },
            if (includeInstructions) 0 else ClassReader.SKIP_CODE,
        )

        return ClassAnalysis(
            version = reader.readUnsignedShort(6),
            access = reader.access,
            name = reader.className.replace('/', '.'),
            superName = reader.superName?.replace('/', '.'),
            interfaces = reader.interfaces?.map { it.replace('/', '.') } ?: emptyList(),
            methods = methods,
            fields = fields,
        )
    }

    /**
     * Gets the textified bytecode for a single method in a class.
     * Returns javap-style output for just that method.
     */
    fun analyzeMethod(
        classBytes: ByteArray,
        targetMethodName: String,
        targetDescriptor: String? = null,
    ): String? {
        val reader = ClassReader(classBytes)
        val sw = StringWriter()
        var found = false

        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (name == targetMethodName &&
                        (targetDescriptor == null || descriptor == targetDescriptor)
                    ) {
                        found = true
                        val printer = Textifier()
                        return object : MethodVisitor(Opcodes.ASM9, TraceMethodVisitor(printer)) {
                            override fun visitEnd() {
                                super.visitEnd()
                                val pw = PrintWriter(sw)
                                pw.println("  // access: ${accessFlagsToString(access)}")
                                pw.println("  // descriptor: $descriptor")
                                if (signature != null) pw.println("  // signature: $signature")
                                pw.println()
                                printer.print(pw)
                                pw.flush()
                            }
                        }
                    }
                    return null
                }
            },
            0,
        )

        return if (found) sw.toString().trimEnd() else null
    }

    private val ACCESS_FLAGS: List<Pair<Int, String>> = listOf(
        Opcodes.ACC_PUBLIC to "public",
        Opcodes.ACC_PRIVATE to "private",
        Opcodes.ACC_PROTECTED to "protected",
        Opcodes.ACC_STATIC to "static",
        Opcodes.ACC_FINAL to "final",
        Opcodes.ACC_SYNCHRONIZED to "synchronized",
        Opcodes.ACC_VOLATILE to "volatile",
        Opcodes.ACC_TRANSIENT to "transient",
        Opcodes.ACC_NATIVE to "native",
        Opcodes.ACC_ABSTRACT to "abstract",
        Opcodes.ACC_SYNTHETIC to "synthetic",
        Opcodes.ACC_BRIDGE to "bridge",
    )

    fun accessFlagsToString(access: Int): String =
        ACCESS_FLAGS
            .filter { (flag, _) -> access and flag != 0 }
            .map { it.second }
            .joinToString(" ")
}
