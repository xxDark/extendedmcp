@file:Suppress("unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpCallInfo
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolSideEffectEvent
import com.intellij.mcpserver.ToolCallListener
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@State(name = "McpToolMetrics", storages = [Storage("mcpToolMetrics.xml")])
@Service(Service.Level.APP)
class McpMetricsService : PersistentStateComponent<McpMetricsService.State> {

	class State {
		var calls: MutableMap<String, Int> = mutableMapOf()
		var errors: MutableMap<String, Int> = mutableMapOf()
		var lastCall: MutableMap<String, Long> = mutableMapOf()
	}

	private var myState = State()

	@Synchronized
	fun recordCall(toolName: String, isError: Boolean) {
		myState.calls.merge(toolName, 1, Int::plus)
		if (isError) {
			myState.errors.merge(toolName, 1, Int::plus)
		}
		myState.lastCall[toolName] = System.currentTimeMillis()
	}

	@Synchronized
	override fun getState(): State = State().also {
		it.calls = HashMap(myState.calls)
		it.errors = HashMap(myState.errors)
		it.lastCall = HashMap(myState.lastCall)
	}

	@Synchronized
	override fun loadState(state: State) {
		myState = state
	}

	@Synchronized
	fun getMetrics(): List<ToolMetric> {
		return myState.calls.keys.map { name ->
			ToolMetric(
				name = name,
				calls = myState.calls[name] ?: 0,
				errors = myState.errors[name] ?: 0,
				lastCall = myState.lastCall[name] ?: 0,
			)
		}.sortedByDescending { it.calls }
	}

	@Synchronized
	fun reset() {
		myState = State()
	}

	data class ToolMetric(val name: String, val calls: Int, val errors: Int, val lastCall: Long)

	companion object {
		fun getInstance(): McpMetricsService = service()
	}
}

class McpMetricsListener : ToolCallListener {
	override fun afterMcpToolCall(
		mcpToolDescriptor: McpToolDescriptor,
		events: List<McpToolSideEffectEvent>,
		error: Throwable?,
		callInfo: McpCallInfo,
	) {
		McpMetricsService.getInstance().recordCall(mcpToolDescriptor.name, error != null)
	}
}
