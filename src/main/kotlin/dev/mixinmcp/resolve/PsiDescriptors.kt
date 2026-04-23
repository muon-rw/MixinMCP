package dev.mixinmcp.resolve

import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.util.ClassUtil
import com.intellij.psi.util.TypeConversionUtil

/**
 * Converts IntelliJ PSI types back into JVM descriptors / internal names.
 * Used where tool output needs to match bytecode-level identity (e.g. for
 * @At(target="...") strings and for deduplicating callees uniformly across
 * the source-walker and bytecode-fallback paths in the call-hierarchy tool).
 *
 * All conversions erase generics via [TypeConversionUtil.erasure] so a
 * `List<String>` parameter yields `Ljava/util/List;`, matching what the
 * compiler actually emits.
 */
object PsiDescriptors {

    fun methodDescriptor(method: PsiMethod): String {
        val sb = StringBuilder("(")
        for (p in method.parameterList.parameters) {
            sb.append(typeDescriptor(p.type))
        }
        sb.append(')')
        if (method.isConstructor) {
            sb.append('V')
        } else {
            val returnType: PsiType? = method.returnType
            sb.append(if (returnType == null) "V" else typeDescriptor(returnType))
        }
        return sb.toString()
    }

    fun typeDescriptor(type: PsiType): String {
        val erased: PsiType = TypeConversionUtil.erasure(type) ?: return "Ljava/lang/Object;"
        return when (erased) {
            is PsiArrayType -> "[" + typeDescriptor(erased.componentType)
            is PsiPrimitiveType -> when (erased.name) {
                "void" -> "V"
                "boolean" -> "Z"
                "byte" -> "B"
                "char" -> "C"
                "short" -> "S"
                "int" -> "I"
                "long" -> "J"
                "float" -> "F"
                "double" -> "D"
                else -> "Ljava/lang/Object;"
            }
            is PsiClassType -> {
                val resolved: PsiClass? = erased.resolve()
                val internal: String = if (resolved != null) {
                    classInternalName(resolved)
                } else {
                    erased.canonicalText.substringBefore('<').replace('.', '/')
                }
                "L$internal;"
            }
            else -> "Ljava/lang/Object;"
        }
    }

    /**
     * JVM internal name for a class — slashes for packages, `$` for nested
     * classes. Falls back to the qualified name when [ClassUtil] returns null
     * (anonymous / local classes without a JVM name still get a best-effort
     * result so output remains readable).
     */
    fun classInternalName(psiClass: PsiClass): String {
        val jvm: String? = ClassUtil.getJVMClassName(psiClass)
        if (jvm != null) return jvm.replace('.', '/')
        return psiClass.qualifiedName?.replace('.', '/')
            ?: psiClass.name
            ?: "?"
    }
}
