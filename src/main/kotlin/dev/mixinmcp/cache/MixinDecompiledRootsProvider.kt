package dev.mixinmcp.cache

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile
import dev.mixinmcp.settings.MixinMcpSettings
import dev.mixinmcp.tools.source.BUILDSCRIPT_LABEL_PREFIX
import dev.mixinmcp.tools.source.LabeledSyntheticRootsProvider

/**
 * Exposes decompiled library sources as SyntheticLibrary roots.
 * See DESIGN.md Section 11.4 and 11.5 Step 4.
 */
class MixinDecompiledRootsProvider : AdditionalLibraryRootsProvider(), LabeledSyntheticRootsProvider {

    override fun labelFor(project: Project, root: VirtualFile): String {
        val buildscriptName: String? =
            DecompilationCacheService.getInstance(project).buildscriptCacheLibraryName(root)
        return if (buildscriptName != null) {
            "$BUILDSCRIPT_LABEL_PREFIX (decompiled cache): $buildscriptName"
        } else {
            "Decompiled cache (MixinMCP)"
        }
    }

    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        return activeCachedRoots(project).map { info ->
            SyntheticLibrary.newImmutableLibrary(
                "mixinmcp-${info.artifactHash}",
                listOf(info.root),
                emptyList(),
                emptySet(),
            ) { isDir, filename, _, _, _ ->
                !isDir && !(filename.endsWith(".java") || filename.endsWith(".kt"))
            }
        }
    }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> =
        activeCachedRoots(project).map { it.root }

    override fun indexingSettingChanged(project: Project) {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val roots: List<VirtualFile> = app.runReadAction<List<VirtualFile>> {
                activeCachedRoots(project).map { it.root }
            }
            app.invokeLater {
                if (project.isDisposed) return@invokeLater
                app.runWriteAction {
                    AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                        project, null, emptyList(), roots, "mixinmcp-decompiled",
                    )
                }
            }
        }
    }

    /**
     * Cache roots whose classes jar already has Library SOURCES attached are skipped; indexing
     * them again would only duplicate every file in Show Usages and Search Everywhere.
     * Buildscript-origin entries honor the indexBuildscriptClasspath opt-out so the setting
     * governs the whole buildscript surface, not only the live-classpath provider.
     */
    private fun activeCachedRoots(project: Project): List<DecompilationCacheService.CachedLibraryInfo> {
        val sourced: Set<String> = classesJarPathsWithAttachedSources(project) +
            AdditionalLibraryRootsProvider.EP_NAME.extensionList
                .filterIsInstance<LabeledSyntheticRootsProvider>()
                .filter { it !== this }
                .flatMap { it.sourcedClassesJarPaths(project) }
        val includeBuildscript: Boolean = MixinMcpSettings.getInstance(project).indexBuildscriptClasspath
        return DecompilationCacheService.getInstance(project).getCachedRoots()
            .filterNot { !includeBuildscript && it.classpathKind == "buildscript" }
            .filterNot { DecompilationCacheService.normalizeJarDiskPath(it.classesJarPath) in sourced }
    }

    private fun classesJarPathsWithAttachedSources(project: Project): Set<String> {
        val out = mutableSetOf<String>()
        for (module in ModuleManager.getInstance(project).modules) {
            for (entry in ModuleRootManager.getInstance(module).orderEntries) {
                if (entry !is LibraryOrderEntry) continue
                val lib = entry.library ?: continue
                if (lib.getUrls(OrderRootType.SOURCES).isEmpty()) continue
                for (root in lib.getFiles(OrderRootType.CLASSES)) {
                    out.add(DecompilationCacheService.normalizeJarDiskPath(root.path))
                }
            }
        }
        return out
    }
}
