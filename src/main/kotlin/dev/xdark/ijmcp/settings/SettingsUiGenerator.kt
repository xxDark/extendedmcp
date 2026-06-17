package dev.xdark.ijmcp.settings

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import kotlin.reflect.KClass

private val LOG = logger<SettingEditor>()

/**
 * A Swing control bound to a single [Setting]. The editor holds the working value; the live
 * [Setting.value] is only mutated by [applyTo], which keeps Cancel cheap (just discard the editor).
 */
interface SettingEditor {
	/** The component to place in the form (already wrapped in a scroll pane where needed). */
	val component: JComponent

	/** Load the editor from [Setting.value]. */
	fun loadFrom(setting: Setting<*>)

	/** True when the editor's current value differs from [Setting.value]. */
	fun isModified(setting: Setting<*>): Boolean

	/** Write the editor's current value into [Setting.value]. */
	fun applyTo(setting: Setting<*>)
}

/** Builds the editor for a setting, or null (logged) if its type is unsupported. */
fun editorFor(setting: Setting<*>): SettingEditor? {
	val type = setting.type
	return when {
		type.classifier == Boolean::class -> BoolEditor()
		type.classifier == Int::class -> IntEditor(setting.min, setting.max)
		type.classifier == String::class -> StringEditor(setting.multiline)
		SettingCodec.isEnum(type) -> EnumEditor((type.classifier as KClass<*>).java.enumConstants ?: emptyArray())
		SettingCodec.isStringList(type) -> StringListEditor()
		else -> {
			LOG.warn("Unsupported setting type for '${setting.name}': $type")
			null
		}
	}
}

private abstract class ValueEditor : SettingEditor {
	protected abstract fun read(): Any
	protected abstract fun write(value: Any)

	override fun loadFrom(setting: Setting<*>) = write(setting.value)
	override fun isModified(setting: Setting<*>): Boolean = read() != setting.value

	@Suppress("UNCHECKED_CAST")
	override fun applyTo(setting: Setting<*>) {
		(setting as Setting<Any>).value = read()
	}
}

private class BoolEditor : ValueEditor() {
	private val checkBox = JBCheckBox()
	override val component: JComponent get() = checkBox
	override fun read(): Any = checkBox.isSelected
	override fun write(value: Any) {
		checkBox.isSelected = value as Boolean
	}
}

private class IntEditor(min: Int?, max: Int?) : ValueEditor() {
	private val spinner = JSpinner(SpinnerNumberModel(0, min ?: Int.MIN_VALUE, max ?: Int.MAX_VALUE, 1))
	override val component: JComponent get() = spinner
	override fun read(): Any = (spinner.value as Number).toInt()
	override fun write(value: Any) {
		spinner.value = value as Int
	}
}

private class StringEditor(multiline: Boolean) : ValueEditor() {
	private val textField = if (multiline) null else JBTextField()
	private val textArea = if (multiline) JBTextArea(5, 40) else null
	override val component: JComponent = if (textArea != null) JBScrollPane(textArea) else textField!!
	override fun read(): Any = textArea?.text ?: textField!!.text
	override fun write(value: Any) {
		val text = value as String
		if (textArea != null) textArea.text = text else textField!!.text = text
	}
}

private class EnumEditor(constants: Array<out Any>) : ValueEditor() {
	private val combo = ComboBox(constants)
	override val component: JComponent get() = combo
	override fun read(): Any = combo.selectedItem!!
	override fun write(value: Any) {
		combo.selectedItem = value
	}
}

private class StringListEditor : ValueEditor() {
	private val textArea = JBTextArea(6, 40)
	override val component: JComponent = JBScrollPane(textArea)

	// Preserve each line verbatim (leading/trailing spaces can be significant in patterns);
	// only drop fully-empty lines (e.g. a trailing newline).
	override fun read(): Any = textArea.text.split("\n").filter { it.isNotEmpty() }

	override fun write(value: Any) {
		@Suppress("UNCHECKED_CAST")
		textArea.text = (value as List<String>).joinToString("\n")
	}
}
