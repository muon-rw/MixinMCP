package dev.mixinmcp.tools.semantic

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.util.ClassUtil
import dev.mixinmcp.mappings.MappingNamespace
import dev.mixinmcp.mappings.MappingsService
import dev.mixinmcp.mappings.McVersionDetector
import dev.mixinmcp.resolve.FqcnResolver
import dev.mixinmcp.tools.source.BytecodeAnnotation
import dev.mixinmcp.tools.source.BytecodeScanStats
import dev.mixinmcp.tools.source.CandidateResolution
import dev.mixinmcp.tools.source.ClassFileView
import dev.mixinmcp.tools.source.ConstantMatcher
import dev.mixinmcp.tools.source.JarCandidate
import dev.mixinmcp.tools.source.annotationsIn
import dev.mixinmcp.tools.source.resolveCandidates
import dev.mixinmcp.tools.source.walkJarClasses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import net.fabricmc.mappingio.tree.MappingTreeView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val SCAN_PARALLELISM: Int = 4
private const val MAX_INJECTIONS_SHOWN: Int = 10

private const val MIXIN_DESCRIPTOR: String = "Lorg/spongepowered/asm/mixin/Mixin;"
private val INJECTOR_PACKAGES: List<String> = listOf("org/spongepowered/asm/mixin/", "com/llamalad7/mixinextras/")

/** First game version without obfuscation, where Mojang names are the only names. */
private const val FIRST_UNOBFUSCATED_MAJOR: Int = 26

internal data class BytecodeInjector(val annotation: BytecodeAnnotation, val handlerName: String) {
    val isOverwrite: Boolean get() = annotation.shortName == "Overwrite"

    /** The `method` selectors; `@Overwrite` targets the handler's own name instead. */
    val methodSelectors: List<String> get() = if (isOverwrite) listOf(handlerName) else annotation.strings("method")

    /** Every `@At(target = ...)` string inside `at` or `slice` (`from` / `to`). */
    val atTargets: List<String>
        get() {
            val ats: List<BytecodeAnnotation> = annotation.annotations("at") +
                annotation.annotations("slice").flatMap { it.annotations("from") + it.annotations("to") }
            return ats.flatMap { it.strings("target") }
        }

    fun targetsMethod(names: TargetNames): Boolean =
        methodSelectors.any(names::matchesMethod) ||
            atTargets.any { atTargetMatches(it, names::matchesClass, names::matchesMethod) }

    fun render(): String = "${annotation.render()} -> $handlerName"
}

internal data class BytecodeMixin(val className: String, val targets: List<String>, val injectors: List<BytecodeInjector>)

/** The `@Mixin` targets (value class literals and `targets` strings, as internal names) and injectors of [view], or null when it is not a mixin. */
internal fun readBytecodeMixin(view: ClassFileView): BytecodeMixin? {
    val structure: ClassFileView.Structure = view.structure ?: return null
    val mixin: BytecodeAnnotation = view.annotationsIn(structure.attributes).firstOrNull { it.descriptor == MIXIN_DESCRIPTOR }
        ?: return null
    val targets: List<String> = (mixin.classes("value") + mixin.strings("targets").map { it.replace('.', '/') }).distinct()
    val injectors: List<BytecodeInjector> = structure.methods.flatMap { method ->
        if (!view.isUtf8(method.nameIndex)) return@flatMap emptyList()
        val handler: String = view.utf8(method.nameIndex)
        view.annotationsIn(method.attributes)
            .filter { ann -> ann.shortName in INJECTOR_ANNOTATION_SHORT_NAMES && INJECTOR_PACKAGES.any { ann.internalName.startsWith(it) } }
            .map { BytecodeInjector(it, handler) }
    }
    return BytecodeMixin(view.className?.replace('/', '.') ?: "?", targets, injectors)
}

/**
 * The names a class (and optionally one of its methods) can appear under in shipped mixins.
 * [classNames] are internal names (`a/b/C$D`).
 */
internal data class TargetNames(val classNames: Set<String>, val methodNames: Set<String>, val note: String?) {
    private val normalizedClasses: Set<String> = classNames.mapTo(HashSet()) { normalizeForMatch(it) }

    fun matchesClass(internalOrDottedName: String): Boolean = normalizeForMatch(internalOrDottedName) in normalizedClasses

    fun matchesMethod(selector: String): Boolean = methodNames.any { methodTargets(selector, it) }

    /** Simple class names, for prefiltering raw class bytes before parsing. */
    val simpleNames: List<String>
        get() = classNames.map { it.substringAfterLast('/').substringAfterLast('$') }.distinct()
}

/** `1.21.1` -> 1, `26.1.2` -> 26; null when [version] does not start with a number. */
internal fun majorVersionOf(version: String): Int? = version.trim().substringBefore('.').toIntOrNull()

/**
 * Expands [internalName] and [methodName] into their Mojang, intermediary, and Yarn names for
 * [mcVersion]. Fabric before 26.x ships mixins in whichever names the mod was built with (Yarn or
 * Mojang plus a refmap, or intermediary written straight into the annotations), so a search for
 * one name misses the others. From 26.x the game is unobfuscated and no expansion is needed.
 */
