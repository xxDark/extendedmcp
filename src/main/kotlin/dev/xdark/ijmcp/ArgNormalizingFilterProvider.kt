package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolFilterProvider
import com.intellij.mcpserver.McpToolFilterProvider.DisallowMcpTools
import com.intellij.mcpserver.McpToolFilterProvider.McpToolFilter
import com.intellij.mcpserver.McpToolFilterProvider.McpToolFilterContext
import com.intellij.mcpserver.McpToolFilterProvider.McpToolFilterModification
import com.intellij.mcpserver.impl.McpServerService
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class ArgNormalizingFilterProvider : McpToolFilterProvider {
	override fun getFilters(
		clientInfo: Implementation?,
		sessionOptions: McpServerService.McpSessionOptions?
	): List<McpToolFilter> {
		return listOf(ToolDisablingFilter, ArgNormalizingFilter)
	}

	override fun getUpdates(
		clientInfo: Implementation?,
		scope: CoroutineScope,
		sessionOptions: McpServerService.McpSessionOptions?
	): Flow<Unit> {
		return updateFlow.asSharedFlow()
	}

	private object ToolDisablingFilter : McpToolFilter {
		override fun modify(context: McpToolFilterContext): McpToolFilterModification {
			val disabled = ToolFilterState.getInstance().getDisabledSet()
			val toDisallow = context.allowedTools.filter { it.descriptor.name in disabled }.toSet()
			return DisallowMcpTools(toDisallow)
		}
	}

	private object ArgNormalizingFilter : McpToolFilter {
		private val normalizers =
			listOf(UnknownParameterNormalizer(), StringEncodedJsonNormalizer(), SingleElementArrayNormalizer())

		override fun modify(context: McpToolFilterContext): McpToolFilterModification {
			val wrapped = context.allowedTools.map { ArgNormalizingMcpTool(it, normalizers) }.toSet()
			return object : McpToolFilterModification {
				override fun apply(context: McpToolFilterContext): McpToolFilterContext =
					context.copy(allowedTools = wrapped)
			}
		}
	}

	companion object {
		private val updateFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

		fun triggerUpdate() {
			updateFlow.tryEmit(Unit)
		}
	}
}
