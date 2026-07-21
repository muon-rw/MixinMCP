package dev.mixinmcp.resolve

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import dev.mixinmcp.tools.source.isBuildscriptClasspathFile
import dev.mixinmcp.tools.source.isGradleToolchainMergedOrBinaryInBuild
import dev.mixinmcp.tools.source.isLoomCacheArtifactPath
import java.util.zip.CRC32

enum class GameJarProvenance(val label: String, val vanillaPipeline: Boolean) {
    LOADER_PATCHED("loader-patched", false),
    LOOM_REMAPPED_VANILLA("loom-remapped vanilla", true),
    RECOMPILED_VANILLA("MDG/NeoForm-recompiled vanilla", true),
    DECOMPILE_CACHE("mixinmcp decompile cache", false),
}

internal fun classifyGameJarProvenance(filePath: String, projectBasePath: String?): GameJarProvenance? {
    val jarPath: String = filePath.replace('\\', '/').substringBefore("!/")
    if (jarPath.contains("/mixinmcp/decompiled/")) return GameJarProvenance.DECOMPILE_CACHE
    val name: String = jarPath.substringAfterLast('/').lowercase()
    if (!name.contains("minecraft") && !name.startsWith("vanilla-")) return null
    if (name.contains("patched")) return GameJarProvenance.LOADER_PATCHED
    val base: String = projectBasePath ?: return null
    return when {
        isLoomCacheArtifactPath(jarPath, base) ->
            if (name.contains("forge")) GameJarProvenance.LOADER_PATCHED
            else GameJarProvenance.LOOM_REMAPPED_VANILLA
        isGradleToolchainMergedOrBinaryInBuild(jarPath, base) ->
            if (name.contains("forge")) GameJarProvenance.LOADER_PATCHED
            else GameJarProvenance.RECOMPILED_VANILLA
        else -> null
    }
}

sealed class ModuleScopeResult {
    class Found(val module: Module, val scope: GlobalSearchScope) : ModuleScopeResult()
    class Error(val message: String) : ModuleScopeResult()
}

/**
 * Resolves a user-supplied module name to a search scope covering that module,
 * its dependencies, and its libraries. Exact name match wins; otherwise a unique
 * dot-boundary suffix match (Architectury module names look like
 * "Dynamic-Difficulty.common.main", so "common.main" pins that module).
 */
object ModuleScopes {

    @RequiresReadLock
    fun resolve(project: Project, moduleName: String): ModuleScopeResult {
        val modules: List<Module> = ModuleManager.getInstance(project).modules.toList()
        modules.firstOrNull { it.name == moduleName }?.let { return found(it) }

        val suffixMatches: List<Module> = modules.filter { it.name.endsWith(".$moduleName") }
        return when (suffixMatches.size) {
            1 -> found(suffixMatches[0])
            0 -> ModuleScopeResult.Error(
                "Unknown module '$moduleName'. Available modules: ${names(modules)}",
            )
            else -> ModuleScopeResult.Error(
                "Ambiguous module '$moduleName': matches ${names(suffixMatches)}. " +
                    "Available modules: ${names(modules)}",
            )
        }
    }

    private fun found(module: Module): ModuleScopeResult.Found =
        ModuleScopeResult.Found(module, module.getModuleWithDependenciesAndLibrariesScope(false))

    private fun names(modules: List<Module>): String =
        modules.map { it.name }.sorted().joinToString(", ")
}

/**
 * Enumerates every copy of a class on the classpath, groups byte-identical
 * copies, and computes a deterministic structural diff of each distinct group
 * against the base group. The base group is the one containing the PsiClass
 * that default resolution (FqcnResolver.resolveNested with allScope) returns;
 * if that copy has no compiled bytes, the group whose first origin label sorts
 * lowest becomes the base.
 */
object ClassVariants {

    const val MAX_ANALYZED_VARIANTS: Int = 8

    enum class VariantStatus { BUILT, NOT_BUILT, UNREADABLE }

    data class VariantInfo(
        val origin: String,
        val modules: List<String>,
        val status: VariantStatus,
        val contentCrc: Long?,
        val provenance: GameJarProvenance?,
    )