internal suspend fun expandTargetNames(internalName: String, methodName: String?, mcVersion: String?): TargetNames {
    val base = TargetNames(setOf(internalName), setOfNotNull(methodName), null)
    if (mcVersion == null) {
        return base.copy(note = "Names were not expanded to intermediary/Yarn: the Minecraft version is unknown; pass mcVersion.")
    }
    if ((majorVersionOf(mcVersion) ?: 0) >= FIRST_UNOBFUSCATED_MAJOR) return base
    val tree: MappingTreeView = try {
        MappingsService.getInstance().get(mcVersion, setOf(MappingNamespace.MOJMAP, MappingNamespace.INTERMEDIARY, MappingNamespace.YARN))
    } catch (e: Exception) {
        return base.copy(note = "Names were not expanded to intermediary/Yarn: mappings for $mcVersion could not be loaded (${e.message}).")
    }
    return expandWithTree(tree, internalName, methodName) ?: base.copy(
        note = "Names were not expanded: $internalName is not in the $mcVersion mappings (searching for it as given).",
    )
}

internal fun expandWithTree(tree: MappingTreeView, internalName: String, methodName: String?): TargetNames? {
    val namespaceIds: List<Int> = tree.dstNamespaces.indices.toList()
    val cls: MappingTreeView.ClassMappingView = namespaceIds.firstNotNullOfOrNull { ns ->
        tree.classes.firstOrNull { it.getDstName(ns) == internalName }
    } ?: return null
    val classNames: Set<String> = namespaceIds.mapNotNullTo(LinkedHashSet()) { cls.getDstName(it) } + internalName
    val methodNames: Set<String> = if (methodName == null) emptySet() else {
        val methods: List<MappingTreeView.MethodMappingView> = cls.methods.filter { m -> namespaceIds.any { m.getDstName(it) == methodName } }
        methods.flatMapTo(LinkedHashSet()) { m -> namespaceIds.mapNotNull { m.getDstName(it) } } + methodName
    }
    return TargetNames(classNames, methodNames, null)
}

private class JarMixinHit(val container: String, val jarPath: String, val mixin: BytecodeMixin, val injectors: List<BytecodeInjector>) {
    fun locationLabel(): String = if (container.contains(" !/")) "$container [nested]" else "$container ($jarPath)"
}

private class JarMixinScan(
    val candidate: JarCandidate,
    val hits: List<JarMixinHit>,
    val methodFilteredOut: List<String>,
    val stats: BytecodeScanStats,
    val scanned: Boolean,
    val error: String?,
)

/**
 * mixin_find_targeting_mixins over jars on disk: reads `@Mixin` targets and injector annotations
 * from bytecode instead of the IDE index, so it covers a pack's mods folder and jar-in-jar.
 */
internal suspend fun findTargetingMixinsInJars(
    project: Project,
    className: String,
    methodName: String?,
    maxResults: Int,
    jarPath: String,
    mcVersion: String?,
    timeoutMs: Long,
): McpToolCallResult {
    val candidates: List<JarCandidate> = when (val resolved = resolveCandidates(project, jarPath, null, null)) {
        is CandidateResolution.Found -> resolved.jars
        is CandidateResolution.Failed -> return resolved.error
    }
    // The classpath copy, when present, tells nested classes (Outer$Inner) apart from packages. Disk
    // scans don't wait out indexing, so during it we fall back to treating every dot as a package.
    val classpathName: String? = if (DumbService.isDumb(project)) null else smartReadAction(project) {
        FqcnResolver.resolveNested(project, className)?.let { ClassUtil.getJVMClassName(it) }
    }
    val internalName: String = (classpathName ?: className).replace('.', '/')
    val names: TargetNames = when {
        mcVersion.equals("none", ignoreCase = true) -> TargetNames(setOf(internalName), setOfNotNull(methodName), null)
        else -> expandTargetNames(internalName, methodName, mcVersion ?: McVersionDetector.detect(project))
    }
    val classPrefilter = ConstantMatcher(names.simpleNames, isRegex = false)
    val mixinPrefilter = ConstantMatcher(listOf(MIXIN_DESCRIPTOR), isRegex = false)

    val startedAt: Long = System.currentTimeMillis()
    val deadline: Long = startedAt + timeoutMs
    val found = AtomicInteger(0)
    val seenMixinClasses: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // Blocking scan code polls this instead of throwing, so a cancelled call stops within one class.
    val callJob: Job = currentCoroutineContext().job
    fun shouldStop(): Boolean = !callJob.isActive || found.get() >= maxResults || System.currentTimeMillis() > deadline

    val scans: List<JarMixinScan> = withContext(Dispatchers.IO.limitedParallelism(SCAN_PARALLELISM)) {
        candidates.map { candidate ->
            async {
                val stats = BytecodeScanStats()
                if (shouldStop()) return@async JarMixinScan(candidate, emptyList(), emptyList(), stats, scanned = false, error = null)
                val hits: MutableList<JarMixinHit> = mutableListOf()
                val filteredOut: MutableList<String> = mutableListOf()
                val error: String? = try {
                    walkJarClasses(candidate.file, null, stats, ::shouldStop) { container, bytes ->
                        if (!mixinPrefilter.mightMatch(bytes) || !classPrefilter.mightMatch(bytes)) return@walkJarClasses
                        val mixin: BytecodeMixin = ClassFileView.parse(bytes)?.let(::readBytecodeMixin) ?: return@walkJarClasses
                        if (mixin.targets.none(names::matchesClass)) return@walkJarClasses
                        val injectors: List<BytecodeInjector> = if (methodName == null) mixin.injectors else {
                            mixin.injectors.filter { inj -> inj.targetsMethod(names) }
                        }
                        if (methodName != null && injectors.isEmpty()) {
                            filteredOut.add(mixin.className)
                        } else {
                            val isNewClass: Boolean = seenMixinClasses.add(mixin.className)
                            if (!isNewClass || found.incrementAndGet() <= maxResults) {
                                hits.add(JarMixinHit(container, candidate.file.absolutePath, mixin, injectors))
                            }
                        }
                    }
                    null
                } catch (e: Exception) {
                    e.message ?: e.toString()
                }
                JarMixinScan(candidate, hits, filteredOut, stats, scanned = true, error = error)
            }
        }.awaitAll()
    }
    callJob.ensureActive()
    return McpToolCallResult.text(renderJarMixinScan(className, methodName, jarPath, names, scans, maxResults, deadline, startedAt))
}

