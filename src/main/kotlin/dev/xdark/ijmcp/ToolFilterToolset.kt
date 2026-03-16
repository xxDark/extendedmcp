@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import kotlinx.serialization.Serializable

class ToolFilterToolset : McpToolset {

    @Serializable
    data class ToolInfo(val name: String, val description: String, val enabled: Boolean)

    @Serializable
    data class ToolListResult(val tools: List<ToolInfo>)

    @McpTool
    @McpDescription("Lists all registered MCP tools with their enabled/disabled status.")
    suspend fun list_tools_filter(): ToolListResult {
        val provider = FilteredToolsProvider.getInstance()
            ?: mcpFail("FilteredToolsProvider not registered")
        val disabled = ToolFilterState.getInstance().getDisabledSet()
        val tools = provider.getAllToolsUnfiltered().map { tool ->
            ToolInfo(
                name = tool.descriptor.name,
                description = tool.descriptor.description,
                enabled = tool.descriptor.name !in disabled,
            )
        }.sortedBy { it.name }
        return ToolListResult(tools)
    }
}
