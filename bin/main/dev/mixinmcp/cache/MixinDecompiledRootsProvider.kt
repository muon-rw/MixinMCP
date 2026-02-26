package dev.mixinmcp.cache

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.roots.SyntheticLibrary.ExcludeFileCondition
import com.intellij.openapi.vfs.VirtualFile

/**
 * Exposes decompiled library sources as SyntheticLibrary roots.
 * See DESIGN.md Section 11.4 and 11.5 Step 4.
 */
class MixinDecompiledRootsProvider : AdditionalLibraryRootsProvider() {

    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val service = DecompilationCacheService.getInstance(project)
        val cachedRoots = service.getCachedRoots()

        return cachedRoots.map { info ->
            SyntheticLibrary.newImmutableLibrary(
                "mixinmcp-${info.artifactHash}",
                listOf(info.root),
                emptyList(),
                emptySet(),
                SyntheticLibrary.ExcludeFileCondition { isDir, filename, _, _, _ ->
                    !isDir && !filename.endsWith(".java")
                },
            )
        }
    }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> {
        val service = DecompilationCacheService.getInstance(project)
        return service.getCachedRoots().map { it.root }
    }
}
