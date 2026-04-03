@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenameUtil
import dev.xdark.ijmcp.util.formatLocation
import dev.xdark.ijmcp.util.getContextText
import dev.xdark.ijmcp.util.resolveFile
import dev.xdark.ijmcp.util.resolveTargetElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

class RenameMemberToolset : McpToolset {

    @Serializable
    data class AffectedUsage(
        val location: String,
        val context: String,
    )

    @Serializable
    data class RenameMemberResult(
        val renamed: Boolean,
        val oldName: String,
        val newName: String,
        val affectedUsages: List<AffectedUsage>,
        val message: String,
    )

    @McpTool
    @McpDescription(
        """
        |Renames a field or method and updates all usages across the project.
        |Fails if renaming would cause a naming conflict (duplicate field name or method signature clash).
        |
        |This is equivalent to IntelliJ's Refactor > Rename (Shift+F6).
    """
    )
    suspend fun rename_member(
        @McpDescription("Path relative to the project root") filePath: String,
        @McpDescription("Name of the member to rename") symbolName: String = "",
        @McpDescription("1-based line number (alternative to symbolName)") line: Int = 0,
        @McpDescription("1-based column number (used with line)") column: Int = 0,
        @McpDescription("New name for the member") newName: String,
        @McpDescription("Search in comments and strings (default true)") searchInComments: Boolean = true,
        @McpDescription("Search in non-Java/Kotlin files (default false)") searchInNonJavaFiles: Boolean = false,
    ): RenameMemberResult {
        val project = currentCoroutineContext().project
        val resolved = resolveFile(project, filePath)
        val element = resolveTargetElement(resolved, symbolName, line, column)

        val oldName = readAction {
            when (element) {
                is PsiField, is PsiMethod,
                is KtNamedFunction, is KtProperty -> {
                }

                else -> mcpFail(
                    "Element '${(element as? PsiNamedElement)?.name ?: "unknown"}' is not a field or method. " +
                            "Only fields and methods can be renamed with this tool."
                )
            }
            (element as PsiNamedElement).name ?: mcpFail("Element has no name")
        }

        if (oldName == newName) {
            mcpFail("New name '$newName' is the same as the current name")
        }

        // Validate the new name is a legal identifier for the element's language
        readAction {
            if (!RenameUtil.isValidName(project, element, newName)) {
                mcpFail("'$newName' is not a valid identifier")
            }
        }

        // Pre-check for conflicts in the containing class
        readAction {
            val containingClass = when (element) {
                is PsiMember -> element.containingClass
                is KtNamedFunction, is KtProperty -> {
                    val ktClass = PsiTreeUtil.getParentOfType(element, KtClassOrObject::class.java)
                    ktClass?.toLightClass()
                }

                else -> null
            }

            if (containingClass != null) {
                val isField = element is PsiField || element is KtProperty
                val isMethod = element is PsiMethod || element is KtNamedFunction

                if (isField) {
                    val existing = containingClass.findFieldByName(newName, false)
                    if (existing != null) {
                        mcpFail("Field '$newName' already exists in class '${containingClass.name}'")
                    }
                }

                if (isMethod) {
                    // Get parameter types of the method being renamed
                    val paramTypes: List<String> = when (element) {
                        is PsiMethod -> element.parameterList.parameters.map { it.type.presentableText }
                        is KtNamedFunction -> {
                            // Find the corresponding light method to get resolved parameter types
                            containingClass.methods
                                .firstOrNull { it.navigationElement === element }
                                ?.parameterList?.parameters?.map { it.type.presentableText }
                                ?: emptyList()
                        }

                        else -> emptyList()
                    }

                    for (sibling in containingClass.methods) {
                        // Skip the method being renamed (and its light class projections)
                        if (sibling === element) continue
                        if (sibling.navigationElement === element) continue
                        if (element is PsiMethod && sibling.navigationElement === element.navigationElement) continue

                        if (sibling.name != newName) continue
                        val siblingTypes = sibling.parameterList.parameters.map { it.type.presentableText }
                        if (siblingTypes == paramTypes) {
                            val params = paramTypes.joinToString(", ")
                            mcpFail("Method '$newName($params)' already exists in class '${containingClass.name}'")
                        }
                    }
                }
            }
        }

        // Collect usages before renaming (for reporting)
        val usagesBefore = readAction {
            ReferencesSearch.search(element, GlobalSearchScope.projectScope(project))
                .findAll()
                .map { ref ->
                    AffectedUsage(
                        location = formatLocation(project, ref.element),
                        context = getContextText(ref.element),
                    )
                }
        }

        // Run rename refactoring — BaseRefactoringProcessor.run() manages its own write actions
        withContext(Dispatchers.EDT) {
            val processor = RenameProcessor(
                project,
                element,
                newName,
                searchInComments,
                searchInNonJavaFiles,
            )
            processor.run()
        }

        withContext(Dispatchers.EDT) {
            FileDocumentManager.getInstance().saveAllDocuments()
        }

        return RenameMemberResult(
            renamed = true,
            oldName = oldName,
            newName = newName,
            affectedUsages = usagesBefore,
            message = "Renamed '$oldName' to '$newName'. ${usagesBefore.size} usage(s) updated.",
        )
    }
}
