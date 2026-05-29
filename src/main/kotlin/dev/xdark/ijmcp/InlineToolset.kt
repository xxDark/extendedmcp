@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.inline.InlineMethodProcessor
import dev.xdark.ijmcp.util.resolveFile
import dev.xdark.ijmcp.util.resolveTargetElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

class InlineToolset : McpToolset {

	@McpTool
	@McpDescription(
		"""
        |Inlines a method at all call sites, replacing each call with the method body.
        |Optionally deletes the original method after inlining.
        |
        |This is equivalent to IntelliJ's Refactor > Inline Method (Ctrl+Alt+N).
    """
	)
	suspend fun inline_method(
		@McpDescription("Path relative to the project root") file_path: String,
		@McpDescription("Name of the method to inline") method_name: String = "",
		@McpDescription("1-based line number (alternative to method_name)") line: Int = 0,
		@McpDescription("1-based column number (used with line)") column: Int = 0,
		@McpDescription("Delete the original method after inlining (default true)") delete_declaration: Boolean = true,
	): Any {
		val project = currentCoroutineContext().project
		val resolved = resolveFile(project, file_path)

		val element = resolveTargetElement(resolved, method_name, line, column)

		val method = readAction {
			when (element) {
				is PsiMethod -> element
				else -> mcpFail("Element '${(element as? PsiNamedElement)?.name ?: "unknown"}' is not a method. Only methods can be inlined with this tool.")
			}
		}

		val name = readAction { method.name }

		// Count call sites before inlining
		val callSiteCount = readAction {
			ReferencesSearch.search(method, GlobalSearchScope.projectScope(project))
				.findAll()
				.size
		}

		if (callSiteCount == 0) {
			mcpFail("Method '$name' has no call sites to inline.")
		}

		withContext(Dispatchers.EDT) {
			val editor = EditorFactory.getInstance().createEditor(resolved.document, project)
			try {
				val processor = InlineMethodProcessor(
					project,
					method,
					null, // reference (null = inline all)
					editor,
					false, // isInlineThisOnly
					false, // search_in_comments
					false, // searchForTextOccurrences
					delete_declaration,
				)
				processor.run()
			} finally {
				EditorFactory.getInstance().releaseEditor(editor)
			}
		}

		withContext(Dispatchers.EDT) {
			FileDocumentManager.getInstance().saveDocument(resolved.document)
		}

		return "Inlined '$name' at $callSiteCount call site(s)." +
				if (delete_declaration) " Declaration removed." else ""
	}
}
