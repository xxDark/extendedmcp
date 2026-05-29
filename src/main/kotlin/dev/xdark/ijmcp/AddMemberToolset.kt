@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

class AddMemberToolset : McpToolset {

	data class AddMemberResult(
		val added: Boolean,
		val class_name: String,
		val memberText: String,
		val message: String,
	)

	@McpTool
	@McpDescription(
		"""
        |Adds a method to a Java or Kotlin class via PSI.
        |
        |For Java, provide the full method source, e.g.:
        |  "public void greet(String name) { System.out.println(name); }"
        |You can use fully qualified names in the method text — they will be automatically shortened and imports added.
        |
        |For Kotlin, provide the full function declaration, e.g.:
        |  "fun greet(name: String) { println(name) }"
        |
        |Specify which class to add to using class_name (simple name of a class in the file).
        |If the file contains a single class, class_name can be omitted.
    """
	)
	suspend fun add_method(
		@McpDescription("Path relative to the project root") file_path: String,
		@McpDescription("The full method/function source text") method_text: String,
		@McpDescription("Simple name of the target class (optional if file has one class)") class_name: String = "",
	): Any {
		val project = currentCoroutineContext().project
		val resolved = resolveFile(project, file_path)

		val isKotlin = resolved.psiFile is KtFile

		return if (isKotlin) {
			addKotlinMethod(resolved, method_text, class_name).message
		} else {
			addJavaMethod(resolved, method_text, class_name).message
		}
	}

	@McpTool
	@McpDescription(
		"""
        |Adds a field (Java) or property (Kotlin) to a class via PSI.
        |
        |For Java, provide the full field declaration, e.g.:
        |  "private final String name;"
        |  "private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(\"MyClass\");"
        |Fully qualified names will be automatically shortened and imports added.
        |
        |For Kotlin, provide the full property declaration, e.g.:
        |  "val name: String = \"\""
        |  "private lateinit var service: MyService"
        |
        |Specify which class to add to using class_name (simple name of a class in the file).
        |If the file contains a single class, class_name can be omitted.
    """
	)
	suspend fun add_field(
		@McpDescription("Path relative to the project root") file_path: String,
		@McpDescription("The full field/property source text") field_text: String,
		@McpDescription("Simple name of the target class (optional if file has one class)") class_name: String = "",
	): Any {
		val project = currentCoroutineContext().project
		val resolved = resolveFile(project, file_path)

		val isKotlin = resolved.psiFile is KtFile

		return if (isKotlin) {
			addKotlinProperty(resolved, field_text, class_name).message
		} else {
			addJavaField(resolved, field_text, class_name).message
		}
	}

	private suspend fun findJavaClass(
		resolved: dev.xdark.ijmcp.util.ResolvedFile,
		class_name: String,
	): PsiClass {
		return readAction {
			val classOwner = resolved.psiFile as? PsiClassOwner
				?: mcpFail("File is not a class file")
			val classes = classOwner.classes
			if (classes.isEmpty()) mcpFail("No classes found in file")

			if (class_name.isNotEmpty()) {
				findJavaClassByName(classes, class_name)
					?: mcpFail("Class '$class_name' not found in file. Available: ${collectJavaClassNames(classes)}")
			} else {
				if (classes.size > 1) {
					mcpFail("File contains multiple classes: ${classes.mapNotNull { it.name }}. Specify class_name.")
				}
				classes[0]
			}
		}
	}

	private fun findJavaClassByName(classes: Array<PsiClass>, name: String): PsiClass? {
		for (cls in classes) {
			if (cls.name == name) return cls
			val inner = findJavaClassByName(cls.innerClasses, name)
			if (inner != null) return inner
		}
		return null
	}

	private fun collectJavaClassNames(classes: Array<PsiClass>): List<String> {
		val names = mutableListOf<String>()
		for (cls in classes) {
			cls.name?.let { names.add(it) }
			names.addAll(collectJavaClassNames(cls.innerClasses))
		}
		return names
	}

	private suspend fun findKotlinClass(
		resolved: dev.xdark.ijmcp.util.ResolvedFile,
		class_name: String,
	): KtClassOrObject {
		return readAction {
			val ktFile = resolved.psiFile as? KtFile
				?: mcpFail("File is not a Kotlin file")
			val declarations = ktFile.declarations.filterIsInstance<KtClassOrObject>()
			if (declarations.isEmpty()) mcpFail("No classes found in file")

			if (class_name.isNotEmpty()) {
				findKotlinClassByName(ktFile.declarations, class_name)
					?: mcpFail("Class '$class_name' not found in file. Available: ${collectKotlinClassNames(ktFile.declarations)}")
			} else {
				if (declarations.size > 1) {
					mcpFail("File contains multiple classes: ${declarations.mapNotNull { it.name }}. Specify class_name.")
				}
				declarations[0]
			}
		}
	}

	private fun findKotlinClassByName(
		declarations: List<org.jetbrains.kotlin.psi.KtDeclaration>,
		name: String
	): KtClassOrObject? {
		for (decl in declarations) {
			if (decl is KtClassOrObject) {
				if (decl.name == name) return decl
				val body = decl.body ?: continue
				val inner = findKotlinClassByName(body.declarations, name)
				if (inner != null) return inner
			}
		}
		return null
	}

	private fun collectKotlinClassNames(declarations: List<org.jetbrains.kotlin.psi.KtDeclaration>): List<String> {
		val names = mutableListOf<String>()
		for (decl in declarations) {
			if (decl is KtClassOrObject) {
				decl.name?.let { names.add(it) }
				val body = decl.body ?: continue
				names.addAll(collectKotlinClassNames(body.declarations))
			}
		}
		return names
	}

