@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import dev.xdark.ijmcp.util.formatLocation
import dev.xdark.ijmcp.util.getContextText
import dev.xdark.ijmcp.util.resolveFile
import dev.xdark.ijmcp.util.resolveTargetElement
import kotlinx.coroutines.currentCoroutineContext

class FindUsagesToolset : McpToolset {

	@McpTool
	@McpDescription(
		"""
        |Finds all usages (references) of a symbol across the project using IntelliJ's semantic search.
        |Unlike text search, this understands code structure and finds only actual references.
        |
        |Two modes of target identification:
        |  1. file_path + (symbol_name OR line+column) — for symbols in project files
        |  2. qualified_class_name + optional symbol_name — for library/JDK classes and their members
        |
        |Examples:
        |  - qualified_class_name="com.intellij.openapi.project.Project" → usages of the class
        |  - qualified_class_name="com.intellij.openapi.project.Project", symbol_name="getBasePath" → usages of that method
        |
        |Returns the declaration location and all usage locations with context.
    """
	)
	suspend fun find_usages(
		@McpDescription("Path relative to the project root. Use this OR qualified_class_name.") file_path: String = "",
		@McpDescription("Fully qualified class name (e.g. com.example.MyClass). Use this OR file_path. Supports library classes.") qualified_class_name: String = "",
		@McpDescription("Name of the symbol to find usages of. With file_path: alternative to line+column. With qualified_class_name: method or field name.") symbol_name: String = "",
		@McpDescription("1-based line number of the symbol. Used with column as alternative to symbol_name.") line: Int = 0,
		@McpDescription("1-based column number of the symbol. Used with line.") column: Int = 0,
		@McpDescription("Index of overloaded method when qualified_class_name + symbol_name matches multiple overloads.") member_index: Int = -1,
		@McpDescription("Search scope: 'project' (default) or 'all' (includes libraries)") scope: String = "project",
		@McpDescription("Maximum number of usages to return (default 50). Prevents slow searches on common symbols.") max_results: Int = 50,
	): Any {
		val project = currentCoroutineContext().project

		val targetElement = if (qualified_class_name.isNotEmpty()) {
			resolveByQualifiedName(project, qualified_class_name, symbol_name, member_index)
		} else if (file_path.isNotEmpty()) {
			val resolved = resolveFile(project, file_path)
			resolveTargetElement(resolved, symbol_name, line, column)
		} else {
			mcpFail("Provide either file_path or qualified_class_name")
		}

		val declarationLocation = readAction { formatLocation(project, targetElement) }
		val resolvedName = readAction { (targetElement as? PsiNamedElement)?.name ?: targetElement.text }

		val baseScope = when (scope) {
			"all" -> GlobalSearchScope.allScope(project)
			else -> GlobalSearchScope.projectScope(project)
		}

		// For member searches via qualified_class_name, narrow scope to files that reference
		// the containing class first. This avoids resolving every text match of common
		// method names (e.g. "getInstance") across all library files.
		val searchScope = if (qualified_class_name.isNotEmpty() && symbol_name.isNotEmpty()) {
			val containingClass = readAction {
				val cls = JavaPsiFacade.getInstance(project)
					.findClass(qualified_class_name, GlobalSearchScope.allScope(project))
					?: mcpFail("Class '$qualified_class_name' not found")
				(cls.navigationElement as? PsiClass) ?: cls
			}
			readAction {
				val files = mutableSetOf<com.intellij.openapi.vfs.VirtualFile>()
				ReferencesSearch.search(containingClass, baseScope).forEach(com.intellij.util.Processor { ref ->
					ref.element.containingFile?.virtualFile?.let { files.add(it) }
					true // collect all
				})
				if (files.isEmpty()) {
					mcpFail("No references to class '$qualified_class_name' found in the specified scope")
				}
				GlobalSearchScope.filesScope(project, files)
			}
		} else {
			baseScope
		}

		data class UsageEntry(val location: String, val context: String)

		val usages = readAction {
			val results = mutableListOf<UsageEntry>()
			ReferencesSearch.search(targetElement, searchScope).forEach(com.intellij.util.Processor { ref ->
				val element = ref.element
				val loc = formatLocation(project, element)
				val context = getContextText(element)
				results.add(UsageEntry(loc, context))
				results.size < max_results // return false to stop search
			})
			results
		}

		val truncated = usages.size >= max_results

		return buildString {
			val count = usages.size
			append(count)
			append(" usage")
			if (count != 1) append("s")
			append(" of ")
			append(resolvedName)
			append(" (declared at ")
			append(declarationLocation)
			append(")")
			if (truncated) append(" (truncated at ").append(max_results).append(" results)")
			append(":\n")

			for (usage in usages) {
				append("\n")
				append(usage.location)
				append("\n  ")
				append(usage.context)
				append("\n")
			}
		}
	}

	private suspend fun resolveByQualifiedName(
		project: Project,
		qualified_class_name: String,
		symbol_name: String,
		member_index: Int,
	): PsiElement {
		return readAction {
			val scope = GlobalSearchScope.allScope(project)
			val compiledClass = JavaPsiFacade.getInstance(project).findClass(qualified_class_name, scope)
				?: mcpFail("Class '$qualified_class_name' not found")
			// Navigate to source if available — compiled classes may not resolve references properly
			val psiClass = (compiledClass.navigationElement as? PsiClass) ?: compiledClass

			if (symbol_name.isEmpty()) {
				psiClass
			} else {
				findMemberRecursively(psiClass, symbol_name, member_index, qualified_class_name)
			}
		}
	}

	private fun findMemberRecursively(
		psiClass: PsiClass,
		symbol_name: String,
		member_index: Int,
		qualified_class_name: String,
	): PsiElement {
		// Collect methods and fields from this class and all inner classes (including Companion)
		val methods = mutableListOf<com.intellij.psi.PsiMethod>()
		val fields = mutableListOf<com.intellij.psi.PsiField>()

		fun collect(cls: PsiClass) {
			methods.addAll(cls.findMethodsByName(symbol_name, false))
			cls.findFieldByName(symbol_name, false)?.let { fields.add(it) }
			for (inner in cls.innerClasses) {
				collect(inner)
			}
		}
		collect(psiClass)

		if (methods.size > 1 && member_index < 0) {
			val overloads = methods.mapIndexed { i, m ->
				val params = m.parameterList.parameters.joinToString(", ") { p ->
					"${p.type.presentableText} ${p.name}"
				}
				"  $i: $symbol_name($params)"
			}.joinToString("\n")
			mcpFail("Multiple overloads found for '$symbol_name'. Specify member_index:\n$overloads")
		}

		if (methods.isNotEmpty()) {
			return methods[if (member_index >= 0) member_index.coerceIn(methods.indices) else 0]
		}
		if (fields.isNotEmpty()) {
			return fields[0]
		}
		mcpFail("Member '$symbol_name' not found in class '$qualified_class_name'")
	}
}
