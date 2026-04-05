@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.mcpserver.util.resolveInProject
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.findOrCreateFile
import kotlinx.coroutines.currentCoroutineContext
import java.io.IOException
import kotlin.io.path.name
import kotlin.io.path.pathString

// Replacement for the built-in create_new_file tool.
// The built-in version's parameter is named "content", but models sometimes send
// "text" or vice versa. With a default value, a name mismatch silently produces
// an empty file. Making the parameter required turns this into a loud error.
class CreateFileToolset : McpToolset {

    @McpTool
    @McpDescription(
        """
        |Creates a new file at the specified path within the project directory and populates it with text.
        |Creates any necessary parent directories automatically.
    """
    )
    suspend fun create_file(
        @McpDescription("Path where the file should be created relative to the project root")
        pathInProject: String,
        @McpDescription("Content to write into the new file")
        content: String,
        @McpDescription("Whether to overwrite an existing file. If false, an error is returned if the file exists.")
        overwrite: Boolean = false,
    ) {
        val project = currentCoroutineContext().project
        val path = project.resolveInProject(pathInProject)
        try {
            writeAction {
                val parent = VfsUtil.createDirectories(path.parent.pathString)
                val existing = parent.findChild(path.name)
                if (existing != null && !overwrite) {
                    mcpFail("File already exists: $pathInProject. Specify overwrite=true to overwrite it.")
                }
                val createdFile = parent.findOrCreateFile(path.name)
                val document = FileDocumentManager.getInstance().getDocument(createdFile)
                    ?: mcpFail("Cannot get document for: $pathInProject")
                document.setText(content)
                FileDocumentManager.getInstance().saveDocument(document)
            }
        } catch (e: IOException) {
            mcpFail("Cannot create file $pathInProject: ${e.message}")
        }
    }
}
