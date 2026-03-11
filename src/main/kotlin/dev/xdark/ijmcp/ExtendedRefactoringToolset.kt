@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

class ExtendedRefactoringToolset : McpToolset {

    @McpTool
    @McpDescription("""
        |Optimizes imports in the specified file by removing unused imports and organizing the remaining ones.
        |This uses IntelliJ's built-in import optimization which respects language-specific rules and project settings.
    """)
    suspend fun optimize_imports(
        @McpDescription("Path relative to the project root") filePath: String,
    ): String {
        val project = currentCoroutineContext().project
        val resolved = resolveFile(project, filePath)

        val psiFile = readAction { resolved.psiFile }
        val finished = CompletableDeferred<Unit>()
        val processor = OptimizeImportsProcessor(project, psiFile)
        processor.setPostRunnable { finished.complete(Unit) }

        withContext(Dispatchers.EDT) {
            processor.run()
        }
        finished.await()

        return "ok"
    }
}
