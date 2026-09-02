package dev.mixinmcp.tools.source

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Lets [collectSourceRootsWithMetadata] ask an [com.intellij.openapi.roots.AdditionalLibraryRootsProvider]
 * for a display label without naming its concrete class. Providers registered behind an optional
 * plugin dependency implement this base-module interface; always-loaded code stays free of
 * references to their (possibly absent) classes.
 */
interface LabeledSyntheticRootsProvider {
    fun labelFor(project: Project, root: VirtualFile): String

    /**
     * Roots the text-search tools should scan even when the provider contributes
     * nothing for indexing (e.g. buildscript roots of a Kotlin-DSL build, which the
     * Kotlin plugin already indexes through the workspace file index).
     */
    fun textSearchOnlyRoots(project: Project): List<VirtualFile> = emptyList()

    /**
     * True when [vf] belongs to this provider's buildscript-classpath roots,
     * classes or sources side. Membership cannot be derived from search roots
     * alone: resolved binary classes live in the classes jar, which never appears
     * among SOURCES roots.
     */
    fun isBuildscriptClasspathFile(project: Project, vf: VirtualFile): Boolean = false

    /**
     * Normalized disk paths of classes jars this provider already pairs with real
     * sources, so the decompiled-cache provider can skip mirroring duplicates.
     */
    fun sourcedClassesJarPaths(project: Project): Set<String> = emptySet()

    /** Providers serving a cached root set recompute and re-announce it. */
    fun indexingSettingChanged(project: Project) {}
}
