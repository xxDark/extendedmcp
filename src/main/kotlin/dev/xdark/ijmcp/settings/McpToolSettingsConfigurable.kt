package dev.xdark.ijmcp.settings

import com.intellij.mcpserver.McpToolset
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Settings > Tools > MCP Toolset Settings. Master-detail: a list of toolsets on the left, a
 * generated form per toolset on the right. Forms are built by reflecting each toolset's
 * [SettingsHolder] via [editorFor]; values load/persist through [ToolSettingsService].
 */
class McpToolSettingsConfigurable : Configurable {

	private class Binding(val setting: Setting<*>, val editor: SettingEditor)
	private class Section(val holder: SettingsHolder, val title: String, val bindings: List<Binding>)

	private var sections: List<Section> = emptyList()

	override fun getDisplayName(): String = "MCP Toolset Settings"

	override fun createComponent(): JComponent {
		sections = McpToolset.EP.extensionList
			.filterIsInstance<ConfigurableToolset>()
			.sortedBy { it.settingsTitle }
			.map { toolset ->
				val holder = toolset.settingsHolder
				holder.ensureHydrated()
				val bindings = holder.settings.mapNotNull { setting ->
					editorFor(setting)?.let { Binding(setting, it) }
				}
				Section(holder, toolset.settingsTitle, bindings)
			}

		val cards = JPanel(CardLayout())
		for (section in sections) {
			cards.add(buildSectionPanel(section), section.title)
		}

		val titles = sections.map { it.title }
		val list = JBList(titles)
		list.selectionMode = ListSelectionModel.SINGLE_SELECTION
		list.addListSelectionListener {
			val selected = list.selectedValue ?: return@addListSelectionListener
			(cards.layout as CardLayout).show(cards, selected)
		}
		if (titles.isNotEmpty()) list.selectedIndex = 0

		val listScroll = JBScrollPane(list)
		listScroll.preferredSize = JBUI.size(180, 400)

		val root = JPanel(BorderLayout())
		root.add(listScroll, BorderLayout.WEST)
		root.add(cards, BorderLayout.CENTER)
		reset()
		return root
	}

	private fun buildSectionPanel(section: Section): JComponent {
		val builder = FormBuilder.createFormBuilder()
		for (binding in section.bindings) {
			val label = JBLabel(binding.setting.label)
			if (binding.setting.description.isNotBlank()) {
				label.toolTipText = binding.setting.description
			}
			builder.addLabeledComponent(label, binding.editor.component, true)
		}
		val panel = builder.panel
		panel.border = JBUI.Borders.emptyLeft(8)
		return JBScrollPane(panel)
	}

	override fun isModified(): Boolean =
		sections.any { section -> section.bindings.any { it.editor.isModified(it.setting) } }

	override fun apply() {
		for (section in sections) {
			for (binding in section.bindings) {
				binding.editor.applyTo(binding.setting)
			}
			ToolSettingsService.getInstance().save(section.holder)
		}
	}

	override fun reset() {
		for (section in sections) {
			section.holder.ensureHydrated()
			for (binding in section.bindings) {
				binding.editor.loadFrom(binding.setting)
			}
		}
	}

	override fun disposeUIResources() {
		sections = emptyList()
	}
}
