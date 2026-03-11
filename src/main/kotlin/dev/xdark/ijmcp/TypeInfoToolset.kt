@file:Suppress("FunctionName", "unused", "UNCHECKED_CAST")

package dev.xdark.ijmcp

import com.intellij.lang.ExpressionTypeProvider
import com.intellij.lang.LanguageExpressionTypes
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.util.DocumentUtil
import dev.xdark.ijmcp.util.resolveFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

class TypeInfoToolset : McpToolset {

    @Serializable
    data class TypeInfoResult(
        val type: String?,
        val expressionText: String,
    )

    @McpTool
    @McpDescription("""
        |Returns the inferred type of the expression at the specified position.
        |
        |This is the same as IntelliJ's "Expression Type" action (Ctrl+Shift+P).
        |Useful for understanding types in languages with type inference (Kotlin, Java var, etc.).
        |
        |Returns the type as a human-readable string and the expression text.
    """)
    suspend fun get_type_info(
        @McpDescription("Path relative to the project root") filePath: String,
        @McpDescription("1-based line number") line: Int,
        @McpDescription("1-based column number") column: Int,
    ): TypeInfoResult {
        val project = currentCoroutineContext().project
        val resolved = resolveFile(project, filePath)

        return readAction {
            val document = FileDocumentManager.getInstance().getDocument(resolved.virtualFile)
                ?: mcpFail("Cannot read file: $filePath")
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
                ?: mcpFail("Cannot get PSI for: $filePath")

            if (!DocumentUtil.isValidLine(line - 1, document)) {
                mcpFail("Line $line is out of bounds")
            }
            val lineStartOffset = document.getLineStartOffset(line - 1)
            val offset = lineStartOffset + column - 1
            if (!DocumentUtil.isValidOffset(offset, document)) {
                mcpFail("Position $line:$column is out of bounds")
            }

            val elementAt = psiFile.findElementAt(offset)
                ?: mcpFail("No element at $line:$column")

            val (typeText, exprText) = findExpressionType(elementAt)

            TypeInfoResult(
                type = typeText,
                expressionText = exprText ?: elementAt.text,
            )
        }
    }

    private fun findExpressionType(element: PsiElement): Pair<String?, String?> {
        val language = element.language
        val providers = LanguageExpressionTypes.INSTANCE.forLanguage(language)
        if (providers != null) {
            val rawProvider = providers as ExpressionTypeProvider<PsiElement>
            try {
                val expressions = rawProvider.getExpressionsAt(element)
                for (expr in expressions) {
                    val hint = rawProvider.getInformationHint(expr)
                    if (hint.isNotBlank()) {
                        return hint to expr.text
                    }
                }
            } catch (_: Exception) {
                // Provider doesn't support this element type
            }
        }
        // Try parent element's language if different
        val parent = element.parent
        if (parent != null && parent.language != language) {
            val parentProvider = LanguageExpressionTypes.INSTANCE.forLanguage(parent.language)
            if (parentProvider != null) {
                val rawProvider = parentProvider as ExpressionTypeProvider<PsiElement>
                try {
                    val expressions = rawProvider.getExpressionsAt(element)
                    for (expr in expressions) {
                        val hint = rawProvider.getInformationHint(expr)
                        if (hint.isNotBlank()) {
                            return hint to expr.text
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        return null to null
    }
}
