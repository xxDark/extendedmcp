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
import com.intellij.psi.PsiMethod
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory

class ReplaceMethodBodyToolset : McpToolset {

    @Serializable
    data class ReplaceMethodBodyResult(
        val success: Boolean,
        val class_name: String,
        val method_name: String,
        val message: String,
    )

    @McpTool
    @McpDescription(
        """
        |Replaces the body of a method or function.
        |
        |Much more efficient than replace_text_in_file — only send the new body, not the old one.
        |
        |For Java, provide the new body including braces:
        |  "{ return items.stream().filter(Objects::nonNull).toList(); }"
        |Fully qualified names will be auto-shortened and imports added.
        |
        |For Kotlin, provide the new block body with braces:
        |  "{ return items.filter { it != null } }"
        |Or an expression body:
        |  "= items.filter { it != null }"
        |
        |If there are overloaded methods, the tool returns an error listing each overload
        |with its index and parameter types. Re-call with member_index to pick the correct one.
    """
    )
    suspend fun replace_method_body(
        @McpDescription("Path relative to the project root") file_path: String,
        @McpDescription("Name of the method/function") method_name: String,
        @McpDescription("The new method body (including braces, or = for Kotlin expression body)") new_body: String,
        @McpDescription("Simple name of the target class (optional if file has one class)") class_name: String = "",
        @McpDescription("Index of the overloaded method (from the overload list error)") member_index: Int = -1,
    ): ReplaceMethodBodyResult {
        val project = currentCoroutineContext().project
        val resolved = resolveFile(project, file_path)

        val isKotlin = resolved.psiFile is KtFile

        val result = if (isKotlin) {
            replaceKotlinBody(resolved, method_name, new_body, class_name, member_index)
        } else {
            replaceJavaBody(resolved, method_name, new_body, class_name, member_index)
        }

        withContext(Dispatchers.EDT) {
            FileDocumentManager.getInstance().saveDocument(resolved.document)
        }

        return result
    }

