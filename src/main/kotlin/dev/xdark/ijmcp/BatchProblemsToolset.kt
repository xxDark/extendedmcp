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
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.jobToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ProperTextRange
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import dev.xdark.ijmcp.util.PsiFileEntry
import dev.xdark.ijmcp.util.ResolvedFileEntry
import dev.xdark.ijmcp.util.resolvePsi
import dev.xdark.ijmcp.util.resolveFilesByPattern
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull

class BatchProblemsToolset : McpToolset {

    data class Problem(
        val severity: String,
        val description: String,
        val line: Int,
        val column: Int,
        val lineContent: String,
    )

    data class FileProblems(
        val file_path: String,
        val problems: List<Problem>,
    )

    @McpTool
    @McpDescription(
        """
        |Analyzes files for errors and warnings using IntelliJ's inspections.
        |Batch version of get_file_problems — saves tokens by checking many files in one call.
        |
        |file_paths accepts semicolon-separated file paths or glob patterns (e.g. "src/**/*.java").
        |Leave empty to check all open editor files.
        |Files with no problems are omitted from the results.
        |Note: Lines and Columns are 1-based.
    """
    )
    suspend fun get_file_problems(
        @McpDescription("Semicolon-separated file paths or glob patterns. Empty = all open editor files.")
        file_paths: String = "",
        @McpDescription("Whether to include only errors or include both errors and warnings")
        errors_only: Boolean = true,
        @McpDescription("Total timeout in milliseconds")
        timeout: Int = 30000,
    ): Any {
        val project = currentCoroutineContext().project

        val entries: List<ResolvedFileEntry> = if (file_paths.isNotBlank()) {
            resolvePatterns(project, file_paths)
        } else {
            resolveOpenFiles(project)
        }

        if (entries.isEmpty()) {
            mcpFail("No files to analyze. Specify file_paths or open files in the editor.")
        }

        val filesToAnalyze = entries.resolvePsi(project)

        awaitExternalChangesAndIndexing(project)

        val minSeverity = if (errors_only) HighlightSeverity.ERROR else HighlightSeverity.WEAK_WARNING
        val results = mutableListOf<FileProblems>()
        var filesAnalyzed = 0

        val timedOut = withTimeoutOrNull(timeout.toLong()) {
            for (entry in filesToAnalyze) {
                val problems = analyzeFile(project, entry, minSeverity)
                filesAnalyzed++
                if (problems.isNotEmpty()) {
                    results.add(FileProblems(file_path = entry.relativePath, problems = problems))
                }
            }
        } == null

        val totalProblems = results.sumOf { it.problems.size }

        if (totalProblems == 0) {
            return buildString {
                append("No problems found (").append(filesAnalyzed).append(" files analyzed)")
                if (timedOut) append(" (timed out)")
            }
        }

        return buildString {
            append("Analyzed ").append(filesAnalyzed).append(" files, ").append(totalProblems).append(" problems found")
            if (timedOut) append(" (timed out)")
            appendLine(":")
            appendLine()
            for (fp in results) {
                append(fp.file_path).appendLine(":")
                for (p in fp.problems) {
                    append("  ").append(p.line).append(':').append(p.column).append(' ').append(p.severity).append(": ")
                        .appendLine(p.description)
                    append("    ").appendLine(p.lineContent)
                }
                appendLine()
            }
        }.trimEnd()
    }

    private suspend fun resolvePatterns(project: Project, file_paths: String): List<ResolvedFileEntry> {
        val tokens = file_paths.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        val seen = LinkedHashSet<VirtualFile>()
        val result = mutableListOf<ResolvedFileEntry>()

        for (token in tokens) {
            val resolved = resolveFilesByPattern(project, token)
            for (entry in resolved.files) {
                if (seen.add(entry.virtualFile)) {
                    result.add(entry)
                }
            }
        }
        return result
    }

    private suspend fun resolveOpenFiles(project: Project): List<ResolvedFileEntry> {
        return readAction {
            val projectDir = project.projectDirectory
            FileEditorManager.getInstance(project).openFiles
                .filter { it.isValid && !it.isDirectory }
                .mapNotNull { vf ->
                    val relativePath = try {
                        projectDir.relativizeIfPossible(vf)
                    } catch (_: IllegalArgumentException) {
                        return@mapNotNull null
                    }
                    if (relativePath.startsWith("..") || relativePath.contains(".jar!")) {
                        return@mapNotNull null
                    }
                    ResolvedFileEntry(relativePath, vf)
                }
        }
    }

    private suspend fun analyzeFile(
        project: Project,
        entry: PsiFileEntry,
        minSeverity: HighlightSeverity,
    ): List<Problem> {
        val collectedInfos = mutableListOf<HighlightInfo>()
        val daemonIndicator = DaemonProgressIndicator()
        val range = readAction { ProperTextRange(0, entry.document.textLength) }

        jobToIndicator(currentCoroutineContext().job, daemonIndicator) {
            HighlightingSessionImpl.runInsideHighlightingSession(
                entry.psiFile, defaultContext(), null, range, false
            ) { session ->
                (session as HighlightingSessionImpl).setMinimumSeverity(minSeverity)
                val codeAnalyzer = DaemonCodeAnalyzer.getInstance(project) as DaemonCodeAnalyzerImpl
                val infos = codeAnalyzer.runMainPasses(entry.psiFile, entry.document, daemonIndicator)
                collectedInfos.addAll(infos)
            }
        }

        return readAction {
            collectedInfos.mapNotNull { info ->
                if (info.description != null && info.severity.myVal >= minSeverity.myVal) {
                    createProblem(entry.document, info)
                } else {
                    null
                }
            }
        }
    }

    private fun createProblem(document: com.intellij.openapi.editor.Document, info: HighlightInfo): Problem? {
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
