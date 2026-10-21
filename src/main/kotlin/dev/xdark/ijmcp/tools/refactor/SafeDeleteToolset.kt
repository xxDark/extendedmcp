@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp.tools.refactor

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import dev.xdark.ijmcp.util.formatLocation
import dev.xdark.ijmcp.util.resolveFile
import dev.xdark.ijmcp.util.resolveTargetElement
import dev.xdark.ijmcp.util.searchReferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

class SafeDeleteToolset : McpToolset {

	@McpTool
	@McpDescription(
		"""
        |Safely deletes a symbol (method, field, class, parameter) and reports all affected usages.
        |
        |By default (force=false), if the symbol has usages, it will NOT be deleted — instead the
        |usages are returned so you can fix them first. Use force=true to delete anyway.
        |
        |This is equivalent to IntelliJ's Refactor > Safe Delete (Alt+Delete).
    """
	)
	suspend fun safe_delete(
		@McpDescription("Path relative to the project root") filePath: String,
		@McpDescription("Name of the symbol to delete") symbolName: String = "",
		@McpDescription("1-based line number (alternative to symbolName)") line: Int = 0,
		@McpDescription("1-based column number (used with line)") column: Int = 0,
		@McpDescription("Delete even if there are usages (default false)") force: Boolean = false,
		@McpDescription("Search in comments and strings (default true)") searchInComments: Boolean = true,
		@McpDescription("Search in non-Java/Kotlin files (default true)") searchInNonJavaFiles: Boolean = true,
	): Any {
		val project = currentCoroutineContext().project
		val resolved = resolveFile(project, filePath)

		val element = resolveTargetElement(resolved, symbolName, line, column)

		val name = readAction {
			(element as? PsiNamedElement)?.name ?: element.text?.take(30) ?: "unknown"
		}

		// Find usages before attempting delete
		val usageLocations = smartReadAction(project) {
			searchReferences(element, GlobalSearchScope.projectScope(project))
				.findAll()
				.map { ref -> formatLocation(project, ref.element) }
		}

		if (usageLocations.isNotEmpty() && !force) {
			return buildString {
				append("Cannot safely delete '").append(name).append("': ").append(usageLocations.size)
					.append(" usage(s) found. Fix them first or use force=true.\n")
				usageLocations.forEach { append("  ").append(it).append('\n') }
			}.trimEnd()
		}

		withContext(Dispatchers.EDT) {
			if (usageLocations.isEmpty()) {
				val processor = SafeDeleteProcessor.createInstance(
					project,
					null, // prepareSuccessfulCallback
					arrayOf(element),
					searchInComments,
					searchInNonJavaFiles,
				)
				processor.run()
			} else {
				// SafeDeleteProcessor asks about usages in a modal ConflictsDialog, which blocks this call
				// until someone answers it in the IDE. force already means "delete anyway".
				WriteCommandAction.runWriteCommandAction(project) { element.delete() }
			}
		}

		return if (usageLocations.isEmpty()) {
			"Deleted '$name' (no usages found)."
		} else {
			"Deleted '$name' (force). ${usageLocations.size} usage(s) may need attention."
		}
	}
}
