@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import dev.xdark.ijmcp.util.formatLocation
import dev.xdark.ijmcp.util.getContextText
import dev.xdark.ijmcp.util.resolveFile
import dev.xdark.ijmcp.util.resolveTargetElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

class FindUsagesToolset : McpToolset {

    @Serializable
    data class UsageLocation(
        val file: String,
        val line: Int,
        val column: Int,
        val context: String,
    )

    @Serializable
    data class FindUsagesResult(
        val symbolName: String,
        val declarationLocation: String,
        val usages: List<UsageLocation>,
        val count: Int,
    )

    @McpTool
    @McpDescription("""
        |Finds all usages (references) of a symbol across the project using IntelliJ's semantic search.
        |Unlike text search, this understands code structure and finds only actual references.
        |
        |Provide either symbolName OR line+column to identify the target symbol.
        |Returns the declaration location and all usage locations with context.
    """)
    suspend fun find_usages(
        @McpDescription("Path relative to the project root") filePath: String,
        @McpDescription("Name of the symbol to find usages of. Alternative to line+column.") symbolName: String = "",
        @McpDescription("1-based line number of the symbol. Used with column as alternative to symbolName.") line: Int = 0,
        @McpDescription("1-based column number of the symbol. Used with line.") column: Int = 0,
        @McpDescription("Search scope: 'project' (default) or 'all' (includes libraries)") scope: String = "project",
    ): FindUsagesResult {
        val project = currentCoroutineContext().project
        val resolved = resolveFile(project, filePath)

        val targetElement = resolveTargetElement(resolved, symbolName, line, column)

        val declarationLocation = readAction { formatLocation(project, targetElement) }
        val resolvedName = readAction { (targetElement as? PsiNamedElement)?.name ?: targetElement.text }

        val searchScope = when (scope) {
            "all" -> GlobalSearchScope.allScope(project)
            else -> GlobalSearchScope.projectScope(project)
        }

        val references = readAction {
            ReferencesSearch.search(targetElement, searchScope).findAll()
        }

        val usages = readAction {
            references.mapNotNull { ref ->
                val element = ref.element
                val loc = formatLocation(project, element)
                if (loc.endsWith("(library)")) {
                    UsageLocation(
                        file = loc,
                        line = 0,
                        column = 0,
                        context = getContextText(element),
                    )
                } else {
                    val parts = loc.split(":")
                    if (parts.size >= 3) {
                        UsageLocation(
                            file = parts[0],
                            line = parts[1].toIntOrNull() ?: 0,
                            column = parts[2].toIntOrNull() ?: 0,
                            context = getContextText(element),
                        )
                    } else null
                }
            }
        }

        return FindUsagesResult(
            symbolName = resolvedName,
            declarationLocation = declarationLocation,
            usages = usages,
            count = usages.size,
        )
    }
}
