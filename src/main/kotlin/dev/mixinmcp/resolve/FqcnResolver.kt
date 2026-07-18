package dev.mixinmcp.resolve

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock

/**
 * Resolves fully-qualified class names to PsiClass instances, including
 * dependency and library classes. Uses GlobalSearchScope.allScope() — the
 * critical difference from built-in tools which use projectScope().
 */
object FqcnResolver {

    const val CLASS_NOT_FOUND_HINT: String =
        "Class names accept dot FQCNs, Outer.Inner or Outer\$Inner nesting, and slash-separated " +
            "internal names; for partial names use mixin_search_symbols."

    /**
     * Resolves a fully-qualified class name to a PsiClass.
     * Defaults to GlobalSearchScope.allScope() to search BOTH project AND dependency
     * classes; pass a narrower scope (e.g. from ModuleScopes) to pin resolution.
     * When variants exist in both project source and libraries (e.g. a stale copy
     * in a build-output jar), the project-source variant wins.
     */
    @RequiresReadLock
    fun resolve(
        project: Project,
        fqcn: String,
        scope: GlobalSearchScope = GlobalSearchScope.allScope(project),
    ): PsiClass? {
        val candidates: Array<PsiClass> = JavaPsiFacade.getInstance(project).findClasses(fqcn, scope)
        if (candidates.size <= 1) return candidates.firstOrNull()
        val index: ProjectFileIndex = ProjectFileIndex.getInstance(project)
        return candidates.firstOrNull { c ->
            c.containingFile?.virtualFile?.let { index.isInContent(it) } == true
        } ?: candidates[0]
    }

    /**
     * Resolves with inner class support.
     * Handles "com.example.Outer.Inner" (dot), "com.example.Outer$Inner" (dollar),
     * and "com/example/Outer$Inner" (slash-separated internal name) input forms.
     * Tries direct resolution first, then progressively converts dots to dollars.
     */
    @RequiresReadLock
    fun resolveNested(
        project: Project,
        fqcn: String,
        scope: GlobalSearchScope = GlobalSearchScope.allScope(project),
    ): PsiClass? {
        resolve(project, fqcn, scope)?.let { return it }

        val normalized: String = fqcn.replace('/', '.').replace('$', '.')
        if (normalized != fqcn) {
            resolve(project, normalized, scope)?.let { return it }
        }

        val parts: List<String> = normalized.split(".")
        for (i in parts.size - 1 downTo 1) {
            val candidate: String = parts.subList(0, i).joinToString(".") + "$" +
                parts.subList(i, parts.size).joinToString("$")
            resolve(project, candidate, scope)?.let { return it }
        }

        return null
    }
}