    private suspend fun replaceJavaBody(
        resolved: dev.xdark.ijmcp.util.ResolvedFile,
        method_name: String,
        new_body: String,
        class_name: String,
        member_index: Int,
    ): ReplaceMethodBodyResult {
        val project = resolved.psiFile.project

        val method: PsiMethod = readAction {
            val classOwner = resolved.psiFile as? PsiClassOwner
                ?: mcpFail("File is not a Java class file")
            val classes = classOwner.classes
            if (classes.isEmpty()) mcpFail("No classes found in file")

            val psiClass = if (class_name.isNotEmpty()) {
                findJavaClassByName(classes, class_name)
                    ?: mcpFail("Class '$class_name' not found. Available: ${collectJavaClassNames(classes)}")
            } else if (classes.size > 1) {
                // Search all classes for the method
                val found = findMethodInClasses(classes, method_name)
                if (found.size == 1) return@readAction found[0]
                else if (found.size > 1) mcpFail("Method '$method_name' found in multiple classes. Specify class_name.")
                else mcpFail("Method '$method_name' not found in any class")
            } else {
                classes[0]
            }
            resolveJavaMethod(psiClass, method_name, member_index)
        }

        val resolvedClassName = readAction { method.containingClass?.name ?: "<anonymous>" }

        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) {
                val oldBody = method.body
                    ?: mcpFail("Method '$method_name' has no body (abstract or native)")
                val factory = PsiElementFactory.getInstance(project)
                val bodyText = if (new_body.trimStart().startsWith("{")) new_body else "{ $new_body }"
                val newBlock = factory.createCodeBlockFromText(bodyText, method as com.intellij.psi.PsiElement)
                oldBody.replace(newBlock)
                JavaCodeStyleManager.getInstance(project).shortenClassReferences(method.body!!)
            }
        }

        return ReplaceMethodBodyResult(
            success = true,
            class_name = resolvedClassName,
            method_name = method_name,
            message = "Replaced body of $resolvedClassName.$method_name"
        )
    }

    private suspend fun replaceKotlinBody(
        resolved: dev.xdark.ijmcp.util.ResolvedFile,
        method_name: String,
        new_body: String,
        class_name: String,
        member_index: Int,
    ): ReplaceMethodBodyResult {
        val project = resolved.psiFile.project

        val function = readAction {
            val ktFile = resolved.psiFile as? KtFile
                ?: mcpFail("File is not a Kotlin file")

            if (class_name.isNotEmpty()) {
                val ktClass = findKotlinClassByName(ktFile.declarations, class_name)
                    ?: mcpFail("Class '$class_name' not found. Available: ${collectKotlinClassNames(ktFile.declarations)}")
                val body = ktClass.body ?: mcpFail("Class '$class_name' has no body")
                resolveKotlinFunction(body.declarations, method_name, member_index, "in class '$class_name'")
            } else {
                // Try top-level first
                val topLevel = ktFile.declarations.filterIsInstance<KtNamedFunction>().filter { it.name == method_name }
                if (topLevel.isNotEmpty()) {
                    resolveKotlinFunction(ktFile.declarations, method_name, member_index, "at top level")
                } else {
                    // Fall back to single class
                    val classes = ktFile.declarations.filterIsInstance<KtClassOrObject>()
                    if (classes.size == 1) {
                        val ktClass = classes[0]
                        val body = ktClass.body ?: mcpFail("Class '${ktClass.name}' has no body")
                        resolveKotlinFunction(body.declarations, method_name, member_index, "in class '${ktClass.name}'")
                    } else {
                        mcpFail("Method '$method_name' not found. Specify class_name.")
                    }
                }
            }
        }

        val resolvedClassName = readAction {
            (function.parent?.parent as? KtClassOrObject)?.name ?: "<top-level>"
        }

        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) {
                val factory = KtPsiFactory(project, markGenerated = true)
                val trimmed = new_body.trimStart()

                if (trimmed.startsWith("=")) {
                    // Expression body
                    val exprText = trimmed.removePrefix("=").trim()
                    val tempFunc = factory.createFunction("fun _t_() = $exprText")
                    val newExpr = tempFunc.bodyExpression
                        ?: mcpFail("Invalid expression body")

                    // Remove old body (block or expression)
                    function.bodyBlockExpression?.delete()
                    function.bodyExpression?.let { oldExpr ->
                        function.equalsToken?.let { eq ->
                            oldExpr.replace(newExpr)
                            return@runWriteCommandAction
                        }
                    }
                    // Add = and expression
                    val eq = tempFunc.equalsToken!!
                    function.add(eq)
                    function.add(factory.createWhiteSpace(" "))
                    function.add(newExpr)
                } else {
                    // Block body
                    val bodyText = if (trimmed.startsWith("{")) new_body else "{\n$new_body\n}"
                    val tempFunc = factory.createFunction("fun _t_() $bodyText")
                    val newBlock = tempFunc.bodyBlockExpression
                        ?: mcpFail("Invalid block body")

                    val oldBlock = function.bodyBlockExpression
                    if (oldBlock != null) {
                        oldBlock.replace(newBlock)
                    } else {
                        // Was expression body, switching to block
                        function.equalsToken?.delete()
                        function.bodyExpression?.delete()
                        function.add(factory.createWhiteSpace(" "))
                        function.add(newBlock)
                    }
                }
            }
        }

        return ReplaceMethodBodyResult(
            success = true,
            class_name = resolvedClassName,
            method_name = method_name,
            message = "Replaced body of $resolvedClassName.$method_name"
        )
    }

    private fun resolveJavaMethod(psiClass: PsiClass, method_name: String, member_index: Int): PsiMethod {
        val methods = psiClass.findMethodsByName(method_name, false)
        if (methods.isEmpty()) mcpFail("Method '$method_name' not found in class '${psiClass.name}'")

        if (methods.size > 1 && member_index < 0) {
            val overloads = methods.mapIndexed { i, m ->
                val params = m.parameterList.parameters.joinToString(", ") { p ->
                    "${p.type.presentableText} ${p.name}"
                }
                "  $i: $method_name($params)"
            }.joinToString("\n")
            mcpFail("Multiple overloads found for '$method_name'. Specify member_index:\n$overloads")
        }

        return methods[if (member_index >= 0) member_index.coerceIn(methods.indices) else 0]
    }

    private fun resolveKotlinFunction(
        declarations: List<org.jetbrains.kotlin.psi.KtDeclaration>,
        method_name: String,
        member_index: Int,
        context: String,
    ): KtNamedFunction {
        val functions = declarations.filterIsInstance<KtNamedFunction>().filter { it.name == method_name }
        if (functions.isEmpty()) mcpFail("Function '$method_name' not found $context")

        if (functions.size > 1 && member_index < 0) {
            val overloads = functions.mapIndexed { i, f ->
                val params = f.valueParameters.joinToString(", ") { p ->
                    "${p.name ?: "_"}: ${p.typeReference?.text ?: "Any"}"
                }
                "  $i: $method_name($params)"
            }.joinToString("\n")
            mcpFail("Multiple overloads found for '$method_name' $context. Specify member_index:\n$overloads")
        }

        return functions[if (member_index >= 0) member_index.coerceIn(functions.indices) else 0]
    }

    private fun findMethodInClasses(classes: Array<PsiClass>, method_name: String): List<PsiMethod> {
        val found = mutableListOf<PsiMethod>()
        for (cls in classes) {
            found.addAll(cls.findMethodsByName(method_name, false))
            found.addAll(findMethodInClasses(cls.innerClasses, method_name))
        }
        return found
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
}
