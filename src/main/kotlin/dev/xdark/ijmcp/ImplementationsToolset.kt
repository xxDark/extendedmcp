@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import dev.xdark.ijmcp.util.formatLocation
import dev.xdark.ijmcp.util.getContextText
import dev.xdark.ijmcp.util.resolveFile
import dev.xdark.ijmcp.util.resolveTargetElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import com.intellij.psi.search.PsiShortNamesCache

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
        |Three ways to identify the target:
        |  1. className — fully qualified name (e.g. "java.util.List"). Works for library/JDK classes.
        |  2. filePath + symbolName — find symbol by name in a project file.
        |  3. filePath + line + column — find symbol at a specific position.
    """)
    suspend fun get_implementations(
        @McpDescription("Path relative to the project root (not needed when using className)") filePath: String = "",
        @McpDescription("Name of the symbol. Alternative to line+column.") symbolName: String = "",
        @McpDescription("1-based line number. Used with column as alternative to symbolName.") line: Int = 0,
        @McpDescription("1-based column number. Used with line.") column: Int = 0,
        @McpDescription("Fully qualified class name (e.g. 'java.util.List'). Works for library/JDK classes.") className: String = "",
        @McpDescription("Search scope: 'project' (default) or 'all' (includes libraries)") scope: String = "project",
    ): GetImplementationsResult {
        val project = currentCoroutineContext().project

        val targetElement = if (className.isNotEmpty()) {
            // Resolve by FQN — works for library/JDK classes
            readAction {
                val searchScope = GlobalSearchScope.allScope(project)
                JavaPsiFacade.getInstance(project).findClass(className, searchScope)
                    ?: mcpFail("Class '$className' not found")
            }
        } else if (filePath.isEmpty() && symbolName.isNotEmpty()) {
            // No file given — try to find by FQN or short name via JavaPsiFacade
            readAction {
                val searchScope = GlobalSearchScope.allScope(project)
                JavaPsiFacade.getInstance(project).findClass(symbolName, searchScope)
            } ?: readAction {
                // Try short name lookup
                val searchScope = GlobalSearchScope.projectScope(project)
                val classes = JavaPsiFacade.getInstance(project)
                    .findClasses(symbolName, searchScope)
                if (classes.size == 1) classes[0]
                else if (classes.size > 1) mcpFail("Multiple classes found for '$symbolName'. Use className with fully qualified name, or specify filePath.")
                else {
                    // Try as short name via short names cache
                    val shortNames = com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
                        .getClassesByName(symbolName, searchScope)
                    if (shortNames.size == 1) shortNames[0]
                    else if (shortNames.size > 1) mcpFail("Multiple classes found for '$symbolName': ${shortNames.mapNotNull { it.qualifiedName }}. Use className with fully qualified name.")
                    else mcpFail("Class '$symbolName' not found. Provide filePath to locate the symbol in a specific file.")
                }
            }
        } else {
            if (filePath.isEmpty()) mcpFail("Provide filePath, className, or symbolName to identify the target")
            val resolved = resolveFile(project, filePath)
            resolveTargetElement(resolved, symbolName, line, column)
        }

        val declarationLocation = readAction { formatLocation(project, targetElement) }
        val resolvedName = readAction { (targetElement as? PsiNamedElement)?.name ?: targetElement.text }

        val searchScope = when (scope) {
            "all" -> GlobalSearchScope.allScope(project)
            else -> GlobalSearchScope.projectScope(project)
        }

        val definitions = readAction {
            DefinitionsScopedSearch.search(targetElement, searchScope).findAll()
        }

        val seen = mutableSetOf<String>()
        val implementations = readAction {
            definitions.mapNotNull { element ->
                val name = (element as? PsiNamedElement)?.name ?: return@mapNotNull null
                val loc = formatLocation(project, element)
                if (!seen.add(loc)) return@mapNotNull null // deduplicate
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
