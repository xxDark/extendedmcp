@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp.tools.docs

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiDocCommentOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import dev.xdark.ijmcp.util.*
import kotlinx.coroutines.currentCoroutineContext
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.psi.*

data class DocEntry(
	val name: String,
	val signature: String,
	val documentation: String?,
)

class GetDocumentationToolset : McpToolset {

	@McpTool
	@McpDescription(
		"""
        |Retrieves documentation from a class, method, or field.
        |
        |Returns the raw doc comment text for the target element.
        |For overloaded methods, returns documentation for all overloads unless memberIndex is specified.
        |
        |Lookup by either:
        |  - qualifiedClassName: fully qualified class name (e.g. "java.util.concurrent.locks.Lock")
        |    Works for any class in the project or libraries.
        |  - filePath + className: for project files, same as add_documentation.
        |
        |To get docs for the class itself, omit symbolName.
        |To get docs for a method or field, specify symbolName.
    """
	)
	suspend fun get_documentation(
		@McpDescription("Fully qualified class name (e.g. java.util.List). Use this OR filePath.") qualifiedClassName: String = "",
		@McpDescription("Path relative to the project root. Use this OR qualifiedClassName.") filePath: String = "",
		@McpDescription("Simple name of the target class (only with filePath)") className: String = "",
		@McpDescription("Name of the method or field (omit to get class docs)") symbolName: String = "",
		@McpDescription("Index of a specific overloaded method") memberIndex: Int = -1,
	): Any {
		val project = currentCoroutineContext().project

		val entries = if (qualifiedClassName.isNotEmpty()) {
			getDocByQualifiedName(project, qualifiedClassName, symbolName, memberIndex)
		} else {
			if (filePath.isEmpty()) {
				mcpFail("Provide either qualifiedClassName or filePath")
			}
			val resolved = resolveFile(project, filePath)
			val isKotlin = resolved.psiFile is KtFile
			if (isKotlin) {
				getKotlinDoc(resolved, className, symbolName, memberIndex)
			} else {
				getJavaDoc(resolved, className, symbolName, memberIndex)
			}
		}

		return formatDocEntries(entries)
	}

	private fun formatDocEntries(entries: List<DocEntry>): String = buildString {
		for ((i, entry) in entries.withIndex()) {
			if (i > 0) {
				appendLine()
				appendLine()
			}
			append("Documentation for ").append(entry.signature).appendLine(":")
			appendLine()
			val doc = entry.documentation
			if (doc != null) {
				append(doc)
			} else {
				append("(no documentation)")
			}
		}
	}.trimEnd()

	private suspend fun getDocByQualifiedName(
		project: Project,
		qualifiedClassName: String,
		symbolName: String,
		memberIndex: Int,
	): List<DocEntry> {
		return smartReadAction(project) {
			val scope = GlobalSearchScope.allScope(project)
			val compiledClass = JavaPsiFacade.getInstance(project).findClass(qualifiedClassName, scope)
				?: mcpFail("Class '$qualifiedClassName' not found")
			// Navigate to source if available (compiled classes don't carry doc comments)
			val psiClass = (compiledClass.navigationElement as? PsiClass) ?: compiledClass

			if (symbolName.isEmpty()) {
				val doc = (psiClass as? PsiDocCommentOwner)?.docComment?.text
				listOf(
					DocEntry(
						psiClass.name ?: "<anonymous>",
						"class ${compiledClass.qualifiedName}",
						doc
					)
				)
			} else {
				val methods = psiClass.findMethodsByName(symbolName, false)
				val field = psiClass.findFieldByName(symbolName, false)

				if (methods.isNotEmpty()) {
					val targets = if (memberIndex >= 0) {
						listOf(methods[memberIndex.coerceIn(methods.indices)])
					} else {
						methods.toList()
					}
					targets.map { m ->
						// Navigate to source for doc comment
						val srcMethod = (m.navigationElement as? PsiDocCommentOwner) ?: m
						val params = m.parameterList.parameters.joinToString(", ") { p ->
							"${p.type.presentableText} ${p.name}"
						}
						DocEntry(m.name, "$symbolName($params)", srcMethod.docComment?.text)
					}
				} else if (field != null) {
					val srcField = (field.navigationElement as? PsiDocCommentOwner) ?: field
					listOf(
						DocEntry(
							field.name ?: symbolName,
							field.text.lines().first().trim(),
							srcField.docComment?.text
						)
					)
				} else {
					mcpFail("Member '$symbolName' not found in class '$qualifiedClassName'")
				}
			}
		}
	}

