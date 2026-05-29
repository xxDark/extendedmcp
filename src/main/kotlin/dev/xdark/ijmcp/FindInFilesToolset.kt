@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.find.FindModel
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.mcpserver.util.projectDirectory
import com.intellij.mcpserver.util.relativizeIfPossible
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.usages.FindUsagesProcessPresentation
import com.intellij.usages.UsageViewPresentation
import com.intellij.util.Processor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class FindInFilesToolset : McpToolset {

    data class MatchLocation(
        val file: String,
        val line: Int,
        val column: Int,
        val match: String,
        val context: String,
    )

    @McpTool
    @McpDescription(
        """
        |Searches for text or regex patterns across project files using IntelliJ's Find in Files engine.
        |Supports whole words, search context filtering (comments/strings), module scope, and file masks.
        |
        |Scope priority: directory > module_name > scope parameter.
        |Search context values: "any" (default), "inComments", "inStrings", "exceptComments", "exceptStrings", "exceptBoth"
    """
    )
    suspend fun find_in_files(
        @McpDescription("Text or regex pattern to search for") search_text: String,
        @McpDescription("Treat search_text as a regular expression (default false)") is_regex: Boolean = false,
        @McpDescription("Case-sensitive search (default false)") case_sensitive: Boolean = false,
        @McpDescription("Match whole words only (default false)") whole_words: Boolean = false,
        @McpDescription("File name filter, e.g. '*.java' or '*.kt,*.java' (default: all files)") file_mask: String = "",
        @McpDescription("Directory to search in, relative to project root") directory: String = "",
        @McpDescription("Module name to search within") module_name: String = "",
        @McpDescription("Search scope: 'project' (default) or 'all' (includes libraries)") scope: String = "project",
        @McpDescription("Where to search: 'any', 'inComments', 'inStrings', 'exceptComments', 'exceptStrings', 'exceptBoth'") context: String = "any",
        @McpDescription("Maximum number of matches to return (default 100)") max_results: Int = 100,
    ): Any {
        val project = currentCoroutineContext().project

        if (search_text.isEmpty()) {
            mcpFail("search_text must not be empty")
        }

        // Validate module if specified
        if (module_name.isNotEmpty()) {
            val exists = readAction {
                ModuleManager.getInstance(project).findModuleByName(module_name) != null
            }
            if (!exists) mcpFail("Module '$module_name' not found")
        }

        // Resolve directory if specified
        val resolvedDir = if (directory.isNotEmpty()) {
            val basePath = project.basePath ?: mcpFail("No project base path")
            val dirPath = java.nio.file.Path.of(basePath, directory)
            if (!Files.isDirectory(dirPath)) {
                mcpFail("Directory not found: $directory")
            }
            dirPath.toString()
        } else null

        val findModel = FindModel().apply {
            stringToFind = search_text
            isRegularExpressions = is_regex
            isCaseSensitive = case_sensitive
            isWholeWordsOnly = whole_words
            isMultipleFiles = true
            isFindAll = true
            isFindAllEnabled = true

            if (file_mask.isNotEmpty()) {
                fileFilter = file_mask
            }

            // Scope configuration (priority: directory > module > scope param)
            when {
                resolvedDir != null -> {
                    directoryName = resolvedDir
                    isWithSubdirectories = true
                    isProjectScope = false
                }

                module_name.isNotEmpty() -> {
                    this.moduleName = module_name
                    isProjectScope = false
                }

                scope == "all" -> {
                    isProjectScope = false
                    isCustomScope = true
                    customScope = GlobalSearchScope.allScope(project)
                    customScopeName = "All Places"
                }

                else -> {
                    isProjectScope = true
                }
            }

            searchContext = when (context) {
                "inComments" -> FindModel.SearchContext.IN_COMMENTS
                "inStrings" -> FindModel.SearchContext.IN_STRING_LITERALS
                "exceptComments" -> FindModel.SearchContext.EXCEPT_COMMENTS
                "exceptStrings" -> FindModel.SearchContext.EXCEPT_STRING_LITERALS
                "exceptBoth" -> FindModel.SearchContext.EXCEPT_COMMENTS_AND_STRING_LITERALS
                else -> FindModel.SearchContext.ANY
            }
        }

        // Thread-safe collection — FindInProjectTask invokes the processor from multiple threads
        val matches = Collections.synchronizedList(mutableListOf<MatchLocation>())
        val truncated = AtomicBoolean(false)
        val presentation = FindUsagesProcessPresentation(UsageViewPresentation())

        withContext(Dispatchers.Default) {
            ProgressManager.getInstance().runProcess({
                FindInProjectUtil.findUsages(findModel, project, Processor { usageInfo ->
                    if (truncated.get()) return@Processor false
                    val match = extractMatch(project, usageInfo) ?: return@Processor true
                    matches.add(match)
                    if (matches.size >= max_results) {
                        truncated.set(true)
                        false
                    } else {
                        true
                    }
                }, presentation)
            }, EmptyProgressIndicator())
        }

        if (matches.isEmpty()) {
            return "No matches found for '$search_text'"
        }

        val truncatedFlag = truncated.get()
        return buildString {
            append(matches.size).append(" matches for '").append(search_text).append('\'')
            if (truncatedFlag) append(" (truncated)")
            appendLine(":")
            appendLine()
            val byFile = matches.groupBy { it.file }
            for ((file, fileMatches) in byFile) {
                appendLine(file)
                for (m in fileMatches) {
                    append("  ").append(m.line).append(':').append(m.column).append(' ').appendLine(m.context)
                }
                appendLine()
            }
        }.trimEnd()
    }

    private fun extractMatch(project: Project, usageInfo: UsageInfo): MatchLocation? {
        val virtualFile = usageInfo.virtualFile ?: return null
        val segment = usageInfo.segment ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null

        val startOffset = segment.startOffset
        val endOffset = segment.endOffset
        if (startOffset < 0 || endOffset > document.textLength) return null

        val lineNumber = document.getLineNumber(startOffset)
        val column = startOffset - document.getLineStartOffset(lineNumber)

        val matchedText = document.getText(TextRange(startOffset, endOffset))
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)
        val contextLine = document.getText(TextRange(lineStart, lineEnd)).trim()

        val relativePath = relativizePath(project, virtualFile)

        return MatchLocation(
            file = relativePath,
            line = lineNumber + 1,
            column = column + 1,
            match = matchedText,
            context = contextLine,
        )
    }

    private fun relativizePath(project: Project, file: VirtualFile): String {
        return try {
            project.projectDirectory.relativizeIfPossible(file)
        } catch (_: IllegalArgumentException) {
            // Windows: paths on different drives can't be relativized
            file.path
        }
    }
}
