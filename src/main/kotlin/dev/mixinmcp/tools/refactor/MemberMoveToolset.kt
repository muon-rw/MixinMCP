package dev.mixinmcp.tools.refactor

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DirectClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.memberPullUp.PullUpConflictsUtil
import com.intellij.refactoring.memberPullUp.PullUpHelper
import com.intellij.refactoring.memberPullUp.PullUpProcessor
import com.intellij.refactoring.memberPushDown.PushDownProcessor
import com.intellij.refactoring.move.moveMembers.MoveMembersOptions
import com.intellij.refactoring.move.moveMembers.MoveMembersProcessor
import com.intellij.refactoring.util.DocCommentPolicy
import com.intellij.refactoring.util.classMembers.MemberInfo
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import com.intellij.util.containers.MultiMap
import dev.mixinmcp.resolve.FqcnResolver
import dev.mixinmcp.tools.requireProject
import dev.mixinmcp.tools.semantic.extractMixinTargets
import kotlin.coroutines.coroutineContext

@Suppress("FunctionName") // @McpTool functions are snake_case by MCP convention
class MemberMoveToolset : McpToolset {

    data class MemberRef(
        val name: String,
        val descriptor: String? = null,
    )

    @McpTool
    @McpDescription(
        "Move Java members between classes with every reference updated. direction='up' pulls the members " +
            "into a superclass or implemented interface (targetClassName required); direction='down' pushes " +
            "them into every direct subclass; direction='toClass' moves static members to any other class " +
            "(targetClassName required). Members are declarations of className, each given as a simple " +
            "name, or name#descriptor for overloaded methods (JVM descriptor, e.g. compute#(II)I). " +
            "Java sources only. On conflicts, each is reported " +
            "with its file and tagged [library] or [source]; ignoreConflicts=true proceeds anyway, same as " +
            "the IDE conflict dialog's Continue button. makeAbstract=true (up only) pulls methods up as " +
            "abstract declarations, keeping the implementations in className. dryRun=true reports affected " +
            "classes and conflicts without modifying anything. Moves into a @Mixin class report external " +
            "references to the moved members as [mixin] conflicts, since mixin class members cannot be " +
            "referenced from ordinary classes at runtime; other mixin-involved moves proceed with advisory notes.",
    )
    @Suppress("unused")
    suspend fun mixin_move_members(
        direction: String,
        className: String,
        members: List<String>,
        targetClassName: String? = null,
        makeAbstract: Boolean = false,
        ignoreConflicts: Boolean = false,
        dryRun: Boolean = false,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val memberRefs: List<MemberRef> = members.map { spec ->
            val name: String = spec.substringBefore('#')
            val descriptor: String? = spec.substringAfter('#', "").ifEmpty { null }
            if (name.isBlank()) {
                return McpToolCallResult.error("Invalid member spec '$spec'; expected name or name#descriptor.")
            }
            MemberRef(name, descriptor)
        }

        when (direction) {
            "up", "toClass" -> if (targetClassName == null) {
                return McpToolCallResult.error("direction='$direction' requires targetClassName.")
            }
            "down" -> if (targetClassName != null) {
                return McpToolCallResult.error(
                    "direction='down' pushes into every direct subclass; targetClassName does not apply.",
                )
            }
            else -> return McpToolCallResult.error("direction must be 'up', 'down', or 'toClass'.")
        }
        if (makeAbstract && direction != "up") {
            return McpToolCallResult.error("makeAbstract only applies with direction='up'.")
        }

        val prepared: Prep = smartReadAction(project) {
            prepare(project, direction, className, memberRefs, targetClassName, makeAbstract)
        }
        val prep: Prep.Ok = when (prepared) {
            is Prep.Failure -> return McpToolCallResult.error(prepared.message)
            is Prep.Ok -> prepared
        }

        return when (direction) {
            "up" -> pullUp(project, prep, ignoreConflicts, dryRun)
            "down" -> runConflictProcessor(project, prep, ignoreConflicts, dryRun) { onConflicts ->
                HeadlessPushDownProcessor(prep.sourceClass, prep.memberInfos, onConflicts)
            }
            else -> runConflictProcessor(project, prep, ignoreConflicts, dryRun) { onConflicts ->
                HeadlessMoveMembersProcessor(project, moveOptions(prep), onConflicts)
            }
        }
    }

