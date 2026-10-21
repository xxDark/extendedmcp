@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp.tools.nav

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import dev.xdark.ijmcp.util.formatLocation
import dev.xdark.ijmcp.util.getContextText
import dev.xdark.ijmcp.util.resolveFile
import dev.xdark.ijmcp.util.resolveTargetElement
import dev.xdark.ijmcp.util.searchReferences
import kotlinx.coroutines.currentCoroutineContext

class FindUsagesToolset : McpToolset {

	@McpTool
	@McpDescription(
		"""
        |Finds all usages (references) of a symbol across the project using IntelliJ's semantic search.
        |Unlike text search, this understands code structure and finds only actual references.
        |
        |Two modes of target identification:
        |  1. filePath + (symbolName OR line+column) — for symbols in project files
        |  2. qualifiedClassName + optional symbolName — for library/JDK classes and their members
        |
        |Examples:
        |  - qualifiedClassName="com.intellij.openapi.project.Project" → usages of the class
        |  - qualifiedClassName="com.intellij.openapi.project.Project", symbolName="getBasePath" → usages of that method
        |
        |Returns the declaration location and all usage locations with context.
    """
	)
	suspend fun find_usages(
		@McpDescription("Path relative to the project root. Use this OR qualifiedClassName.") filePath: String = "",
		@McpDescription("Fully qualified class name (e.g. com.example.MyClass). Use this OR filePath. Supports library classes.") qualifiedClassName: String = "",
		@McpDescription("Name of the symbol to find usages of. With filePath: alternative to line+column. With qualifiedClassName: method or field name.") symbolName: String = "",
		@McpDescription("1-based line number of the symbol. Used with column as alternative to symbolName.") line: Int = 0,
		@McpDescription("1-based column number of the symbol. Used with line.") column: Int = 0,
		@McpDescription("Index of overloaded method when qualifiedClassName + symbolName matches multiple overloads.") memberIndex: Int = -1,
		@McpDescription("Search scope: 'project' (default) or 'all' (includes libraries)") scope: String = "project",
		@McpDescription("Maximum number of usages to return (default 50). Prevents slow searches on common symbols.") maxResults: Int = 50,
	): Any {
		val project = currentCoroutineContext().project

		val targetElement = if (qualifiedClassName.isNotEmpty()) {
			resolveByQualifiedName(project, qualifiedClassName, symbolName, memberIndex)
		} else if (filePath.isNotEmpty()) {
			val resolved = resolveFile(project, filePath)
			resolveTargetElement(resolved, symbolName, line, column)
		} else {
			mcpFail("Provide either filePath or qualifiedClassName")
		}

		val declarationLocation = readAction { formatLocation(project, targetElement) }
		val resolvedName = readAction { (targetElement as? PsiNamedElement)?.name ?: targetElement.text }

		val searchScope = when (scope) {
			"all" -> GlobalSearchScope.allScope(project)
			else -> GlobalSearchScope.projectScope(project)
		}

		data class UsageEntry(val location: String, val context: String)

		val usages = smartReadAction(project) {
			val results = mutableListOf<UsageEntry>()
			searchReferences(targetElement, searchScope).forEach(Processor { ref ->
				val element = ref.element
				val loc = formatLocation(project, element)
				val context = getContextText(element)
				results.add(UsageEntry(loc, context))
				results.size < maxResults // return false to stop search
			})
			results
		}

		val truncated = usages.size >= maxResults

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
			if (truncated) append(" (truncated at ").append(maxResults).append(" results)")
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
		qualifiedClassName: String,
		symbolName: String,
		memberIndex: Int,
	): PsiElement {
		return smartReadAction(project) {
			val scope = GlobalSearchScope.allScope(project)
			val compiledClass = JavaPsiFacade.getInstance(project).findClass(qualifiedClassName, scope)
				?: mcpFail("Class '$qualifiedClassName' not found")
			// Navigate to source if available — compiled classes may not resolve references properly
			val psiClass = (compiledClass.navigationElement as? PsiClass) ?: compiledClass

			if (symbolName.isEmpty()) {
				psiClass
			} else {
				findMemberRecursively(psiClass, symbolName, memberIndex, qualifiedClassName)
			}
		}
	}

	private fun findMemberRecursively(
		psiClass: PsiClass,
		symbolName: String,
		memberIndex: Int,
		qualifiedClassName: String,
	): PsiElement {
		// Collect methods and fields from this class and all inner classes (including Companion)
		val methods = mutableListOf<PsiMethod>()
		val fields = mutableListOf<PsiField>()

		fun collect(cls: PsiClass) {
			methods.addAll(cls.findMethodsByName(symbolName, false))
			cls.findFieldByName(symbolName, false)?.let { fields.add(it) }
			for (inner in cls.innerClasses) {
				collect(inner)
			}
		}
		collect(psiClass)

		if (methods.size > 1 && memberIndex < 0) {
			val overloads = methods.mapIndexed { i, m ->
				val params = m.parameterList.parameters.joinToString(", ") { p ->
					"${p.type.presentableText} ${p.name}"
				}
				"  $i: $symbolName($params)"
			}.joinToString("\n")
			mcpFail("Multiple overloads found for '$symbolName'. Specify memberIndex:\n$overloads")
		}

		if (methods.isNotEmpty()) {
			return methods[if (memberIndex >= 0) memberIndex.coerceIn(methods.indices) else 0]
		}
		if (fields.isNotEmpty()) {
			return fields[0]
		}
		mcpFail("Member '$symbolName' not found in class '$qualifiedClassName'")
	}
}
