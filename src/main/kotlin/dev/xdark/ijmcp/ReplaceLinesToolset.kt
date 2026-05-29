@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.max

class ReplaceLinesToolset : McpToolset {

    @McpTool
    @McpDescription(
        """
        |Replaces a range of lines in a file with new text.
        |More token-efficient than replace_text_in_file — only send line numbers and the new content,
        |no need to repeat the old text.
        |
        |start_line and end_line are 1-based and inclusive.
        |The text content of those lines is replaced with new_text.
        |If new_text is empty, the lines are deleted.
        |Returns a few lines of context around the replacement for verification.
    """
    )
    suspend fun replace_lines(
        @McpDescription("Path relative to the project root") file_path: String,
        @McpDescription("1-based first line to replace (inclusive)") start_line: Int,
        @McpDescription("1-based last line to replace (inclusive)") end_line: Int,
        @McpDescription("Replacement text (empty to delete lines)") new_text: String = "",
    ): Any {
        val project = currentCoroutineContext().project
        val resolved = resolveFile(project, file_path)
        val document = resolved.document
        val lineCount = document.lineCount

        if (start_line <= 0) mcpFail("start_line must be > 0")
        if (end_line <= 0) mcpFail("end_line must be > 0")
        if (start_line > end_line) mcpFail("start_line must be <= end_line")
        if (start_line > lineCount) mcpFail("start_line exceeds file length ($lineCount lines)")
        if (end_line > lineCount) mcpFail("end_line exceeds file length ($lineCount lines)")

        val startOffset = document.getLineStartOffset(start_line - 1)
        val endOffset = document.getLineEndOffset(end_line - 1)

        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) {
                document.replaceString(startOffset, endOffset, new_text)
            }
            FileDocumentManager.getInstance().saveDocument(document)
        }

        val newLineCount = document.lineCount
        val newTextLines = if (new_text.isEmpty()) 0 else new_text.count { it == '\n' } + 1
        val contextStart = max(1, start_line - 2)
        val contextEnd = min(newLineCount, start_line + newTextLines + 1)

        return buildString {
            append("[replaced lines ").append(start_line).append('-').append(end_line).appendLine("]")
            val chars = document.immutableCharSequence
            for (lineNumber in contextStart..contextEnd) {
                val lineIndex = lineNumber - 1
                append('L').append(lineNumber).append(": ")
                append(chars, document.getLineStartOffset(lineIndex), document.getLineEndOffset(lineIndex))
                append('\n')
            }
        }.trimEnd()
    }
}
