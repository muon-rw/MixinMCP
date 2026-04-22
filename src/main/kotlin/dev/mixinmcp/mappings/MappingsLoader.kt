package dev.mixinmcp.mappings

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.adapter.MappingNsRenamer
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.format.MappingFormat
import net.fabricmc.mappingio.tree.MemoryMappingTree
import java.nio.file.Path

/**
 * Loads downloaded mapping files into a single `MemoryMappingTree` whose
 * source namespace is `obf` and whose destination namespaces are any of
 * {mojmap, intermediary, yarn, srg} — normalized from the raw file's
 * namespace names.
 *
 * Loading is minimized: Yarn tiny files already carry intermediary, so
 * we only load intermediary separately when Yarn isn't also needed.
 */
object MappingsLoader {
    private val LOG = Logger.getInstance(MappingsLoader::class.java)

    suspend fun load(
        mcVersion: String,
        required: Set<MappingNamespace>,
    ): MemoryMappingTree = withContext(Dispatchers.IO) {
        val tree = MemoryMappingTree()

        val wantYarn = MappingNamespace.YARN in required
        val wantIntermediary = MappingNamespace.INTERMEDIARY in required && !wantYarn
        val wantMojmap = MappingNamespace.MOJMAP in required
        val wantSrg = MappingNamespace.SRG in required

        if (wantYarn) {
            loadYarn(
                MappingsDownloader.getIntermediary(mcVersion),
                MappingsDownloader.getYarn(mcVersion),
                tree,
            )
        } else if (wantIntermediary) {
            loadIntermediary(MappingsDownloader.getIntermediary(mcVersion), tree)
        }

        if (wantMojmap) {
            loadMojmap(MappingsDownloader.getMojmap(mcVersion), tree)
        }

        if (wantSrg) {
            loadSrg(MappingsDownloader.getSrg(mcVersion), tree)
        }

        LOG.info(
            "MixinMCP: mappings tree for $mcVersion " +
                "src=${tree.srcNamespace} dst=${tree.dstNamespaces} classes=${tree.classes.size}",
        )
        tree
    }

    /**
     * Probes the file to discover its actual namespace names, then visits
     * into `master` with the roles assigned by `assign`.
     *
     * `assign(srcName, dstNames)` returns a map from the file's original
     * namespace name to our canonical namespace id. Names the file declares
     * that aren't in the returned map are left unchanged (mapping-io's
     * `MappingNsRenamer` passes unknown names through).
     *
     * If `newSourceNs` is non-null, its value (in the file's naming) is made
     * the source namespace before renaming — needed for ProGuard where the
     * file naturally orients deobf→obf but we want obf→deobf.
     */
    private fun probeLoadAndMerge(
        path: Path,
        format: MappingFormat?,
        master: MemoryMappingTree,
        assign: (srcName: String, dstNames: List<String>) -> Pair<Map<String, String>, String?>,
    ) {
        val probe = MemoryMappingTree()
        if (format != null) {
            MappingReader.read(path, format, probe)
        } else {
            MappingReader.read(path, probe)
        }
        val srcName = requireNotNull(probe.srcNamespace) {
            "Mapping file at $path has no source namespace"
        }
        val (renameMap, newSourceNs) = assign(srcName, probe.dstNamespaces)
        val renamer = MappingNsRenamer(master, renameMap)
        val sink = if (newSourceNs != null) MappingSourceNsSwitch(renamer, newSourceNs) else renamer
        probe.accept(sink)
    }

