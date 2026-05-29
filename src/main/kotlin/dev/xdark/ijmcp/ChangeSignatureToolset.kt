@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.changeSignature.ChangeSignatureProcessor
import com.intellij.refactoring.changeSignature.ParameterInfoImpl
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class ChangeSignatureToolset : McpToolset {

	@Serializable
	data class ParamSpec(
		val name: String,
		val type: String,
		val oldIndex: Int = -1,
		val defaultValue: String = "",
	)

	@McpTool
	@McpDescription(
		"""
        |Changes a method's signature — rename, change return type, add/remove/reorder/rename parameters.
        |Automatically updates all call sites across the project.
        |
        |The `parameters` field is an array describing the NEW parameter list in desired order.
        |Each element: {"name": "paramName", "type": "paramType", "oldIndex": N, "defaultValue": "value"}
        |  - oldIndex: index of this parameter in the ORIGINAL method (0-based). Use -1 for newly added parameters.
        |  - defaultValue: for new parameters (oldIndex=-1), the value to insert at existing call sites.
        |  - To remove a parameter: simply omit it from the array.
        |  - To reorder: list in the new desired order with correct oldIndex values.
        |  - To rename: use the same oldIndex but a different name.
    """
	)
	suspend fun change_method_signature(
		@McpDescription("Path relative to the project root") file_path: String,
		@McpDescription("Name of the method to change") method_name: String,
		@McpDescription("1-based line number to disambiguate overloads (0 = first match)") line: Int = 0,
		@McpDescription("New method name (empty = keep current)") new_name: String = "",
		@McpDescription("New return type (empty = keep current)") new_return_type: String = "",
		@McpDescription("New visibility: public/protected/private/package (empty = keep current)") new_visibility: String = "",
		@McpDescription("Array of parameter specs (see description)") parameters: List<ParamSpec>,
		@McpDescription("Keep old method as a delegating overload (default false)") generate_delegate: Boolean = false,
	): Any {
		val project = currentCoroutineContext().project
		val resolved = resolveFile(project, file_path)

		val method = readAction {
			val classOwner = resolved.psiFile as? PsiClassOwner
				?: mcpFail("File is not a class file: $file_path")

			val allMethods = classOwner.classes.flatMap { cls ->
				cls.methods.filter { it.name == method_name }
			}

			if (allMethods.isEmpty()) {
				mcpFail("Method '$method_name' not found in $file_path")
			}

			if (line > 0) {
				val document = resolved.document
				allMethods.find { m ->
					val offset = m.navigationElement.textOffset
					if (offset < 0 || offset >= document.textLength) false
					else document.getLineNumber(offset) + 1 == line
				} ?: mcpFail("Method '$method_name' not found at line $line")
			} else {
				if (allMethods.size > 1) {
					val overloads = allMethods.mapIndexed { i, m ->
						val params = m.parameterList.parameters.joinToString(", ") {
							"${it.type.presentableText} ${it.name}"
						}
						val l = resolved.document.getLineNumber(m.navigationElement.textOffset) + 1
						"  [$i] $method_name($params) at line $l"
					}.joinToString("\n")
					mcpFail("Multiple overloads found. Specify 'line' to disambiguate:\n$overloads")
				}
				allMethods.first()
			}
		}

		// Check for signature conflicts (duplicate after change)
		readAction {
			val containingClass = method.containingClass
			if (containingClass != null) {
				val effectiveMethodName = new_name.ifEmpty { method.name }
				val newParamTypes = parameters.map { it.type }

				for (sibling in containingClass.methods) {
					if (sibling === method) continue
					if (sibling.name != effectiveMethodName) continue
					val siblingTypes = sibling.parameterList.parameters.map { it.type.presentableText }
					if (siblingTypes == newParamTypes) {
						val params = newParamTypes.joinToString(", ")
						mcpFail("Signature conflict: $effectiveMethodName($params) already exists in ${containingClass.name}")
					}
				}
			}
		}

		// Count call sites before refactoring
		val callSiteCount = readAction {
			ReferencesSearch.search(method, GlobalSearchScope.projectScope(project))
				.findAll()
				.size
		}

		// Build ParameterInfoImpl array
		val parameterInfos = readAction {
			val elementFactory = JavaPsiFacade.getElementFactory(project)
			parameters.map { spec ->
				if (spec.oldIndex >= 0) {
					// Existing parameter (possibly renamed/retyped)
					val psiType = try {
						elementFactory.createTypeFromText(spec.type, method)
					} catch (e: Exception) {
						mcpFail("Invalid type '${spec.type}': ${e.message}")
					}
					ParameterInfoImpl(spec.oldIndex, spec.name, psiType)
				} else {
					// New parameter
					val psiType = try {
						elementFactory.createTypeFromText(spec.type, method)
					} catch (e: Exception) {
						mcpFail("Invalid type '${spec.type}': ${e.message}")
					}
					ParameterInfoImpl(-1, spec.name, psiType, spec.defaultValue)
				}
			}.toTypedArray()
		}

		// Determine new return type (must always be non-null for the processor)
		val returnType = readAction {
			if (new_return_type.isNotEmpty()) {
				val elementFactory = JavaPsiFacade.getElementFactory(project)
				try {
					elementFactory.createTypeFromText(new_return_type, method)
				} catch (e: Exception) {
					mcpFail("Invalid return type '$new_return_type': ${e.message}")
				}
			} else {
				method.returnType ?: JavaPsiFacade.getElementFactory(project).createTypeFromText("void", method)
			}
		}

		val effectiveVisibility = when (new_visibility) {
			"public" -> com.intellij.psi.PsiModifier.PUBLIC
			"protected" -> com.intellij.psi.PsiModifier.PROTECTED
			"private" -> com.intellij.psi.PsiModifier.PRIVATE
			"package" -> com.intellij.psi.PsiModifier.PACKAGE_LOCAL
			"" -> null // keep current
			else -> mcpFail("Invalid visibility '$new_visibility'. Use: public/protected/private/package")
		}

		val effectiveName = new_name.ifEmpty {
			readAction { method.name }
		}

		// Run the refactoring
		withContext(Dispatchers.EDT) {
			val processor = ChangeSignatureProcessor(
				project,
				method,
				generate_delegate,
				effectiveVisibility,
				effectiveName,
				returnType,
				parameterInfos,
			)
			processor.run()
		}

		return "Changed signature of '$method_name'. $callSiteCount call site(s) updated."
	}
}
