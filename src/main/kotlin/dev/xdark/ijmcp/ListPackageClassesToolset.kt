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
import kotlinx.serialization.Serializable

class ListPackageClassesToolset : McpToolset {

    @Serializable
    data class ClassEntry(
        val qualifiedName: String,
        val kind: String,
    )

    @Serializable
    data class ListPackageResult(
        val packageName: String,
        val classes: List<ClassEntry>,
        val subPackages: List<String>,
        val count: Int,
    )

    @McpTool
    @McpDescription("""
        |Lists all classes in a Java/Kotlin package, including library and JDK packages.
        |
        |Returns class names with their kind (class/interface/enum/annotation) and any sub-packages.
        |Use recursive=true to include classes from all sub-packages.
        |
        |Examples:
        |  packageName="java.util.concurrent.locks"
        |  packageName="com.google.common.collect", recursive=true
    """)
    suspend fun list_package_classes(
        @McpDescription("Fully qualified package name (e.g. 'java.util.concurrent')") packageName: String,
        @McpDescription("Include classes from sub-packages (default false)") recursive: Boolean = false,
        @McpDescription("Search scope: 'all' (default, includes libraries) or 'project'") scope: String = "all",
    ): ListPackageResult {
        val project = currentCoroutineContext().project

        val searchScope = when (scope) {
            "project" -> GlobalSearchScope.projectScope(project)
            else -> GlobalSearchScope.allScope(project)
        }

        return readAction {
            val psiPackage = JavaPsiFacade.getInstance(project).findPackage(packageName)
                ?: mcpFail("Package '$packageName' not found")

            val classes = mutableListOf<ClassEntry>()
            val subPackages = mutableListOf<String>()

            collectClasses(psiPackage, searchScope, recursive, classes, subPackages)

            ListPackageResult(
                packageName = packageName,
                classes = classes,
                subPackages = subPackages,
                count = classes.size,
            )
        }
    }

    private fun collectClasses(
        pkg: com.intellij.psi.PsiPackage,
        scope: GlobalSearchScope,
        recursive: Boolean,
        classes: MutableList<ClassEntry>,
        subPackages: MutableList<String>,
    ) {
        for (cls in pkg.getClasses(scope)) {
            val qn = cls.qualifiedName ?: continue
            // Skip synthetic Kotlin classes and duplicates
            if (qn.contains("$")) continue
            if (classes.any { it.qualifiedName == qn }) continue
            classes.add(ClassEntry(qualifiedName = qn, kind = classKind(cls)))
        }

        for (sub in pkg.getSubPackages(scope)) {
            val subName = sub.qualifiedName
            if (subName in subPackages) continue
            if (recursive) {
                collectClasses(sub, scope, true, classes, subPackages)
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
