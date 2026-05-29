@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.currentCoroutineContext

class ListPackageClassesToolset : McpToolset {

    @McpTool
    @McpDescription(
        """
        |Lists all classes in a Java/Kotlin package, including library and JDK packages.
        |
        |Returns class names with their kind (class/interface/enum/annotation) and any sub-packages.
        |Use recursive=true to include classes from all sub-packages.
        |
        |Examples:
        |  package_name="java.util.concurrent.locks"
        |  package_name="com.google.common.collect", recursive=true
    """
    )
    suspend fun list_package_classes(
        @McpDescription("Fully qualified package name (e.g. 'java.util.concurrent')") package_name: String,
        @McpDescription("Include classes from sub-packages (default false)") recursive: Boolean = false,
        @McpDescription("Search scope: 'all' (default, includes libraries) or 'project'") scope: String = "all",
    ): Any {
        val project = currentCoroutineContext().project

        val searchScope = when (scope) {
            "project" -> GlobalSearchScope.projectScope(project)
            else -> GlobalSearchScope.allScope(project)
        }

        return readAction {
            val psiPackage = JavaPsiFacade.getInstance(project).findPackage(package_name)
                ?: mcpFail("Package '$package_name' not found")

            data class ClassEntry(val qualifiedName: String, val kind: String)

            val classes = mutableListOf<ClassEntry>()
            val subPackages = mutableListOf<String>()

            fun collectClasses(pkg: com.intellij.psi.PsiPackage) {
                for (cls in pkg.getClasses(searchScope)) {
                    val qn = cls.qualifiedName ?: continue
                    if (qn.contains("$")) continue
                    if (classes.any { it.qualifiedName == qn }) continue
                    classes.add(ClassEntry(qualifiedName = qn, kind = classKind(cls)))
                }
                for (sub in pkg.getSubPackages(searchScope)) {
                    val subName = sub.qualifiedName
                    if (subName in subPackages) continue
                    subPackages.add(subName)
                    if (recursive) {
                        collectClasses(sub)
                    }
                }
            }

            collectClasses(psiPackage)

            buildString {
                append(package_name)
                append(": ")
                append(classes.size)
                append(" class")
                if (classes.size != 1) append("es")
                if (subPackages.isNotEmpty()) {
                    append(", ")
                    append(subPackages.size)
                    append(" sub-package")
                    if (subPackages.size != 1) append("s")
                }
                append("\n")

                if (classes.isNotEmpty()) {
                    append("\nClasses:\n")
                    for (entry in classes) {
                        append("  ")
                        append(entry.kind)
                        append(" ")
                        append(entry.qualifiedName)
                        append("\n")
                    }
                }

                if (subPackages.isNotEmpty()) {
                    append("\nSub-packages:\n")
                    for (sub in subPackages) {
                        append("  ")
                        append(sub)
                        append("\n")
                    }
                }
            }
        }
    }

    private fun classKind(cls: PsiClass): String = when {
        cls.isInterface -> "interface"
        cls.isEnum -> "enum"
        cls.isAnnotationType -> "annotation"
        cls.isRecord -> "record"
        else -> "class"
    }
}
