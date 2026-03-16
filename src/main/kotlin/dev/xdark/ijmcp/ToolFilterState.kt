package dev.xdark.ijmcp

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@State(name = "McpToolFilter", storages = [Storage("mcpToolFilter.xml")])
@Service(Service.Level.APP)
class ToolFilterState : PersistentStateComponent<ToolFilterState.State> {

    class State {
        var disabledTools: MutableSet<String> = mutableSetOf()
    }

    private var myState = State()

    @Synchronized
    fun isDisabled(toolName: String): Boolean = toolName in myState.disabledTools

    @Synchronized
    fun setDisabled(toolName: String, disabled: Boolean) {
        if (disabled) {
            myState.disabledTools.add(toolName)
        } else {
            myState.disabledTools.remove(toolName)
        }
    }

    @Synchronized
    fun getDisabledSet(): Set<String> = HashSet(myState.disabledTools)

    @Synchronized
    override fun getState(): State = State().also {
        it.disabledTools = HashSet(myState.disabledTools)
    }

    @Synchronized
    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): ToolFilterState = service()
    }
}
