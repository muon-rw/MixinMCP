package dev.mixinmcp.buildscript

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Buildscript entries as last computed on project open, after Gradle sync, or when the indexing
 * setting changes. The roots provider serves only this snapshot. The workspace file index asks
 * providers while VFS events are being applied under the write action, and every query on
 * `GradleBuildClasspathManager` reloads with a synchronous VFS refresh per classpath path as soon
 * as one cached root is invalid (a rebuilt `build/` output dir is enough). From inside event
 * processing that refresh cannot complete, and the IDE stays frozen until the wait gives up.
 */
@Service(Service.Level.PROJECT)
class BuildscriptClasspathSnapshot(private val project: Project, private val scope: CoroutineScope) {

    @Volatile
    private var entries: List<BuildscriptEntry> = emptyList()
    private val refreshLock = Mutex()

    internal fun entries(): List<BuildscriptEntry> = entries

    fun scheduleRefresh(reason: String) {
        scope.launch(Dispatchers.IO + CoroutineName("mixinmcp-buildscript-$reason")) { refresh(reason) }
    }

    suspend fun refresh(reason: String) {
        refreshLock.withLock {
            if (project.isDisposed) return
            val fresh: List<BuildscriptEntry> = BuildscriptClasspathRoots.collectEntries(project)
            val previousRoots: List<VirtualFile> = indexedRoots(entries)
            val freshRoots: List<VirtualFile> = indexedRoots(fresh)
            entries = fresh
            if (freshRoots == previousRoots) return
            LOG.info("MixinMCP: buildscript classpath ($reason): ${fresh.size} entries, ${freshRoots.size} indexed roots")
            edtWriteAction {
                if (project.isDisposed) return@edtWriteAction
                AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                    project, null, previousRoots, freshRoots, "mixinmcp-buildscript",
                )
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(BuildscriptClasspathSnapshot::class.java)

        fun getInstance(project: Project): BuildscriptClasspathSnapshot = project.service()

        internal fun indexedRoots(entries: List<BuildscriptEntry>): List<VirtualFile> =
            entries.filterNot { it.kotlinDslOnly }.flatMap { listOfNotNull(it.classesRoot, it.sourcesRoot) }
    }
}
