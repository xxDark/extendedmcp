@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

class ReplaceLinesToolset : McpToolset {

    @Serializable
    data class Replacement(
        val file_path: String,
        val start_line: Int,
        val end_line: Int,
        val new_text: String = "",
    )

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

    @McpTool
    @McpDescription(
        """
        |Replaces multiple line ranges across one or more files in a single call.
        |Much more efficient than multiple replace_lines calls when making several changes.
        |
        |Each replacement has: file_path, start_line, end_line, new_text (all 1-based, inclusive).
        |Ranges within the same file must not overlap.
        |Replacements are applied bottom-to-top per file so line numbers stay stable.
    """
    )
    suspend fun batch_replace_lines(
        @McpDescription("Array of replacements to apply") replacements: List<Replacement>,
    ): Any {
        if (replacements.isEmpty()) mcpFail("replacements must not be empty")

        val project = currentCoroutineContext().project
        val byFile = replacements.groupBy { it.file_path }

        return buildString {
            for ((filePath, fileReplacements) in byFile) {
                val resolved = resolveFile(project, filePath)
                val document = resolved.document
                val lineCount = document.lineCount

                val sorted = fileReplacements.sortedByDescending { it.start_line }

                for (r in sorted) {
                    if (r.start_line <= 0) mcpFail("$filePath: start_line must be > 0 (got ${r.start_line})")
                    if (r.end_line <= 0) mcpFail("$filePath: end_line must be > 0 (got ${r.end_line})")
                    if (r.start_line > r.end_line) mcpFail("$filePath: start_line (${r.start_line}) must be <= end_line (${r.end_line})")
                    if (r.start_line > lineCount) mcpFail("$filePath: start_line (${r.start_line}) exceeds file length ($lineCount lines)")
                    if (r.end_line > lineCount) mcpFail("$filePath: end_line (${r.end_line}) exceeds file length ($lineCount lines)")
                }

                for (i in 0 until sorted.size - 1) {
                    val upper = sorted[i]
                    val lower = sorted[i + 1]
                    if (lower.end_line >= upper.start_line) {
                        mcpFail("$filePath: overlapping ranges [${lower.start_line}-${lower.end_line}] and [${upper.start_line}-${upper.end_line}]")
                    }
                }

                withContext(Dispatchers.EDT) {
                    WriteCommandAction.runWriteCommandAction(project) {
                        for (r in sorted) {
                            val startOffset = document.getLineStartOffset(r.start_line - 1)
                            val endOffset = document.getLineEndOffset(r.end_line - 1)
                            document.replaceString(startOffset, endOffset, r.new_text)
                        }
                    }
                    FileDocumentManager.getInstance().saveDocument(document)
                }

                append(filePath).append(": ").append(fileReplacements.size).appendLine(" replacement(s) applied")
                appendContext(document, sorted.last().start_line)
            }
        }.trimEnd()
    }

    private fun StringBuilder.appendContext(document: Document, firstChangeLine: Int) {
        val lineCount = document.lineCount
        val contextStart = max(1, firstChangeLine - 1)
        val contextEnd = min(lineCount, firstChangeLine + 3)
        val chars = document.immutableCharSequence
        for (lineNumber in contextStart..contextEnd) {
            val lineIndex = lineNumber - 1
            append("  L").append(lineNumber).append(": ")
            append(chars, document.getLineStartOffset(lineIndex), document.getLineEndOffset(lineIndex))
            append('\n')
        }
    }
}