    data class StructuralDiff(
        val methodsAdded: List<String>,
        val methodsRemoved: List<String>,
        val methodsChanged: List<String>,
        val fieldsAdded: List<String>,
        val fieldsRemoved: List<String>,
        val headerChanges: List<String>,
    ) {
        val isEmpty: Boolean
            get() = methodsAdded.isEmpty() && methodsRemoved.isEmpty() && methodsChanged.isEmpty() &&
                fieldsAdded.isEmpty() && fieldsRemoved.isEmpty() && headerChanges.isEmpty()
    }

    data class VariantGroup(
        val variants: List<VariantInfo>,
        val isBase: Boolean,
        val diff: StructuralDiff?,
    )

    data class VariantReport(
        val fqcn: String,
        val totalOnClasspath: Int,
        val groups: List<VariantGroup>,
        val excluded: List<VariantInfo>,
        val truncatedCount: Int,
    ) {
        val distinctCount: Int get() = groups.size
        val hasMultipleVariants: Boolean get() = totalOnClasspath > 1
    }

    @RequiresReadLock
    fun findVariants(project: Project, fqcn: String): VariantReport? {
        val defaultClass: PsiClass = FqcnResolver.resolveNested(project, fqcn) ?: return null
        val enumerated: List<PsiClass> = enumerate(project, defaultClass)
        val total: Int = enumerated.size
        val capped: List<PsiClass> = enumerated.take(MAX_ANALYZED_VARIANTS)

        val located = mutableListOf<Triple<VariantInfo, ByteArray?, Boolean>>()
        for ((index, variant) in capped.withIndex()) {
            ProgressManager.checkCanceled()
            val vf: VirtualFile? = variant.containingFile?.virtualFile
            val origin: String = originLabel(vf)
            val modules: List<String> = ownerModules(project, vf)
            val provenance: GameJarProvenance? =
                vf?.path?.let { classifyGameJarProvenance(it, project.basePath) }
            val isDefault: Boolean = index == 0
            when (val result = ClassFileLocator.locateForClass(variant)) {
                is ClassFileLocator.LocateResult.Found -> {
                    val crc = CRC32().apply { update(result.bytes) }.value
                    located.add(
                        Triple(VariantInfo(origin, modules, VariantStatus.BUILT, crc, provenance), result.bytes, isDefault),
                    )
                }
                ClassFileLocator.LocateResult.NotBuilt ->
                    located.add(
                        Triple(VariantInfo(origin, modules, VariantStatus.NOT_BUILT, null, provenance), null, isDefault),
                    )
                ClassFileLocator.LocateResult.NotFound ->
                    located.add(
                        Triple(VariantInfo(origin, modules, VariantStatus.UNREADABLE, null, provenance), null, isDefault),
                    )
            }
        }

        val built = located.filter { it.first.status == VariantStatus.BUILT }
        val excluded: List<VariantInfo> = located
            .filter { it.first.status != VariantStatus.BUILT }
            .map { it.first }
            .sortedBy { it.origin }

        val byCrc: Map<Long, List<Triple<VariantInfo, ByteArray?, Boolean>>> =
            built.groupBy { it.first.contentCrc!! }
        val baseCrc: Long? = built.firstOrNull { it.third }?.first?.contentCrc
            ?: byCrc.entries.minByOrNull { entry -> entry.value.minOf { it.first.origin } }?.key
        if (baseCrc == null) {
            return VariantReport(fqcn, total, emptyList(), excluded, total - capped.size)
        }

        val baseBytes: ByteArray = byCrc.getValue(baseCrc).first().second!!
        val baseAnalysis = BytecodeAnalyzer.analyze(baseBytes, includeCodeHashes = true)

        val groups = mutableListOf<VariantGroup>()
        for ((crc, members) in byCrc.entries.sortedBy { entry -> entry.value.minOf { it.first.origin } }) {
            ProgressManager.checkCanceled()
            val infos: List<VariantInfo> = members.map { it.first }.sortedBy { it.origin }
            if (crc == baseCrc) {
                groups.add(VariantGroup(infos, isBase = true, diff = null))
            } else {
                val analysis = BytecodeAnalyzer.analyze(members.first().second!!, includeCodeHashes = true)
                groups.add(VariantGroup(infos, isBase = false, diff = structuralDiff(baseAnalysis, analysis)))
            }
        }
        groups.sortWith(compareBy({ !it.isBase }, { it.variants.first().origin }))

        return VariantReport(fqcn, total, groups, excluded, total - capped.size)
    }

