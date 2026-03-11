@file:Suppress("FunctionName")

package dev.xdark.ijmcp.util

import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.util.projectDirectory
import com.intellij.mcpserver.util.relativizeIfPossible
import com.intellij.mcpserver.util.resolveInProject
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.DocumentUtil

data class ResolvedFile(
    val virtualFile: VirtualFile,
    val psiFile: PsiFile,
    val document: Document,
)

suspend fun resolveFile(project: Project, filePath: String): ResolvedFile {
    val resolvedPath = project.resolveInProject(filePath)
    val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(resolvedPath)
        ?: LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolvedPath)
        ?: mcpFail("File not found: $filePath")

    val document = readAction {
        FileDocumentManager.getInstance().getDocument(virtualFile)
    } ?: mcpFail("Cannot read file: $filePath")

    val psiFile = readAction {
        PsiDocumentManager.getInstance(project).getPsiFile(document)
    } ?: mcpFail("Cannot get PSI for: $filePath")

    return ResolvedFile(virtualFile, psiFile, document)
}

suspend fun findElementAtOffset(psiFile: PsiFile, document: Document, line: Int, column: Int): PsiElement {
    return readAction {
        if (!DocumentUtil.isValidLine(line - 1, document)) {
            mcpFail("Line $line is out of bounds")
        }
        val lineStartOffset = document.getLineStartOffset(line - 1)
        val offset = lineStartOffset + column - 1
        if (!DocumentUtil.isValidOffset(offset, document)) {
            mcpFail("Position $line:$column is out of bounds")
        }
        psiFile.findElementAt(offset)
    } ?: mcpFail("No element at $line:$column")
}

suspend fun findSymbolByName(psiFile: PsiFile, symbolName: String): PsiElement? {
    return readAction {
        var found: PsiElement? = null
        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (found != null) return
                if (element is PsiNamedElement && element.name == symbolName) {
                    found = element
                } else {
                    super.visitElement(element)
                }
            }
        })
        found
    }
}

fun formatLocation(project: Project, element: PsiElement): String {
    val navElement = element.navigationElement
    val file = navElement.containingFile?.virtualFile ?: return "<unknown>"
    val document = FileDocumentManager.getInstance().getDocument(file)
    val relativePath = project.projectDirectory.relativizeIfPossible(file)
    // For library classes, relativizeIfPossible returns a long cache path — use qualified name instead
    if (relativePath.contains(".jar!") || relativePath.startsWith("..")) {
        // Try to get FQN from PSI, then fall back to extracting from .class file path in JAR
        val target = element as? PsiQualifiedNamedElement
            ?: PsiTreeUtil.getParentOfType(element, PsiQualifiedNamedElement::class.java)
        val fqn = target?.qualifiedName
            ?: file.path.substringAfter("!/", "")
                .removeSuffix(".class")
                .replace('/', '.')
                .ifEmpty { (element as? PsiNamedElement)?.name ?: file.nameWithoutExtension }
        return if (document != null) {
            val offset = navElement.textOffset
            val line = document.getLineNumber(offset) + 1
            "$fqn (library)"
        } else {
            "$fqn (library)"
        }
    }
    if (document == null) return relativePath
    val offset = navElement.textOffset
    val line = document.getLineNumber(offset) + 1
    val col = offset - document.getLineStartOffset(line - 1) + 1
    return "$relativePath:$line:$col"
}

suspend fun resolveTargetElement(
    resolved: ResolvedFile,
    symbolName: String,
    line: Int,
    column: Int,
): PsiElement {
    return if (symbolName.isNotEmpty()) {
        findSymbolByName(resolved.psiFile, symbolName)
            ?: mcpFail("Symbol '$symbolName' not found")
    } else if (line > 0 && column > 0) {
        val element = findElementAtOffset(resolved.psiFile, resolved.document, line, column)
        readAction {
            // Walk up, trying to resolve references at each level before settling on a named element
            var current: PsiElement? = element
            while (current != null) {
                val ref = current.reference?.resolve()
                if (ref is PsiNamedElement) return@readAction ref
                if (current is PsiNamedElement) return@readAction current
                current = current.parent
            }
            current
        } ?: mcpFail("No named symbol at $line:$column")
    } else {
        mcpFail("Provide either symbolName or line+column")
    }
}

fun getContextText(element: PsiElement): String {
    val file = element.containingFile ?: return ""
    val document = FileDocumentManager.getInstance().getDocument(file.virtualFile) ?: return ""
    val offset = element.textOffset
    val lineNumber = document.getLineNumber(offset)
    val lineStart = document.getLineStartOffset(lineNumber)
    val lineEnd = document.getLineEndOffset(lineNumber)
    return document.getText(TextRange(lineStart, lineEnd)).trim()
}
