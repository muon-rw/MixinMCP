package dev.mixinmcp.util

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope

/**
 * Resolves fully-qualified class names to PsiClass instances, including
 * dependency and library classes. Uses GlobalSearchScope.allScope() — the
 * critical difference from built-in tools which use projectScope().
 */
object FqcnResolver {

    /**
     * Resolves a fully-qualified class name to a PsiClass.
     * Uses GlobalSearchScope.allScope() to search BOTH project AND dependency classes.
     */
    fun resolve(project: Project, fqcn: String): PsiClass? {
        return ReadAction.compute<PsiClass?, Throwable> {
            JavaPsiFacade.getInstance(project)
                .findClass(fqcn, GlobalSearchScope.allScope(project))
        }
    }

    /**
     * Resolves with inner class support.
     * Handles both "com.example.Outer.Inner" (dot) and "com.example.Outer$Inner" (dollar).
     * Tries direct resolution first, then progressively converts dots to dollars.
     */
    fun resolveNested(project: Project, fqcn: String): PsiClass? {
        resolve(project, fqcn)?.let { return it }

        val parts: List<String> = fqcn.split(".")
        for (i in parts.size - 1 downTo 1) {
            val candidate: String = parts.subList(0, i).joinToString(".") + "$" +
                parts.subList(i, parts.size).joinToString("$")
            resolve(project, candidate)?.let { return it }
        }

        return null
    }
}
