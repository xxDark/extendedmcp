package dev.xdark.ijmcp

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import java.awt.Dimension
import javax.swing.JComponent

class ToolFilterAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val provider = FilteredToolsProvider.getInstance() ?: return
        ToolFilterDialog(provider).show()
    }
}

private class ToolFilterDialog(private val provider: FilteredToolsProvider) : DialogWrapper(null) {
    private val checkBoxList = CheckBoxList<String>()
    private val toolNames: List<String>

    init {
        title = "MCP Tool Filter"
        val disabled = ToolFilterState.getInstance().getDisabledSet()
        val tools = provider.getAllToolsUnfiltered()
            .map { it.descriptor.name }
            .distinct()
            .sorted()
        toolNames = tools
        for (name in tools) {
            checkBoxList.addItem(name, name, name !in disabled)
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val scrollPane = JBScrollPane(checkBoxList)
        scrollPane.preferredSize = Dimension(400, 500)
        return scrollPane
    }

    override fun doOKAction() {
        val state = ToolFilterState.getInstance()
        for (i in toolNames.indices) {
            val name = toolNames[i]
            val enabled = checkBoxList.isItemSelected(i)
            state.setDisabled(name, !enabled)
        }
        FilteredToolsProvider.triggerRefresh()
        super.doOKAction()
    }
}
