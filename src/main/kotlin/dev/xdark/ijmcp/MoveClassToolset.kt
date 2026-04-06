@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import com.intellij.refactoring.PackageWrapper
import com.intellij.refactoring.move.moveClassesOrPackages.AutocreatingSingleSourceRootMoveDestination
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

class MoveClassToolset : McpToolset {

    @McpTool
    @McpDescription(
        """
        |Moves a class to a different package (like F6 / Refactor > Move in IntelliJ).
        |
        |Automatically updates all references, imports, and package declarations across the project.
        |Creates the target package directory if it doesn't exist.
        |
        |Examples:
        |  filePath="src/main/kotlin/com/example/Foo.kt", targetPackage="com.example.util"
        |  filePath="src/main/java/com/example/MyService.java", targetPackage="com.example.service"
    """
    )
    suspend fun move_class(
        @McpDescription("Path relative to the project root") filePath: String,
        @McpDescription("Fully qualified target package name (e.g. 'com.example.util')") targetPackage: String,
        @McpDescription("Update references in comments and strings (default true)") searchInComments: Boolean = true,
        @McpDescription("Update references in non-Java/Kotlin files (default true)") searchInNonJavaFiles: Boolean = true,
    ): String {
        val project = currentCoroutineContext().project
        val resolved = resolveFile(project, filePath)

        val sourceRoot = readAction {
            val classOwner = resolved.psiFile as? PsiClassOwner
                ?: mcpFail("File is not a class file: $filePath")
            if (classOwner.classes.isEmpty()) {
                mcpFail("No classes found in: $filePath")
            }
            ProjectFileIndex.getInstance(project).getSourceRootForFile(resolved.virtualFile)
                ?: mcpFail("Cannot determine source root for: $filePath")
        }

        val classNames = readAction {
            (resolved.psiFile as PsiClassOwner).classes.mapNotNull { it.qualifiedName }
        }

        withContext(Dispatchers.EDT) {
            val targetPkg = PackageWrapper(PsiManager.getInstance(project), targetPackage)
            val destination = AutocreatingSingleSourceRootMoveDestination(targetPkg, sourceRoot)

            val verifyError = destination.verify(resolved.psiFile)
            if (verifyError != null) {
                mcpFail("Cannot move to '$targetPackage': $verifyError")
            }

            // Get/create the target directory — write action needed for directory creation
            val targetDir = WriteCommandAction.writeCommandAction(project)
                .compute<PsiDirectory, RuntimeException> {
                    destination.getTargetDirectory(resolved.psiFile)
                }

            // Move the file (not classes) — this triggers MoveFileHandler EPs
            // which correctly update package declarations for both Java and Kotlin
            val processor = MoveFilesOrDirectoriesProcessor(
                project,
                arrayOf(resolved.psiFile),
                targetDir,
                searchInComments,
                searchInNonJavaFiles,
                null, // moveCallback
                null, // prepareSuccessfulCallback
            )
            processor.run()
        }

        return "Moved ${classNames.joinToString(", ")} to $targetPackage"
    }
}
