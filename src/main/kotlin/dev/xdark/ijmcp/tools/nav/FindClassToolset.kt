@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp.tools.nav

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import dev.xdark.ijmcp.util.detectIndentation
import dev.xdark.ijmcp.util.formatLocation
import kotlinx.coroutines.currentCoroutineContext

class FindClassToolset : McpToolset {

	@McpTool
	@McpDescription(
		"""
        |Finds a class by name across the project and all libraries (like Ctrl+N in IntelliJ).
        |
        |Accepts a short class name (e.g. "ExternalSystemUtil") or a fully qualified name
        |(e.g. "com.intellij.openapi.project.Project").
        |
        |When exactly one class matches and its sources are available, prints the source itself with
        |1-based line numbers, windowed by startLine/maxLines. This is the only way to read library
        |sources — read_files is limited to project content roots.
        |
        |When several classes match, prints a compact list so you can narrow by qualified name.
        |Without sources, or with includeSource=false, prints the class outline instead: fields,
        |methods with signatures, and supertypes.
    """
	)
	suspend fun find_class(
		@McpDescription("Class name (short or fully qualified)") className: String,
		@McpDescription("Search scope: 'all' (default, includes libraries) or 'project'") scope: String = "all",
		@McpDescription("Max results to return (default 5)") maxResults: Int = 5,
		@McpDescription("Print the class source when it is available (default true)") includeSource: Boolean = true,
		@McpDescription("1-based file line to start the source at (0 = start at the class declaration)") startLine: Int = 0,
		@McpDescription("Max lines of source to print (default 200)") maxLines: Int = 200,
	): Any {
		val project = currentCoroutineContext().project
		val searchScope = when (scope) {
			"project" -> GlobalSearchScope.projectScope(project)
			else -> GlobalSearchScope.allScope(project)
		}

		if (maxLines <= 0) mcpFail("maxLines must be > 0")
		if (startLine < 0) mcpFail("startLine must be >= 0")

		return smartReadAction(project) {
			val found = if (className.contains('.')) {
				JavaPsiFacade.getInstance(project).findClasses(className, searchScope).toList()
			} else {
				PsiShortNamesCache.getInstance(project)
					.getClassesByName(className, searchScope).toList()
			}

			// Same class can come from several jars; keep one entry per FQN, preferring real sources.
			val byQualifiedName = LinkedHashMap<String, PsiClass>()
			for (cls in found) {
				val key = cls.qualifiedName ?: cls.name ?: continue
				val existing = byQualifiedName[key]
				if (existing == null || (!hasSource(existing) && hasSource(cls))) {
					byQualifiedName[key] = cls
				}
			}
			val results = byQualifiedName.values.take(maxResults)

			when {
				results.isEmpty() -> "No classes found for \"$className\""

				results.size == 1 && includeSource && hasSource(results[0]) ->
					formatSourceView(project, results[0], startLine, maxLines)

				results.size == 1 -> formatClassInfo(results[0], includeSource, maxLines)

				includeSource -> formatCompactList(project, className, results, byQualifiedName.size)

				else -> buildString {
					append(results.size).append(" classes found for \"").append(className).append("\":\n")
					for (cls in results) {
						append("\n").append(formatClassInfo(cls, false, maxLines)).append("\n")
					}
				}
			}
		}
	}

	// A library class without attached sources navigates to its own compiled stub.
	private fun hasSource(cls: PsiClass): Boolean {
		val navElement = cls.navigationElement
		return navElement !is PsiCompiledElement && navElement.containingFile?.virtualFile != null
	}

	private fun formatSourceView(project: Project, cls: PsiClass, startLine: Int, maxLines: Int): String {
		val name = cls.qualifiedName ?: cls.name ?: "<anonymous>"
		val navElement = cls.navigationElement
		val file = navElement.containingFile?.virtualFile ?: mcpFail("No source file for $name")
		val document = FileDocumentManager.getInstance().getDocument(file)
			?: mcpFail("Cannot read source for $name")

		val lineCount = document.lineCount
		val declLine = navElement.textRange?.startOffset?.let { document.getLineNumber(it) } ?: 0
		val firstLine = if (startLine > 0) startLine - 1 else declLine
		if (firstLine >= lineCount) {
			mcpFail("startLine $startLine is past the end of the file ($lineCount lines)")
		}
		val lastLine = minOf(lineCount - 1, firstLine + maxLines - 1)
		val chars = document.immutableCharSequence

		return buildString {
			append(formatLocation(project, cls)).append('\n')
			for (lineIndex in firstLine..lastLine) {
				append('L').append(lineIndex + 1).append(": ")
				append(chars, document.getLineStartOffset(lineIndex), document.getLineEndOffset(lineIndex))
				append('\n')
			}
			val remaining = lineCount - 1 - lastLine
			if (remaining > 0) {
				append("\n[truncated, ").append(remaining)
					.append(" more lines — use startLine=").append(lastLine + 2).append(']')
			}
		}.trimEnd()
	}

