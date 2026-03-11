@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.refactoring.extractMethod.ExtractMethodHandler
import com.intellij.refactoring.extractMethod.ExtractMethodProcessor
import com.intellij.util.DocumentUtil
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

class ExtractMethodToolset : McpToolset {

    @McpTool
    @McpDescription("""
        |Extracts the selected code range into a new method (Java).
        |
        |IntelliJ automatically determines parameters, return type, and exceptions.
        |Replaces the original code with a call to the new method.
        |
        |Specify the range of code to extract using start/end line numbers (1-based).
        |The entire lines in the range will be extracted.
    """)
    suspend fun extract_method(
        @McpDescription("Path relative to the project root") filePath: String,
        @McpDescription("1-based start line of the code to extract") startLine: Int,
        @McpDescription("1-based end line of the code to extract (inclusive)") endLine: Int,
        @McpDescription("Name for the new method") methodName: String,
    ): String {
        val project = currentCoroutineContext().project
        val resolved = resolveFile(project, filePath)

        val (startOffset, endOffset) = readAction {
            if (!DocumentUtil.isValidLine(startLine - 1, resolved.document)) {
                mcpFail("Start line $startLine is out of bounds")
            }
            if (!DocumentUtil.isValidLine(endLine - 1, resolved.document)) {
                mcpFail("End line $endLine is out of bounds")
            }
            val start = resolved.document.getLineStartOffset(startLine - 1)
            val end = resolved.document.getLineEndOffset(endLine - 1)
            start to end
        }

        withContext(Dispatchers.EDT) {
            writeIntentReadAction {
                val editor = EditorFactory.getInstance().createEditor(resolved.document, project)
                try {
                    editor.selectionModel.setSelection(startOffset, endOffset)

                    val elements = ExtractMethodHandler.getElements(project, editor, resolved.psiFile)
                    if (elements.isNullOrEmpty()) {
                        mcpFail("Cannot extract method from the selected range (lines $startLine-$endLine). Ensure the selection contains complete statements.")
                    }

                    val processor = ExtractMethodProcessor(
                        project, editor, elements,
                        null, // forcedReturnType
                        "Extract Method",
                        methodName,
                        null, // helpId
                    )

                    if (!processor.prepare()) {
                        mcpFail("Cannot extract method: preparation failed. The selected code may not form a valid extractable block.")
                    }

                    processor.testPrepare()
                    ExtractMethodHandler.extractMethod(project, processor)
                } finally {
                    EditorFactory.getInstance().releaseEditor(editor)
                }
            }
        }

        withContext(Dispatchers.EDT) {
            FileDocumentManager.getInstance().saveDocument(resolved.document)
        }

        return "Successfully extracted lines $startLine-$endLine into method '$methodName'"
    }
}
