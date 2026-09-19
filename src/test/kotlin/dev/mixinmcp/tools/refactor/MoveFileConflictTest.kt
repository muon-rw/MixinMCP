package dev.mixinmcp.tools.refactor

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class MoveFileConflictTest : LightJavaCodeInsightFixtureTestCase() {

    fun testPackagePrivateUseRefusesMoveAndChangesNothing() {
        addHelper()
        val user: PsiFile = addUser()
        val originalText: String = user.text

        val result: McpToolCallResult = moveToB(user, ignoreConflicts = false, dryRun = false)

        assertTrue("expected a refusal, got: ${text(result)}", result.isError)
        assertTrue(text(result), text(result).contains("[source]"))
        assertTrue(text(result), text(result).contains("ignoreConflicts=true"))
        assertClassIn("a.User")
        assertNull(findClass("b.User"))
        assertEquals(originalText, findClass("a.User")!!.containingFile.text)
        assertNull("the refused move must remove the target directory it created", sourceRoot(user).findSubdirectory("b"))
    }

    fun testMovingPackagePrivateClassReportsUsersLeftBehind() {
        val helper: PsiFile = addHelper()
        addUser()

        val result: McpToolCallResult = moveToB(helper, ignoreConflicts = false, dryRun = false)

        assertTrue("expected a refusal, got: ${text(result)}", result.isError)
        assertTrue(text(result), text(result).contains("[source]"))
        assertClassIn("a.Helper")
    }

    fun testIgnoreConflictsMovesAndReportsPushedConflicts() {
        addHelper()
        val user: PsiFile = addUser()

        val result: McpToolCallResult = moveToB(user, ignoreConflicts = true, dryRun = false)

        assertFalse("expected the move to proceed, got: ${text(result)}", result.isError)
        assertTrue(text(result), text(result).contains("Proceeded despite"))
        assertNull(findClass("a.User"))
        assertEquals("b", (findClass("b.User")!!.containingFile as PsiJavaFile).packageName)
    }

    fun testDryRunReportsUsagesAndLeavesFilesUntouched() {
        val api: PsiFile = myFixture.addFileToProject("a/Api.java", "package a;\n\npublic class Api {}\n")
        myFixture.addFileToProject("c/Client.java", "package c;\n\nimport a.Api;\n\npublic class Client {\n    Api api;\n}\n")

        val result: McpToolCallResult = moveToB(api, ignoreConflicts = false, dryRun = true)

        assertFalse(text(result), result.isError)
        assertTrue(text(result), text(result).contains("Client.java"))
        assertTrue(text(result), text(result).contains("No conflicts detected."))
        assertClassIn("a.Api")
        assertNull(findClass("b.Api"))
        assertNull("the dry run must remove the target directory it created", sourceRoot(api).findSubdirectory("b"))
    }

    fun testCleanMoveRewritesPackageAndImports() {
        val api: PsiFile = myFixture.addFileToProject("a/Api.java", "package a;\n\npublic class Api {}\n")
        val client: PsiFile = myFixture.addFileToProject(
            "c/Client.java",
            "package c;\n\nimport a.Api;\n\npublic class Client {\n    Api api;\n}\n",
        )

        val result: McpToolCallResult = moveToB(api, ignoreConflicts = false, dryRun = false)

        assertFalse(text(result), result.isError)
        assertClassIn("b.Api")
        assertTrue(client.text, client.text.contains("import b.Api;"))
    }

    private fun addHelper(): PsiFile = myFixture.addFileToProject(
        "a/Helper.java",
        "package a;\n\nclass Helper {\n    static int secret() { return 1; }\n}\n",
    )

    private fun addUser(): PsiFile = myFixture.addFileToProject(
        "a/User.java",
        "package a;\n\npublic class User {\n    int read() { return Helper.secret(); }\n}\n",
    )

    private fun moveToB(file: PsiFile, ignoreConflicts: Boolean, dryRun: Boolean): McpToolCallResult {
        val root: PsiDirectory = sourceRoot(file)
        val target: PsiDirectory = WriteCommandAction.writeCommandAction(project).compute<PsiDirectory, Throwable> {
            root.createSubdirectory("b")
        }
        val fqn = "${(file as PsiJavaFile).packageName}.${file.name.removeSuffix(".java")}"
        val prep = SymbolRefactorToolset.MovePreparation(
            psiFile = file,
            targetDir = target,
            targetPackage = "b",
            sourceFqn = fqn,
            sourceRelative = file.virtualFile.path,
            targetRelative = "${target.virtualFile.path}/${file.name}",
            createdDir = target.virtualFile,
            moveMessage = null,
        )
        return SymbolRefactorToolset().runMove(project, prep, ignoreConflicts, dryRun)
    }

    private fun sourceRoot(file: PsiFile): PsiDirectory = file.containingDirectory.parentDirectory!!

    private fun findClass(fqn: String): PsiClass? =
        JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.projectScope(project))

    private fun assertClassIn(fqn: String) {
        assertNotNull("expected $fqn to exist", findClass(fqn))
    }

    private fun text(result: McpToolCallResult): String =
        (result.content.single() as McpToolCallResultContent.Text).text
}
