@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
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
class ExtendedRefactoringToolset : McpToolset {

    @McpTool
    @McpDescription(
        """
        |Optimizes imports in the specified file(s) by removing unused imports and organizing the remaining ones.
        |This uses IntelliJ's built-in import optimization which respects language-specific rules and project settings.
        |
        |file_path can be a literal path or a glob pattern (e.g. "src/**/*.java").
    """
    )
    suspend fun optimize_imports(
        @McpDescription("Path relative to the project root, or glob pattern (e.g. 'src/**/*.kt')") file_path: String,
    ): Any {
        val project = currentCoroutineContext().project
        val psiFiles = resolveFilesByPattern(project, file_path).resolvePsi(project)

        if (psiFiles.isEmpty()) {
            mcpFail("No files match '$file_path'")
        }

        val finished = CompletableDeferred<Unit>()
        val processor = OptimizeImportsProcessor(
            project,
            psiFiles.map { it.psiFile }.toTypedArray()
        ) { finished.complete(Unit) }

        withContext(Dispatchers.EDT) {
            processor.run()
        }
        finished.await()

        return if (psiFiles.size == 1) "Optimized imports in ${psiFiles[0].relativePath}"
            else "Optimized imports in ${psiFiles.size} files"
    }

}