private fun renderJarMixinScan(
    className: String,
    methodName: String?,
    jarPath: String,
    names: TargetNames,
    scans: List<JarMixinScan>,
    maxResults: Int,
    deadline: Long,
    startedAt: Long,
): String = buildString {
    appendLine("=== Mixins targeting $className${if (methodName != null) "#$methodName" else ""} in $jarPath ===")
    if (names.classNames.size > 1 || names.methodNames.size > 1) {
        append("Matched as: ${names.classNames.joinToString(", ")}")
        if (names.methodNames.size > 1) append("; method ${names.methodNames.joinToString(", ")}")
        appendLine()
    }
    names.note?.let { appendLine("($it)") }
    appendLine()
    val groupedHits: List<List<JarMixinHit>> = scans.flatMap { it.hits }
        .groupBy { it.mixin.className }
        .values
        .take(maxResults)
    for ((i, group) in groupedHits.withIndex()) {
        val hit: JarMixinHit = group.first()
        appendLine("${i + 1}. ${hit.mixin.className}")
        for (injector in hit.injectors.take(MAX_INJECTIONS_SHOWN)) appendLine("   ${injector.render()}")
        if (hit.injectors.size > MAX_INJECTIONS_SHOWN) appendLine("   ... (${hit.injectors.size - MAX_INJECTIONS_SHOWN} more)")
        if (hit.injectors.isEmpty()) appendLine("   (no injectors: accessors, shadows, or added interfaces only)")
        val locations: List<String> = group.map { it.locationLabel() }.distinct()
        appendLine("   Jar: ${locations.first()}")
        if (locations.size > 1) {
            appendLine("   Also in: ${locations.drop(1).joinToString("; ")}")
        }
        appendLine()
    }
    scans.mapNotNull { scan -> scan.error?.let { "${scan.candidate.file.name} (unreadable: $it)" } }.forEach { appendLine(it) }
    val matchedClasses: Set<String> = groupedHits.mapTo(HashSet()) { it.first().mixin.className }
    val filteredOut: List<String> = scans.flatMap { it.methodFilteredOut }.distinct().filter { it !in matchedClasses }
    if (methodName != null && filteredOut.isNotEmpty()) {
        appendLine(
            "${filteredOut.size} more mixin(s) target this class through other members: " +
                filteredOut.take(10).joinToString(", ") + (if (filteredOut.size > 10) ", ..." else "") +
                ". Rerun without methodName for their injectors.",
        )
    }
    val scanned: List<JarMixinScan> = scans.filter { it.scanned }
    val elapsed: String = "%.1f".format((System.currentTimeMillis() - startedAt) / 1000.0)
    append("${groupedHits.size} mixin(s) found; scanned ${scanned.sumOf { it.stats.classesScanned }} classes in ${scanned.size} jar(s) ")
    append("and ${scanned.sumOf { it.stats.nestedJarsScanned }} nested jar(s) in ${elapsed}s")
    if (groupedHits.size >= maxResults) append("; maxResults reached")
    else if (System.currentTimeMillis() > deadline) append("; timeout reached")
    val notReached: List<String> = scans.filter { !it.scanned }.map { it.candidate.file.name }
    if (notReached.isNotEmpty()) {
        append(". Not scanned: ${notReached.take(10).joinToString(", ")}")
        if (notReached.size > 10) append(" and ${notReached.size - 10} more")
    }
    appendLine(".")
    append("Matching uses the class and method names above; a mod built with other mappings (e.g. MCP or Quilt) is not matched.")
}