	private suspend fun addJavaMethod(
		resolved: dev.xdark.ijmcp.util.ResolvedFile,
		method_text: String,
		class_name: String,
	): AddMemberResult {
		val project = resolved.psiFile.project
		val targetClass = findJavaClass(resolved, class_name)
		val resolvedClassName = readAction { targetClass.name ?: "<anonymous>" }

		withContext(Dispatchers.EDT) {
			WriteCommandAction.runWriteCommandAction(project) {
				val factory = PsiElementFactory.getInstance(project)
				val method = factory.createMethodFromText(method_text, targetClass)
				val added = targetClass.add(method)
				JavaCodeStyleManager.getInstance(project).shortenClassReferences(added)
			}
		}

		withContext(Dispatchers.EDT) {
			FileDocumentManager.getInstance().saveDocument(resolved.document)
		}

		return AddMemberResult(
			added = true,
			class_name = resolvedClassName,
			memberText = method_text.lines().first().trim(),
			message = "Method added to $resolvedClassName"
		)
	}

	private suspend fun addJavaField(
		resolved: dev.xdark.ijmcp.util.ResolvedFile,
		field_text: String,
		class_name: String,
	): AddMemberResult {
		val project = resolved.psiFile.project
		val targetClass = findJavaClass(resolved, class_name)
		val resolvedClassName = readAction { targetClass.name ?: "<anonymous>" }

		withContext(Dispatchers.EDT) {
			WriteCommandAction.runWriteCommandAction(project) {
				val factory = PsiElementFactory.getInstance(project)
				val field = factory.createFieldFromText(field_text, targetClass)
				val existingFields = targetClass.fields
				val added = if (existingFields.isNotEmpty()) {
					targetClass.addAfter(field, existingFields.last())
				} else {
					val lBrace = targetClass.lBrace
					if (lBrace != null) {
						targetClass.addAfter(field, lBrace)
					} else {
						targetClass.add(field)
					}
				}
				JavaCodeStyleManager.getInstance(project).shortenClassReferences(added)
			}
		}

		withContext(Dispatchers.EDT) {
			FileDocumentManager.getInstance().saveDocument(resolved.document)
		}

		return AddMemberResult(
			added = true,
			class_name = resolvedClassName,
			memberText = field_text.trim(),
			message = "Field added to $resolvedClassName"
		)
	}

	private suspend fun addKotlinMethod(
		resolved: dev.xdark.ijmcp.util.ResolvedFile,
		method_text: String,
		class_name: String,
	): AddMemberResult {
		val project = resolved.psiFile.project
		val targetClass = findKotlinClass(resolved, class_name)
		val resolvedClassName = readAction { targetClass.name ?: "<anonymous>" }

		withContext(Dispatchers.EDT) {
			WriteCommandAction.runWriteCommandAction(project) {
				val factory = KtPsiFactory(project, markGenerated = true)
				val function = factory.createFunction(method_text)

				val body = targetClass.body
				if (body != null) {
					val rBrace = body.rBrace
					if (rBrace != null) {
						body.addBefore(factory.createNewLine(), rBrace)
						body.addBefore(function, rBrace)
					} else {
						body.add(function)
					}
				} else {
					val emptyBody = factory.createEmptyClassBody()
					val addedBody = targetClass.add(emptyBody) as org.jetbrains.kotlin.psi.KtClassBody
					val rBrace = addedBody.rBrace
					if (rBrace != null) {
						addedBody.addBefore(factory.createNewLine(), rBrace)
						addedBody.addBefore(function, rBrace)
					} else {
						addedBody.add(function)
					}
				}
			}
		}

		withContext(Dispatchers.EDT) {
			FileDocumentManager.getInstance().saveDocument(resolved.document)
		}

		return AddMemberResult(
			added = true,
			class_name = resolvedClassName,
			memberText = method_text.lines().first().trim(),
			message = "Function added to $resolvedClassName"
		)
	}

	private suspend fun addKotlinProperty(
		resolved: dev.xdark.ijmcp.util.ResolvedFile,
		field_text: String,
		class_name: String,
	): AddMemberResult {
		val project = resolved.psiFile.project
		val targetClass = findKotlinClass(resolved, class_name)
		val resolvedClassName = readAction { targetClass.name ?: "<anonymous>" }

		withContext(Dispatchers.EDT) {
			WriteCommandAction.runWriteCommandAction(project) {
				val factory = KtPsiFactory(project, markGenerated = true)
				val property = factory.createProperty(field_text)

				val body = targetClass.body
				if (body != null) {
					val existingProperties = body.properties
					if (existingProperties.isNotEmpty()) {
						body.addAfter(factory.createNewLine(), existingProperties.last())
						body.addAfter(property, existingProperties.last())
					} else {
						val lBrace = body.lBrace
						if (lBrace != null) {
							body.addAfter(factory.createNewLine(), lBrace)
							body.addAfter(property, lBrace)
						} else {
							body.add(property)
						}
					}
				} else {
					val emptyBody = factory.createEmptyClassBody()
					val addedBody = targetClass.add(emptyBody) as org.jetbrains.kotlin.psi.KtClassBody
					val lBrace = addedBody.lBrace
					if (lBrace != null) {
						addedBody.addAfter(factory.createNewLine(), lBrace)
						addedBody.addAfter(property, lBrace)
					} else {
						addedBody.add(property)
					}
				}
			}
		}

		withContext(Dispatchers.EDT) {
			FileDocumentManager.getInstance().saveDocument(resolved.document)
		}

		return AddMemberResult(
			added = true,
			class_name = resolvedClassName,
			memberText = field_text.trim(),
			message = "Property added to $resolvedClassName"
		)
	}
}
