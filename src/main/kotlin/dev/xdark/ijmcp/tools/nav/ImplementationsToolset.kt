@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp.tools.nav

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
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
        |  1. qualifiedClassName — fully qualified name (e.g. "java.util.List"). Works for library/JDK classes.
        |  2. filePath + symbolName — find symbol by name in a project file.
        |  3. filePath + line + column — find symbol at a specific position.
    """
	)
	suspend fun get_implementations(
		@McpDescription("Path relative to the project root (not needed when using qualifiedClassName)") filePath: String = "",
		@McpDescription("Name of the symbol. Alternative to line+column.") symbolName: String = "",
		@McpDescription("1-based line number. Used with column as alternative to symbolName.") line: Int = 0,
		@McpDescription("1-based column number. Used with line.") column: Int = 0,
		@McpDescription("Fully qualified class name (e.g. 'java.util.List'). Works for library/JDK classes.") qualifiedClassName: String = "",
		@McpDescription("Search scope: 'project' (default) or 'all' (includes libraries)") scope: String = "project",
	): Any {
		val project = currentCoroutineContext().project

		val targetElement = if (qualifiedClassName.isNotEmpty()) {
			// Resolve by FQN — works for library/JDK classes
			smartReadAction(project) {
				val searchScope = GlobalSearchScope.allScope(project)
				JavaPsiFacade.getInstance(project).findClass(qualifiedClassName, searchScope)
					?: mcpFail("Class '$qualifiedClassName' not found")
			}
		} else if (filePath.isEmpty() && symbolName.isNotEmpty()) {
			// No file given — try to find by FQN or short name via JavaPsiFacade
			smartReadAction(project) {
				val searchScope = GlobalSearchScope.allScope(project)
				JavaPsiFacade.getInstance(project).findClass(symbolName, searchScope)
			} ?: smartReadAction(project) {
				// Try short name lookup
				val searchScope = GlobalSearchScope.projectScope(project)
				val classes = JavaPsiFacade.getInstance(project)
					.findClasses(symbolName, searchScope)
				if (classes.size == 1) classes[0]
				else if (classes.size > 1) mcpFail("Multiple classes found for '$symbolName'. Use qualifiedClassName with fully qualified name, or specify filePath.")
				else {
					// Try as short name via short names cache
					val shortNames = PsiShortNamesCache.getInstance(project)
						.getClassesByName(symbolName, searchScope)
					if (shortNames.size == 1) shortNames[0]
					else if (shortNames.size > 1) mcpFail("Multiple classes found for '$symbolName': ${shortNames.mapNotNull { it.qualifiedName }}. Use qualifiedClassName with fully qualified name.")
					else mcpFail("Class '$symbolName' not found. Provide filePath to locate the symbol in a specific file.")
				}
			}
		} else {
			if (filePath.isEmpty()) mcpFail("Provide filePath, qualifiedClassName, or symbolName to identify the target")
			val resolved = resolveFile(project, filePath)
			resolveTargetElement(resolved, symbolName, line, column)
		}

		val declarationLocation = readAction { formatLocation(project, targetElement) }
		val resolvedName = readAction { (targetElement as? PsiNamedElement)?.name ?: targetElement.text }

		val searchScope = when (scope) {
			"all" -> GlobalSearchScope.allScope(project)
			else -> GlobalSearchScope.projectScope(project)
		}

		val definitions = smartReadAction(project) {
			DefinitionsScopedSearch.search(targetElement, searchScope).findAll()
		}

		data class ImplEntry(val name: String, val location: String, val context: String)

		val implementations = readAction {
			val seen = mutableSetOf<String>()
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
