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

private fun findMismatchDiagnostic(documentText: String, searchText: String): String {
	val anchorLen = minOf(20, searchText.length)
	val anchor = searchText.substring(0, anchorLen)
	var bestMatchLen = 0
	var bestOffset = -1
	var start = 0
	while (true) {
		val idx = documentText.indexOf(anchor, start)
		if (idx < 0) break
		var matchLen = anchorLen
		val limit = minOf(searchText.length, documentText.length - idx)
		while (matchLen < limit && searchText[matchLen] == documentText[idx + matchLen]) matchLen++
		if (matchLen > bestMatchLen) {
			bestMatchLen = matchLen
			bestOffset = idx
		}
		start = idx + 1
	}
	if (bestOffset < 0 || bestMatchLen >= searchText.length) {
		return "no occurrences of \"${searchText.take(80)}\""
	}
	val line = documentText.substring(0, bestOffset + bestMatchLen).count { it == '\n' } + 1
	val exp = searchText.substring(bestMatchLen, minOf(bestMatchLen + 20, searchText.length)).escapeWhitespace()
	val act = documentText.substring(bestOffset + bestMatchLen, minOf(bestOffset + bestMatchLen + 20, documentText.length)).escapeWhitespace()
	return "no exact match (best candidate at line $line, diverges at offset $bestMatchLen/${searchText.length}): expected \"$exp\" but found \"$act\""
}

private fun String.escapeWhitespace() = replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

private data class NormalizedMatch(val start: Int, val end: Int, val adjustedNewText: String)

private fun findNormalizedMatch(
	documentText: String,
	searchText: String,
	newText: String,
	startFrom: Int,
): NormalizedMatch? {
	val endsWithNewline = searchText.endsWith('\n')
	val searchLines = (if (endsWithNewline) searchText.dropLast(1) else searchText).split('\n')
	if (searchLines.isEmpty()) return null

	val strippedSearch = searchLines.map { it.trimStart() }
	val docLines = documentText.split('\n')

	val lineOffsets = IntArray(docLines.size)
	var off = 0
	for (i in docLines.indices) {
		lineOffsets[i] = off
		off += docLines[i].length + 1
	}

	val startLineIdx = if (startFrom == 0) 0 else lineOffsets.indexOfLast { it <= startFrom }.coerceAtLeast(0)

	outer@ for (i in startLineIdx..docLines.size - searchLines.size) {
		for (j in searchLines.indices) {
			if (docLines[i + j].trimStart() != strippedSearch[j]) continue@outer
		}

		val matchStart = lineOffsets[i]
		val lastIdx = i + searchLines.size - 1
		val matchEnd = if (endsWithNewline && lastIdx + 1 < docLines.size) {
			lineOffsets[lastIdx + 1]
		} else {
			lineOffsets[lastIdx] + docLines[lastIdx].length
		}

		val nonBlankPairs = searchLines.withIndex().filter { it.value.isNotBlank() }
		val adjustedNewText = if (nonBlankPairs.isNotEmpty()) {
			val minEntry = nonBlankPairs.minBy { it.value.length - it.value.trimStart().length }
			val searchPrefix = minEntry.value.takeWhile { it.isWhitespace() }
			val docPrefix = docLines[i + minEntry.index].takeWhile { it.isWhitespace() }
			if (searchPrefix != docPrefix) {
				val newEndsWithNewline = newText.endsWith('\n')
				val newLines = (if (newEndsWithNewline) newText.dropLast(1) else newText).split('\n')
				val adjusted = newLines.joinToString("\n") { line ->
					if (line.startsWith(searchPrefix)) {
						docPrefix + line.removePrefix(searchPrefix)
					} else line
				}
				if (newEndsWithNewline) adjusted + '\n' else adjusted
			} else newText
		} else newText

		return NormalizedMatch(matchStart, matchEnd, adjustedNewText)
	}
	return null
}

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
							if (idx < 0) {
								val normalized = findNormalizedMatch(text, r.old_text, r.new_text, currentStart)
								if (normalized != null) {
									found = true
									marked.add(document.createRangeMarker(normalized.start, normalized.end, true) to normalized.adjustedNewText)
									if (!r.replace_all) break
									currentStart = normalized.end
									continue
								}
								break
							}
							found = true
							marked.add(document.createRangeMarker(idx, idx + r.old_text.length, true) to r.new_text)
							if (!r.replace_all) break
							currentStart = idx + r.old_text.length
						}

						if (!found) mcpFail("$filePath: ${findMismatchDiagnostic(text, r.old_text)}")
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