    // ───────────────────────────────────── Preparation ─────────────────────────────────────

    private sealed class Prep {
        class Ok(
            val sourceClass: PsiClass,
            val members: List<PsiMember>,
            val memberInfos: List<MemberInfo>,
            val targetClass: PsiClass?,
            val targetName: String?,
            val description: String,
            val sourceFile: String,
            val affectedNote: String,
            val prepConflicts: List<RefactorSupport.ConflictRef>,
            val notes: List<String>,
        ) : Prep()

        data class Failure(val message: String) : Prep()
    }

    private fun prepare(
        project: Project,
        direction: String,
        className: String,
        memberRefs: List<MemberRef>,
        targetClassName: String?,
        makeAbstract: Boolean,
    ): Prep {
        val resolved = when (val r = RefactorSupport.resolveMembers(
            project, className, memberRefs.map { RefactorSupport.MemberSpec(it.name, it.descriptor) },
        )) {
            is RefactorSupport.MemberResolution.Failure -> return Prep.Failure(r.message)
            is RefactorSupport.MemberResolution.Resolved -> r
        }
        val sourceClass: PsiClass = resolved.sourceClass
        val sourceDisplay: String = sourceClass.qualifiedName ?: className
        val sourceIsMixin: Boolean = isMixinClass(sourceClass)
        val membersLabel: String = resolved.members.joinToString(", ") { memberLabel(it) }
        val sourceFile: String = sourceClass.containingFile?.virtualFile
            ?.let { RefactorSupport.projectRelative(project, it) } ?: "(no file)"

        if (direction == "down") {
            val inheritors: MutableList<String> = mutableListOf()
            val mixinInheritors: MutableList<PsiClass> = mutableListOf()
            for (inheritor in DirectClassInheritorsSearch.search(sourceClass).findAll()) {
                ProgressManager.checkCanceled()
                val inheritorDisplay: String = inheritor.qualifiedName ?: inheritor.name ?: "(anonymous)"
                if (isMixinClass(inheritor)) mixinInheritors.add(inheritor)
                inheritors.add(inheritorDisplay)
            }
            if (inheritors.isEmpty()) {
                return Prep.Failure(
                    "$sourceDisplay has no direct subclasses; pushing down would only delete the member(s) " +
                        "after an interactive confirmation. Nothing to push down into.",
                )
            }
            return Prep.Ok(
                sourceClass = sourceClass,
                members = resolved.members,
                memberInfos = resolved.memberInfos,
                targetClass = null,
                targetName = null,
                description = "push down of [$membersLabel] from $sourceDisplay",
                sourceFile = sourceFile,
                affectedNote = "into ${inheritors.size} direct subclass(es): ${inheritors.joinToString(", ")}",
                prepConflicts = mixinInheritors.map { mixinDestinationConflict(project, it) },
                notes = buildList {
                    addAll(uniqueInertNotes(sourceIsMixin, destinationIsMixin = false, resolved.members))
                    if (mixinInheritors.isNotEmpty()) {
                        add(mixinDestinationNote(mixinInheritors))
                    }
                },
            )
        }

        val target: PsiClass = FqcnResolver.resolveNested(project, targetClassName!!)
            ?: return Prep.Failure("Class not found: $targetClassName. ${FqcnResolver.CLASS_NOT_FOUND_HINT}")
        val targetDisplay: String = target.qualifiedName
            ?: return Prep.Failure("$targetClassName has no qualified name; it cannot be a move target.")
        RefactorSupport.guardJavaSourceTarget(project, target, targetDisplay)?.let { return Prep.Failure(it) }
        if (target === sourceClass) {
            return Prep.Failure("Target class equals the source class; nothing to move.")
        }
        val targetIsMixin: Boolean = isMixinClass(target)
        val mixinConflicts: List<RefactorSupport.ConflictRef> =
            if (targetIsMixin) externalReferenceConflicts(project, resolved.members, target) else emptyList()
        val notes: List<String> = buildList {
            addAll(uniqueInertNotes(sourceIsMixin, targetIsMixin, resolved.members))
            if (targetIsMixin) add(mixinDestinationNote(listOf(target)))
        }

        if (direction == "up") {
            if (!sourceClass.isInheritor(target, true)) {
                return Prep.Failure(supersListing(sourceClass, sourceDisplay, targetDisplay))
            }
            if (makeAbstract) {
                val methodInfos: List<MemberInfo> =
                    resolved.memberInfos.filter { it.member is PsiMethod }
                if (methodInfos.isEmpty()) {
                    return Prep.Failure(
                        "makeAbstract applies to methods; none of the selected members is a method.",
                    )
                }
                methodInfos.forEach { it.isToAbstract = true }
            }
            return Prep.Ok(
                sourceClass = sourceClass,
                members = resolved.members,
                memberInfos = resolved.memberInfos,
                targetClass = target,
                targetName = targetDisplay,
                description = "pull up of [$membersLabel] from $sourceDisplay to $targetDisplay" +
                    if (makeAbstract) " (methods as abstract)" else "",
                sourceFile = sourceFile,
                affectedNote = "target: $targetDisplay",
                prepConflicts = pullUpConflicts(project, sourceClass, target, resolved.memberInfos) + mixinConflicts,
                notes = notes,
            )
        }

        val nonStatic: List<PsiMember> = resolved.members.filter { !it.hasModifierProperty(PsiModifier.STATIC) }
        if (nonStatic.isNotEmpty()) {
            return Prep.Failure(
                "Only static members can move to an unrelated class; not static: " +
                    nonStatic.joinToString(", ") { memberLabel(it) } +
                    ". Use direction='up' or 'down' for instance members.",
            )
        }
        JavaPsiFacade.getInstance(project).findClass(targetDisplay, GlobalSearchScope.projectScope(project))
            ?: return Prep.Failure(
                "$targetDisplay does not resolve in project scope; the move-members processor " +
                    "requires a project source target.",
            )
        return Prep.Ok(
            sourceClass = sourceClass,
            members = resolved.members,
            memberInfos = resolved.memberInfos,
            targetClass = target,
            targetName = targetDisplay,
            description = "move of [$membersLabel] from $sourceDisplay to $targetDisplay",
            sourceFile = sourceFile,
            affectedNote = "target: $targetDisplay",
            prepConflicts = mixinConflicts,
            notes = notes,
        )
    }

