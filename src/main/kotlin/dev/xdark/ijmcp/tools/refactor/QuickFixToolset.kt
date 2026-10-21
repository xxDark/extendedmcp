@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp.tools.refactor

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.MainPassesRunner
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler
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
import com.intellij.openapi.progress.jobToIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.util.DocumentUtil
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.*

class QuickFixToolset : McpToolset {

	data class FixInfo(
		val index: Int,
		val description: String,
		val problemDescription: String,
		val severity: String,
	)

	@McpTool
	@McpDescription(
		"""
        |Lists or applies quick fixes (intentions) at a specific location in a file.
        |
        |Quick fixes are IDE-suggested corrections for problems like unused imports, type mismatches,
        |missing overrides, etc. — the same fixes shown when hovering over a warning/error in the IDE.
        |
        |With fixIndex=-1 (default): lists all available fixes at the location.
        |With fixIndex>=0: applies the fix at that index.
        |
        |Typical workflow: first call with fixIndex=-1 to see available fixes, then call again with the desired fixIndex.
    """
	)
	suspend fun apply_quick_fix(
		@McpDescription("Path relative to the project root") filePath: String,
		@McpDescription("1-based line number") line: Int,
		@McpDescription("1-based column number") column: Int,
		@McpDescription("-1 to list available fixes, >= 0 to apply a fix at that index") fixIndex: Int = -1,
		@McpDescription("Timeout in milliseconds for analysis") timeout: Int = 10000,
	): Any {
		val project = currentCoroutineContext().project

		val resolved = resolveFile(project, filePath)

		val offset = readAction {
			if (!DocumentUtil.isValidLine(line - 1, resolved.document)) {
				mcpFail("Line $line is out of bounds")
			}
			val lineStart = resolved.document.getLineStartOffset(line - 1)
			val off = lineStart + column - 1
			if (!DocumentUtil.isValidOffset(off, resolved.document)) {
				mcpFail("Position $line:$column is out of bounds")
			}
			off
		}

		// MainPassesRunner yields to write actions; a bare runMainPasses holds a blocking read lock and freezes the UI
		val highlightInfos: List<HighlightInfo> = withTimeoutOrNull(timeout.toLong()) {
			jobToIndicator(coroutineContext.job, ProgressIndicatorBase()) {
				MainPassesRunner(project, "", null).runMainPasses(listOf(resolved.virtualFile))
			}.values.firstOrNull()
		}.orEmpty()

		// Collect quick fixes at offset. Passes run in parallel and report infos in a different order on
		// every call, so sort them: fixIndex must point at the fix the previous listing showed.
		val fixActions: List<Pair<IntentionAction, HighlightInfo>> = readAction {
			highlightInfos.filter { info: HighlightInfo ->
				info.startOffset <= offset && offset <= info.endOffset
			}.sortedWith(
				compareBy<HighlightInfo>({ it.startOffset }, { it.endOffset }, { it.description })
			).flatMap { info: HighlightInfo ->
				val fixes = mutableListOf<Pair<IntentionAction, HighlightInfo>>()
				info.findRegisteredQuickFix { descriptor, range ->
					val action = descriptor.action
					if (action.isAvailable(project, null, resolved.psiFile)) {
						fixes.add(action to info)
					}
				}
				fixes
			}
		}

		if (fixIndex == -1) {
			if (fixActions.isEmpty()) {
				return "No quick fixes available at $line:$column"
			}
			return buildString {
				append(fixActions.size).append(" quick fixes available at ").append(line).append(':').append(column)
					.appendLine(":")
				appendLine()
				for ((index, pair) in fixActions.withIndex()) {
					val action = pair.first
					val info = pair.second
					append("  ").append(index).append(": ").appendLine(action.text)
					if (!info.description.isNullOrEmpty()) {
						append("     problem: ").append(info.description).append(" [").append(info.severity.name)
							.appendLine("]")
					}
				}
			}.trimEnd()
		}

		if (fixIndex < 0 || fixIndex >= fixActions.size) {
			mcpFail("fixIndex $fixIndex is out of range (0..${fixActions.size - 1})")
		}

		val actionPair = fixActions[fixIndex]
		val action = actionPair.first
		val description = action.text

		val applied = withContext(Dispatchers.EDT) {
			writeIntentReadAction {
				val editor = EditorFactory.getInstance().createEditor(resolved.document, project)
				try {
					editor.caretModel.moveToOffset(offset)
					// Wraps the fix in a command and, when it asks for one, a write action — as the IDE does
					ShowIntentionActionsHandler.chooseActionAndInvoke(resolved.psiFile, editor, action, description)
				} finally {
					EditorFactory.getInstance().releaseEditor(editor)
				}
			}
		}
		if (!applied) {
			mcpFail("Fix '$description' is not available at $line:$column anymore")
		}

		withContext(Dispatchers.EDT) {
			FileDocumentManager.getInstance().saveDocument(resolved.document)
		}

		return "Applied fix: $description"
	}
}
