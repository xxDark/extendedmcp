package dev.xdark.ijmcp

import com.intellij.mcpserver.impl.McpServerService
import com.intellij.mcpserver.McpToolFilterProvider
import com.intellij.mcpserver.McpToolFilterProvider.McpToolFilter
import com.intellij.mcpserver.McpToolFilterProvider.McpToolFilterContext
import com.intellij.mcpserver.McpToolFilterProvider.McpToolFilterModification
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class ArgNormalizingFilterProvider : McpToolFilterProvider {
	override fun getFilters(clientInfo: Implementation?, sessionOptions: McpServerService.McpSessionOptions?): List<McpToolFilter> {
		return listOf(ArgNormalizingFilter)
	}

	override fun getUpdates(clientInfo: Implementation?, scope: CoroutineScope, sessionOptions: McpServerService.McpSessionOptions?): Flow<Unit> {
		return emptyFlow()
	}

	private object ArgNormalizingFilter : McpToolFilter {
		private val normalizers = listOf(UnknownParameterNormalizer(), StringEncodedArrayNormalizer())

		override fun modify(context: McpToolFilterContext): McpToolFilterModification {
			val wrapped = context.allowedTools.map { ArgNormalizingMcpTool(it, normalizers) }.toSet()
			return object : McpToolFilterModification {
				override fun apply(context: McpToolFilterContext): McpToolFilterContext =
					context.copy(allowedTools = wrapped)
			}
		}
	}
}
