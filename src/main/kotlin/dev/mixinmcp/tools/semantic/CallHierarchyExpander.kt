package dev.mixinmcp.tools.semantic

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassInitializer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import dev.mixinmcp.tools.projectRelativePath
import dev.mixinmcp.resolve.BytecodeAnalyzer
import dev.mixinmcp.resolve.ClassFileLocator
import dev.mixinmcp.resolve.FqcnResolver
import dev.mixinmcp.resolve.PsiDescriptors
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UCallableReferenceExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UField
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.getUastParentOfType
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Recursive call hierarchy expansion for `mixin_call_hierarchy`.
 *
 * - Callers/callees are explored up to a caller-supplied depth.
 * - `maxResults` is a shared global budget across all depths and branches.
 * - Cycle detection keys on the (owner, name, JVM descriptor) triple so
 *   diamond hierarchies collapse into a single expansion and recursion halts
 *   cleanly.
 * - Output is indented per depth and tagged `[L1]`, `[L2]`, ... so agents can
 *   see depth at a glance.
 *
 * The callees path integrates with [BytecodeAnalyzer.extractCallees] / the
 * PSI source walker so that constructors, method references, and synthetic
 * lambda targets (INVOKEDYNAMIC → LambdaMetafactory impl handle) are surfaced
 * uniformly at every depth. When the callee resolves to a PSI method its body
 * is walked; when it doesn't (lambdas, binary-only classes), recursion
 * continues via bytecode if the owning class is on the classpath.
 */
internal object CallHierarchyExpander {

    /** Shared output-capacity tracker across caller/callee walks. */
    internal class Budget(val maxResults: Int) {
        var used: Int = 0
        var truncated: Boolean = false

        /** Reserve one slot. Returns false (and marks truncated) when exhausted. */
        fun tryConsume(): Boolean {
            if (used >= maxResults) {
                truncated = true
                return false
            }
            used++
            return true
        }
    }

    /** Identifier used for cycle detection across both PSI and bytecode paths. */
    private fun cycleKey(owner: String, name: String, descriptor: String): String =
        "$owner#$name$descriptor"

    /**
     * Cycle key derived from a PsiMethod. Uses the JVM internal-name form
     * (with `$` for nested classes) converted to dotted package notation so
     * it matches exactly what `extractCallees` / `calleeRefFor` emit — a
     * qualifiedName-based key would diverge for nested classes and break
     * cycle detection when the target sits in one.
     */
    fun cycleKeyOf(method: PsiMethod): String {
        val owner: String = method.containingClass?.let { cls ->
            PsiDescriptors.classInternalName(cls).replace('/', '.')
        } ?: "?"
        val name: String = if (method.isConstructor) "<init>" else method.name
        val descriptor: String = PsiDescriptors.methodDescriptor(method)
        return cycleKey(owner, name, descriptor)
    }

    /** Presentable signature `Class#name(params)` for the tool header. */
    fun presentableSignature(method: PsiMethod): String {
        val declClass: String = method.containingClass?.qualifiedName
            ?: method.containingClass?.name?.let { "$it (anon)" }
            ?: "?"
        val params: String = method.parameterList.parameters
            .joinToString(", ") { it.type.presentableText }
        val name: String = if (method.isConstructor) "<init>" else method.name
        return "$declClass#$name($params)"
    }