    fun renderIfMultiple(report: VariantReport): String? =
        if (report.hasMultipleVariants) render(report) else null

    fun render(report: VariantReport): String {
        val sb = StringBuilder()
        sb.append("--- Variants (${report.totalOnClasspath} on classpath, ${report.distinctCount} distinct) ---\n")
        val baseIsVanillaPipeline: Boolean = report.groups
            .firstOrNull { it.isBase }
            ?.variants?.all { it.provenance?.vanillaPipeline == true } == true
        for (group in report.groups) {
            val label: String = if (group.isBase) "shown" else "differs"
            val origins: String = group.variants.joinToString(", ") { originWithProvenance(it) }
            val modules: String = group.variants.flatMap { it.modules }.distinct().sorted().joinToString(", ")
            sb.append("$label: $origins [modules: $modules]\n")
            val condenseChanged: Boolean = baseIsVanillaPipeline &&
                group.variants.all { it.provenance?.vanillaPipeline == true }
            group.diff?.let { sb.append(renderDiff(it, condenseChanged)) }
        }
        for (info in report.excluded) {
            val reason: String = if (info.status == VariantStatus.NOT_BUILT) "not built" else "unreadable"
            sb.append("$reason: ${originWithProvenance(info)} [modules: ${info.modules.joinToString(", ")}]\n")
        }
        if (report.truncatedCount > 0) {
            sb.append("(${report.truncatedCount} more variants not analyzed)\n")
        }
        sb.append("(pass module= to pin resolution to one classpath)")
        return sb.toString()
    }

    fun originWithProvenance(info: VariantInfo): String =
        info.provenance?.let { "${info.origin} [${it.label}]" } ?: info.origin

    private fun renderDiff(diff: StructuralDiff, condenseChanged: Boolean): String {
        if (diff.isEmpty) return "  (only debug info differs)\n"
        val sb = StringBuilder()
        val methodParts = mutableListOf<String>()
        if (diff.methodsChanged.isNotEmpty()) {
            methodParts.add(
                if (condenseChanged) {
                    "changed: ${diff.methodsChanged.size} (expected remap-vs-recompile artifacts between vanilla jars)"
                } else {
                    "changed: ${shortMethods(diff.methodsChanged)}"
                },
            )
        }
        if (diff.methodsAdded.isNotEmpty()) methodParts.add("added: ${shortMethods(diff.methodsAdded)}")
        if (diff.methodsRemoved.isNotEmpty()) methodParts.add("removed: ${shortMethods(diff.methodsRemoved)}")
        if (methodParts.isNotEmpty()) sb.append("  methods ${methodParts.joinToString("; ")}\n")
        val fieldParts = mutableListOf<String>()
        if (diff.fieldsAdded.isNotEmpty()) fieldParts.add("added: ${diff.fieldsAdded.joinToString(", ")}")
        if (diff.fieldsRemoved.isNotEmpty()) fieldParts.add("removed: ${diff.fieldsRemoved.joinToString(", ")}")
        if (fieldParts.isNotEmpty()) sb.append("  fields ${fieldParts.joinToString("; ")}\n")
        for (change in diff.headerChanges) sb.append("  $change\n")
        return sb.toString()
    }

    private fun shortMethods(keys: List<String>): String {
        val shorts: List<String> = keys.map { key ->
            val paren: Int = key.indexOf('(')
            if (paren < 0) key else key.substring(0, paren) + "(...)" + key.substringAfterLast(')')
        }
        val counts: Map<String, Int> = shorts.groupingBy { it }.eachCount()
        return keys.indices.joinToString(", ") { i ->
            if ((counts[shorts[i]] ?: 0) > 1) keys[i] else shorts[i]
        }
    }

