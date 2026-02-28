package dev.mixinmcp.util

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod

/**
 * Resolves methods within a class by name and optional parameter type list.
 * Uses FqcnResolver and GlobalSearchScope.allScope for dependency support.
 */
object MethodResolver {

    /** Result of [resolveDetailed]: either the resolved method or a diagnostic error. */
    sealed class Resolution {
        data class Found(val method: PsiMethod) : Resolution()
        data class Error(val message: String) : Resolution()
    }

    /**
     * Finds methods in a class by name and optional parameter type list.
     * If parameterTypes is null and there are multiple overloads, returns all of them.
     * If parameterTypes is provided, matches against presentable type names.
     */
    fun resolve(
        project: Project,
        className: String,
        methodName: String,
        parameterTypes: List<String>? = null,
    ): List<PsiMethod> {
        return ReadAction.compute<List<PsiMethod>, Throwable> {
            val psiClass: PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@compute emptyList()

            val methods: List<PsiMethod> = findMethodsByName(psiClass, methodName)

            if (parameterTypes == null) {
                methods
            } else {
                methods.filter { method -> matchesParameterTypes(method, parameterTypes) }
            }
        }
    }

    /**
     * Convenience: resolve a single method, erroring if ambiguous.
     */
    fun resolveSingle(
        project: Project,
        className: String,
        methodName: String,
        parameterTypes: List<String>? = null,
    ): PsiMethod? {
        val resolution: Resolution = resolveDetailed(project, className, methodName, parameterTypes)
        return (resolution as? Resolution.Found)?.method
    }

    /**
     * Resolves a single method with full diagnostics. Returns [Resolution.Found] on
     * success, or [Resolution.Error] with an actionable message listing overloads,
     * suggesting parameterTypes, or noting that the class/method doesn't exist.
     *
     * Must be called inside ReadAction when invoked from tool methods (the tools
     * already wrap their logic in ReadAction.compute).
     */
    fun resolveDetailed(
        project: Project,
        className: String,
        methodName: String,
        parameterTypes: List<String>? = null,
    ): Resolution {
        return ReadAction.compute<Resolution, Throwable> {
            val psiClass: PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@compute Resolution.Error("Class not found: $className")

            val methods: List<PsiMethod> = findMethodsByName(psiClass, methodName)

            if (methods.isEmpty()) {
                return@compute Resolution.Error(
                    "No method named '$methodName' found in ${psiClass.qualifiedName ?: className}.",
                )
            }

            if (parameterTypes != null) {
                val matched: List<PsiMethod> = methods.filter { matchesParameterTypes(it, parameterTypes) }
                if (matched.isNotEmpty()) {
                    return@compute Resolution.Found(matched.first())
                }
                return@compute Resolution.Error(buildString {
                    append("No overload of ${psiClass.qualifiedName}#$methodName matches parameterTypes $parameterTypes.")
                    append(" Available overloads:\n")
                    for (sig in formatOverloads(methods)) {
                        append("  $sig\n")
                    }
                })
            }

            if (methods.size == 1) {
                return@compute Resolution.Found(methods.first())
            }

            Resolution.Error(buildString {
                append("Multiple overloads of ${psiClass.qualifiedName}#$methodName.")
                append(" Pass parameterTypes to disambiguate:\n")
                for (sig in formatOverloads(methods)) {
                    append("  $sig\n")
                }
            })
        }
    }

    private fun findMethodsByName(psiClass: PsiClass, methodName: String): List<PsiMethod> {
        val methods: List<PsiMethod> = psiClass.findMethodsByName(methodName, true).toList()
        if (methods.isNotEmpty()) return methods
        return psiClass.methods.filter { it.name == methodName }
    }

    private fun matchesParameterTypes(method: PsiMethod, parameterTypes: List<String>): Boolean {
        val params = method.parameterList.parameters
        return params.size == parameterTypes.size &&
            params.zip(parameterTypes).all { (param, expectedType) ->
                param.type.presentableText == expectedType ||
                    param.type.canonicalText == expectedType
            }
    }

    private fun formatOverloads(methods: List<PsiMethod>): List<String> {
        return methods.map { method ->
            val params: String = method.parameterList.parameters
                .joinToString(", ") { "${it.type.presentableText} ${it.name}" }
            val typeList: String = method.parameterList.parameters
                .joinToString(", ") { "\"${it.type.presentableText}\"" }
            "${method.name}($params)  →  parameterTypes: [$typeList]"
        }
    }
}
