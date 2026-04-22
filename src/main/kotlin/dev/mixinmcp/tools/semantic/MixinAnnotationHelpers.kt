package dev.mixinmcp.tools.semantic

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiType
import dev.mixinmcp.tools.source.collectAllSourceRoots
import dev.mixinmcp.tools.source.getPathForMask
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Short names of annotations recognized as mixin call-site injectors for cross-mod
 * conflict analysis. Covers SpongePowered Mixin built-ins and MixinExtras additions.
 * Does not include `@Accessor` / `@Invoker` (not injectors) or `@Definition` (a helper
 * for expression-based injectors, not an injector itself).
 */
internal val INJECTOR_ANNOTATION_SHORT_NAMES: Set<String> = setOf(
    // SpongePowered Mixin
    "Inject",
    "Redirect",
    "Overwrite",
    "ModifyArg",
    "ModifyArgs",
    "ModifyConstant",
    "ModifyVariable",
    // MixinExtras
    "ModifyExpressionValue",
    "ModifyReturnValue",
    "ModifyReceiver",
    "WrapOperation",
    "WrapWithCondition",
    "WrapMethod",
)

internal fun normalizeForMatch(name: String): String =
    name.replace('/', '.').trim()

internal fun extractMixinTargets(psiClass: PsiClass): List<String> {
    val mixinAnnotation: PsiAnnotation = psiClass.modifierList?.annotations?.find {
        it.qualifiedName == "org.spongepowered.asm.mixin.Mixin" || it.qualifiedName?.endsWith(".Mixin") == true
    }
        ?: return emptyList()

    val targets = mutableListOf<String>()

    fun collectFromValue(value: PsiAnnotationMemberValue?) {
        when (value) {
            is PsiClassObjectAccessExpression -> {
                val operandType: PsiType = value.operand.type
                if (operandType is PsiClassType) {
                    operandType.resolve()?.qualifiedName?.let { targets.add(it) }
                }
            }
            is PsiArrayInitializerMemberValue -> {
                for (init in value.initializers) {
                    collectFromValue(init)
                }
            }
            is PsiLiteralExpression -> {
                (value.value as? String)?.let { targets.add(it) }
            }
            else -> {}
        }
    }

    mixinAnnotation.findAttributeValue("value")?.let { collectFromValue(it) }
    mixinAnnotation.findAttributeValue("targets")?.let { collectFromValue(it) }
    return targets
}

internal fun extractAllInjections(psiClass: PsiClass): List<String> {
    val result = mutableListOf<String>()
    for (method in psiClass.methods) {
        for (ann in method.modifierList?.annotations ?: emptyArray()) {
            val shortName: String? = ann.qualifiedName?.substringAfterLast('.')
            if (shortName != null && shortName in INJECTOR_ANNOTATION_SHORT_NAMES) {
                result.add(ann.text.trim())
            }
        }
    }
    return result
}

internal fun extractInjectionsForMethod(psiClass: PsiClass, methodName: String): List<String> {
    val result = mutableListOf<String>()
    for (method in psiClass.methods) {
        for (ann in method.modifierList?.annotations ?: emptyArray()) {
            val shortName: String? = ann.qualifiedName?.substringAfterLast('.')
            if (shortName != null && shortName in INJECTOR_ANNOTATION_SHORT_NAMES) {
                val methodValues: List<String> = extractMethodAttributeValues(ann.findAttributeValue("method"))
                if (methodValues.any { methodTargets(it, methodName) }) {
                    result.add(ann.text.trim())
                }
            }
        }
    }
    return result
}

private fun extractMethodAttributeValues(value: PsiAnnotationMemberValue?): List<String> {
    return when (value) {
        is PsiLiteralExpression -> (value.value as? String)?.let { listOf(it) } ?: emptyList()
        is PsiArrayInitializerMemberValue -> value.initializers
            .mapNotNull { (it as? PsiLiteralExpression)?.value as? String }
        else -> emptyList()
    }
}

private fun methodTargets(methodStr: String, methodName: String): Boolean {
    return methodStr == methodName ||
        methodStr.startsWith("$methodName(") ||
        methodStr.contains(";$methodName(") ||
        methodStr.endsWith(";$methodName")
}

internal fun findTargetingMixinsByRegex(
    project: Project,
    className: String,
    methodName: String?,
    maxResults: Int,
): List<Pair<String, String>> {
    val escapedClass: String = Pattern.quote(className)
    val pattern: Pattern = try {
        if (methodName != null) {
            Pattern.compile("@Mixin.*$escapedClass.*$methodName", Pattern.DOTALL)
        } else {
            Pattern.compile("@Mixin.*$escapedClass", Pattern.DOTALL)
        }
    } catch (_: Exception) {
        return emptyList()
    }
    val results: MutableList<Pair<String, String>> = mutableListOf()
    val classPattern: Pattern = Pattern.compile("(?:class|interface)\\s+(\\S+)\\s+")
    ReadAction.compute<Unit, Throwable> {
        for (root in collectAllSourceRoots(project)) {
            if (results.size >= maxResults) break
            collectMixinRegexMatches(root, root, pattern, classPattern, results, maxResults)
        }
    }
    return results
}

private fun collectMixinRegexMatches(
    vf: VirtualFile,
    root: VirtualFile,
    pattern: Pattern,
    classPattern: Pattern,
    results: MutableList<Pair<String, String>>,
    maxResults: Int,
) {
    if (results.size >= maxResults) return
    if (vf.isDirectory) {
        for (child in vf.children) {
            collectMixinRegexMatches(child, root, pattern, classPattern, results, maxResults)
        }
    } else if (vf.name.endsWith(".java")) {
        val content: String = try {
            String(vf.contentsToByteArray(), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            return
        }
        if (pattern.matcher(content).find()) {
            val classMatcher = classPattern.matcher(content)
            val classNameFound: String = if (classMatcher.find()) {
                classMatcher.group(1) ?: vf.nameWithoutExtension
            } else {
                vf.nameWithoutExtension
            }
            val path: String = getPathForMask(root, vf)
            val fqcn: String = path.removeSuffix(".java").replace("/", ".")
            if (results.none { it.first == fqcn }) {
                results.add(fqcn to vf.path)
            }
        }
    }
}