@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.mcpserver.util.projectDirectory
import com.intellij.mcpserver.util.relativizeIfPossible
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths

class BatchFileTextToolset : McpToolset {

    @Serializable
    data class FileContent(
        val filePath: String,
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
        |is capped at maxFiles; hitMaxFiles=true indicates the cap was reached during iteration.
    """
    )
    suspend fun batch_get_file_text(
        @McpDescription("Semicolon-separated list of glob patterns or project-relative file paths.")
        patterns: String,
        @McpDescription("Maximum total number of files to examine. Default 50.")
        maxFiles: Int = 50,
    ): BatchFileTextResult {
        val project = currentCoroutineContext().project
        val tokens = patterns.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) mcpFail("No patterns provided.")
        if (maxFiles <= 0) mcpFail("maxFiles must be positive.")

        // Pre-compile globs up front so invalid patterns fail fast (avoids mcpFail inside readAction).
        val compiled: List<Pair<String, PathMatcher?>> = tokens.map { token ->
            val matcher: PathMatcher? = if (token.any { it in "*?[{" }) {
                try {
                    FileSystems.getDefault().getPathMatcher("glob:$token")
                } catch (e: Exception) {
                    mcpFail("Invalid glob pattern '$token': ${e.message}")
                }
            } else null
            token to matcher
        }

        val collected = readAction {
            collectMatchingFiles(project, compiled, maxFiles)
        }

        val files = mutableListOf<FileContent>()
        var skipped = collected.missingLiteralPaths
        for (entry in collected.entries) {
            val content = readContent(entry.virtualFile)
            if (content == null) {
                skipped++
            } else {
                files.add(FileContent(entry.relativePath, content))
            }
        }

        return BatchFileTextResult(
            files = files,
            filesRead = files.size,
            filesSkipped = skipped,
            hitMaxFiles = collected.hitMax,
        )
    }

    private data class CollectedFile(val relativePath: String, val virtualFile: VirtualFile)
    private data class CollectResult(
        val entries: List<CollectedFile>,
        val hitMax: Boolean,
        val missingLiteralPaths: Int,
    )

    private fun collectMatchingFiles(
        project: Project,
        compiled: List<Pair<String, PathMatcher?>>,
        maxFiles: Int,
    ): CollectResult {
        val projectDir = project.projectDirectory
        val fileIndex = ProjectFileIndex.getInstance(project)
        val seen = LinkedHashMap<VirtualFile, String>()
        var hitMax = false
        var missingLiteralPaths = 0

        fun relativeOf(vf: VirtualFile): String? {
            val rel = try {
                projectDir.relativizeIfPossible(vf)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (rel.startsWith("..") || rel.contains(".jar!")) return null
            return rel
        }

        // Returns true to continue iteration, false to stop (cap reached).
        fun tryAdd(vf: VirtualFile): Boolean {
            if (!vf.isValid || vf.isDirectory) return true
            if (seen.containsKey(vf)) return true
            val rel = relativeOf(vf) ?: return true
            seen[vf] = rel
            if (seen.size >= maxFiles) {
                hitMax = true
                return false
            }
            return true
        }

        for ((token, matcher) in compiled) {
            if (hitMax) break
            if (matcher == null) {
                // Literal path — resolve relative to project root, reject escapes.
                val resolved: Path = try {
                    projectDir.resolve(token).normalize()
                } catch (_: Exception) {
                    missingLiteralPaths++
                    continue
                }
                if (!resolved.startsWith(projectDir)) {
                    missingLiteralPaths++
                    continue
                }
                val vf = LocalFileSystem.getInstance().findFileByNioFile(resolved)
                if (vf == null || !vf.isValid || vf.isDirectory) {
                    missingLiteralPaths++
                    continue
                }
                if (!tryAdd(vf)) break
            } else {
                // Glob — walk project content, match each file's project-relative path.
                fileIndex.iterateContent(ContentIterator { vf ->
                    if (vf.isDirectory) return@ContentIterator true
                    val rel = relativeOf(vf) ?: return@ContentIterator true
                    val path = try {
                        Paths.get(rel)
                    } catch (_: Exception) {
                        return@ContentIterator true
                    }
                    if (matcher.matches(path)) {
                        if (!tryAdd(vf)) return@ContentIterator false
                    }
                    true
                })
            }
        }

        return CollectResult(
            entries = seen.entries.map { CollectedFile(it.value, it.key) },
            hitMax = hitMax,
            missingLiteralPaths = missingLiteralPaths,
        )
    }

    private suspend fun readContent(vf: VirtualFile): String? {
        return readAction {
            if (vf.fileType.isBinary) return@readAction null
            FileDocumentManager.getInstance().getDocument(vf)?.text
        }
    }
}
