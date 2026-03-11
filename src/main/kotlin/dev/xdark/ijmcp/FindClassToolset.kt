@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import dev.xdark.ijmcp.util.formatLocation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

class FindClassToolset : McpToolset {

    @Serializable
    data class MethodInfo(
        val name: String,
        val signature: String,
        val line: Int = 0,
    )

    @Serializable
    data class FieldInfo(
        val name: String,
        val type: String,
    )

    @Serializable
    data class ClassInfo(
        val qualifiedName: String,
        val kind: String,
        val location: String,
        val superClass: String?,
        val interfaces: List<String>,
        val fields: List<FieldInfo>,
        val methods: List<MethodInfo>,
        val sourceSnippet: String = "",
    )

    @Serializable
    data class FindClassResult(
        val classes: List<ClassInfo>,
        val count: Int,
    )

    @McpTool
    @McpDescription("""
        |Finds a class by name across the project and all libraries (like Ctrl+N in IntelliJ).
        |
        |Accepts a short class name (e.g. "ExternalSystemUtil") or a fully qualified name
        |(e.g. "com.intellij.openapi.project.Project").
        |
        |Returns the class outline: fields, methods with signatures, supertypes, and source location.
        |Useful for discovering and understanding APIs in dependencies.
    """)
    suspend fun find_class(
        @McpDescription("Class name (short or fully qualified)") className: String,
        @McpDescription("Search scope: 'all' (default, includes libraries) or 'project'") scope: String = "all",
        @McpDescription("Max results to return (default 5)") limit: Int = 5,
        @McpDescription("Include source snippet of the class declaration (default true)") includeSource: Boolean = true,
        @McpDescription("Max lines of source to include (default 50)") maxSourceLines: Int = 50,
    ): FindClassResult {
        val project = currentCoroutineContext().project
        val searchScope = when (scope) {
            "project" -> GlobalSearchScope.projectScope(project)
            else -> GlobalSearchScope.allScope(project)
        }

        val classes = readAction {
            val found = if (className.contains('.')) {
                JavaPsiFacade.getInstance(project).findClasses(className, searchScope).toList()
            } else {
                PsiShortNamesCache.getInstance(project)
                    .getClassesByName(className, searchScope).toList()
            }
            found.take(limit).map { cls -> buildClassInfo(cls, includeSource, maxSourceLines) }
        }

        return FindClassResult(classes = classes, count = classes.size)
    }

    private fun buildClassInfo(
        cls: PsiClass,
        includeSource: Boolean,
        maxSourceLines: Int,
    ): ClassInfo {
        val project = cls.project

        val kind = when {
            cls.isInterface -> "interface"
            cls.isEnum -> "enum"
            cls.isAnnotationType -> "annotation"
            cls.isRecord -> "record"
            else -> "class"
        }

        val fields = cls.fields
            .filter { !it.name.startsWith("$") && it.name != "INSTANCE" }
            .map { field ->
                val typeName = try { field.type.presentableText } catch (_: Exception) { "?" }
                FieldInfo(name = field.name, type = typeName)
            }

        val methods = cls.methods
            .filter { m ->
                val name = m.name
                // Filter Kotlin synthetic methods
                !name.startsWith("get") && !name.startsWith("set")
                    && !name.startsWith("component") && !name.startsWith("$")
                    && name != "copy" && name != "toString" && name != "hashCode"
                    && name != "equals" && name != "serializer"
            }
            .map { method ->
                val params = method.parameterList.parameters
                    .filter { it.name != "\$completion" }
                    .joinToString(", ") {
                        val typeName = try { it.type.presentableText } catch (_: Exception) { "?" }
                        "$typeName ${it.name}"
                    }
                val returnType = try {
                    method.returnType?.presentableText ?: "void"
                } catch (_: Exception) { "?" }
                MethodInfo(
                    name = method.name,
                    signature = "$returnType ${method.name}($params)",
                )
            }

        val superClass = cls.superClass?.qualifiedName?.takeIf { it != "java.lang.Object" }
        val interfaces = cls.interfaces.mapNotNull { it.qualifiedName }

        val sourceSnippet = if (includeSource) {
            getClassSource(cls, maxSourceLines)
        } else ""

        return ClassInfo(
            qualifiedName = cls.qualifiedName ?: cls.name ?: "<anonymous>",
            kind = kind,
            location = formatLocation(project, cls),
            superClass = superClass,
            interfaces = interfaces,
            fields = fields,
            methods = methods,
            sourceSnippet = sourceSnippet,
        )
    }

    private fun getClassSource(cls: PsiClass, maxLines: Int): String {
        val navElement = cls.navigationElement
        val file = navElement.containingFile?.virtualFile ?: return ""
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return ""
        val startOffset = navElement.textRange?.startOffset ?: return ""
        val startLine = document.getLineNumber(startOffset)
        val endLine = minOf(document.lineCount - 1, startLine + maxLines - 1)
        val endOffset = document.getLineEndOffset(endLine)
        return document.getText(TextRange(startOffset, endOffset))
    }
}
