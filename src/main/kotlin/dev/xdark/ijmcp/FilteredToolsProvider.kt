package dev.xdark.ijmcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.util.asTools
import com.intellij.openapi.diagnostic.logger

class FilteredToolsProvider : McpToolsProvider {

    private var initialized = false
    private val cachedProviderTools = mutableListOf<McpTool>()

    override fun getTools(): List<McpTool> {
        if (!initialized) {
            initialize()
        }

        val toolsetTools = McpToolset.EP.extensionList.flatMap { toolset ->
            try {
                toolset.asTools()
            } catch (e: Exception) {
                LOG.warn("Cannot load tools for $toolset", e)
                emptyList()
            }
        }

        val allTools = cachedProviderTools + toolsetTools
        val disabled = ToolFilterState.getInstance().getDisabledSet()
        return allTools.filter { it.descriptor.name !in disabled }
    }

    fun getAllToolsUnfiltered(): List<McpTool> {
        if (!initialized) {
            initialize()
        }

        val toolsetTools = McpToolset.EP.extensionList.flatMap { toolset ->
            try {
                toolset.asTools()
            } catch (e: Exception) {
                LOG.warn("Cannot load tools for $toolset", e)
                emptyList()
            }
        }

        return cachedProviderTools + toolsetTools
    }

    private fun initialize() {
        val ep = McpToolsProvider.EP.point
        val myClassName = this::class.java.name

        // Collect tools from non-toolset, non-self providers before we remove them
        for (provider in McpToolsProvider.EP.extensionList) {
            if (provider === this) continue
            // Skip ReflectionToolsProvider — we handle McpToolset conversion ourselves
            if (provider::class.java.name == REFLECTION_TOOLS_PROVIDER_CLASS) continue
            try {
                cachedProviderTools.addAll(provider.getTools())
            } catch (e: Exception) {
                LOG.warn("Cannot load tools from provider ${provider::class.java.name}", e)
            }
        }

        initialized = true

        // Unregister all other providers — keep only ourselves
        ep.unregisterExtensions({ className, _ ->
            className == myClassName
        }, false)

        LOG.info("FilteredToolsProvider initialized: ${cachedProviderTools.size} cached provider tools")
    }

    companion object {
        private val LOG = logger<FilteredToolsProvider>()
        private const val REFLECTION_TOOLS_PROVIDER_CLASS = "com.intellij.mcpserver.impl.ReflectionToolsProvider"

        fun getInstance(): FilteredToolsProvider? =
            McpToolsProvider.EP.findExtension(FilteredToolsProvider::class.java)

        fun triggerRefresh() {
            val ep = McpToolsProvider.EP.point
            val dummy = object : McpToolsProvider {
                override fun getTools(): List<McpTool> = emptyList()
            }
            @Suppress("DEPRECATION")
            ep.registerExtension(dummy)
            @Suppress("DEPRECATION")
            ep.unregisterExtension(dummy)
        }
    }
}
