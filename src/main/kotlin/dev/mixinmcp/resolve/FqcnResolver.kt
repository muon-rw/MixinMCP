package dev.mixinmcp.resolve

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.util.concurrency.annotations.RequiresReadLock
import java.io.File

/**
 * Resolves fully-qualified class names to PsiClass instances, including
 * dependency and library classes. Defaults to GlobalSearchScope.everythingScope():
 * unlike allScope (ProjectAndLibrariesScope), an EverythingGlobalScope lets
 * NonClasspathClassFinder extensions answer, which is how Gradle buildscript
 * classes (GradleClassFinder) resolve on projects where they are not indexed.
 * Index-backed searches elsewhere (references, inheritors, short names) keep
 * allScope on purpose; unindexed content has no stub entries for any scope.
 */
object FqcnResolver {

    const val CLASS_NOT_FOUND_HINT: String =
        "Class names accept dot FQCNs, Outer.Inner or Outer\$Inner nesting, and slash-separated " +
            "internal names; for partial names use mixin_search_symbols."

    /** [prefix] plus the classpath classes sharing [className]'s simple name, for a wrong package or a bare name. */
    @RequiresReadLock
    fun notFoundMessage(project: Project, className: String, prefix: String = "Class not found: $className"): String =
        buildString {
            append(prefix).append(". ")
            unloadedSourceFile(project, className)?.let { path ->
                append("A source file that may declare it exists on disk but the IDE has not loaded it yet: $path. ")
                append("Run mixin_refresh_vfs(filePath=\"$path\") and retry. ")
            }
            val similar: List<String> = sameSimpleName(project, className)
            if (similar.isNotEmpty()) append("Classes with that simple name: ${similar.joinToString(", ")}. ")
            append(CLASS_NOT_FOUND_HINT)
        }

    @RequiresReadLock
    fun unloadedSourceFile(project: Project, className: String): String? {
        val roots: List<String> = ProjectRootManager.getInstance(project).contentSourceRoots
            .filter { it.isInLocalFileSystem }
            .map { it.path }
        val fileSystem: LocalFileSystem = LocalFileSystem.getInstance()
        return unloadedSourceFile(roots, className) { fileSystem.findFileByIoFile(it)?.timeStamp }
    }

    /** The first file under [sourceRoots] that could declare [className] and is missing from the VFS or newer on disk. */
    internal fun unloadedSourceFile(sourceRoots: List<String>, className: String, vfsTimeStamp: (File) -> Long?): String? {
        val segments: List<String> = className.trim().replace('/', '.').replace('$', '.').split('.').filter { it.isNotEmpty() }
        for (root: String in sourceRoots) {
            for (end: Int in segments.size downTo 1) {
                val base: String = segments.subList(0, end).joinToString("/")
                for (extension: String in SOURCE_EXTENSIONS) {
                    val file = File(root, "$base.$extension")
                    if (!file.isFile) continue
                    val stamp: Long? = vfsTimeStamp(file)
                    if (stamp == null || file.lastModified() > stamp) return file.path.replace('\\', '/')
                }
            }
        }
        return null
    }

    private val SOURCE_EXTENSIONS: List<String> = listOf("java", "kt", "groovy", "scala")

    @RequiresReadLock
    fun sameSimpleName(project: Project, className: String, limit: Int = 8): List<String> {
        val simple: String = className.trim().replace('/', '.').replace('$', '.').substringAfterLast('.')
        if (simple.isEmpty()) return emptyList()
        val cache: PsiShortNamesCache = PsiShortNamesCache.getInstance(project)
        val scope: GlobalSearchScope = GlobalSearchScope.allScope(project)
        return sequenceOf(simple, simple.replaceFirstChar { it.uppercaseChar() })
            .distinct()
            .flatMap { name -> cache.getClassesByName(name, scope).asSequence() }
            .mapNotNull { it.qualifiedName }
            .distinct()
            .sorted()
            .take(limit)
            .toList()
    }

    /**
     * Resolves a fully-qualified class name to a PsiClass.
     * Defaults to GlobalSearchScope.everythingScope() to search project, dependency,
     * and buildscript classes; pass a narrower scope (e.g. from ModuleScopes) to pin resolution.
     * When variants exist in both project source and libraries (e.g. a stale copy
     * in a build-output jar), the project-source variant wins.
     */
    @RequiresReadLock
    fun resolve(
        project: Project,
        fqcn: String,
        scope: GlobalSearchScope = GlobalSearchScope.everythingScope(project),
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
        scope: GlobalSearchScope = GlobalSearchScope.everythingScope(project),
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