	private fun formatCompactList(
		project: Project,
		query: String,
		results: List<PsiClass>,
		totalMatches: Int,
	): String = buildString {
		append(totalMatches).append(" classes match \"").append(query).append('"')
		if (results.size < totalMatches) append(" (showing ").append(results.size).append(')')
		append(":\n")
		for (cls in results) {
			append("  ").append(cls.qualifiedName ?: cls.name ?: "<anonymous>")
			append("  ").append(formatLocation(project, cls))
			if (!hasSource(cls)) append("  [no source]")
			append('\n')
		}
		append("\nNarrow with a fully qualified name to see source.")
	}

	private fun formatClassInfo(
		cls: PsiClass,
		includeSource: Boolean,
		maxLines: Int,
	): String {
		val project = cls.project

		val kind = when {
			cls.isInterface -> "interface"
			cls.isEnum -> "enum"
			cls.isAnnotationType -> "annotation"
			cls.isRecord -> "record"
			else -> "class"
		}

		val location = formatLocation(project, cls)
		val qualifiedName = cls.qualifiedName ?: cls.name ?: "<anonymous>"
		val superClass = cls.superClass?.qualifiedName?.takeIf { it != "java.lang.Object" }
		val interfaces = cls.interfaces.mapNotNull { it.qualifiedName }

		val fields = cls.fields
			.filter { !it.name.startsWith("$") && it.name != "INSTANCE" }
			.map { field ->
				val typeName = try {
					field.type.presentableText
				} catch (_: Exception) {
					"?"
				}
				"    $typeName ${field.name}"
			}

		val methods = cls.methods
			.filter { m ->
				val name = m.name
				!name.startsWith("get") && !name.startsWith("set")
						&& !name.startsWith("component") && !name.startsWith("$")
						&& name != "copy" && name != "toString" && name != "hashCode"
						&& name != "equals" && name != "serializer"
			}
			.map { method ->
				val params = method.parameterList.parameters
					.filter { it.name != "\$completion" }
					.joinToString(", ") {
						val typeName = try {
							it.type.presentableText
						} catch (_: Exception) {
							"?"
						}
						"$typeName ${it.name}"
					}
				val returnType = try {
					method.returnType?.presentableText ?: "void"
				} catch (_: Exception) {
					"?"
				}
				"    $returnType ${method.name}($params)"
			}

		return buildString {
			append(kind).append(' ').append(qualifiedName)
			append("\n  Location: ").append(location)
			val indentVf = cls.containingFile?.virtualFile
			if (indentVf != null && ProjectFileIndex.getInstance(project).isInContent(indentVf)) {
				val doc = FileDocumentManager.getInstance().getDocument(indentVf)
				if (doc != null) append("\n  Indent: ").append(detectIndentation(doc))
			}
			if (superClass != null) append("\n  Extends: ").append(superClass)
			if (interfaces.isNotEmpty()) append("\n  Implements: ").append(interfaces.joinToString(", "))

			if (fields.isNotEmpty()) {
				append("\n  Fields:")
				for (f in fields) {
					append('\n').append(f)
				}
			}
			if (methods.isNotEmpty()) {
				append("\n  Methods:")
				for (m in methods) {
					append('\n').append(m)
				}
			}

			if (includeSource) {
				val snippet = getClassSource(cls, maxLines)
				if (snippet.isNotEmpty()) {
					append("\n  Source:")
					append("\n    ")
					append(snippet.replace("\n", "\n    "))
				}
			}

			val vf = cls.containingFile?.virtualFile
			if (vf != null) {
				val entries = ProjectFileIndex.getInstance(project).getOrderEntriesForFile(vf)
					.filterIsInstance<LibraryOrderEntry>()
				val lib = entries.firstOrNull()?.library
				if (lib != null && lib.getFiles(OrderRootType.SOURCES).isEmpty()) {
					append("\n\n  [sources missing] Use download_sources(qualifiedClassName=\"")
					append(qualifiedName)
					append("\") to fetch real sources.")
				}
			}
		}
	}

	private fun getClassSource(cls: PsiClass, maxLines: Int): String {
		val navElement = cls.navigationElement
		val file = navElement.containingFile?.virtualFile ?: return ""
		val document = FileDocumentManager.getInstance().getDocument(file) ?: return ""
		val startOffset = navElement.textRange?.startOffset ?: return ""
		val startLine = document.getLineNumber(startOffset)
		val endLine = minOf(document.lineCount - 1, startLine + maxLines - 1)
		val endOffset = document.getLineEndOffset(endLine)
		return document.getText(TextRange(startOffset, endOffset))
	}
}
