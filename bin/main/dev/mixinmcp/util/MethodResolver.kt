package dev.mixinmcp.util

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod

/**
 * Resolves methods within a class by name and optional parameter type list
 * or JVM method descriptor. Uses FqcnResolver and GlobalSearchScope.allScope
 * for dependency support.
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
     * Resolves a method by JVM descriptor. Tries PSI matching first; if that fails
     * (e.g. remapped names differ), falls back to bytecode-level match by exact
     * descriptor and maps back to PSI by parameter count.
     */
    fun resolveByDescriptor(
        project: Project,
        className: String,
        methodName: String,
        descriptor: String,
    ): PsiMethod? {
        return ReadAction.compute<PsiMethod?, Throwable> {
            val psiClass: PsiClass = FqcnResolver.resolveNested(project, className)
                ?: return@compute null

            val methods: List<PsiMethod> = findMethodsByName(psiClass, methodName)
            if (methods.isEmpty()) return@compute null

            val canonicalTypes: List<String> = DescriptorParser.parseParameterTypes(descriptor)
            if (canonicalTypes.isEmpty()) return@compute null

            val parameterTypes: List<String> = DescriptorParser.toParameterTypesFormat(canonicalTypes)
            val psiMatched: List<PsiMethod> = methods.filter { matchesDescriptorTypes(it, canonicalTypes, parameterTypes) }
            if (psiMatched.isNotEmpty()) return@compute psiMatched.first()

            val classBytes: ByteArray = ClassFileLocator.locate(project, className)
                ?: return@compute null
            val analysis: BytecodeAnalyzer.ClassAnalysis = BytecodeAnalyzer.analyze(classBytes, false)
            val bytecodeMatch: BytecodeAnalyzer.MethodInfo? = analysis.methods.find { m ->
                m.name == methodName && m.descriptor == descriptor
            }
            if (bytecodeMatch == null) return@compute null

            val paramCount: Int = canonicalTypes.size
            val sameParamCount: List<PsiMethod> = methods.filter { it.parameterList.parametersCount == paramCount }
            if (sameParamCount.size == 1) return@compute sameParamCount.first()
            if (sameParamCount.isEmpty()) return@compute null

            sameParamCount.first()
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
        methodDescriptor: String? = null,
    ): PsiMethod? {
        val resolution: Resolution = resolveDetailed(
            project, className, methodName,
            parameterTypes = parameterTypes,
            methodDescriptor = methodDescriptor,
        )
        return (resolution as? Resolution.Found)?.method
    }

    /**
     * Resolves a single method with full diagnostics. Returns [Resolution.Found] on
     * success, or [Resolution.Error] with an actionable message listing overloads,
     * suggesting parameterTypes or methodDescriptor, or noting that the class/method
     * doesn't exist.
     *
     * If both methodDescriptor and parameterTypes are provided, methodDescriptor
     * takes precedence. Error messages show both formats when applicable.
     *
     * Must be called inside ReadAction when invoked from tool methods (the tools
     * already wrap their logic in ReadAction.compute).
     */
    fun resolveDetailed(
        project: Project,
        className: String,
        methodName: String,
        parameterTypes: List<String>? = null,
        methodDescriptor: String? = null,
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

            val effectiveTypes: List<String>? = when {
                !methodDescriptor.isNullOrBlank() -> {
                    val canonical: List<String> = DescriptorParser.parseParameterTypes(methodDescriptor)
                    if (canonical.isEmpty()) {
                        return@compute Resolution.Error(
                            "Invalid method descriptor: '$methodDescriptor'. Expected format: (params)returnType, e.g. (Lnet/minecraft/world/entity/Entity;)V",
                        )
                    }
                    DescriptorParser.toParameterTypesFormat(canonical)
                }
                parameterTypes != null -> parameterTypes
                else -> null
            }

            if (effectiveTypes != null) {
                val canonicalTypes: List<String>? = if (!methodDescriptor.isNullOrBlank()) {
                    DescriptorParser.parseParameterTypes(methodDescriptor)
                } else null

                val matched: List<PsiMethod> = if (canonicalTypes != null) {
                    methods.filter { matchesDescriptorTypes(it, canonicalTypes, effectiveTypes) }
                } else {
                    methods.filter { matchesParameterTypes(it, effectiveTypes) }
                }

                if (matched.isNotEmpty()) {
                    return@compute Resolution.Found(matched.first())
                }

                val byDescriptor: PsiMethod? = if (!methodDescriptor.isNullOrBlank()) {
                    resolveByDescriptor(project, className, methodName, methodDescriptor)
                } else null
                if (byDescriptor != null) {
                    return@compute Resolution.Found(byDescriptor)
                }

                return@compute Resolution.Error(buildString {
                    append("No overload of ${psiClass.qualifiedName}#$methodName matches ")
                    if (!methodDescriptor.isNullOrBlank()) {
                        append("methodDescriptor '$methodDescriptor'")
                        append(" (parameterTypes equivalent: $effectiveTypes)")
                    } else {
                        append("parameterTypes $effectiveTypes")
                    }
                    append(".\n Available overloads:\n")
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
                append(" Pass parameterTypes or methodDescriptor to disambiguate:\n")
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

    /**
     * Matches a PsiMethod against descriptor-derived types. Tries canonical and
     * simple names since PSI may use either (remapped vs. fully-qualified).
     */
    private fun matchesDescriptorTypes(
        method: PsiMethod,
        canonicalTypes: List<String>,
        simpleTypes: List<String>,
    ): Boolean {
        val params = method.parameterList.parameters
        if (params.size != canonicalTypes.size) return false
        return params.zip(canonicalTypes.zip(simpleTypes)).all { (param, expected) ->
            val (canonical, simple) = expected
            param.type.canonicalText == canonical ||
                param.type.presentableText == canonical ||
                param.type.presentableText == simple
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
