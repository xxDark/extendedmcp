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
import kotlinx.serialization.Serializable
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

class AddMemberToolset : McpToolset {

    @Serializable
    data class AddMemberResult(
        val added: Boolean,
        val className: String,
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
        |Specify which class to add to using className (simple name of a class in the file).
        |If the file contains a single class, className can be omitted.
    """
    )
    suspend fun add_method(
        @McpDescription("Path relative to the project root") filePath: String,
        @McpDescription("The full method/function source text") methodText: String,
        @McpDescription("Simple name of the target class (optional if file has one class)") className: String = "",
    ): AddMemberResult {
        val project = currentCoroutineContext().project
        val resolved = resolveFile(project, filePath)

        val isKotlin = resolved.psiFile is KtFile

        if (isKotlin) {
            return addKotlinMethod(resolved, methodText, className)
        } else {
            return addJavaMethod(resolved, methodText, className)
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
        |Specify which class to add to using className (simple name of a class in the file).
        |If the file contains a single class, className can be omitted.
    """
    )
    suspend fun add_field(
        @McpDescription("Path relative to the project root") filePath: String,
        @McpDescription("The full field/property source text") fieldText: String,
        @McpDescription("Simple name of the target class (optional if file has one class)") className: String = "",
    ): AddMemberResult {
        val project = currentCoroutineContext().project
        val resolved = resolveFile(project, filePath)

        val isKotlin = resolved.psiFile is KtFile

        if (isKotlin) {
            return addKotlinProperty(resolved, fieldText, className)
        } else {
            return addJavaField(resolved, fieldText, className)
        }
    }

    private suspend fun findJavaClass(
        resolved: dev.xdark.ijmcp.util.ResolvedFile,
        className: String,
    ): PsiClass {
        return readAction {
            val classOwner = resolved.psiFile as? PsiClassOwner
                ?: mcpFail("File is not a class file")
            val classes = classOwner.classes
            if (classes.isEmpty()) mcpFail("No classes found in file")

            if (className.isNotEmpty()) {
                findJavaClassByName(classes, className)
                    ?: mcpFail("Class '$className' not found in file. Available: ${collectJavaClassNames(classes)}")
            } else {
                if (classes.size > 1) {
                    mcpFail("File contains multiple classes: ${classes.mapNotNull { it.name }}. Specify className.")
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
        className: String,
    ): KtClassOrObject {
        return readAction {
            val ktFile = resolved.psiFile as? KtFile
                ?: mcpFail("File is not a Kotlin file")
            val declarations = ktFile.declarations.filterIsInstance<KtClassOrObject>()
            if (declarations.isEmpty()) mcpFail("No classes found in file")

            if (className.isNotEmpty()) {
                findKotlinClassByName(ktFile.declarations, className)
                    ?: mcpFail("Class '$className' not found in file. Available: ${collectKotlinClassNames(ktFile.declarations)}")
            } else {
                if (declarations.size > 1) {
                    mcpFail("File contains multiple classes: ${declarations.mapNotNull { it.name }}. Specify className.")
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
        methodText: String,
        className: String,
    ): AddMemberResult {
        val project = resolved.psiFile.project
        val targetClass = findJavaClass(resolved, className)
        val resolvedClassName = readAction { targetClass.name ?: "<anonymous>" }

        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) {
                val factory = PsiElementFactory.getInstance(project)
                val method = factory.createMethodFromText(methodText, targetClass)
                val added = targetClass.add(method)
                JavaCodeStyleManager.getInstance(project).shortenClassReferences(added)
            }
        }

        withContext(Dispatchers.EDT) {
            FileDocumentManager.getInstance().saveDocument(resolved.document)
        }

        return AddMemberResult(
            added = true,
            className = resolvedClassName,
            memberText = methodText.lines().first().trim(),
            message = "Method added to $resolvedClassName"
        )
    }

    private suspend fun addJavaField(
        resolved: dev.xdark.ijmcp.util.ResolvedFile,
        fieldText: String,
        className: String,
    ): AddMemberResult {
        val project = resolved.psiFile.project
        val targetClass = findJavaClass(resolved, className)
        val resolvedClassName = readAction { targetClass.name ?: "<anonymous>" }

        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) {
                val factory = PsiElementFactory.getInstance(project)
                val field = factory.createFieldFromText(fieldText, targetClass)
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
            className = resolvedClassName,
            memberText = fieldText.trim(),
            message = "Field added to $resolvedClassName"
        )
    }

    private suspend fun addKotlinMethod(
        resolved: dev.xdark.ijmcp.util.ResolvedFile,
        methodText: String,
        className: String,
    ): AddMemberResult {
        val project = resolved.psiFile.project
        val targetClass = findKotlinClass(resolved, className)
        val resolvedClassName = readAction { targetClass.name ?: "<anonymous>" }

        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) {
                val factory = KtPsiFactory(project, markGenerated = true)
                val function = factory.createFunction(methodText)

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
            className = resolvedClassName,
            memberText = methodText.lines().first().trim(),
            message = "Function added to $resolvedClassName"
        )
    }

    private suspend fun addKotlinProperty(
        resolved: dev.xdark.ijmcp.util.ResolvedFile,
        fieldText: String,
        className: String,
    ): AddMemberResult {
        val project = resolved.psiFile.project
        val targetClass = findKotlinClass(resolved, className)
        val resolvedClassName = readAction { targetClass.name ?: "<anonymous>" }

        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) {
                val factory = KtPsiFactory(project, markGenerated = true)
                val property = factory.createProperty(fieldText)

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
            className = resolvedClassName,
            memberText = fieldText.trim(),
            message = "Property added to $resolvedClassName"
        )
    }
}
