package dev.mixinmcp.cache

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.startup.ProjectActivity
import dev.mixinmcp.tools.source.TrigramShardIndex
import java.nio.file.Path

/**
 * On project open: attach existing cached roots immediately.
 * The cache is populated by the Gradle plugin; no IDE-side decompilation.
 * See DESIGN.md Section 11.5 Step 5.
 */
class MixinDecompileCacheStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        LOG.info("MixinMCP: startup activity running for ${project.name}")
        val service = DecompilationCacheService.getInstance(project)

        service.refreshVfs()

        val cachedInfos: List<DecompilationCacheService.CachedLibraryInfo> = service.getCachedRoots()
        val existingRoots = cachedInfos.map { it.root }

        if (existingRoots.isNotEmpty()) {
            LOG.info("MixinMCP: attaching ${existingRoots.size} decompiled source roots")
            edtWriteAction {
                AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                    project, null, emptyList(), existingRoots, "mixinmcp-decompiled",
                )
            }
            LOG.info("MixinMCP: fireAdditionalLibraryChanged completed")
            for (info in cachedInfos) {
                if (!info.root.isInLocalFileSystem) continue
                val dir: Path = Path.of(info.root.path)
                val stamp: String = TrigramShardIndex.cacheDirectoryStamp(dir, info.manifestCreatedAt) ?: continue
                TrigramShardIndex.scheduleDirectoryBuild(dir, stamp)
            }
        } else {
            LOG.info("MixinMCP: no cached roots found — run ./gradlew genDependencySources in the project")
        }

        CacheMaintenanceService.getInstance().schedule()
        SourceAutoAttacher.getInstance(project).schedule("startup")
    }

    companion object {
        private val LOG = Logger.getInstance(MixinDecompileCacheStartupActivity::class.java)
    }
}
