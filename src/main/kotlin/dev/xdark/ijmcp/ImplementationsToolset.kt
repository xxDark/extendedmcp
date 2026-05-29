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

class ImplementationsToolset : McpToolset {

    @McpTool
    @McpDescription(
        """
        |Finds all implementations of an interface, abstract class, or overrides of a method.
        |
        |For an interface/abstract class: returns all implementing/extending classes.
        |For a method: returns all overriding methods.
        |
        |Three ways to identify the target:
        |  1. class_name — fully qualified name (e.g. "java.util.List"). Works for library/JDK classes.
        |  2. file_path + symbol_name — find symbol by name in a project file.
        |  3. file_path + line + column — find symbol at a specific position.
    """
    )
    suspend fun get_implementations(
        @McpDescription("Path relative to the project root (not needed when using class_name)") file_path: String = "",
        @McpDescription("Name of the symbol. Alternative to line+column.") symbol_name: String = "",
        @McpDescription("1-based line number. Used with column as alternative to symbol_name.") line: Int = 0,
        @McpDescription("1-based column number. Used with line.") column: Int = 0,
        @McpDescription("Fully qualified class name (e.g. 'java.util.List'). Works for library/JDK classes.") class_name: String = "",
        @McpDescription("Search scope: 'project' (default) or 'all' (includes libraries)") scope: String = "project",
    ): Any {
        val project = currentCoroutineContext().project

        val targetElement = if (class_name.isNotEmpty()) {
            // Resolve by FQN — works for library/JDK classes
            readAction {
                val searchScope = GlobalSearchScope.allScope(project)
                JavaPsiFacade.getInstance(project).findClass(class_name, searchScope)
                    ?: mcpFail("Class '$class_name' not found")
            }
        } else if (file_path.isEmpty() && symbol_name.isNotEmpty()) {
            // No file given — try to find by FQN or short name via JavaPsiFacade
            readAction {
                val searchScope = GlobalSearchScope.allScope(project)
                JavaPsiFacade.getInstance(project).findClass(symbol_name, searchScope)
            } ?: readAction {
                // Try short name lookup
                val searchScope = GlobalSearchScope.projectScope(project)
                val classes = JavaPsiFacade.getInstance(project)
                    .findClasses(symbol_name, searchScope)
                if (classes.size == 1) classes[0]
                else if (classes.size > 1) mcpFail("Multiple classes found for '$symbol_name'. Use class_name with fully qualified name, or specify file_path.")
                else {
                    // Try as short name via short names cache
                    val shortNames = com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
                        .getClassesByName(symbol_name, searchScope)
                    if (shortNames.size == 1) shortNames[0]
                    else if (shortNames.size > 1) mcpFail("Multiple classes found for '$symbol_name': ${shortNames.mapNotNull { it.qualifiedName }}. Use class_name with fully qualified name.")
                    else mcpFail("Class '$symbol_name' not found. Provide file_path to locate the symbol in a specific file.")
                }
            }
        } else {
            if (file_path.isEmpty()) mcpFail("Provide file_path, class_name, or symbol_name to identify the target")
            val resolved = resolveFile(project, file_path)
            resolveTargetElement(resolved, symbol_name, line, column)
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

        data class ImplEntry(val name: String, val location: String, val context: String)

        val seen = mutableSetOf<String>()
        val implementations = readAction {
            definitions.mapNotNull { element ->
                val name = (element as? PsiNamedElement)?.name ?: return@mapNotNull null
                val loc = formatLocation(project, element)
                if (!seen.add(loc)) return@mapNotNull null // deduplicate
                val context = getContextText(element)
                ImplEntry(name, loc, context)
            }
        }

        return buildString {
            val count = implementations.size
            append(count)
            append(" implementation")
            if (count != 1) append("s")
            append(" of ")
            append(resolvedName)
            append(" (declared at ")
            append(declarationLocation)
            append("):\n")

            for (impl in implementations) {
                append("\n")
                append(impl.location)
                append("\n  ")
                append(impl.context)
                append("\n")
            }
        }
    }
}
