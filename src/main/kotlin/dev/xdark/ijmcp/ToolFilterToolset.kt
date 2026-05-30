@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool

class ToolFilterToolset : McpToolset {

	@McpTool
	@McpDescription("Lists all registered MCP tools with their enabled/disabled status.")
	suspend fun list_tools_filter(): Any {
		val disabled = ToolFilterState.getInstance().getDisabledSet()
		val tools = ToolListService.getInstance().getAllTools().map { tool ->
			Triple(
				tool.descriptor.name,
				tool.descriptor.description,
				tool.descriptor.name !in disabled,
			)
		}.sortedBy { it.first }

		return buildString {
			append(tools.size)
			append(" tools registered:\n\n")
			for ((name, description, enabled) in tools) {
				val status = if (enabled) "enabled" else "DISABLED"
				append('[').append(status).append("] ").append(name)
				val firstLine = description.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
				if (firstLine != null) {
					append(" — ").append(firstLine)
				}
				append("\n")
			}
		}
	}
}
