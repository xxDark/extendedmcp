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
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class BatchReplaceTextToolset : McpToolset {

	@Serializable
	data class TextReplacement(
		val file_path: String,
		val old_text: String,
		val new_text: String,
		val replace_all: Boolean = true,
	)

	@McpTool
	@McpDescription(
		"""
        |Replaces text in multiple files or multiple text regions in one call.
        |Batch version of replace_text_in_file — saves round-trips when making several changes.
        |
        |Each replacement specifies: file_path, old_text, new_text, and optional replace_all.
        |Replacements are grouped by file and applied atomically per file using range markers,
        |so earlier replacements don't invalidate later ones even if they shift text.
        |
        |Returns a summary of replacements applied per file.
        |Fails if any replacement's old_text is not found in the target file.
    """
	)
	suspend fun batch_replace_text_in_file(
		@McpDescription("List of text replacements to apply")
		replacements: List<TextReplacement>,
	): Any {
		if (replacements.isEmpty()) mcpFail("replacements must not be empty")

		val project = currentCoroutineContext().project
		val byFile = replacements.groupBy { it.file_path }

		return buildString {
			for ((filePath, fileReplacements) in byFile) {
				val resolved = resolveFile(project, filePath)
				val document = resolved.document

				val marked = mutableListOf<Pair<RangeMarker, String>>()

				readAction {
					val text = document.text
					for (r in fileReplacements) {
						if (r.old_text.isEmpty()) mcpFail("$filePath: old_text must not be empty")

						var found = false
						var currentStart = 0
						while (true) {
							val idx = text.indexOf(r.old_text, currentStart)
							if (idx < 0) break
							found = true
							marked.add(document.createRangeMarker(idx, idx + r.old_text.length, true) to r.new_text)
							if (!r.replace_all) break
							currentStart = idx + r.old_text.length
						}

						if (!found) mcpFail("$filePath: no occurrences of \"${r.old_text.take(80)}\"")
					}
				}

				marked.sortByDescending { it.first.startOffset }

				withContext(Dispatchers.EDT) {
					WriteCommandAction.runWriteCommandAction(project) {
						for ((marker, newText) in marked) {
							if (!marker.isValid) continue
							document.replaceString(marker.startOffset, marker.endOffset, newText)
							marker.dispose()
						}
					}
					FileDocumentManager.getInstance().saveDocument(document)
				}

				append(filePath).append(": ").append(marked.size).appendLine(" replacement(s) applied")
			}
		}.trimEnd()
	}
}
