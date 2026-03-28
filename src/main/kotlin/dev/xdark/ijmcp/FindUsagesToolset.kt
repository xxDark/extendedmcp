@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import dev.xdark.ijmcp.util.formatLocation
import dev.xdark.ijmcp.util.getContextText
import dev.xdark.ijmcp.util.resolveFile
import dev.xdark.ijmcp.util.resolveTargetElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

class FindUsagesToolset : McpToolset {

    @Serializable
    data class UsageLocation(
        val file: String,
        val line: Int,
        val column: Int,
        val context: String,
    )

    @Serializable
    data class FindUsagesResult(
        val symbolName: String,
        val declarationLocation: String,
        val usages: List<UsageLocation>,
        val count: Int,
        val truncated: Boolean = false,
    )

    @McpTool
    @McpDescription(
        """
        |Finds all usages (references) of a symbol across the project using IntelliJ's semantic search.
        |Unlike text search, this understands code structure and finds only actual references.
        |
        |Two modes of target identification:
        |  1. filePath + (symbolName OR line+column) — for symbols in project files
        |  2. qualifiedClassName + optional symbolName — for library/JDK classes and their members
        |
        |Examples:
        |  - qualifiedClassName="com.intellij.openapi.project.Project" → usages of the class
        |  - qualifiedClassName="com.intellij.openapi.project.Project", symbolName="getBasePath" → usages of that method
        |
        |Returns the declaration location and all usage locations with context.
    """
    )
    suspend fun find_usages(
        @McpDescription("Path relative to the project root. Use this OR qualifiedClassName.") filePath: String = "",
        @McpDescription("Fully qualified class name (e.g. com.example.MyClass). Use this OR filePath. Supports library classes.") qualifiedClassName: String = "",
        @McpDescription("Name of the symbol to find usages of. With filePath: alternative to line+column. With qualifiedClassName: method or field name.") symbolName: String = "",
        @McpDescription("1-based line number of the symbol. Used with column as alternative to symbolName.") line: Int = 0,
        @McpDescription("1-based column number of the symbol. Used with line.") column: Int = 0,
        @McpDescription("Index of overloaded method when qualifiedClassName + symbolName matches multiple overloads.") memberIndex: Int = -1,
        @McpDescription("Search scope: 'project' (default) or 'all' (includes libraries)") scope: String = "project",
        @McpDescription("Maximum number of usages to return (default 50). Prevents slow searches on common symbols.") maxResults: Int = 50,
    ): FindUsagesResult {
        val project = currentCoroutineContext().project

        val targetElement = if (qualifiedClassName.isNotEmpty()) {
            resolveByQualifiedName(project, qualifiedClassName, symbolName, memberIndex)
        } else if (filePath.isNotEmpty()) {
            val resolved = resolveFile(project, filePath)
            resolveTargetElement(resolved, symbolName, line, column)
        } else {
            mcpFail("Provide either filePath or qualifiedClassName")
        }

        val declarationLocation = readAction { formatLocation(project, targetElement) }
        val resolvedName = readAction { (targetElement as? PsiNamedElement)?.name ?: targetElement.text }

        val baseScope = when (scope) {
            "all" -> GlobalSearchScope.allScope(project)
            else -> GlobalSearchScope.projectScope(project)
        }

        // For member searches via qualifiedClassName, narrow scope to files that reference
        // the containing class first. This avoids resolving every text match of common
        // method names (e.g. "getInstance") across all library files.
        val searchScope = if (qualifiedClassName.isNotEmpty() && symbolName.isNotEmpty()) {
            val containingClass = readAction {
                val cls = JavaPsiFacade.getInstance(project).findClass(qualifiedClassName, GlobalSearchScope.allScope(project))
                    ?: mcpFail("Class '$qualifiedClassName' not found")
                (cls.navigationElement as? PsiClass) ?: cls
            }
            val classRefFiles = readAction {
                val files = mutableSetOf<com.intellij.openapi.vfs.VirtualFile>()
                ReferencesSearch.search(containingClass, baseScope).forEach(com.intellij.util.Processor { ref ->
                    ref.element.containingFile?.virtualFile?.let { files.add(it) }
                    true // collect all
                })
                files
            }
            if (classRefFiles.isEmpty()) {
                mcpFail("No references to class '$qualifiedClassName' found in the specified scope")
            }
            GlobalSearchScope.filesScope(project, classRefFiles)
        } else {
            baseScope
        }

        val usages = readAction {
            val results = mutableListOf<UsageLocation>()
            ReferencesSearch.search(targetElement, searchScope).forEach(com.intellij.util.Processor { ref ->
                val element = ref.element
                val loc = formatLocation(project, element)
                val usage = if (loc.endsWith("(library)")) {
                    UsageLocation(
                        file = loc,
                        line = 0,
                        column = 0,
                        context = getContextText(element),
                    )
                } else {
                    val parts = loc.split(":")
                    if (parts.size >= 3) {
                        UsageLocation(
                            file = parts[0],
                            line = parts[1].toIntOrNull() ?: 0,
                            column = parts[2].toIntOrNull() ?: 0,
                            context = getContextText(element),
                        )
                    } else null
                }
                if (usage != null) results.add(usage)
                results.size < maxResults // return false to stop search
            })
            results
        }

        val truncated = usages.size >= maxResults
        return FindUsagesResult(
            symbolName = resolvedName,
            declarationLocation = declarationLocation,
            usages = usages,
            count = usages.size,
            truncated = truncated,
        )
    }

    private suspend fun resolveByQualifiedName(
        project: Project,
        qualifiedClassName: String,
        symbolName: String,
        memberIndex: Int,
    ): PsiElement {
        return readAction {
            val scope = GlobalSearchScope.allScope(project)
            val compiledClass = JavaPsiFacade.getInstance(project).findClass(qualifiedClassName, scope)
                ?: mcpFail("Class '$qualifiedClassName' not found")
            // Navigate to source if available — compiled classes may not resolve references properly
            val psiClass = (compiledClass.navigationElement as? PsiClass) ?: compiledClass

            if (symbolName.isEmpty()) {
                psiClass
            } else {
                findMemberRecursively(psiClass, symbolName, memberIndex, qualifiedClassName)
            }
        }
    }

    private fun findMemberRecursively(
        psiClass: PsiClass,
        symbolName: String,
        memberIndex: Int,
        qualifiedClassName: String,
    ): PsiElement {
        // Collect methods and fields from this class and all inner classes (including Companion)
        val methods = mutableListOf<com.intellij.psi.PsiMethod>()
        val fields = mutableListOf<com.intellij.psi.PsiField>()

        fun collect(cls: PsiClass) {
            methods.addAll(cls.findMethodsByName(symbolName, false))
            cls.findFieldByName(symbolName, false)?.let { fields.add(it) }
            for (inner in cls.innerClasses) {
                collect(inner)
            }
        }
        collect(psiClass)

        if (methods.size > 1 && memberIndex < 0) {
            val overloads = methods.mapIndexed { i, m ->
                val params = m.parameterList.parameters.joinToString(", ") { p ->
                    "${p.type.presentableText} ${p.name}"
                }
                "  $i: $symbolName($params)"
            }.joinToString("\n")
            mcpFail("Multiple overloads found for '$symbolName'. Specify memberIndex:\n$overloads")
        }

        if (methods.isNotEmpty()) {
            return methods[if (memberIndex >= 0) memberIndex.coerceIn(methods.indices) else 0]
        }
        if (fields.isNotEmpty()) {
            return fields[0]
        }
        mcpFail("Member '$symbolName' not found in class '$qualifiedClassName'")
    }
}
