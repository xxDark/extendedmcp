@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.OrderRootType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

class LibrarySourcesToolset : McpToolset {

    @Serializable
    data class LibraryInfo(
        val name: String,
        val hasSources: Boolean,
    )

    @Serializable
    data class LibrarySourcesResult(
        val totalLibraries: Int,
        val withSources: Int,
        val withoutSources: Int,
        val missingSourcesList: List<String>,
    )

    @McpTool
    @McpDescription("""
        |Checks which project libraries have their sources downloaded.
        |
        |Libraries without sources limit IDE features like "Find Implementations" and "Go to Source".
        |If many libraries are missing sources, ask the user to download them:
        |  - Gradle: Settings > Build > Build Tools > Gradle > check "Download sources"
        |  - Or right-click a dependency in the Project view > Download Sources
    """)
    suspend fun check_library_sources(): LibrarySourcesResult {
        val project = currentCoroutineContext().project

        return readAction {
            val withSources = mutableListOf<String>()
            val withoutSources = mutableListOf<String>()

            OrderEnumerator.orderEntries(project).forEachLibrary { library ->
                val name = library.name ?: "unknown"
                if (library.getFiles(OrderRootType.SOURCES).isNotEmpty()) {
                    withSources.add(name)
                } else {
                    withoutSources.add(name)
                }
                true
            }

            LibrarySourcesResult(
                totalLibraries = withSources.size + withoutSources.size,
                withSources = withSources.size,
                withoutSources = withoutSources.size,
                missingSourcesList = withoutSources,
            )
        }
    }
}