    private fun pullUpConflicts(
        project: Project,
        sourceClass: PsiClass,
        target: PsiClass,
        memberInfos: List<MemberInfo>,
    ): List<RefactorSupport.ConflictRef> {
        val targetDirectory = target.containingFile?.containingDirectory ?: return emptyList()
        val targetPackage = JavaDirectoryService.getInstance().getPackage(targetDirectory) ?: return emptyList()
        val conflictMap = PullUpConflictsUtil.checkConflicts(
            memberInfos.toTypedArray(), sourceClass, target, targetPackage, targetDirectory,
        ) { psiMethod -> PullUpProcessor.checkedInterfacesContain(memberInfos, psiMethod) }
        return RefactorSupport.renderConflicts(project, conflictMap)
    }

    private fun memberLabel(member: PsiMember): String = when (member) {
        is PsiMethod ->
            member.name + "(" + member.parameterList.parameters.joinToString(", ") { it.type.presentableText } + ")"
        else -> (member as? PsiNamedElement)?.name ?: "(unnamed)"
    }

    private fun isMixinClass(psiClass: PsiClass): Boolean =
        psiClass.modifierList?.annotations?.any {
            it.qualifiedName == "org.spongepowered.asm.mixin.Mixin"
        } == true

    private fun mixinDestinationConflict(project: Project, mixinClass: PsiClass): RefactorSupport.ConflictRef {
        val display: String = mixinClass.qualifiedName ?: mixinClass.name ?: "(anonymous)"
        val file: String = mixinClass.containingFile?.virtualFile
            ?.let { RefactorSupport.projectRelative(project, it) } ?: "(unknown file)"
        val targets: String = extractMixinTargets(mixinClass).joinToString(", ").ifEmpty { "(unresolved)" }
        return RefactorSupport.ConflictRef(
            "$display is a @Mixin class (targets: $targets); members placed in it are merged into the " +
                "target class at runtime, not kept on $display itself",
            file, "mixin",
        )
    }

    private fun mixinDestinationNote(mixinClasses: List<PsiClass>): String {
        val destinations: String = mixinClasses.joinToString(", ") { mixin ->
            val targets: String = extractMixinTargets(mixin).joinToString(", ").ifEmpty { "(unresolved)" }
            "${mixin.qualifiedName ?: mixin.name} (targets: $targets)"
        }
        return "destination is a @Mixin class: $destinations; run get_file_problems on it to validate " +
            "injector and @Shadow resolution against the target."
    }

