package dev.xdark.ijmcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.util.asTools
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
class ToolListService {

	fun getAllTools(): List<McpTool> {
		return McpToolsProvider.EP.extensionList.flatMap { provider ->
			try {
				provider.getTools()
			} catch (_: Exception) {
				emptyList()
			}
		}
	}

	fun getBuiltInToolNames(): Set<String> {
		val ourClassLoader = this::class.java.classLoader
		val builtIn = mutableSetOf<String>()
		for (toolset in McpToolset.EP.extensionList) {
			if (toolset::class.java.classLoader != ourClassLoader) {
				try {
					for (tool in toolset.asTools()) {
						builtIn.add(tool.descriptor.name)
					}
				} catch (_: Exception) {
				}
			}
		}
		return builtIn
	}

	companion object {
		fun getInstance(): ToolListService = service()
	}
}
