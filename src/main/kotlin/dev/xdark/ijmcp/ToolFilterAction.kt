package dev.xdark.ijmcp

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ToolFilterAction : AnAction() {
	override fun actionPerformed(e: AnActionEvent) {
		val provider = FilteredToolsProvider.getInstance() ?: return
		ToolFilterDialog(provider).show()
	}
}

private class ToolEntry(val name: String, val isBuiltIn: Boolean, var enabled: Boolean)

private class ToolTableModel(val tools: List<ToolEntry>) : AbstractTableModel() {
	override fun getRowCount() = tools.size
	override fun getColumnCount() = 3

	override fun getColumnName(column: Int) = when (column) {
		0 -> ""
		1 -> "Tool"
		2 -> "Source"
		else -> ""
	}

	override fun getColumnClass(column: Int) = when (column) {
		0 -> java.lang.Boolean::class.java
		else -> String::class.java
	}

	override fun isCellEditable(row: Int, column: Int) = column == 0

	override fun getValueAt(row: Int, column: Int): Any = when (column) {
		0 -> tools[row].enabled
		1 -> tools[row].name
		2 -> if (tools[row].isBuiltIn) "built-in" else "ext"
		else -> ""
	}

	override fun setValueAt(value: Any?, row: Int, column: Int) {
		if (column == 0) {
			tools[row].enabled = value as Boolean
			fireTableCellUpdated(row, column)
		}
	}
}

private class ToolFilterDialog(private val provider: FilteredToolsProvider) : DialogWrapper(null) {

	private val tools: List<ToolEntry>
	private val tableModel: ToolTableModel
	private val table: JBTable
	private val sorter: TableRowSorter<ToolTableModel>
	private val statusLabel = JBLabel()

	init {
		title = "MCP Tool Filter"

		val disabled = ToolFilterState.getInstance().getDisabledSet()
		val builtInNames = provider.getBuiltInToolNames()
		val allToolNames = provider.getAllToolsUnfiltered()
			.map { it.descriptor.name }
			.distinct()
			.sorted()

		tools = allToolNames.map { name ->
			ToolEntry(name, name in builtInNames, name !in disabled)
		}

		tableModel = ToolTableModel(tools)
		table = JBTable(tableModel).apply {
			setShowGrid(false)
			intercellSpacing = JBUI.emptySize()
			columnModel.getColumn(0).apply {
				maxWidth = JBUI.scale(30)
				minWidth = JBUI.scale(30)
			}
			columnModel.getColumn(2).apply {
				maxWidth = JBUI.scale(60)
				minWidth = JBUI.scale(60)
			}
		}
		sorter = TableRowSorter(tableModel)
		table.rowSorter = sorter

		tableModel.addTableModelListener { updateStatus() }
		updateStatus()

		init()
	}

	override fun createCenterPanel(): JComponent {
		val panel = JPanel(BorderLayout(0, JBUI.scale(4)))

		val searchField = SearchTextField(false).apply {
			addDocumentListener(object : DocumentAdapter() {
				override fun textChanged(e: DocumentEvent) {
					val text = text.orEmpty()
					sorter.rowFilter = if (text.isEmpty()) null
					else RowFilter.regexFilter("(?i)${Regex.escape(text)}", 1)
				}
			})
		}
		panel.add(searchField, BorderLayout.NORTH)

		val buttonRow1 = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
			add(createButton("Enable All") { setAll(true) })
			add(createButton("Disable All") { setAll(false) })
			add(Box.createHorizontalStrut(JBUI.scale(8)))
			add(createButton("Built-in On") { setBySource(builtIn = true, enabled = true) })
			add(createButton("Built-in Off") { setBySource(builtIn = true, enabled = false) })
			add(Box.createHorizontalStrut(JBUI.scale(8)))
			add(createButton("Reset") { setAll(true) })
		}

		val buttonRow2 = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
			add(createButton("Copy Config") { copyConfig() })
			add(createButton("Apply Config") { applyConfig() })
		}

		val buttonsPanel = JPanel().apply {
			layout = BoxLayout(this, BoxLayout.Y_AXIS)
			add(buttonRow1)
			add(buttonRow2)
		}

		val topPanel = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
			add(searchField, BorderLayout.NORTH)
			add(buttonsPanel, BorderLayout.SOUTH)
		}
		panel.add(topPanel, BorderLayout.NORTH)

		val scrollPane = JBScrollPane(table)
		scrollPane.preferredSize = JBUI.size(500, 400)
		panel.add(scrollPane, BorderLayout.CENTER)

		panel.add(statusLabel, BorderLayout.SOUTH)

		return panel
	}

	private fun createButton(text: String, action: () -> Unit): JButton {
		return JButton(text).apply {
			addActionListener { action(); updateStatus() }
			isFocusable = false
		}
	}

	private fun setAll(enabled: Boolean) {
		tools.forEach { it.enabled = enabled }
		tableModel.fireTableDataChanged()
	}

	private fun setBySource(builtIn: Boolean, enabled: Boolean) {
		tools.filter { it.isBuiltIn == builtIn }.forEach { it.enabled = enabled }
		tableModel.fireTableDataChanged()
	}

	private fun copyConfig() {
		val disabled = tools.filter { !it.enabled }.map { it.name }.sorted()
		val json = Json { prettyPrint = true }.encodeToString(disabled)
		Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(json), null)
	}

	private fun applyConfig() {
		val clipboard = try {
			Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
		} catch (_: Exception) {
			null
		}
		val dialog = ApplyConfigDialog(clipboard)
		if (!dialog.showAndGet()) return
		val disabled = try {
			Json.decodeFromString<List<String>>(dialog.textArea.text.trim())
		} catch (e: Exception) {
			Messages.showErrorDialog("Invalid JSON: ${e.message}", "Apply Config")
			return
		}
		val disabledSet = disabled.toSet()
		for (tool in tools) {
			tool.enabled = tool.name !in disabledSet
		}
		tableModel.fireTableDataChanged()
	}

	private fun updateStatus() {
		val enabled = tools.count { it.enabled }
		val builtInEnabled = tools.count { it.isBuiltIn && it.enabled }
		val builtInTotal = tools.count { it.isBuiltIn }
		val extEnabled = tools.count { !it.isBuiltIn && it.enabled }
		val extTotal = tools.count { !it.isBuiltIn }
		statusLabel.text =
			"$enabled/${tools.size} enabled  |  built-in: $builtInEnabled/$builtInTotal  |  ext: $extEnabled/$extTotal"
	}

	override fun doOKAction() {
		val state = ToolFilterState.getInstance()
		for (tool in tools) {
			state.setDisabled(tool.name, !tool.enabled)
		}
		FilteredToolsProvider.triggerRefresh()
		super.doOKAction()
	}
}

private class ApplyConfigDialog(clipboard: String?) : DialogWrapper(null) {
	val textArea = JTextArea(10, 50).apply {
		lineWrap = true
		wrapStyleWord = true
		if (clipboard != null && clipboard.trimStart().startsWith("[")) {
			text = clipboard
		}
	}

	init {
		title = "Apply Tool Filter Config"
		init()
	}

	override fun createCenterPanel(): JComponent {
		val panel = JPanel(BorderLayout(0, JBUI.scale(4)))
		panel.add(JBLabel("Paste JSON array of disabled tool names:"), BorderLayout.NORTH)
		panel.add(JBScrollPane(textArea).apply {
			preferredSize = JBUI.size(400, 200)
		}, BorderLayout.CENTER)
		return panel
	}
}