    private fun uniqueInertNotes(
        sourceIsMixin: Boolean,
        destinationIsMixin: Boolean,
        members: List<PsiMember>,
    ): List<String> {
        fun hasUnique(member: PsiMember): Boolean = member.modifierList?.annotations?.any {
            it.qualifiedName == "org.spongepowered.asm.mixin.Unique"
        } == true
        return when {
            sourceIsMixin && !destinationIsMixin -> members.filter(::hasUnique).map {
                "@Unique on ${memberLabel(it)} is inert outside a mixin; consider removing it."
            }
            destinationIsMixin && !sourceIsMixin -> members.filterNot(::hasUnique).map {
                "${memberLabel(it)} lands in a mixin without @Unique; convention is a modid\$-prefixed " +
                    "@Unique member."
            }
            else -> emptyList()
        }
    }

    private fun externalReferenceConflicts(
        project: Project,
        members: List<PsiMember>,
        destination: PsiClass,
    ): List<RefactorSupport.ConflictRef> {
        val conflicts: MutableList<RefactorSupport.ConflictRef> = mutableListOf()
        for (member in members) {
            ProgressManager.checkCanceled()
            ReferencesSearch.search(member, GlobalSearchScope.projectScope(project)).forEach(Processor { ref ->
                val element: PsiElement = ref.element
                val insideMove: Boolean = PsiTreeUtil.isAncestor(destination, element, false) ||
                    members.any { PsiTreeUtil.isAncestor(it, element, false) }
                if (!insideMove) {
                    val vf = element.containingFile?.virtualFile
                    val path: String = vf?.let { RefactorSupport.projectRelative(project, it) } ?: "(unknown file)"
                    val line: Int = element.containingFile
                        ?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
                        ?.getLineNumber(element.textOffset)?.plus(1) ?: 0
                    conflicts.add(
                        RefactorSupport.ConflictRef(
                            "line $line references ${memberLabel(member)}, which would move into a @Mixin " +
                                "class; mixin class members cannot be referenced from ordinary classes at runtime",
                            path, "mixin",
                        ),
                    )
                }
                conflicts.size < 50
            })
        }
        return conflicts
    }

    private fun supersListing(sourceClass: PsiClass, sourceDisplay: String, targetDisplay: String): String {
        val supers: MutableList<String> = mutableListOf()
        val seen: MutableSet<PsiClass> = mutableSetOf()
        val queue: ArrayDeque<PsiClass> = ArrayDeque(listOf(sourceClass))
        while (queue.isNotEmpty()) {
            ProgressManager.checkCanceled()
            val cursor: PsiClass = queue.removeFirst()
            for (superClass in cursor.supers) {
                if (seen.add(superClass)) {
                    superClass.qualifiedName?.let { supers.add(it) }
                    queue.addLast(superClass)
                }
            }
        }
        return "$targetDisplay is not a superclass or implemented interface of $sourceDisplay; " +
            "direction='up' can only move members along the inheritance chain. Supers of $sourceDisplay: " +
            if (supers.isEmpty()) "(none)" else supers.joinToString(", ")
    }

    // ───────────────────────────────────── Pull up ─────────────────────────────────────

    private fun pullUp(
        project: Project,
        prep: Prep.Ok,
        ignoreConflicts: Boolean,
        dryRun: Boolean,
    ): McpToolCallResult {
        if (dryRun) {
            return McpToolCallResult.text(buildString {
                appendLine("Dry run for ${prep.description}")
                appendLine("  source file: ${prep.sourceFile}")
                appendLine("  ${prep.affectedNote}")
                appendLine()
                if (prep.prepConflicts.isEmpty()) {
                    appendLine("No conflicts detected.")
                } else {
                    append(RefactorSupport.formatConflicts(prep.prepConflicts))
                }
                appendLine("Re-run without dryRun=true to perform the move.")
            })
        }
        if (prep.prepConflicts.isNotEmpty() && !ignoreConflicts) {
            return McpToolCallResult.error(
                "Cannot perform ${prep.description}.\n" + RefactorSupport.formatConflicts(prep.prepConflicts),
            )
        }

        val error: String? = RefactorSupport.runRefactoringOnEdt(project) {
            check(
                prep.sourceClass.isValid && prep.targetClass!!.isValid && prep.members.all { it.isValid },
            ) { RefactorSupport.STALE_TARGET }
            HeadlessPullUpProcessor(
                prep.sourceClass, prep.targetClass, prep.memberInfos.toTypedArray(),
                DocCommentPolicy(DocCommentPolicy.ASIS),
            ).run()
        }
        if (error != null) return McpToolCallResult.error("Move members failed: $error")
        if (membersStillInSource(prep)) return McpToolCallResult.error(RefactorSupport.BAIL_OUT)
        return McpToolCallResult.text(successText(prep))
    }

