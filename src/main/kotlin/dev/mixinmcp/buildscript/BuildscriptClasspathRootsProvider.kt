package dev.mixinmcp.buildscript

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.JavaSyntheticLibrary
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile
import dev.mixinmcp.tools.source.LabeledSyntheticRootsProvider

class BuildscriptClasspathRootsProvider : AdditionalLibraryRootsProvider(), LabeledSyntheticRootsProvider {

    private fun entries(project: Project): List<BuildscriptEntry> =
        BuildscriptClasspathSnapshot.getInstance(project).entries()

    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        return entries(project)
            .filterNot { it.kotlinDslOnly }
            .map { e ->
                // Must be JavaSyntheticLibrary: NonIncrementalContributors gives binary roots
                // real library-classes semantics (stub indexing, short names) only for that
                // subtype; a plain SyntheticLibrary's binary roots are never indexed.
                JavaSyntheticLibrary(
                    "mixinmcp-buildscript-${e.pairKey}",
                    listOfNotNull(e.sourcesRoot),
                    listOfNotNull(e.classesRoot),
                    emptySet(),
                )
            }
    }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> =
        BuildscriptClasspathSnapshot.indexedRoots(entries(project))

    override fun labelFor(project: Project, root: VirtualFile): String =
        BuildscriptClasspathRoots.labelForRoot(root)

    /**
     * Sources of Kotlin-DSL builds' buildscript entries: the Kotlin plugin already
     * indexes those roots, so [getAdditionalProjectLibraries] skips them, but the
     * text-search tools walk roots directly and would otherwise never see them.
     */
    override fun textSearchOnlyRoots(project: Project): List<VirtualFile> =
        entries(project)
            .filter { it.kotlinDslOnly }
            .mapNotNull { it.sourcesRoot }

    override fun sourcedClassesJarPaths(project: Project): Set<String> =
        entries(project)
            .filter { it.sourcesRoot != null }
            .mapNotNull { it.classesRoot?.path?.substringBefore("!/") }
            .map { normalizeDiskPath(it) }
            .toSet()

    override fun isBuildscriptClasspathFile(project: Project, vf: VirtualFile): Boolean {
        val vfUrl: String = vf.url
        return entries(project).any { e ->
            sequenceOf(e.classesRoot, e.sourcesRoot).filterNotNull().any { root ->
                vfUrl.startsWith(root.url.trimEnd('/'))
            }
        }
    }

    override fun indexingSettingChanged(project: Project) {
        BuildscriptClasspathSnapshot.getInstance(project).scheduleRefresh("settings")
    }
}
