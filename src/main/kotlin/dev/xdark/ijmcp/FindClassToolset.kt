@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
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
        |Returns the class outline: fields, methods with signatures, supertypes, and source location.
        |Useful for discovering and understanding APIs in dependencies.
    """
	)
	suspend fun find_class(
		@McpDescription("Class name (short or fully qualified)") class_name: String,
		@McpDescription("Search scope: 'all' (default, includes libraries) or 'project'") scope: String = "all",
		@McpDescription("Max results to return (default 5)") limit: Int = 5,
		@McpDescription("Include source snippet of the class declaration (default true)") include_source: Boolean = true,
		@McpDescription("Max lines of source to include (default 50)") max_source_lines: Int = 50,
	): Any {
		val project = currentCoroutineContext().project
		val searchScope = when (scope) {
			"project" -> GlobalSearchScope.projectScope(project)
			else -> GlobalSearchScope.allScope(project)
		}

		val classTexts = readAction {
			val found = if (class_name.contains('.')) {
				JavaPsiFacade.getInstance(project).findClasses(class_name, searchScope).toList()
			} else {
				PsiShortNamesCache.getInstance(project)
					.getClassesByName(class_name, searchScope).toList()
			}
			found.take(limit).map { cls -> formatClassInfo(cls, include_source, max_source_lines) }
		}

		return buildString {
			append(classTexts.size)
			append(" class")
			if (classTexts.size != 1) append("es")
			append(" found for \"")
			append(class_name)
			append("\":\n")

			for (text in classTexts) {
				append("\n")
				append(text)
				append("\n")
			}
		}
	}

	private fun formatClassInfo(
		cls: PsiClass,
		include_source: Boolean,
		max_source_lines: Int,
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

			if (include_source) {
				val snippet = getClassSource(cls, max_source_lines)
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
					append("\n\n  [sources missing] Use download_sources(class_name=\"")
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
		val start_line = document.getLineNumber(startOffset)
		val end_line = minOf(document.lineCount - 1, start_line + maxLines - 1)
		val endOffset = document.getLineEndOffset(end_line)
		return document.getText(TextRange(startOffset, endOffset))
	}
}
