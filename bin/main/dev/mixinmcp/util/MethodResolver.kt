package dev.mixinmcp.util

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod

/**
 * Resolves methods within a class by name and optional parameter type list.
 * Uses FqcnResolver and GlobalSearchScope.allScope for dependency support.
 */
object MethodResolver {

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
            val psiClass: com.intellij.psi.PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@compute emptyList()

            // findMethodsByName can return empty for JDK classes; fallback to direct methods
            var methods: List<PsiMethod> = psiClass.findMethodsByName(methodName, true).toList()
            if (methods.isEmpty()) {
                methods = psiClass.methods.filter { it.name == methodName }
            }

            if (parameterTypes == null) {
                methods
            } else {
                methods.filter { method ->
                    val params = method.parameterList.parameters
                    params.size == parameterTypes.size &&
                        params.zip(parameterTypes).all { (param, expectedType) ->
                            param.type.presentableText == expectedType ||
                                param.type.canonicalText == expectedType
                        }
                }
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
        val methods: List<PsiMethod> = resolve(project, className, methodName, parameterTypes)
        return when {
            methods.isEmpty() -> null
            methods.size == 1 -> methods.first()
            parameterTypes != null -> methods.firstOrNull()
            else -> null // Ambiguous without parameter types
        }
    }
}
