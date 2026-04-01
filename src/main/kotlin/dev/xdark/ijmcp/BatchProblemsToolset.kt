@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightingSessionImpl
import com.intellij.codeInsight.multiverse.defaultContext
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.mcpserver.util.awaitExternalChangesAndIndexing
import com.intellij.mcpserver.util.projectDirectory
import com.intellij.mcpserver.util.relativizeIfPossible
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.jobToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ProperTextRange
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

class BatchProblemsToolset : McpToolset {

    @Serializable
    data class Problem(
        val severity: String,
        val description: String,
        val line: Int,
        val column: Int,
        val lineContent: String,
    )

    @Serializable
    data class FileProblems(
        val filePath: String,
        val problems: List<Problem>,
    )

    @Serializable
    data class BatchFileProblemsResult(
        val files: List<FileProblems>,
        val totalProblems: Int,
        val filesAnalyzed: Int,
        val timedOut: Boolean = false,
    )

    @McpTool
    @McpDescription(
        """
        |Analyzes multiple files for errors and warnings using IntelliJ's inspections.
        |Batch version of get_file_problems — saves tokens by checking many files in one call.
        |
        |Pass specific files via semicolon-separated filePaths, or leave empty to check all open editor files.
        |Files with no problems are omitted from the results.
        |Note: Lines and Columns are 1-based.
    """
    )
    suspend fun get_batch_file_problems(
        @McpDescription("Semicolon-separated file paths relative to project root. Empty = all open editor files.")
        filePaths: String = "",
        @McpDescription("Whether to include only errors or include both errors and warnings")
        errorsOnly: Boolean = true,
        @McpDescription("Total timeout in milliseconds")
        timeout: Int = 30000,
    ): BatchFileProblemsResult {
        val project = currentCoroutineContext().project

        val filesToAnalyze: List<FileEntry> = if (filePaths.isNotBlank()) {
            resolveSpecificFiles(project, filePaths)
        } else {
            resolveOpenFiles(project)
        }

        if (filesToAnalyze.isEmpty()) {
            mcpFail("No files to analyze. Specify filePaths or open files in the editor.")
        }

        awaitExternalChangesAndIndexing(project)

        val minSeverity = if (errorsOnly) HighlightSeverity.ERROR else HighlightSeverity.WEAK_WARNING
        val results = mutableListOf<FileProblems>()
        var filesAnalyzed = 0

        val timedOut = withTimeoutOrNull(timeout.toLong()) {
            for (entry in filesToAnalyze) {
                val problems = analyzeFile(project, entry, minSeverity)
                filesAnalyzed++
                if (problems.isNotEmpty()) {
                    results.add(FileProblems(filePath = entry.relativePath, problems = problems))
                }
            }
        } == null

        val totalProblems = results.sumOf { it.problems.size }

        return BatchFileProblemsResult(
            files = results,
            totalProblems = totalProblems,
            filesAnalyzed = filesAnalyzed,
            timedOut = timedOut,
        )
    }

    private data class FileEntry(
        val relativePath: String,
        val virtualFile: VirtualFile,
    )

    private suspend fun resolveSpecificFiles(project: Project, filePaths: String): List<FileEntry> {
        val paths = filePaths.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        return paths.mapNotNull { path ->
            try {
                val resolved = resolveFile(project, path)
                FileEntry(path, resolved.virtualFile)
            } catch (_: Exception) {
                null // Skip files that can't be resolved
            }
        }
    }

    private suspend fun resolveOpenFiles(project: Project): List<FileEntry> {
        return readAction {
            val projectDir = project.projectDirectory
            FileEditorManager.getInstance(project).openFiles
                .filter { it.isValid && !it.isDirectory }
                .mapNotNull { vf ->
                    val relativePath = try {
                        projectDir.relativizeIfPossible(vf)
                    } catch (_: IllegalArgumentException) {
                        return@mapNotNull null // Skip files outside project (different drive, etc.)
                    }
                    // Skip library/external files
                    if (relativePath.startsWith("..") || relativePath.contains(".jar!")) {
                        return@mapNotNull null
                    }
                    FileEntry(relativePath, vf)
                }
        }
    }

    private suspend fun analyzeFile(
        project: Project,
        entry: FileEntry,
        minSeverity: HighlightSeverity,
    ): List<Problem> {
        val psiFile: PsiFile = readAction {
            PsiManager.getInstance(project).findFile(entry.virtualFile)
        } ?: return emptyList()

        val document: Document = readAction {
            FileDocumentManager.getInstance().getDocument(entry.virtualFile)
        } ?: return emptyList()

        val collectedInfos = mutableListOf<HighlightInfo>()
        val daemonIndicator = DaemonProgressIndicator()
        val range = readAction { ProperTextRange(0, document.textLength) }

        jobToIndicator(currentCoroutineContext().job, daemonIndicator) {
            HighlightingSessionImpl.runInsideHighlightingSession(
                psiFile, defaultContext(), null, range, false
            ) { session ->
                (session as HighlightingSessionImpl).setMinimumSeverity(minSeverity)
                val codeAnalyzer = DaemonCodeAnalyzer.getInstance(project) as DaemonCodeAnalyzerImpl
                val infos = codeAnalyzer.runMainPasses(psiFile, document, daemonIndicator)
                collectedInfos.addAll(infos)
            }
        }

        return readAction {
            collectedInfos.mapNotNull { info ->
                if (info.description != null && info.severity.myVal >= minSeverity.myVal) {
                    createProblem(document, info)
                } else {
                    null
                }
            }
        }
    }

    private fun createProblem(document: Document, info: HighlightInfo): Problem? {
        val startOffset = info.startOffset
        if (startOffset < 0 || startOffset >= document.textLength) return null

        val lineNumber = document.getLineNumber(startOffset)
        val column = startOffset - document.getLineStartOffset(lineNumber)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)
        val lineContent = document.getText(TextRange(lineStart, lineEnd)).trim()

        return Problem(
            severity = info.severity.name,
            description = info.description!!,
            line = lineNumber + 1,
            column = column + 1,
            lineContent = lineContent,
        )
    }
}