    // ───────────────────────────────────── Processor-driven execution ─────────────────────────────────────

    private fun runConflictProcessor(
        project: Project,
        prep: Prep.Ok,
        ignoreConflicts: Boolean,
        dryRun: Boolean,
        makeProcessor: (onConflicts: (MultiMap<PsiElement, String>, Array<out UsageInfo>?) -> Boolean) -> BaseRefactoringProcessor,
    ): McpToolCallResult {
        if (dryRun) return dryRunConflictProcessor(project, prep, makeProcessor)
        if (prep.prepConflicts.isNotEmpty() && !ignoreConflicts) {
            return McpToolCallResult.error(
                "Cannot perform ${prep.description}.\n" + RefactorSupport.formatConflicts(prep.prepConflicts),
            )
        }

        val capture = RefactorSupport.ConflictCapture(ignoreConflicts)
        val error: String? = RefactorSupport.runRefactoringOnEdt(project) {
            checkPrepValid(prep)
            makeProcessor { conflicts, _ -> capture.onConflicts(conflicts) }.run()
        }
        if (error != null) return McpToolCallResult.error("Move members failed: $error")
        capture.captured?.let { conflictMap ->
            val rendered = ApplicationManager.getApplication().runReadAction<List<RefactorSupport.ConflictRef>> {
                RefactorSupport.renderConflicts(project, conflictMap)
            }
            return McpToolCallResult.error(
                "Cannot perform ${prep.description}.\n" + RefactorSupport.formatConflicts(rendered),
            )
        }
        if (membersStillInSource(prep)) return McpToolCallResult.error(RefactorSupport.BAIL_OUT)
        return McpToolCallResult.text(successText(prep))
    }

    private fun dryRunConflictProcessor(
        project: Project,
        prep: Prep.Ok,
        makeProcessor: (onConflicts: (MultiMap<PsiElement, String>, Array<out UsageInfo>?) -> Boolean) -> BaseRefactoringProcessor,
    ): McpToolCallResult {
        var capturedConflicts: MultiMap<PsiElement, String>? = null
        var capturedUsages: Array<out UsageInfo>? = null
        val error: String? = RefactorSupport.runRefactoringOnEdt(project) {
            checkPrepValid(prep)
            makeProcessor { conflicts, usages ->
                capturedConflicts = conflicts
                capturedUsages = usages
                false
            }.run()
        }
        if (error != null) return McpToolCallResult.error("Dry run failed: $error")
        val conflictMap: MultiMap<PsiElement, String> = capturedConflicts
            ?: return McpToolCallResult.error(RefactorSupport.DRY_RUN_BAIL_OUT)

        data class DryRunData(val conflicts: List<RefactorSupport.ConflictRef>, val usagesByFile: Map<String, Int>)
        val usages: Array<out UsageInfo> = capturedUsages ?: emptyArray()
        val data: DryRunData = ApplicationManager.getApplication().runReadAction<DryRunData> {
            DryRunData(
                conflicts = RefactorSupport.renderConflicts(project, conflictMap),
                usagesByFile = usages
                    .mapNotNull { usage -> usage.virtualFile?.let { RefactorSupport.projectRelative(project, it) } }
                    .groupingBy { it }
                    .eachCount(),
            )
        }

        return McpToolCallResult.text(buildString {
            appendLine("Dry run for ${prep.description}")
            appendLine("  source file: ${prep.sourceFile}")
            appendLine("  ${prep.affectedNote}")
            appendLine()
            if (data.usagesByFile.isEmpty()) {
                appendLine("No affected code locations found.")
            } else {
                appendLine("${usages.size} affected code location(s) in ${data.usagesByFile.size} file(s):")
                for ((file, count) in data.usagesByFile.entries.sortedByDescending { it.value }) {
                    appendLine("  $file  ($count)")
                }
            }
            appendLine()
            val allConflicts: List<RefactorSupport.ConflictRef> = prep.prepConflicts + data.conflicts
            if (allConflicts.isEmpty()) {
                appendLine("No conflicts detected.")
            } else {
                append(RefactorSupport.formatConflicts(allConflicts))
            }
            appendLine("Re-run without dryRun=true to perform the move.")
        })
    }

