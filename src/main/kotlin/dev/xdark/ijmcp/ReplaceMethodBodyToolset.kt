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
        val className: String,
        val methodName: String,
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
        |with its index and parameter types. Re-call with memberIndex to pick the correct one.
    """
    )
    suspend fun replace_method_body(
        @McpDescription("Path relative to the project root") filePath: String,
        @McpDescription("Name of the method/function") methodName: String,
        @McpDescription("The new method body (including braces, or = for Kotlin expression body)") newBody: String,
        @McpDescription("Simple name of the target class (optional if file has one class)") className: String = "",
        @McpDescription("Index of the overloaded method (from the overload list error)") memberIndex: Int = -1,
    ): ReplaceMethodBodyResult {
        val project = currentCoroutineContext().project
        val resolved = resolveFile(project, filePath)

        val isKotlin = resolved.psiFile is KtFile

        val result = if (isKotlin) {
            replaceKotlinBody(resolved, methodName, newBody, className, memberIndex)
        } else {
            replaceJavaBody(resolved, methodName, newBody, className, memberIndex)
        }

        withContext(Dispatchers.EDT) {
            FileDocumentManager.getInstance().saveDocument(resolved.document)
        }

        return result
    }

    private suspend fun replaceJavaBody(
        resolved: dev.xdark.ijmcp.util.ResolvedFile,
        methodName: String,
        newBody: String,
        className: String,
        memberIndex: Int,
    ): ReplaceMethodBodyResult {
        val project = resolved.psiFile.project

        val method: PsiMethod = readAction {
            val classOwner = resolved.psiFile as? PsiClassOwner
                ?: mcpFail("File is not a Java class file")
            val classes = classOwner.classes
            if (classes.isEmpty()) mcpFail("No classes found in file")

            val psiClass = if (className.isNotEmpty()) {
                findJavaClassByName(classes, className)
                    ?: mcpFail("Class '$className' not found. Available: ${collectJavaClassNames(classes)}")
            } else if (classes.size > 1) {
                // Search all classes for the method
                val found = findMethodInClasses(classes, methodName)
                if (found.size == 1) return@readAction found[0]
                else if (found.size > 1) mcpFail("Method '$methodName' found in multiple classes. Specify className.")
                else mcpFail("Method '$methodName' not found in any class")
            } else {
                classes[0]
            }
            resolveJavaMethod(psiClass, methodName, memberIndex)
        }

        val resolvedClassName = readAction { method.containingClass?.name ?: "<anonymous>" }

        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) {
                val oldBody = method.body
                    ?: mcpFail("Method '$methodName' has no body (abstract or native)")
                val factory = PsiElementFactory.getInstance(project)
                val bodyText = if (newBody.trimStart().startsWith("{")) newBody else "{ $newBody }"
                val newBlock = factory.createCodeBlockFromText(bodyText, method as com.intellij.psi.PsiElement)
                oldBody.replace(newBlock)
                JavaCodeStyleManager.getInstance(project).shortenClassReferences(method.body!!)
            }
        }

        return ReplaceMethodBodyResult(
            success = true,
            className = resolvedClassName,
            methodName = methodName,
            message = "Replaced body of $resolvedClassName.$methodName"
        )
    }

    private suspend fun replaceKotlinBody(
        resolved: dev.xdark.ijmcp.util.ResolvedFile,
        methodName: String,
        newBody: String,
        className: String,
        memberIndex: Int,
    ): ReplaceMethodBodyResult {
        val project = resolved.psiFile.project

        val function = readAction {
            val ktFile = resolved.psiFile as? KtFile
                ?: mcpFail("File is not a Kotlin file")

            if (className.isNotEmpty()) {
                val ktClass = findKotlinClassByName(ktFile.declarations, className)
                    ?: mcpFail("Class '$className' not found. Available: ${collectKotlinClassNames(ktFile.declarations)}")
                val body = ktClass.body ?: mcpFail("Class '$className' has no body")
                resolveKotlinFunction(body.declarations, methodName, memberIndex, "in class '$className'")
            } else {
                // Try top-level first
                val topLevel = ktFile.declarations.filterIsInstance<KtNamedFunction>().filter { it.name == methodName }
                if (topLevel.isNotEmpty()) {
                    resolveKotlinFunction(ktFile.declarations, methodName, memberIndex, "at top level")
                } else {
                    // Fall back to single class
                    val classes = ktFile.declarations.filterIsInstance<KtClassOrObject>()
                    if (classes.size == 1) {
                        val ktClass = classes[0]
                        val body = ktClass.body ?: mcpFail("Class '${ktClass.name}' has no body")
                        resolveKotlinFunction(body.declarations, methodName, memberIndex, "in class '${ktClass.name}'")
                    } else {
                        mcpFail("Method '$methodName' not found. Specify className.")
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
                val trimmed = newBody.trimStart()

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
                    val bodyText = if (trimmed.startsWith("{")) newBody else "{\n$newBody\n}"
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
            className = resolvedClassName,
            methodName = methodName,
            message = "Replaced body of $resolvedClassName.$methodName"
        )
    }

    private fun resolveJavaMethod(psiClass: PsiClass, methodName: String, memberIndex: Int): PsiMethod {
        val methods = psiClass.findMethodsByName(methodName, false)
        if (methods.isEmpty()) mcpFail("Method '$methodName' not found in class '${psiClass.name}'")

        if (methods.size > 1 && memberIndex < 0) {
            val overloads = methods.mapIndexed { i, m ->
                val params = m.parameterList.parameters.joinToString(", ") { p ->
                    "${p.type.presentableText} ${p.name}"
                }
                "  $i: $methodName($params)"
            }.joinToString("\n")
            mcpFail("Multiple overloads found for '$methodName'. Specify memberIndex:\n$overloads")
        }

        return methods[if (memberIndex >= 0) memberIndex.coerceIn(methods.indices) else 0]
    }

    private fun resolveKotlinFunction(
        declarations: List<org.jetbrains.kotlin.psi.KtDeclaration>,
        methodName: String,
        memberIndex: Int,
        context: String,
    ): KtNamedFunction {
        val functions = declarations.filterIsInstance<KtNamedFunction>().filter { it.name == methodName }
        if (functions.isEmpty()) mcpFail("Function '$methodName' not found $context")

        if (functions.size > 1 && memberIndex < 0) {
            val overloads = functions.mapIndexed { i, f ->
                val params = f.valueParameters.joinToString(", ") { p ->
                    "${p.name ?: "_"}: ${p.typeReference?.text ?: "Any"}"
                }
                "  $i: $methodName($params)"
            }.joinToString("\n")
            mcpFail("Multiple overloads found for '$methodName' $context. Specify memberIndex:\n$overloads")
        }

        return functions[if (memberIndex >= 0) memberIndex.coerceIn(functions.indices) else 0]
    }

    private fun findMethodInClasses(classes: Array<PsiClass>, methodName: String): List<PsiMethod> {
        val found = mutableListOf<PsiMethod>()
        for (cls in classes) {
            found.addAll(cls.findMethodsByName(methodName, false))
            found.addAll(findMethodInClasses(cls.innerClasses, methodName))
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