    private fun enumerate(project: Project, defaultClass: PsiClass): List<PsiClass> {
        val innerChain = mutableListOf<String>()
        var top: PsiClass = defaultClass
        while (true) {
            val outer: PsiClass = top.containingClass ?: break
            innerChain.add(0, top.name ?: return listOf(defaultClass))
            top = outer
        }
        val topFqcn: String = top.qualifiedName ?: return listOf(defaultClass)

        val tops: Array<PsiClass> = JavaPsiFacade.getInstance(project)
            .findClasses(topFqcn, GlobalSearchScope.everythingScope(project))
        val variants = mutableListOf<PsiClass>()
        for (topVariant in tops) {
            ProgressManager.checkCanceled()
            var current: PsiClass? = topVariant
            for (name in innerChain) current = current?.findInnerClassByName(name, false)
            current?.let { variants.add(it) }
        }

        val defaultPath: String? = defaultClass.containingFile?.virtualFile?.path
        val deduped: List<PsiClass> = variants.distinctBy { it.containingFile?.virtualFile?.path }
        val others: List<PsiClass> = deduped
            .filter { it.containingFile?.virtualFile?.path != defaultPath }
            .sortedBy { it.containingFile?.virtualFile?.path ?: "" }
        return listOf(defaultClass) + others
    }

    private fun structuralDiff(
        base: BytecodeAnalyzer.ClassAnalysis,
        other: BytecodeAnalyzer.ClassAnalysis,
    ): StructuralDiff {
        val baseMethods: Map<String, BytecodeAnalyzer.MethodInfo> =
            base.methods.associateBy { it.name + it.descriptor }
        val otherMethods: Map<String, BytecodeAnalyzer.MethodInfo> =
            other.methods.associateBy { it.name + it.descriptor }
        val methodsAdded: List<String> = (otherMethods.keys - baseMethods.keys).sorted()
        val methodsRemoved: List<String> = (baseMethods.keys - otherMethods.keys).sorted()
        val methodsChanged: List<String> = baseMethods.keys.intersect(otherMethods.keys)
            .filter { baseMethods.getValue(it).codeHash != otherMethods.getValue(it).codeHash }
            .sorted()

        val baseFields: Set<String> = base.fields.map { it.name }.toSet()
        val otherFields: Set<String> = other.fields.map { it.name }.toSet()

        val headerChanges = mutableListOf<String>()
        if (base.superName != other.superName) {
            headerChanges.add("superclass: ${base.superName} -> ${other.superName}")
        }
        val interfacesAdded: List<String> = (other.interfaces.toSet() - base.interfaces.toSet()).sorted()
        val interfacesRemoved: List<String> = (base.interfaces.toSet() - other.interfaces.toSet()).sorted()
        if (interfacesAdded.isNotEmpty() || interfacesRemoved.isNotEmpty()) {
            val parts: List<String> = interfacesAdded.map { "+$it" } + interfacesRemoved.map { "-$it" }
            headerChanges.add("interfaces: ${parts.joinToString(", ")}")
        }
        if (base.access != other.access) {
            headerChanges.add(
                "access: ${BytecodeAnalyzer.accessFlagsToString(base.access)} -> " +
                    BytecodeAnalyzer.accessFlagsToString(other.access),
            )
        }

        return StructuralDiff(
            methodsAdded = methodsAdded,
            methodsRemoved = methodsRemoved,
            methodsChanged = methodsChanged,
            fieldsAdded = (otherFields - baseFields).sorted(),
            fieldsRemoved = (baseFields - otherFields).sorted(),
            headerChanges = headerChanges,
        )
    }

    private fun originLabel(vf: VirtualFile?): String {
        if (vf == null) return "unknown"
        val path: String = vf.path
        val bang: Int = path.indexOf("!/")
        if (bang >= 0) return path.substring(0, bang).substringAfterLast('/')
        return if (vf.extension == "class") vf.name else "module output"
    }

    private fun ownerModules(project: Project, vf: VirtualFile?): List<String> {
        if (vf == null) return emptyList()
        val fromIndex: List<String> = ProjectFileIndex.getInstance(project)
            .getOrderEntriesForFile(vf)
            .map { it.ownerModule.name }
            .distinct()
            .sorted()
        if (fromIndex.isNotEmpty()) return fromIndex
        return if (isBuildscriptClasspathFile(project, vf)) listOf("(buildscript classpath)") else emptyList()
    }
}
