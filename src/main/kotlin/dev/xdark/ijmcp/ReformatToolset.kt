@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import dev.xdark.ijmcp.util.resolveFilesByPattern
import dev.xdark.ijmcp.util.resolvePsi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
class ReformatToolset : McpToolset {

    @McpTool
    @McpDescription(
        """
        |Reformats code in the specified file(s) using IntelliJ's code style settings.
        |
        |file_path can be a literal path or a glob pattern (e.g. "src/**/*.java").
        |Glob mode reformats all matching files in the project.
    """
    )
    suspend fun reformat_files(
        @McpDescription("Path relative to project root, or glob pattern (e.g. 'src/**/*.kt')") file_path: String,
    ): Any {
        val project = currentCoroutineContext().project
        val psiFiles = resolveFilesByPattern(project, file_path).resolvePsi(project)

        if (psiFiles.isEmpty()) {
            mcpFail("No files match '$file_path'")
        }

        val finished = CompletableDeferred<Unit>()
        val processor = ReformatCodeProcessor(
            project,
            psiFiles.map { it.psiFile }.toTypedArray(),
            { finished.complete(Unit) },
            false
        )

        withContext(Dispatchers.EDT) {
            processor.run()
        }
        finished.await()

        return if (psiFiles.size == 1) "Reformatted ${psiFiles[0].relativePath}"
            else "Reformatted ${psiFiles.size} files"
    }
}
