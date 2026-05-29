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
import kotlinx.serialization.Serializable
import java.io.IOException
import kotlin.io.path.name
import kotlin.io.path.pathString

class CreateFileToolset : McpToolset {

	@Serializable
	data class FileToCreate(
		val path_in_project: String,
		val content: String,
	)

	@McpTool
	@McpDescription(
		"""
        |Creates one or more files at the specified paths within the project directory and populates them with text.
        |Creates any necessary parent directories automatically.
    """
	)
	suspend fun create_file(
		@McpDescription("List of files to create, each with path_in_project and content")
		files: List<FileToCreate>,
		@McpDescription("Whether to overwrite existing files. If false, an error is returned if any file exists.")
		overwrite: Boolean = false,
	): Any {
		val project = currentCoroutineContext().project
		val results = mutableListOf<String>()
		for (file in files) {
			val path = project.resolveInProject(file.path_in_project)
			try {
				writeAction {
					val parent = VfsUtil.createDirectories(path.parent.pathString)
					val existing = parent.findChild(path.name)
					if (existing != null && !overwrite) {
						mcpFail("File already exists: ${file.path_in_project}. Specify overwrite=true to overwrite it.")
					}
					val createdFile = parent.findOrCreateFile(path.name)
					val document = FileDocumentManager.getInstance().getDocument(createdFile)
						?: mcpFail("Cannot get document for: ${file.path_in_project}")
					document.setText(file.content)
					FileDocumentManager.getInstance().saveDocument(document)
				}
				results.add("Created ${file.path_in_project}")
			} catch (e: IOException) {
				mcpFail("Cannot create file ${file.path_in_project}: ${e.message}")
			}
		}
		return results.joinToString("\n")
	}
}
