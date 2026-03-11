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
import kotlinx.serialization.Serializable

class FileStructureToolset : McpToolset {

    @Serializable
    data class StructureEntry(
        val kind: String,
        val name: String,
        val line: Int,
        val signature: String = "",
        val children: List<StructureEntry> = emptyList(),
    )

    @Serializable
    data class FileStructureResult(
        val filePath: String,
        val entries: List<StructureEntry>,
    )

    @McpTool
    @McpDescription("""
        |Returns the structural outline of a file — classes, methods, fields with line numbers and signatures.
        |This is the same as IntelliJ's Structure view (Alt+7).
        |
        |Useful for understanding file organization without reading the entire file.
        |Works with Java, Kotlin, and other JVM languages.
    """)
    suspend fun get_file_outline(
        @McpDescription("Path relative to the project root") filePath: String,
    ): FileStructureResult {
        val project = currentCoroutineContext().project
        val resolved = resolveFile(project, filePath)

        val entries = readAction {
            val document = resolved.document
            val classes = (resolved.psiFile as? PsiClassOwner)?.classes ?: emptyArray()
            classes.map { cls -> buildClassEntry(cls, document) }
        }

        return FileStructureResult(filePath = filePath, entries = entries)
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

    private fun buildClassEntry(cls: PsiClass, document: Document): StructureEntry {
        val children = mutableListOf<StructureEntry>()

        for (field in cls.fields) {
            // Skip synthetic fields (serialization, companion INSTANCE)
            if (field.name == "INSTANCE" || field.name.startsWith("$")) continue
            val typeName = try { field.type.presentableText } catch (_: Exception) { "?" }
            children.add(StructureEntry(
                kind = "field",
                name = field.name,
                line = lineOf(field, document),
                signature = "$typeName ${field.name}",
            ))
        }

        for (method in cls.methods) {
            // Skip Kotlin synthetic methods (componentN, copy, getters/setters, etc.)
            if (isSynthetic(method.name)) continue
            val params = method.parameterList.parameters
                .filter { it.name != "\$completion" } // Filter Kotlin continuation param
                .joinToString(", ") {
                    val typeName = try { it.type.presentableText } catch (_: Exception) { "?" }
                    "$typeName ${it.name}"
                }
            val returnType = try { method.returnType?.presentableText ?: "void" } catch (_: Exception) { "?" }
            children.add(StructureEntry(
                kind = if (method.isConstructor) "constructor" else "method",
                name = method.name,
                line = lineOf(method, document),
                signature = "$returnType ${method.name}($params)",
            ))
        }

        for (inner in cls.innerClasses) {
            if (isSyntheticClass(inner)) continue
            children.add(buildClassEntry(inner, document))
        }

        val kind = when {
            cls.isInterface -> "interface"
            cls.isEnum -> "enum"
            cls.isAnnotationType -> "annotation"
            else -> "class"
        }

        return StructureEntry(
            kind = kind,
            name = cls.name ?: "<anonymous>",
            line = lineOf(cls, document),
            signature = cls.qualifiedName ?: cls.name ?: "",
            children = children,
        )
    }
}