	private suspend fun getJavaDoc(
		resolved: ResolvedFile,
		className: String,
		symbolName: String,
		memberIndex: Int,
	): List<DocEntry> {
		return readAction {
			val classOwner = resolved.psiFile as? PsiClassOwner
				?: mcpFail("File is not a Java class file")
			val classes = classOwner.classes
			if (classes.isEmpty()) mcpFail("No classes found in file")

			val psiClass = if (className.isNotEmpty()) {
				findJavaClassByName(classes, className)
					?: mcpFail("Class '$className' not found. Available: ${collectJavaClassNames(classes)}")
			} else {
				classes[0]
			}

			if (symbolName.isEmpty()) {
				val doc = psiClass.docComment?.text
				listOf(DocEntry(psiClass.name ?: "<anonymous>", "class ${psiClass.name}", doc))
			} else {
				val methods = psiClass.findMethodsByName(symbolName, false)
				val field = psiClass.findFieldByName(symbolName, false)

				if (methods.isNotEmpty()) {
					val targets = if (memberIndex >= 0) {
						listOf(methods[memberIndex.coerceIn(methods.indices)])
					} else {
						methods.toList()
					}
					targets.map { m ->
						val params = m.parameterList.parameters.joinToString(", ") { p ->
							"${p.type.presentableText} ${p.name}"
						}
						DocEntry(m.name, "$symbolName($params)", m.docComment?.text)
					}
				} else if (field != null) {
					listOf(
						DocEntry(
							field.name ?: symbolName,
							field.text.lines().first().trim(),
							field.docComment?.text
						)
					)
				} else {
					mcpFail("Member '$symbolName' not found in class '${psiClass.name}'")
				}
			}
		}
	}

	private suspend fun getKotlinDoc(
		resolved: ResolvedFile,
		className: String,
		symbolName: String,
		memberIndex: Int,
	): List<DocEntry> {
		return readAction {
			val ktFile = resolved.psiFile as? KtFile
				?: mcpFail("File is not a Kotlin file")

			if (className.isNotEmpty()) {
				val ktClass = findKotlinClassByName(ktFile.declarations, className)
					?: mcpFail("Class '$className' not found. Available: ${collectKotlinClassNames(ktFile.declarations)}")

				if (symbolName.isEmpty()) {
					val doc = PsiTreeUtil.getChildOfType(ktClass, KDoc::class.java)?.text
					listOf(DocEntry(ktClass.name ?: "<anonymous>", "class ${ktClass.name}", doc))
				} else {
					val body = ktClass.body ?: mcpFail("Class '${ktClass.name}' has no body")
					getKotlinMemberDocs(body.declarations, symbolName, memberIndex, "in class '${ktClass.name}'")
				}
			} else if (symbolName.isNotEmpty()) {
				val topLevel = ktFile.declarations.filter {
					(it is KtNamedFunction && it.name == symbolName) ||
							(it is KtProperty && it.name == symbolName)
				}
				if (topLevel.isNotEmpty()) {
					getKotlinMemberDocs(ktFile.declarations, symbolName, memberIndex, "at top level")
				} else {
					val classes = ktFile.declarations.filterIsInstance<KtClassOrObject>()
					if (classes.size == 1) {
						val ktClass = classes[0]
						val body = ktClass.body ?: mcpFail("Class '${ktClass.name}' has no body")
						getKotlinMemberDocs(
							body.declarations,
							symbolName,
							memberIndex,
							"as top-level or in class '${ktClass.name}'"
						)
					} else {
						mcpFail("'$symbolName' not found as a top-level declaration")
					}
				}
			} else {
				val classes = ktFile.declarations.filterIsInstance<KtClassOrObject>()
				if (classes.isEmpty()) mcpFail("No classes found and no symbolName specified")
				val ktClass = classes[0]
				val doc = PsiTreeUtil.getChildOfType(ktClass, KDoc::class.java)?.text
				listOf(DocEntry(ktClass.name ?: "<anonymous>", "class ${ktClass.name}", doc))
			}
		}
	}

	private fun getKotlinMemberDocs(
		declarations: List<KtDeclaration>,
		symbolName: String,
		memberIndex: Int,
		context: String,
	): List<DocEntry> {
		val functions = declarations.filter { it is KtNamedFunction && it.name == symbolName }
		val properties = declarations.filter { it is KtProperty && it.name == symbolName }

		if (functions.isNotEmpty()) {
			val targets = if (memberIndex >= 0) {
				listOf(functions[memberIndex.coerceIn(functions.indices)])
			} else {
				functions
			}
			return targets.map { f ->
				val func = f as KtNamedFunction
				val params = func.valueParameters.joinToString(", ") { p ->
					"${p.name ?: "_"}: ${p.typeReference?.text ?: "Any"}"
				}
				DocEntry(
					func.name ?: symbolName,
					"$symbolName($params)",
					PsiTreeUtil.getChildOfType(f, KDoc::class.java)?.text
				)
			}
		}
		if (properties.isNotEmpty()) {
			val prop = properties[0] as KtProperty
			return listOf(
				DocEntry(
					prop.name ?: symbolName,
					prop.text.lines().first().trim(),
					PsiTreeUtil.getChildOfType(prop, KDoc::class.java)?.text
				)
			)
		}
		mcpFail("'$symbolName' not found $context")
	}
}
