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

class LibrarySourcesToolset : McpToolset {

	@McpTool
	@McpDescription(
		"""
        |Checks which project libraries have their sources downloaded.
        |
        |Libraries without sources limit IDE features like "Find Implementations" and "Go to Source".
        |If many libraries are missing sources, ask the user to download them:
        |  - Gradle: Settings > Build > Build Tools > Gradle > check "Download sources"
        |  - Or right-click a dependency in the Project view > Download Sources
    """
	)
	suspend fun check_library_sources(): Any {
		val project = currentCoroutineContext().project

		return readAction {
			val withSourcesCount = intArrayOf(0)
			val withoutSourceNames = mutableListOf<String>()

			OrderEnumerator.orderEntries(project).forEachLibrary { library ->
				val name = library.name ?: "unknown"
				if (library.getFiles(OrderRootType.SOURCES).isNotEmpty()) {
					withSourcesCount[0]++
				} else {
					withoutSourceNames.add(name)
				}
				true
			}

			val total = withSourcesCount[0] + withoutSourceNames.size

			buildString {
				append("Library sources summary: ")
				append(total)
				append(" total, ")
				append(withSourcesCount[0])
				append(" with sources, ")
				append(withoutSourceNames.size)
				append(" without sources\n")

				if (withoutSourceNames.isNotEmpty()) {
					append("\nMissing sources:\n")
					for (name in withoutSourceNames) {
						append("  ")
						append(name)
						append("\n")
					}
				}
			}
		}
	}
}
