@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiElement
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.currentCoroutineContext
import org.jetbrains.kotlin.psi.*

class FileStructureToolset : McpToolset {

    @McpTool
    @McpDescription(
        """
        |Returns the structural outline of a file — classes, methods, fields with line numbers and signatures.
        |This is the same as IntelliJ's Structure view (Alt+7).
        |
        |Useful for understanding file organization without reading the entire file.
        |Works with Java, Kotlin, and other JVM languages.
    """
    )
    suspend fun get_file_outline(
        @McpDescription("Path relative to the project root") file_path: String,
    ): Any {
        val project = currentCoroutineContext().project
        val resolved = resolveFile(project, file_path)

        return readAction {
            val document = resolved.document
            val psiFile = resolved.psiFile
            val entries = if (psiFile is KtFile) {
                psiFile.declarations.mapNotNull { buildKtEntry(it, document, 0) }
            } else {
                val classes = (psiFile as? PsiClassOwner)?.classes ?: emptyArray()
                classes.map { cls -> buildClassEntry(cls, document, 0) }
            }

            buildString {
                append(file_path)
                append(":\n")
                for (entry in entries) {
                    append(entry)
                }
            }
        }
    }

    private fun lineOf(element: PsiElement, document: Document): Int {
        val nav = element.navigationElement
        val offset = nav.textOffset
        if (offset < 0 || offset >= document.textLength) return 0
        return document.getLineNumber(offset) + 1
    }

    // Filter out Kotlin synthetic artifacts from light classes
    private fun isSynthetic(name: String): Boolean {
        return name.startsWith("component") || name == "copy" || name == "toString"
                || name == "hashCode" || name == "equals" || name.startsWith("get")
                || name.startsWith("set") || name == "serializer"
                || name.startsWith("$")
    }

    private fun isSyntheticClass(cls: PsiClass): Boolean {
        val name = cls.name ?: return true
        return name == "Companion" || name.startsWith("$")
    }

    private fun indent(depth: Int): String = "  ".repeat(depth + 1)

    private fun buildClassEntry(cls: PsiClass, document: Document, depth: Int): String {
        val prefix = indent(depth)

        val kind = when {
            cls.isInterface -> "interface"
            cls.isEnum -> "enum"
            cls.isAnnotationType -> "annotation"
            else -> "class"
        }

        return buildString {
            append(prefix)
            append(kind).append(' ').append(cls.name ?: "<anonymous>")
            append(" [line ").append(lineOf(cls, document)).append(']')
            append("\n")

            // Fields
            for (field in cls.fields) {
                if (field.name == "INSTANCE" || field.name.startsWith("$")) continue
                val typeName = try {
                    field.type.presentableText
                } catch (_: Exception) {
                    "?"
                }
                append(prefix)
                append("  ").append(typeName).append(' ').append(field.name)
                append(" [line ").append(lineOf(field, document)).append(']')
                append("\n")
            }

            // Methods
            for (method in cls.methods) {
                if (isSynthetic(method.name)) continue
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
                val label = if (method.isConstructor) "constructor" else returnType
                append(prefix)
                append("  ").append(label).append(' ').append(method.name).append('(').append(params).append(')')
                append(" [line ").append(lineOf(method, document)).append(']')
                append("\n")
            }

            // Inner classes
            for (inner in cls.innerClasses) {
                if (isSyntheticClass(inner)) continue
                append(buildClassEntry(inner, document, depth + 1))
            }
        }
    }

    private fun buildKtEntry(declaration: KtDeclaration, document: Document, depth: Int): String? {
        return when (declaration) {
            is KtEnumEntry -> null
            is KtClassOrObject -> buildKtClassEntry(declaration, document, depth)
            is KtNamedFunction -> buildKtFunctionEntry(declaration, document, depth)
            is KtProperty -> buildKtPropertyEntry(declaration, document, depth)
            is KtSecondaryConstructor -> {
                val prefix = indent(depth)
                val params = declaration.valueParameters.joinToString(", ") { p ->
                    "${p.name}: ${p.typeReference?.text ?: "?"}"
                }
                buildString {
                    append(prefix).append("constructor(").append(params).append(')')
                    append(" [line ").append(lineOf(declaration, document)).append("]\n")
                }
            }

            is KtTypeAlias -> {
                val prefix = indent(depth)
                buildString {
                    append(prefix).append("typealias ").append(declaration.name)
                    append(" = ").append(declaration.getTypeReference()?.text ?: "?")
                    append(" [line ").append(lineOf(declaration, document)).append("]\n")
                }
            }

            else -> null
        }
    }

    private fun buildKtClassEntry(cls: KtClassOrObject, document: Document, depth: Int): String {
        val prefix = indent(depth)

        val kind = when {
            cls is KtObjectDeclaration && cls.isCompanion() -> "companion object"
            cls is KtObjectDeclaration -> "object"
            (cls as? KtClass)?.isInterface() == true -> "interface"
            (cls as? KtClass)?.isEnum() == true -> "enum"
            (cls as? KtClass)?.isAnnotation() == true -> "annotation"
            else -> "class"
        }

        return buildString {
            append(prefix)
            append(kind).append(' ').append(cls.name ?: "<anonymous>")
            append(" [line ").append(lineOf(cls, document)).append(']')
            append("\n")

            cls.primaryConstructor?.let { ctor ->
                val params = ctor.valueParameters.joinToString(", ") { p ->
                    val valVar = when (p.valOrVarKeyword?.text) {
                        "var" -> "var "
                        "val" -> "val "
                        else -> ""
                    }
                    "$valVar${p.name}: ${p.typeReference?.text ?: "?"}"
                }
                append(prefix)
                append("  constructor(").append(params).append(')')
                append(" [line ").append(lineOf(ctor, document)).append(']')
                append("\n")
            }

            cls.body?.declarations?.forEach { decl ->
                buildKtEntry(decl, document, depth + 1)?.let { append(it) }
            }
        }
    }

    private fun buildKtFunctionEntry(function: KtNamedFunction, document: Document, depth: Int): String {
        val prefix = indent(depth)
        val params = function.valueParameters.joinToString(", ") { p ->
            "${p.name}: ${p.typeReference?.text ?: "?"}"
        }
        val returnType = function.typeReference?.text
        return buildString {
            append(prefix).append("fun ").append(function.name).append('(').append(params).append(')')
            if (returnType != null) append(": ").append(returnType)
            append(" [line ").append(lineOf(function, document)).append("]\n")
        }
    }

    private fun buildKtPropertyEntry(property: KtProperty, document: Document, depth: Int): String {
        val prefix = indent(depth)
        val typeText = property.typeReference?.text
        val keyword = if (property.isVar) "var" else "val"
        return buildString {
            append(prefix).append(keyword).append(' ').append(property.name)
            if (typeText != null) append(": ").append(typeText)
            append(" [line ").append(lineOf(property, document)).append("]\n")
        }
    }
}
