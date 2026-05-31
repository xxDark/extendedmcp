@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import dev.xdark.ijmcp.util.detectIndentation
import dev.xdark.ijmcp.util.resolveFilesByPattern
import kotlinx.coroutines.currentCoroutineContext

class BatchFileTextToolset : McpToolset {

	@McpTool
	@McpDescription(
		"""
        |Reads text content from multiple files in one call — batch version of read_file.
        |Drastically reduces MCP round-trips when many files are needed at once.
        |Returns numbered lines (1-indexed, "L<line>: ...") per file.
        |
        |Each entry in `patterns` is either a project-relative
        |file path or a standard glob pattern matched against project-relative paths.
        |
        |Glob semantics (consistent with find_files_by_glob):
        |  **/*.java         — all Java files anywhere in the project
        |  src/**/*.kt       — all Kotlin files under src/
        |  src/main/Foo.kt   — a single specific file (no glob chars)
        |  *.java            — Java files at the project root only (non-recursive)
        |
        |start_line and max_lines apply uniformly to every file.
        |For different windows per file, use separate tool calls.
        |
        |Binary files, files outside the project, missing literal paths, and unreadable files
        |are silently skipped.
    """
	)
	suspend fun read_files(
		@McpDescription("List of glob patterns or project-relative file paths.")
		patterns: List<String>,
		@McpDescription("1-based line number to start reading from in each file. Default 1.")
		start_line: Int = 1,
		@McpDescription("Maximum number of lines to return per file. Default 2000.")
		max_lines: Int = 2000,
	): Any {
		val project = currentCoroutineContext().project
		val tokens = patterns.map { it.trim() }.filter { it.isNotEmpty() }
		if (tokens.isEmpty()) mcpFail("No patterns provided.")
		if (start_line <= 0) mcpFail("start_line must be > 0.")
		if (max_lines <= 0) mcpFail("max_lines must be > 0.")

		val seen = HashSet<VirtualFile>()
		val entries = mutableListOf<Pair<String, VirtualFile>>()
		var hitMax = false
		var missingPaths = 0

		for (token in tokens) {
			if (hitMax) break
			val remaining = 50 - entries.size
			if (remaining <= 0) {
				hitMax = true
				break
			}
			try {
				val result = resolveFilesByPattern(project, token, max_files = remaining)
				for (entry in result.files) {
					if (seen.add(entry.virtualFile)) {
						entries.add(entry.relativePath to entry.virtualFile)
					}
				}
				missingPaths += result.missingPaths
				if (result.hitMax) hitMax = true
			} catch (_: Exception) {
				missingPaths++
			}
		}

		val sb = StringBuilder()
		var filesRead = 0
		var skipped = missingPaths

		for ((path, vf) in entries) {
			val mark = sb.length
			if (appendFileContent(sb, path, vf, start_line, max_lines)) {
				sb.appendLine()
				filesRead++
			} else {
				sb.setLength(mark)
				skipped++
			}
		}

		if (filesRead == 0) {
			return "No files read ($skipped skipped)"
		}

		sb.append("Read ").append(filesRead).append(" files")
		if (skipped > 0) sb.append(" (").append(skipped).append(" skipped)")
		if (hitMax) sb.append(" (hit resolution cap)")
		return sb.toString()
	}

	private suspend fun appendFileContent(
		sb: StringBuilder,
		path: String,
		vf: VirtualFile,
		startLine: Int,
		maxLines: Int
	): Boolean {
		return readAction {
			if (vf.fileType.isBinary) return@readAction false
			val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@readAction false
			val lineCount = document.lineCount
			if (lineCount == 0 || startLine > lineCount) return@readAction false
			val indent = detectIndentation(document)
			sb.append("=== ").append(path).append(" (").append(indent).appendLine(") ===")
			val endLine = (startLine + maxLines - 1).coerceAtMost(lineCount)
			val chars = document.immutableCharSequence
			for (lineNumber in startLine..endLine) {
				val lineIndex = lineNumber - 1
				sb.append('L').append(lineNumber).append(": ")
				sb.append(chars, document.getLineStartOffset(lineIndex), document.getLineEndOffset(lineIndex))
				sb.append('\n')
			}
			true
		}
	}
}
