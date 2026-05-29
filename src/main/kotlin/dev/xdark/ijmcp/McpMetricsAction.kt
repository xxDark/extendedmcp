package dev.xdark.ijmcp

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.text.DateFormatUtil
import java.awt.Component
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

class McpMetricsAction : AnAction() {
	override fun actionPerformed(e: AnActionEvent) {
		McpMetricsDialog().show()
	}
}

private class McpMetricsDialog : DialogWrapper(null) {
	private val model = MetricsTableModel(McpMetricsService.getInstance().getMetrics())
	private lateinit var table: JBTable

	init {
		title = "MCP Tool Metrics"
		setOKButtonText("Close")
		init()
	}

	override fun createCenterPanel(): JComponent {
		table = JBTable(model).apply {
			rowSorter = TableRowSorter(model).apply {
				sortKeys = listOf(RowSorter.SortKey(1, SortOrder.DESCENDING))
			}
			autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
			columnModel.getColumn(0).preferredWidth = 250
			columnModel.getColumn(1).preferredWidth = 70
			columnModel.getColumn(2).preferredWidth = 70
			columnModel.getColumn(3).preferredWidth = 80
			columnModel.getColumn(4).preferredWidth = 150

			val rightAligned = object : DefaultTableCellRenderer() {
				init {
					horizontalAlignment = RIGHT
				}
			}
			columnModel.getColumn(1).cellRenderer = rightAligned
			columnModel.getColumn(2).cellRenderer = rightAligned

			columnModel.getColumn(3).cellRenderer = object : DefaultTableCellRenderer() {
				init {
					horizontalAlignment = RIGHT
				}

				override fun getTableCellRendererComponent(
					table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
				): Component {
					super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
					text = if (value is Double && value > 0.0) "%.1f%%".format(value) else "\u2014"
					return this
				}
			}

			columnModel.getColumn(4).cellRenderer = object : DefaultTableCellRenderer() {
				override fun getTableCellRendererComponent(
					table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
				): Component {
					super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
					text = if (value is Long && value > 0L) DateFormatUtil.formatPrettyDateTime(value) else "\u2014"
					return this
				}
			}
		}

		val scrollPane = JBScrollPane(table)
		scrollPane.preferredSize = Dimension(650, 400)
		return scrollPane
	}

	override fun createLeftSideActions(): Array<Action> {
		return arrayOf(object : AbstractAction("Clear All") {
			override fun actionPerformed(e: java.awt.event.ActionEvent) {
				if (Messages.showYesNoDialog(
						"Clear all MCP tool metrics?",
						"Confirm Clear",
						Messages.getQuestionIcon(),
					) == Messages.YES
				) {
					McpMetricsService.getInstance().reset()
					model.refresh(emptyList())
				}
			}
		})
	}

	override fun createActions(): Array<Action> = arrayOf(okAction)
}

private class MetricsTableModel(
	private var metrics: List<McpMetricsService.ToolMetric>,
) : AbstractTableModel() {
	private val columns = arrayOf("Tool", "Calls", "Errors", "Error %", "Last Used")

	fun refresh(newMetrics: List<McpMetricsService.ToolMetric>) {
		metrics = newMetrics
		fireTableDataChanged()
	}

	override fun getRowCount() = metrics.size
	override fun getColumnCount() = columns.size
	override fun getColumnName(column: Int) = columns[column]

	override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
		1, 2 -> Int::class.javaObjectType
		3 -> Double::class.javaObjectType
		4 -> Long::class.javaObjectType
		else -> String::class.java
	}

	override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
		val m = metrics[rowIndex]
		return when (columnIndex) {
			0 -> m.name
			1 -> m.calls
			2 -> m.errors
			3 -> if (m.calls > 0) m.errors * 100.0 / m.calls else 0.0
			4 -> m.lastCall
			else -> ""
		}
	}
}
