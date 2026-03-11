@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.util.DocumentUtil
import dev.xdark.ijmcp.util.formatLocation
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

class GoToDeclarationToolset : McpToolset {

    @Serializable
    data class DeclarationTarget(
        val name: String,
        val location: String,
        val language: String,
        val snippet: String,
    )

    @Serializable
    data class GoToDeclarationResult(
        val targets: List<DeclarationTarget>,
    )

    @McpTool
    @McpDescription("""
        |Navigates to the declaration of the symbol at the given position.
        |Returns the declaration's file location and a code snippet around it.
        |This is the same as Ctrl+Click / Go to Declaration in the IDE.
        |
        |Useful for quickly understanding what a symbol is without reading the whole file.
    """)
    suspend fun go_to_declaration(
        @McpDescription("Path relative to the project root") filePath: String,
        @McpDescription("1-based line number") line: Int,
        @McpDescription("1-based column number") column: Int,
        @McpDescription("Number of context lines around the declaration (default 5)") contextLines: Int = 5,
    ): GoToDeclarationResult {
        val project = currentCoroutineContext().project
        val resolved = resolveFile(project, filePath)

        val targets = readAction {
            val document = resolved.document
            if (!DocumentUtil.isValidLine(line - 1, document)) {
                mcpFail("Line $line is out of bounds")
            }
            val lineStartOffset = document.getLineStartOffset(line - 1)
            val offset = lineStartOffset + column - 1
            if (!DocumentUtil.isValidOffset(offset, document)) {
                mcpFail("Position $line:$column is out of bounds")
            }

            // Use findReferenceAt — the same approach as IntelliJ's built-in CodeInsightToolset
            val ref = resolved.psiFile.findReferenceAt(offset)
            val resolvedElement = ref?.resolve()

            if (resolvedElement != null) {
                val name = (resolvedElement as? PsiNamedElement)?.name
                    ?: resolvedElement.text?.take(50)
                    ?: "unknown"
                val loc = formatLocation(project, resolvedElement)
                val snippet = getSnippet(resolvedElement, contextLines)
                val lang = resolvedElement.navigationElement.language.displayName
                listOf(DeclarationTarget(name, loc, lang, snippet))
            } else {
                emptyList()
            }
        }

        if (targets.isEmpty()) {
            mcpFail("No declaration found at $line:$column")
        }

        return GoToDeclarationResult(targets = targets)
    }

    private fun getSnippet(element: PsiElement, contextLines: Int): String {
        val navElement = element.navigationElement
        val file = navElement.containingFile?.virtualFile ?: return ""
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return ""
        val textRange = navElement.textRange ?: return ""
        // Start from element start (to capture doc comments), end after name + context
        val startLine = document.getLineNumber(textRange.startOffset)
        val nameLine = document.getLineNumber(navElement.textOffset)
        val endLine = minOf(document.lineCount - 1, nameLine + contextLines)
        val startOffset = document.getLineStartOffset(startLine)
        val endOffset = document.getLineEndOffset(endLine)
        return document.getText(TextRange(startOffset, endOffset))
    }
}
