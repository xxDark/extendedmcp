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
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.inline.InlineMethodProcessor
import dev.xdark.ijmcp.util.formatLocation
import dev.xdark.ijmcp.util.getContextText
import dev.xdark.ijmcp.util.resolveFile
import dev.xdark.ijmcp.util.resolveTargetElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class InlineToolset : McpToolset {

    @Serializable
    data class InlinedSite(
        val location: String,
        val context: String,
    )

    @Serializable
    data class InlineResult(
        val symbolName: String,
        val inlinedSites: List<InlinedSite>,
        val message: String,
    )

    @McpTool
    @McpDescription("""
        |Inlines a method at all call sites, replacing each call with the method body.
        |Optionally deletes the original method after inlining.
        |
        |This is equivalent to IntelliJ's Refactor > Inline Method (Ctrl+Alt+N).
    """)
    suspend fun inline_method(
        @McpDescription("Path relative to the project root") filePath: String,
        @McpDescription("Name of the method to inline") methodName: String = "",
        @McpDescription("1-based line number (alternative to methodName)") line: Int = 0,
        @McpDescription("1-based column number (used with line)") column: Int = 0,
        @McpDescription("Delete the original method after inlining (default true)") deleteDeclaration: Boolean = true,
    ): InlineResult {
        val project = currentCoroutineContext().project
        val resolved = resolveFile(project, filePath)

        val element = resolveTargetElement(resolved, methodName, line, column)

        val method = readAction {
            when (element) {
                is PsiMethod -> element
                else -> mcpFail("Element '${(element as? PsiNamedElement)?.name ?: "unknown"}' is not a method. Only methods can be inlined with this tool.")
            }
        }

        val name = readAction { method.name }

        // Collect call sites before inlining
        val callSites = readAction {
            ReferencesSearch.search(method, GlobalSearchScope.projectScope(project))
                .findAll()
                .map { ref ->
                    InlinedSite(
                        location = formatLocation(project, ref.element),
                        context = getContextText(ref.element),
                    )
                }
        }

        if (callSites.isEmpty()) {
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
                    false, // searchInComments
                    false, // searchForTextOccurrences
                    deleteDeclaration,
                )
                processor.run()
            } finally {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        }

        withContext(Dispatchers.EDT) {
            FileDocumentManager.getInstance().saveDocument(resolved.document)
        }

        return InlineResult(
            symbolName = name,
            inlinedSites = callSites,
            message = "Inlined '$name' at ${callSites.size} call site(s)." +
                if (deleteDeclaration) " Declaration removed." else "",
        )
    }
}
