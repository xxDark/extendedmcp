@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import dev.xdark.ijmcp.util.resolveFilesByPattern
import dev.xdark.ijmcp.util.resolvePsi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.kotlin.idea.base.codeInsight.ShortenReferencesFacility
import org.jetbrains.kotlin.psi.KtFile

class ShortenReferencesToolset : McpToolset {

    @Serializable
    data class ShortenReferencesResult(
        val filesProcessed: Int,
        val filesChanged: Int,
        val message: String,
    )

    @McpTool
    @McpDescription(
        """
        |Shortens fully qualified class references to simple names and adds imports.
        |Handles name clashes automatically — leaves FQ names where shortening would be ambiguous.
        |Supports both Java and Kotlin files.
        |
        |file_path can be a literal path or a glob pattern (e.g. "src/**/*.java").
        |Glob mode processes all matching Java/Kotlin files in the project.
        |
        |Use after editing code with fully qualified names (e.g. via replace_text_in_file).
    """
    )
    suspend fun shorten_references(
        @McpDescription("Path relative to project root, or glob pattern (e.g. 'src/**/*.java')") file_path: String,
    ): ShortenReferencesResult {
        val project = currentCoroutineContext().project
        val psiFiles = resolveFilesByPattern(project, file_path, extensions = setOf("java", "kt")).resolvePsi(project)

        if (psiFiles.isEmpty()) {
            return ShortenReferencesResult(0, 0, "No Java/Kotlin files match '$file_path'")
        }

        val textsBefore = readAction {
            psiFiles.associate { it.relativePath to it.document.text }
        }

        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) {
                for (entry in psiFiles) {
                    shortenFile(project, entry.psiFile)
                }
            }
        }

        val changedFiles = readAction {
            psiFiles.filter { it.document.text != textsBefore[it.relativePath] }
                .map { it.relativePath }
        }

        withContext(Dispatchers.EDT) {
            val fdm = FileDocumentManager.getInstance()
            for (entry in psiFiles) {
                fdm.saveDocument(entry.document)
            }
        }

        val message = buildString {
            if (psiFiles.size == 1) {
                if (changedFiles.isNotEmpty()) append("Shortened references in ${psiFiles[0].relativePath}")
                else append("No fully qualified references to shorten in ${psiFiles[0].relativePath}")
            } else {
                append("Processed ${psiFiles.size} files, ${changedFiles.size} changed")
                if (changedFiles.isNotEmpty()) {
                    append(":\n")
                    changedFiles.forEach { append("  $it\n") }
                }
            }
        }

        return ShortenReferencesResult(
            filesProcessed = psiFiles.size,
            filesChanged = changedFiles.size,
            message = message.trimEnd()
        )
    }

    private fun shortenFile(project: Project, psiFile: PsiFile) {
        if (psiFile is KtFile) {
            ShortenReferencesFacility.getInstance().shorten(psiFile, psiFile.textRange)
        } else {
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(psiFile)
        }
    }
}