    private fun loadYarn(
        interPath: Path,
        yarnPath: Path,
        master: MemoryMappingTree,
    ) {
        val interProbe = MemoryMappingTree()
        MappingReader.read(interPath, interProbe)
        val yarnProbe = MemoryMappingTree()
        MappingReader.read(yarnPath, yarnProbe)

        val interObfNs = requireNotNull(interProbe.srcNamespace) {
            "Intermediary at $interPath has no source namespace"
        }
        val interMidNs = interProbe.dstNamespaces.singleOrNull()
            ?: error("Intermediary at $interPath has unexpected dst namespaces: ${interProbe.dstNamespaces}")
        val yarnSrc = requireNotNull(yarnProbe.srcNamespace) {
            "Yarn at $yarnPath has no source namespace"
        }

        when {
            yarnSrc == interMidNs -> {
                // Yarn: src=intermediary, dst=[named]. Bridge: invert intermediary
                // into aux (src=intermediary, dst=[official]), merge yarn into aux
                // (aux becomes src=intermediary, dst=[official, named]), then invert
                // aux into master so obf becomes the source.
                val yarnNamedNs = yarnProbe.dstNamespaces.singleOrNull()
                    ?: error("Yarn at $yarnPath has unexpected dst namespaces: ${yarnProbe.dstNamespaces}")
                val aux = MemoryMappingTree()
                interProbe.accept(MappingSourceNsSwitch(aux, interMidNs))
                yarnProbe.accept(aux)
                val renameMap = mapOf(
                    interObfNs to MappingNamespace.OBF.id,
                    interMidNs to MappingNamespace.INTERMEDIARY.id,
                    yarnNamedNs to MappingNamespace.YARN.id,
                )
                aux.accept(MappingSourceNsSwitch(MappingNsRenamer(master, renameMap), interObfNs))
            }
            yarnSrc == interObfNs -> {
                // Yarn: src=obf, dst=[intermediary, named] — direct merge.
                val yarnDst = yarnProbe.dstNamespaces
                val namedNs = yarnDst.firstOrNull { it != interMidNs }
                    ?: error("Couldn't identify Yarn 'named' dst among $yarnDst")
                val renameMap = mapOf(
                    yarnSrc to MappingNamespace.OBF.id,
                    interMidNs to MappingNamespace.INTERMEDIARY.id,
                    namedNs to MappingNamespace.YARN.id,
                )
                yarnProbe.accept(MappingNsRenamer(master, renameMap))
            }
            else -> error(
                "Yarn src ns '$yarnSrc' doesn't align with intermediary " +
                    "('$interObfNs' or '$interMidNs')",
            )
        }
    }

    private fun loadIntermediary(path: Path, master: MemoryMappingTree) {
        probeLoadAndMerge(path, null, master) { src, dst ->
            check(dst.size == 1) { "Intermediary tiny at $path has unexpected dst namespaces: $dst" }
            val map = mapOf(
                src to MappingNamespace.OBF.id,
                dst[0] to MappingNamespace.INTERMEDIARY.id,
            )
            map to null
        }
    }

    private fun loadMojmap(path: Path, master: MemoryMappingTree) {
        probeLoadAndMerge(path, MappingFormat.PROGUARD_FILE, master) { src, dst ->
            check(dst.size == 1) {
                "ProGuard file at $path has unexpected dst namespaces: $dst"
            }
            val obfSideName = dst[0]
            val namedSideName = src
            val map = mapOf(
                obfSideName to MappingNamespace.OBF.id,
                namedSideName to MappingNamespace.MOJMAP.id,
            )
            // Switch source to the obf-side namespace (in the file's naming)
            // so renaming produces src="obf", dst=["mojmap"].
            map to obfSideName
        }
    }

    private fun loadSrg(path: Path, master: MemoryMappingTree) {
        val format = MappingReader.detectFormat(path)
        probeLoadAndMerge(path, format, master) { src, dst ->
            // mcp_config tsrg2 declares dst=[srg, id] where "id" is a numeric
            // priority column we don't care about; older tsrg files have just
            // one dst. Pick the first non-"id" namespace as the SRG axis.
            val srgNs = dst.firstOrNull { it != "id" }
                ?: error("SRG file at $path has no SRG dst namespace in $dst")
            val map = mapOf(
                src to MappingNamespace.OBF.id,
                srgNs to MappingNamespace.SRG.id,
            )
            map to null
        }
    }
}
