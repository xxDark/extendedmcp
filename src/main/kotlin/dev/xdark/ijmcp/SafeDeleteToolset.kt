@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import dev.xdark.ijmcp.util.formatLocation
import dev.xdark.ijmcp.util.getContextText
import dev.xdark.ijmcp.util.resolveFile
import dev.xdark.ijmcp.util.resolveTargetElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class SafeDeleteToolset : McpToolset {

    @Serializable
    data class UsageInfo(
        val location: String,
        val context: String,
    )

    @Serializable
    data class SafeDeleteResult(
        val deleted: Boolean,
        val symbolName: String,
        val usages: List<UsageInfo>,
        val message: String,
    )

    @McpTool
    @McpDescription("""
        |Safely deletes a symbol (method, field, class, parameter) and reports all affected usages.
        |
        |By default (force=false), if the symbol has usages, it will NOT be deleted — instead the
        |usages are returned so you can fix them first. Use force=true to delete anyway.
        |
        |This is equivalent to IntelliJ's Refactor > Safe Delete (Alt+Delete).
    """)
    suspend fun safe_delete(
        @McpDescription("Path relative to the project root") filePath: String,
        @McpDescription("Name of the symbol to delete") symbolName: String = "",
        @McpDescription("1-based line number (alternative to symbolName)") line: Int = 0,
        @McpDescription("1-based column number (used with line)") column: Int = 0,
        @McpDescription("Delete even if there are usages (default false)") force: Boolean = false,
        @McpDescription("Search in comments and strings (default true)") searchInComments: Boolean = true,
        @McpDescription("Search in non-Java/Kotlin files (default true)") searchInNonJavaFiles: Boolean = true,
    ): SafeDeleteResult {
        val project = currentCoroutineContext().project
        val resolved = resolveFile(project, filePath)

        val element = resolveTargetElement(resolved, symbolName, line, column)

        val name = readAction {
            (element as? PsiNamedElement)?.name ?: element.text?.take(30) ?: "unknown"
        }

        // Find usages before attempting delete
        val usages = readAction {
            ReferencesSearch.search(element, GlobalSearchScope.projectScope(project))
                .findAll()
                .map { ref ->
                    UsageInfo(
                        location = formatLocation(project, ref.element),
                        context = getContextText(ref.element),
                    )
                }
        }

        if (usages.isNotEmpty() && !force) {
            return SafeDeleteResult(
                deleted = false,
                symbolName = name,
                usages = usages,
                message = "Cannot safely delete '$name': ${usages.size} usage(s) found. Fix them first or use force=true.",
            )
        }

        // Proceed with safe delete
        withContext(Dispatchers.EDT) {
            val processor = SafeDeleteProcessor.createInstance(
                project,
                null, // prepareSuccessfulCallback
                arrayOf(element),
                searchInComments,
                searchInNonJavaFiles,
            )
            processor.run()
        }

        return SafeDeleteResult(
            deleted = true,
            symbolName = name,
            usages = usages,
            message = if (usages.isEmpty()) {
                "Deleted '$name' (no usages found)."
            } else {
                "Deleted '$name' (force). ${usages.size} usage(s) may need attention."
            },
        )
    }
}
