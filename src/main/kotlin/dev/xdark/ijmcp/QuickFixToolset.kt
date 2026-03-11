@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightingSessionImpl
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.multiverse.defaultContext
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
import com.intellij.openapi.util.ProperTextRange
import com.intellij.psi.PsiManager
import com.intellij.util.DocumentUtil
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds

class QuickFixToolset : McpToolset {

    @Serializable
    data class FixInfo(
        val index: Int,
        val description: String,
        val problemDescription: String,
        val severity: String,
    )

    @Serializable
    data class QuickFixResult(
        val fixes: List<FixInfo>? = null,
        val applied: Boolean? = null,
        val fixDescription: String? = null,
    )

    @McpTool
    @McpDescription("""
        |Lists or applies quick fixes (intentions) at a specific location in a file.
        |
        |Quick fixes are IDE-suggested corrections for problems like unused imports, type mismatches,
        |missing overrides, etc. — the same fixes shown when hovering over a warning/error in the IDE.
        |
        |With fixIndex=-1 (default): lists all available fixes at the location.
        |With fixIndex>=0: applies the fix at that index.
        |
        |Typical workflow: first call with fixIndex=-1 to see available fixes, then call again with the desired fixIndex.
    """)
    suspend fun apply_quick_fix(
        @McpDescription("Path relative to the project root") filePath: String,
        @McpDescription("1-based line number") line: Int,
        @McpDescription("1-based column number") column: Int,
        @McpDescription("-1 to list available fixes, >= 0 to apply a fix at that index") fixIndex: Int = -1,
        @McpDescription("Timeout in milliseconds for analysis") timeout: Int = 10000,
    ): QuickFixResult {
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

        // Run highlighting passes to get HighlightInfo (same pattern as AnalysisToolset)
        val collectedInfos = mutableListOf<HighlightInfo>()
        withTimeoutOrNull(timeout.milliseconds) {
            coroutineScope {
                val psiFile = readAction { PsiManager.getInstance(project).findFile(resolved.virtualFile) }
                    ?: mcpFail("Cannot find PSI file: $filePath")
                val daemonIndicator = DaemonProgressIndicator()
                val range = ProperTextRange(0, resolved.document.textLength)

                jobToIndicator(coroutineContext.job, daemonIndicator) {
                    HighlightingSessionImpl.runInsideHighlightingSession(psiFile, defaultContext(), null, range, false) { _ ->
                        val codeAnalyzer = DaemonCodeAnalyzer.getInstance(project) as DaemonCodeAnalyzerImpl
                        val infos = codeAnalyzer.runMainPasses(psiFile, resolved.document, daemonIndicator)
                        collectedInfos.addAll(infos)
                    }
                }
            }
        }
        val highlightInfos: List<HighlightInfo> = collectedInfos

        // Collect quick fixes at offset
        val fixActions: List<Pair<IntentionAction, HighlightInfo>> = readAction {
            highlightInfos.filter { info: HighlightInfo ->
                info.startOffset <= offset && offset <= info.endOffset
            }.flatMap { info: HighlightInfo ->
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
            val fixes = fixActions.mapIndexed { index, pair ->
                val action = pair.first
                val info = pair.second
                FixInfo(
                    index = index,
                    description = action.text,
                    problemDescription = info.description ?: "",
                    severity = info.severity.name,
                )
            }
            return QuickFixResult(fixes = fixes)
        }

        if (fixIndex < 0 || fixIndex >= fixActions.size) {
            mcpFail("fixIndex $fixIndex is out of range (0..${fixActions.size - 1})")
        }

        val actionPair = fixActions[fixIndex]
        val action = actionPair.first
        val description = action.text

        withContext(Dispatchers.EDT) {
            writeIntentReadAction {
                val editor = EditorFactory.getInstance().createEditor(resolved.document)
                try {
                    editor.caretModel.moveToOffset(offset)
                    action.invoke(project, editor, resolved.psiFile)
                } finally {
                    EditorFactory.getInstance().releaseEditor(editor)
                }
            }
        }

        withContext(Dispatchers.EDT) {
            FileDocumentManager.getInstance().saveDocument(resolved.document)
        }

        return QuickFixResult(applied = true, fixDescription = description)
    }
}
