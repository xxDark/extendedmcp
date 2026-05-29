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
import dev.xdark.ijmcp.util.resolveFilesByPattern
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

class BatchFileTextToolset : McpToolset {

    @Serializable
    data class FileContent(
        val file_path: String,
        val content: String,
    )

    @Serializable
    data class BatchFileTextResult(
        val files: List<FileContent>,
        val filesRead: Int,
        val filesSkipped: Int,
        val hitMaxFiles: Boolean = false,
    )

    @McpTool
    @McpDescription(
        """
        |Reads text content from multiple files in one call — batch version of get_file_text_by_path.
        |Drastically reduces MCP round-trips when many files are needed at once.
        |
        |`patterns` is a semicolon-separated list; each entry is either a project-relative
        |file path or a standard glob pattern matched against project-relative paths.
        |
        |Glob semantics (consistent with find_files_by_glob):
        |  **/*.java         — all Java files anywhere in the project
        |  src/**/*.kt       — all Kotlin files under src/
        |  src/main/Foo.kt   — a single specific file (no glob chars)
        |  *.java            — Java files at the project root only (non-recursive)
        |
        |Binary files, files outside the project, missing literal paths, and unreadable files
        |are silently skipped and counted in filesSkipped. The total number of files examined
        |is capped at max_files; hitMaxFiles=true indicates the cap was reached during iteration.
    """
    )
    suspend fun read_files(
        @McpDescription("Semicolon-separated list of glob patterns or project-relative file paths.")
        patterns: String,
        @McpDescription("Maximum total number of files to examine. Default 50.")
        max_files: Int = 50,
    ): BatchFileTextResult {
        val project = currentCoroutineContext().project
        val tokens = patterns.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) mcpFail("No patterns provided.")
        if (max_files <= 0) mcpFail("max_files must be positive.")

        val seen = LinkedHashSet<VirtualFile>()
        val entries = mutableListOf<Pair<String, VirtualFile>>()
        var hitMax = false
        var missingPaths = 0

        for (token in tokens) {
            if (hitMax) break
            val remaining = max_files - entries.size
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

        val files = mutableListOf<FileContent>()
        var skipped = missingPaths
        for ((path, vf) in entries) {
            val content = readContent(vf)
            if (content == null) {
                skipped++
            } else {
                files.add(FileContent(path, content))
            }
        }

        return BatchFileTextResult(
            files = files,
            filesRead = files.size,
            filesSkipped = skipped,
            hitMaxFiles = hitMax,
        )
    }

    private suspend fun readContent(vf: VirtualFile): String? {
        return readAction {
            if (vf.fileType.isBinary) return@readAction null
            FileDocumentManager.getInstance().getDocument(vf)?.text
        }
    }
}