    /**
     * A call outside any method is nearly always a field initializer, which is how registry-style mod
     * code is written, so naming the field is what makes the entry actionable.
     */
    private fun nonMethodContextOf(element: PsiElement): String {
        val field: PsiField? = PsiTreeUtil.getParentOfType(element, PsiField::class.java)
            ?: element.getUastParentOfType<UField>()?.javaPsi as? PsiField
        if (field != null) {
            val owner: String? = field.containingClass?.name
            return if (owner != null) "field $owner.${field.name}" else "field ${field.name}"
        }
        val initializer: PsiClassInitializer? =
            PsiTreeUtil.getParentOfType(element, PsiClassInitializer::class.java)
        if (initializer != null) {
            val kind: String =
                if (initializer.hasModifierProperty(PsiModifier.STATIC)) "static initializer" else "initializer"
            return "$kind in ${initializer.containingClass?.name ?: "(anonymous)"}"
        }
        val owner: String? = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)?.name
        return if (owner != null) "(non-method context in $owner)" else "(non-method context)"
    }

    private fun lineOf(project: Project, element: PsiElement): Int {
        val file = element.containingFile ?: return 0
        val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return 0
        return doc.getLineNumber(element.textOffset) + 1
    }

    // ------------------------------------------------------------------
    // Callers
    // ------------------------------------------------------------------

    /**
     * Expands callers of [method] up to [maxDepth] levels deep.
     *
     * @param depth zero-based level of [method] itself; direct callers emit at `depth + 1`.
     */
    fun expandCallers(
        project: Project,
        method: PsiMethod,
        depth: Int,
        maxDepth: Int,
        scope: GlobalSearchScope,
        visited: MutableSet<String>,
        budget: Budget,
        out: StringBuilder,
    ) {
        ProgressManager.checkCanceled()
        if (depth >= maxDepth) return
        if (budget.used >= budget.maxResults) {
            // Budget hit before we could explore this branch. Mark truncated so
            // the footer fires; the caller won't know otherwise.
            budget.truncated = true
            return
        }

        val childLevel: Int = depth + 1
        val indent: String = "  ".repeat(depth)
        val target: BytecodeAnalyzer.InvokeTarget = invokeTargetOf(method)
        val (groups: List<CallerGroup>, more: Boolean) =
            collectCallerGroups(method, scope, budget.maxResults - budget.used)

        for (group: CallerGroup in groups) {
            ProgressManager.checkCanceled()
            if (!budget.tryConsume()) break

            val element: PsiElement = group.refs.first()
            val filePath: String = element.containingFile?.virtualFile
                ?.let { projectRelativePath(project, it) } ?: "(unknown)"
            val line: Int = lineOf(project, element)
            val raw: String = element.text
            val snippet: String = raw.take(80).let { s -> if (raw.length > 80) "$s..." else s }

            val enclosing: PsiMethod? = group.enclosing
            if (enclosing == null) {
                out.append(indent)
                    .append("[L").append(childLevel).append("] ").append(nonMethodContextOf(element))
                    .append("  at ").append(filePath).append(":").append(line)
                    .append("  : ").append(snippet)
                    .appendLine()
                continue
            }

            val key: String = cycleKeyOf(enclosing)
            val isCycle: Boolean = !visited.add(key)
            val tags: List<OrdinalTag> = repeatedInvokeTags(project, ordinalsOf(project, enclosing), target, true)
            val shownLines: Set<Int> = tags.flatMap { tag -> tag.sites.mapNotNull { it.line } }.toSet() + line
            val otherRefLines: List<Int> = group.refs.drop(1)
                .map { lineOf(project, it) }
                .filter { it > 0 && it !in shownLines }
                .distinct()

            out.append(indent)
                .append("[L").append(childLevel).append("] ")
                .append(presentableSignature(enclosing))
                .append("  at ").append(filePath).append(":").append(line)
                .append("  : ").append(snippet)
            appendTags(out, tags)
            if (otherRefLines.isNotEmpty()) {
                out.append("  (also line").append(if (otherRefLines.size > 1) "s " else " ")
                    .append(otherRefLines.joinToString(", ")).append(')')
            }
            if (isCycle) out.append("  [cycle]")
            out.appendLine()

            if (!isCycle) {
                expandCallers(project, enclosing, childLevel, maxDepth, scope, visited, budget, out)
            }
        }
        if (more) budget.truncated = true
    }

    private class CallerGroup(val enclosing: PsiMethod?, val refs: MutableList<PsiElement>)

    /** Groups references by calling method; stops once a group beyond [limit] appears. */
    private fun collectCallerGroups(
        method: PsiMethod,
        scope: GlobalSearchScope,
        limit: Int,
    ): Pair<List<CallerGroup>, Boolean> {
        val groups: LinkedHashMap<PsiElement, CallerGroup> = LinkedHashMap()
        var more = false
        MethodReferencesSearch.search(method, scope, true).forEach(Processor<PsiReference> { ref ->
            ProgressManager.checkCanceled()
            val element: PsiElement = ref.element
            val (groupKey: PsiElement, enclosing: PsiMethod?) = enclosingMethodOf(element) ?: (element to null)
            val existing: CallerGroup? = groups[groupKey]
            if (existing != null) {
                existing.refs.add(element)
                return@Processor true
            }
            if (groups.size >= limit) {
                more = true
                return@Processor false
            }
            groups[groupKey] = CallerGroup(enclosing, mutableListOf(element))
            true
        })
        return groups.values.toList() to more
    }

    /**
     * Java PSI gives the PsiMethod directly; other JVM languages go through UAST, keyed by the
     * source declaration because UAST wrappers are not stable across lookups.
     */
    private fun enclosingMethodOf(element: PsiElement): Pair<PsiElement, PsiMethod>? {
        PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)?.let { return it to it }
        val uMethod: UMethod = element.getUastParentOfType<UMethod>() ?: return null
        return (uMethod.sourcePsi ?: uMethod.javaPsi) to uMethod.javaPsi
    }

    // ------------------------------------------------------------------
    // Callees
    // ------------------------------------------------------------------

    /**
     * Identifies the body to walk for callee discovery. When [psiMethod] is
     * non-null and has a source body, that path is used; otherwise we fall
     * back to bytecode using [owner] / [name] / [descriptor].
     */
    internal data class CalleeTarget(
        val owner: String,
        val name: String,
        val descriptor: String,
        val psiMethod: PsiMethod?,
    )

    /**
     * Build a target for the user-requested starting method. Owner is the
     * JVM internal name with `/` mapped to `.` (matches `CalleeRef.owner` on
     * children, so cycle keys collide correctly when the target is in a
     * nested class).
     */
    fun targetFor(method: PsiMethod, userDescriptor: String?): CalleeTarget {
        val owner: String = method.containingClass?.let { cls ->
            PsiDescriptors.classInternalName(cls).replace('/', '.')
        } ?: "?"
        val name: String = if (method.isConstructor) "<init>" else method.name
        val descriptor: String = userDescriptor ?: PsiDescriptors.methodDescriptor(method)
        return CalleeTarget(owner, name, descriptor, method)
    }

    /**
     * Expands callees of [target] up to [maxDepth] levels deep, walking the
     * PSI source body when available and falling back to bytecode when the
     * callee has no source (abstract + native methods are terminal; lambdas
     * and binary-only methods descend via bytecode).
     */
    fun expandCallees(
        project: Project,
        target: CalleeTarget,
        depth: Int,
        maxDepth: Int,
        visited: MutableSet<String>,
        budget: Budget,
        out: StringBuilder,
    ) {
        ProgressManager.checkCanceled()
        if (depth >= maxDepth) return
        if (budget.used >= budget.maxResults) {
            // Budget hit before we could explore this branch. Mark truncated so
            // the footer fires; the caller won't know otherwise.
            budget.truncated = true
            return
        }

        val childLevel: Int = depth + 1
        val indent: String = "  ".repeat(depth)

        val listing: CalleeListing? = loadCallees(project, target)
        if (listing == null) {
            // Emitted at every depth so deeper leaves read as terminal rather
            // than depth-capped.
            out.append(indent)
                .append(
                    "  (no body available in source or bytecode: abstract, native, " +
                        "or class not locatable on the classpath; these cannot be distinguished here)",
                )
                .appendLine()
            return
        }
        if (depth == 0 && target.psiMethod?.body == null) {
            // Non-Java source (Kotlin etc.) flows through UAST; library classes resolve to
            // compiled PSI, which has no body even when sources are attached, and flow
            // through bytecode.
            val label: String = if (target.psiMethod?.toUElement(UMethod::class.java)?.uastBody != null) {
                "  (non-Java source, walked via UAST)"
            } else {
                "  (callees read from bytecode)"
            }
            out.append(indent).append(label).appendLine()
        }
        val callees: List<BytecodeAnalyzer.CalleeRef> = listing.callees
        if (callees.isEmpty()) {
            if (depth == 0) {
                out.append(indent).append("  (no outgoing calls)").appendLine()
            }
            return
        }
        val ordinals: MethodOrdinals? =
            ordinalsOf(project, target.owner, target.name, target.descriptor, target.psiMethod)

        // Dedupe by (owner, name, descriptor), preserving first-seen order.
        val seenInBody: HashSet<String> = HashSet()
        for (c: BytecodeAnalyzer.CalleeRef in callees) {
            ProgressManager.checkCanceled()
            val localKey: String = cycleKey(c.owner, c.name, c.descriptor)
            if (!seenInBody.add(localKey)) continue
            if (!budget.tryConsume()) break

            val globalCycle: Boolean = !visited.add(localKey)
            val tag: String = when (c.kind) {
                BytecodeAnalyzer.CalleeKind.CONSTRUCTOR -> "  [ctor]"
                BytecodeAnalyzer.CalleeKind.LAMBDA -> "  [lambda]"
                BytecodeAnalyzer.CalleeKind.METHOD -> ""
            }

            out.append(indent)
                .append("[L").append(childLevel).append("] ")
                .append(c.owner).append('#').append(c.name).append(c.descriptor)
                .append(tag)
            val invoked = BytecodeAnalyzer.InvokeTarget(c.owner, c.name, c.descriptor)
            appendTags(out, repeatedInvokeTags(project, ordinals, invoked, listing.fromSource))
            if (globalCycle) out.append("  [cycle]")
            out.appendLine()

            if (!globalCycle && depth + 1 < maxDepth) {
                val childTarget: CalleeTarget = resolveChildTarget(project, c)
                expandCallees(project, childTarget, childLevel, maxDepth, visited, budget, out)
            }
        }
    }

    /**
     * Returns the callees of [target]:
     *  - non-null list (possibly empty) when a body was located (Java PSI,
     *    UAST, or bytecode). Empty = "method exists but has no outgoing calls".
     *  - null when no body is reachable via any path — treat as abstract /
     *    native for output purposes.
     *
     * Order of attempts:
     *  1. Java PSI body (`PsiMethod.body`) — covers Java source directly.
     *  2. UAST body (`UMethod.uastBody`) — covers Kotlin / other JVM
     *     languages whose PSI body is not a `PsiCodeBlock`.
     *  3. Bytecode (`BytecodeAnalyzer.extractCallees`) — covers compiled
     *     dependencies and surfaces the real synthetic lambda target behind
     *     `INVOKEDYNAMIC` via the `LambdaMetafactory` impl handle (something
     *     neither source walker can see).
     */
    private fun loadCallees(project: Project, target: CalleeTarget): CalleeListing? {
        val psi: PsiMethod? = target.psiMethod
        if (psi?.body != null) return CalleeListing(collectSourceCallees(psi.body!!), fromSource = true)

        if (psi != null) {
            val uBody: UElement? = psi.toUElement(UMethod::class.java)?.uastBody
            if (uBody != null) return CalleeListing(collectUastCallees(uBody), fromSource = true)
        }

        val bytes: ByteArray = ClassFileLocator.locate(project, target.owner) ?: return null
        return BytecodeAnalyzer.extractCallees(bytes, target.name, target.descriptor)
            ?.let { CalleeListing(it, fromSource = false) }
    }

    /** [fromSource] callees name the declaring class as owner, not the owner written in the INVOKE. */
    private class CalleeListing(val callees: List<BytecodeAnalyzer.CalleeRef>, val fromSource: Boolean)

    // ------------------------------------------------------------------
    // Mixin INVOKE ordinals
    // ------------------------------------------------------------------

    private class MethodOrdinals(
        val sites: Map<BytecodeAnalyzer.InvokeTarget, List<BytecodeAnalyzer.InvokeSite>>,
        val fromBytecode: Boolean,
        val qualifier: String?,
    )

    internal class OrdinalTag(val sites: List<BytecodeAnalyzer.InvokeSite>, val qualifiers: List<String>)

    private const val MAX_LISTED_SITES: Int = 10

    private fun ordinalsOf(project: Project, method: PsiMethod): MethodOrdinals? {
        val target: BytecodeAnalyzer.InvokeTarget = invokeTargetOf(method)
        return ordinalsOf(project, target.owner, target.name, target.descriptor, method)
    }

    /**
     * Bytecode when the class file is readable; otherwise source order, which misses compiler-generated
     * calls (boxing, string switch, enum switch maps) and may order calls differently.
     */
    private fun ordinalsOf(
        project: Project,
        owner: String,
        name: String,
        descriptor: String,
        psiMethod: PsiMethod?,
    ): MethodOrdinals? {
        val found: ClassFileLocator.LocateResult.Found? =
            psiMethod?.containingClass?.let { ClassFileLocator.locateForClass(it) } as? ClassFileLocator.LocateResult.Found
                ?: ClassFileLocator.locateDetailed(project, owner) as? ClassFileLocator.LocateResult.Found
        if (found != null) {
            val invocations: List<BytecodeAnalyzer.Invocation>? =
                BytecodeAnalyzer.extractInvocations(found.bytes, name, descriptor)
            if (invocations != null) {
                val qualifier: String? = if (found.maybeStale) "build may be stale" else null
                return MethodOrdinals(BytecodeAnalyzer.invokeOrdinals(invocations), fromBytecode = true, qualifier)
            }
        }
        val source: List<BytecodeAnalyzer.Invocation> = psiMethod?.let { sourceInvocations(project, it) } ?: return null
        return MethodOrdinals(BytecodeAnalyzer.invokeOrdinals(source), fromBytecode = false, "source order")
    }

    /**
     * Calls in evaluation order, skipping lambda bodies and nested classes because their INVOKEs live
     * in other methods. Method references compile to INVOKEDYNAMIC and are not counted.
     */
    private fun sourceInvocations(project: Project, method: PsiMethod): List<BytecodeAnalyzer.Invocation>? {
        val body: UElement = method.toUElement(UMethod::class.java)?.uastBody ?: return null
        val invocations: MutableList<BytecodeAnalyzer.Invocation> = mutableListOf()
        body.accept(object : AbstractUastVisitor() {
            override fun visitLambdaExpression(node: ULambdaExpression): Boolean = true

            override fun visitClass(node: UClass): Boolean = true

            override fun visitMethod(node: UMethod): Boolean = true

            override fun afterVisitCallExpression(node: UCallExpression) {
                val resolved: PsiMethod = node.resolve() ?: return
                val anchor: PsiElement? = node.methodIdentifier?.sourcePsi ?: node.sourcePsi
                val line: Int? = anchor?.let { lineOf(project, it) }?.takeIf { it > 0 }
                invocations.add(BytecodeAnalyzer.Invocation(invokeTargetOf(resolved), line))
            }
        })
        return invocations
    }

    /**
     * Tags for [invoked] when [ordinals] holds it more than once. With [declaredOwner], [invoked] names
     * the declaring class, so bytecode calls through a subclass or interface owner are matched too and
     * tagged with that owner, which is what the @At target must use.
     */
    private fun repeatedInvokeTags(
        project: Project,
        ordinals: MethodOrdinals?,
        invoked: BytecodeAnalyzer.InvokeTarget,
        declaredOwner: Boolean,
    ): List<OrdinalTag> {
        if (ordinals == null) return emptyList()
        val matchOtherOwners: Boolean = declaredOwner && ordinals.fromBytecode
        return ordinals.sites.entries
            .filter { (candidate, sites) ->
                sites.size > 1 && (
                    candidate == invoked ||
                        matchOtherOwners &&
                        candidate.name == invoked.name &&
                        candidate.descriptor == invoked.descriptor &&
                        declaringTargetOf(project, candidate) == invoked
                    )
            }
            .map { (candidate, sites) ->
                val ownerNote: String? = if (candidate == invoked) null else "INVOKE owner ${candidate.owner}"
                OrdinalTag(sites, listOfNotNull(ownerNote, ordinals.qualifier))
            }
    }

    private fun declaringTargetOf(
        project: Project,
        invoked: BytecodeAnalyzer.InvokeTarget,
    ): BytecodeAnalyzer.InvokeTarget? {
        val owner: PsiClass = FqcnResolver.resolveNested(project, invoked.owner) ?: return null
        return owner.findMethodsByName(invoked.name, true)
            .firstOrNull { PsiDescriptors.methodDescriptor(it) == invoked.descriptor }
            ?.let { invokeTargetOf(it) }
    }

    private fun invokeTargetOf(method: PsiMethod): BytecodeAnalyzer.InvokeTarget {
        val ref: BytecodeAnalyzer.CalleeRef = calleeRefFor(method, BytecodeAnalyzer.CalleeKind.METHOD)
        return BytecodeAnalyzer.InvokeTarget(ref.owner, ref.name, ref.descriptor)
    }

    private fun appendTags(out: StringBuilder, tags: List<OrdinalTag>) {
        for (tag: OrdinalTag in tags) out.append("  ").append(formatOrdinalTag(tag))
    }

    internal fun formatOrdinalTag(tag: OrdinalTag): String {
        val head: String = (listOf("x${tag.sites.size}") + tag.qualifiers).joinToString(", ")
        val listed: String = tag.sites.take(MAX_LISTED_SITES).joinToString(", ") { site ->
            if (site.line != null) "ordinal ${site.ordinal} line ${site.line}" else "ordinal ${site.ordinal}"
        }
        val rest: Int = tag.sites.size - MAX_LISTED_SITES
        return if (rest > 0) "[$head: $listed, +$rest more]" else "[$head: $listed]"
    }

    /**
     * UAST equivalent of [collectSourceCallees]. Covers Kotlin and every
     * other JVM language with a UAST bridge, via the language-neutral
     * [UCallExpression] / [UCallableReferenceExpression] nodes. Handles
     * direct method calls, `new Foo(...)` constructors, and method
     * references (`Foo::bar`, `Foo::new`). Lambdas are walked in place —
     * the synthetic `lambda$X$N` target only surfaces through the bytecode
     * path, same as in the Java PSI walker.
     */
    private fun collectUastCallees(body: UElement): List<BytecodeAnalyzer.CalleeRef> {
        val callees: MutableList<BytecodeAnalyzer.CalleeRef> = mutableListOf()
        body.accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                val resolved: PsiMethod? = node.resolve()
                if (resolved != null) {
                    val kind: BytecodeAnalyzer.CalleeKind = when {
                        resolved.isConstructor || node.kind == UastCallKind.CONSTRUCTOR_CALL ->
                            BytecodeAnalyzer.CalleeKind.CONSTRUCTOR
                        else -> BytecodeAnalyzer.CalleeKind.METHOD
                    }
                    callees.add(calleeRefFor(resolved, kind))
                }
                return super.visitCallExpression(node)
            }

            override fun visitCallableReferenceExpression(
                node: UCallableReferenceExpression,
            ): Boolean {
                val resolved: PsiMethod? = node.resolve() as? PsiMethod
                if (resolved != null) {
                    val kind: BytecodeAnalyzer.CalleeKind = if (resolved.isConstructor) {
                        BytecodeAnalyzer.CalleeKind.CONSTRUCTOR
                    } else {
                        BytecodeAnalyzer.CalleeKind.METHOD
                    }
                    callees.add(calleeRefFor(resolved, kind))
                }
                return super.visitCallableReferenceExpression(node)
            }
        })
        return callees
    }

    /**
     * Walks a PSI method body and records every outgoing call as a
     * [BytecodeAnalyzer.CalleeRef] — direct method calls, constructor
     * invocations (`new Foo(...)`), and method references (`Foo::bar`,
     * `Foo::new`). Signatures are recorded as JVM descriptors so output and
     * deduplication match the bytecode-fallback path exactly.
     *
     * INVOKEDYNAMIC-backed lambdas: the source walker visits the lambda body
     * directly (as it's lexically part of the enclosing method), so the
     * synthetic `lambda$...` target isn't emitted from the source path. The
     * bytecode path (extractCallees) does emit them — the two views agree on
     * the underlying call graph but surface lambdas at different points.
     */
    internal fun collectSourceCallees(body: PsiElement): List<BytecodeAnalyzer.CalleeRef> {
        val callees: MutableList<BytecodeAnalyzer.CalleeRef> = mutableListOf()
        body.accept(object : JavaRecursiveElementVisitor() {
            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                super.visitMethodCallExpression(expression)
                val m: PsiMethod = expression.resolveMethod() ?: return
                callees.add(calleeRefFor(m, BytecodeAnalyzer.CalleeKind.METHOD))
            }

            override fun visitNewExpression(expression: PsiNewExpression) {
                super.visitNewExpression(expression)
                val ctor: PsiMethod = expression.resolveConstructor() ?: return
                callees.add(calleeRefFor(ctor, BytecodeAnalyzer.CalleeKind.CONSTRUCTOR))
            }

            override fun visitMethodReferenceExpression(expression: PsiMethodReferenceExpression) {
                super.visitMethodReferenceExpression(expression)
                val resolved = expression.resolve() as? PsiMethod ?: return
                val kind: BytecodeAnalyzer.CalleeKind = if (resolved.isConstructor) {
                    BytecodeAnalyzer.CalleeKind.CONSTRUCTOR
                } else {
                    BytecodeAnalyzer.CalleeKind.METHOD
                }
                callees.add(calleeRefFor(resolved, kind))
            }
        })
        return callees
    }

    private fun calleeRefFor(
        method: PsiMethod,
        kind: BytecodeAnalyzer.CalleeKind,
    ): BytecodeAnalyzer.CalleeRef {
        val declClass: PsiClass? = method.containingClass
        val ownerInternal: String = declClass?.let { PsiDescriptors.classInternalName(it) } ?: "?"
        val owner: String = ownerInternal.replace('/', '.')
        val name: String = if (method.isConstructor) "<init>" else method.name
        val descriptor: String = PsiDescriptors.methodDescriptor(method)
        return BytecodeAnalyzer.CalleeRef(
            owner = owner,
            name = name,
            descriptor = descriptor,
            kind = kind,
        )
    }

    /**
     * Resolves a discovered callee to a [CalleeTarget] for recursion. PSI
     * method lookup is preferred (so we walk source bodies when available);
     * lambdas and unresolvable classes fall back to the bytecode path
     * transparently via [loadCallees].
     */
    private fun resolveChildTarget(
        project: Project,
        c: BytecodeAnalyzer.CalleeRef,
    ): CalleeTarget {
        val psiMethod: PsiMethod? = resolveCalleeToPsi(project, c)
        return CalleeTarget(c.owner, c.name, c.descriptor, psiMethod)
    }

    private fun resolveCalleeToPsi(
        project: Project,
        c: BytecodeAnalyzer.CalleeRef,
    ): PsiMethod? {
        val psiClass: PsiClass = FqcnResolver.resolveNested(project, c.owner) ?: return null
        return when (c.kind) {
            BytecodeAnalyzer.CalleeKind.CONSTRUCTOR -> matchByDescriptor(psiClass.constructors.toList(), c.descriptor)
            BytecodeAnalyzer.CalleeKind.LAMBDA -> null // synthetic; walk via bytecode if classpath has it
            BytecodeAnalyzer.CalleeKind.METHOD -> matchByDescriptor(
                psiClass.findMethodsByName(c.name, true).toList(),
                c.descriptor,
            )
        }
    }

    private fun matchByDescriptor(candidates: List<PsiMethod>, descriptor: String): PsiMethod? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        return candidates.firstOrNull { PsiDescriptors.methodDescriptor(it) == descriptor }
            ?: candidates.first()
    }
}
