@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import dev.xdark.ijmcp.util.formatLocation
import dev.xdark.ijmcp.util.getContextText
import dev.xdark.ijmcp.util.resolveFile
import dev.xdark.ijmcp.util.resolveTargetElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

class ImplementationsToolset : McpToolset {

    @Serializable
    data class ImplementationInfo(
        val name: String,
        val file: String,
        val line: Int,
        val column: Int,
        val context: String,
    )

    @Serializable
    data class GetImplementationsResult(
        val symbolName: String,
        val declarationLocation: String,
        val implementations: List<ImplementationInfo>,
        val count: Int,
    )

    @McpTool
    @McpDescription("""
        |Finds all implementations of an interface, abstract class, or overrides of a method.
        |
        |For an interface/abstract class: returns all implementing/extending classes.
        |For a method: returns all overriding methods.
        |
        |Provide either symbolName OR line+column to identify the target symbol.
    """)
    suspend fun get_implementations(
        @McpDescription("Path relative to the project root") filePath: String,
        @McpDescription("Name of the symbol. Alternative to line+column.") symbolName: String = "",
        @McpDescription("1-based line number. Used with column as alternative to symbolName.") line: Int = 0,
        @McpDescription("1-based column number. Used with line.") column: Int = 0,
        @McpDescription("Search scope: 'project' (default) or 'all' (includes libraries)") scope: String = "project",
    ): GetImplementationsResult {
        val project = currentCoroutineContext().project
        val resolved = resolveFile(project, filePath)

        val targetElement = resolveTargetElement(resolved, symbolName, line, column)

        val declarationLocation = readAction { formatLocation(project, targetElement) }
        val resolvedName = readAction { (targetElement as? PsiNamedElement)?.name ?: targetElement.text }

        val searchScope = when (scope) {
            "all" -> GlobalSearchScope.allScope(project)
            else -> GlobalSearchScope.projectScope(project)
        }

        val definitions = readAction {
            DefinitionsScopedSearch.search(targetElement, searchScope).findAll()
        }

        val implementations = readAction {
            definitions.mapNotNull { element ->
                val name = (element as? PsiNamedElement)?.name ?: return@mapNotNull null
                val loc = formatLocation(project, element)
                if (loc.endsWith("(library)")) {
                    ImplementationInfo(
                        name = name,
                        file = loc,
                        line = 0,
                        column = 0,
                        context = getContextText(element),
                    )
                } else {
                    val parts = loc.split(":")
                    if (parts.size >= 3) {
                        ImplementationInfo(
                            name = name,
                            file = parts[0],
                            line = parts[1].toIntOrNull() ?: 0,
                            column = parts[2].toIntOrNull() ?: 0,
                            context = getContextText(element),
                        )
                    } else null
                }
            }
        }

        return GetImplementationsResult(
            symbolName = resolvedName,
            declarationLocation = declarationLocation,
            implementations = implementations,
            count = implementations.size,
        )
    }
}
