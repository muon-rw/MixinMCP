package dev.mixinmcp.tools.semantic

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import dev.mixinmcp.resolve.BytecodeAnalyzer
import dev.mixinmcp.resolve.ClassFileLocator
import dev.mixinmcp.resolve.FqcnResolver
import dev.mixinmcp.resolve.PsiDescriptors
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UCallableReferenceExpression
import org.jetbrains.uast.UElement
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
        if (depth >= maxDepth) return
        if (budget.used >= budget.maxResults) {
            // Budget hit before we could explore this branch. Mark truncated so
            // the footer fires; the caller won't know otherwise.
            budget.truncated = true
            return
        }

        val childLevel: Int = depth + 1
        val indent: String = "  ".repeat(depth)

        MethodReferencesSearch.search(method, scope, false).forEach(Processor<PsiReference> { ref ->
            if (!budget.tryConsume()) return@Processor false

            val element: PsiElement = ref.element
            val filePath: String = element.containingFile?.virtualFile?.path ?: "(unknown)"
            val line: Int = lineOf(project, element)
            val raw: String = element.text
            val snippet: String = raw.take(80).let { s -> if (raw.length > 80) "$s..." else s }

            // Java: PSI tree walk finds PsiMethod directly. For Kotlin / other
            // JVM languages, UAST bridges to a light PsiMethod so one pass
            // handles every language whose UAST plugin is loaded (Kotlin is
            // bundled with recent IDEA, so this covers most mod projects).
            val enclosing: PsiMethod? = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                ?: element.getUastParentOfType<UMethod>()?.javaPsi
            if (enclosing == null) {
                out.append(indent)
                    .append("[L").append(childLevel).append("] (non-method context)")
                    .append("  at ").append(filePath).append(":").append(line)
                    .append("  : ").append(snippet)
                    .appendLine()
                return@Processor true
            }

            val key: String = cycleKeyOf(enclosing)
            val isCycle: Boolean = !visited.add(key)

            out.append(indent)
                .append("[L").append(childLevel).append("] ")
                .append(presentableSignature(enclosing))
                .append("  at ").append(filePath).append(":").append(line)
                .append("  : ").append(snippet)
            if (isCycle) out.append("  [cycle]")
            out.appendLine()

            if (!isCycle) {
                expandCallers(project, enclosing, childLevel, maxDepth, scope, visited, budget, out)
            }
            true
        })
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
        if (depth >= maxDepth) return
        if (budget.used >= budget.maxResults) {
            // Budget hit before we could explore this branch. Mark truncated so
            // the footer fires; the caller won't know otherwise.
            budget.truncated = true
            return
        }

        val childLevel: Int = depth + 1
        val indent: String = "  ".repeat(depth)

        val callees: List<BytecodeAnalyzer.CalleeRef>? = loadCallees(project, target)
        if (callees == null) {
            // No body in source or bytecode: abstract method, native method, or
            // unresolvable class. Emit at every depth so deeper leaves read
            // unambiguously as "terminal because abstract" vs. "terminal because
            // depth cap".
            val msg: String = if (depth == 0) {
                "  (abstract or native method — no body available in source or bytecode)"
            } else {
                "  (abstract — no body to walk)"
            }
            out.append(indent).append(msg).appendLine()
            return
        }
        if (depth == 0 && target.psiMethod?.body == null) {
            // Non-Java source (Kotlin etc.) flows through UAST; binary-only
            // classes flow through bytecode. Pick the label that actually
            // describes where the callees came from.
            val label: String = if (target.psiMethod?.toUElement(UMethod::class.java)?.uastBody != null) {
                "  (non-Java source — walking via UAST)"
            } else {
                "  (source body not available — extracting from bytecode)"
            }
            out.append(indent).append(label).appendLine()
        }
        if (callees.isEmpty()) {
            if (depth == 0) {
                out.append(indent).append("  (no outgoing calls)").appendLine()
            }
            return
        }

        // Dedupe by (owner, name, descriptor), preserving first-seen order.
        val seenInBody: HashSet<String> = HashSet()
        for (c: BytecodeAnalyzer.CalleeRef in callees) {
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
    private fun loadCallees(project: Project, target: CalleeTarget): List<BytecodeAnalyzer.CalleeRef>? {
        val psi: PsiMethod? = target.psiMethod
        if (psi?.body != null) return collectSourceCallees(psi.body!!)

        if (psi != null) {
            val uBody: UElement? = psi.toUElement(UMethod::class.java)?.uastBody
            if (uBody != null) return collectUastCallees(uBody)
        }

        val bytes: ByteArray = ClassFileLocator.locate(project, target.owner) ?: return null
        return BytecodeAnalyzer.extractCallees(bytes, target.name, target.descriptor)
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