    // ───────────────────────────────────── Shared helpers ─────────────────────────────────────

    private fun checkPrepValid(prep: Prep.Ok) {
        check(
            prep.sourceClass.isValid && prep.targetClass?.isValid != false && prep.members.all { it.isValid },
        ) { RefactorSupport.STALE_TARGET }
    }

    /** Members pulled up as abstract intentionally keep their implementation in the source class. */
    private fun membersStillInSource(prep: Prep.Ok): Boolean =
        ApplicationManager.getApplication().runReadAction<Boolean> {
            prep.sourceClass.isValid && prep.members.withIndex().any { (index, member) ->
                !prep.memberInfos[index].isToAbstract &&
                    member.isValid && member.containingClass === prep.sourceClass
            }
        }

    private fun successText(prep: Prep.Ok): String = buildString {
        appendLine("Completed ${prep.description}")
        appendLine("  source file: ${prep.sourceFile}")
        appendLine("  ${prep.affectedNote}")
        appendLine()
        for (note in prep.notes) {
            appendLine("Note: $note")
        }
        appendLine("References updated project-wide by IntelliJ.")
    }

    private fun moveOptions(prep: Prep.Ok): MoveMembersOptions {
        val membersArray: Array<PsiMember> = prep.members.toTypedArray()
        val targetName: String = prep.targetName!!
        return object : MoveMembersOptions {
            override fun getSelectedMembers(): Array<PsiMember> = membersArray
            override fun getTargetClassName(): String = targetName
            override fun getMemberVisibility(): String? = null
            override fun makeEnumConstant(): Boolean = false
        }
    }

    private class HeadlessPushDownProcessor(
        sourceClass: PsiClass,
        members: List<MemberInfo>,
        private val onConflictsFound: (MultiMap<PsiElement, String>, Array<out UsageInfo>?) -> Boolean,
    ) : PushDownProcessor<MemberInfo, PsiMember, PsiClass>(
        sourceClass, members, DocCommentPolicy(DocCommentPolicy.ASIS),
    ) {
        override fun isPreviewUsages(usages: Array<UsageInfo>): Boolean = false
        override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo>?): Boolean =
            onConflictsFound(conflicts, usages)
    }

    private class HeadlessMoveMembersProcessor(
        project: Project,
        options: MoveMembersOptions,
        private val onConflictsFound: (MultiMap<PsiElement, String>, Array<out UsageInfo>?) -> Boolean,
    ) : MoveMembersProcessor(project, options) {
        override fun isPreviewUsages(usages: Array<UsageInfo>): Boolean = false
        override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo>?): Boolean =
            onConflictsFound(conflicts, usages)
    }

    // Reimplements PullUpProcessor.performRefactoring without its trailing processMethodsDuplicates(),
    // which asynchronously pops a modal "replace duplicate?" dialog and rewrites lookalike code across
    // the hierarchy; both are unwanted for a headless member move.
    private class HeadlessPullUpProcessor(
        sourceClass: PsiClass,
        targetSuperClass: PsiClass,
        membersToMove: Array<MemberInfo>,
        docPolicy: DocCommentPolicy,
    ) : PullUpProcessor(sourceClass, targetSuperClass, membersToMove, docPolicy) {
        override fun isPreviewUsages(usages: Array<UsageInfo>): Boolean = false

        override fun performRefactoring(usages: Array<UsageInfo>) {
            moveMembersToBase()
            moveFieldInitializations()
            val helpers: MutableMap<Language, PullUpHelper<MemberInfo>?> = HashMap()
            for (usage in usages) {
                val element: PsiElement = usage.element ?: continue
                val helper: PullUpHelper<MemberInfo> = helpers.getOrPut(element.language) {
                    @Suppress("UNCHECKED_CAST")
                    PullUpHelper.INSTANCE.forLanguage(element.language)?.createPullUpHelper(this)
                        as PullUpHelper<MemberInfo>?
                } ?: continue
                helper.updateUsage(element)
            }
        }
    }
}
