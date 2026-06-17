package dev.xdark.ijmcp.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * App-level persistence for toolset settings. Stores a flat map of `"<settingsId>.<name>" -> encoded`
 * in `ijMcpToolSettings.xml`. Only values that differ from their default are stored, so defaults
 * stay future-proof and the file stays minimal.
 */
@State(name = "McpToolSettings", storages = [Storage("ijMcpToolSettings.xml")])
@Service(Service.Level.APP)
class ToolSettingsService : PersistentStateComponent<ToolSettingsService.State> {

	class State {
		var values: MutableMap<String, String> = mutableMapOf()
	}

	private var myState = State()

	/** Populate each setting's value from the store (or its default when absent / undecodable). */
	@Synchronized
	fun load(holder: SettingsHolder) {
		for (setting in holder.settings) {
			val raw = myState.values[keyOf(holder, setting)]
			val decoded = if (raw != null) SettingCodec.decode(setting.type, raw) else null
			setValue(setting, decoded ?: setting.default)
		}
	}

	/** Persist each setting's current value, clearing entries equal to the default. */
	@Synchronized
	fun save(holder: SettingsHolder) {
		for (setting in holder.settings) {
			val key = keyOf(holder, setting)
			val encoded = SettingCodec.encode(setting.type, setting.value)
			val defaultEncoded = SettingCodec.encode(setting.type, setting.default)
			if (encoded == null || encoded == defaultEncoded) {
				myState.values.remove(key)
			} else {
				myState.values[key] = encoded
			}
		}
	}

	@Synchronized
	override fun getState(): State = State().also { it.values = HashMap(myState.values) }

	@Synchronized
	override fun loadState(state: State) {
		myState = state
	}

	private fun keyOf(holder: SettingsHolder, setting: Setting<*>): String =
		"${holder.settingsId}.${setting.name}"

	@Suppress("UNCHECKED_CAST")
	private fun setValue(setting: Setting<*>, value: Any) {
		(setting as Setting<Any>).value = value
	}

	companion object {
		fun getInstance(): ToolSettingsService = service()
	}
}

/**
 * Encodes/decodes setting values to/from strings, dispatching on the declared [KType].
 * Supported kinds: Boolean, Int, String, Enum, and `List<String>`.
 */
internal object SettingCodec {

	fun encode(type: KType, value: Any?): String? = when {
		value == null -> null
		type.classifier == Boolean::class -> value.toString()
		type.classifier == Int::class -> value.toString()
		type.classifier == String::class -> value as String
		isEnum(type) -> (value as Enum<*>).name
		isStringList(type) -> Json.encodeToString(ListSerializer(String.serializer()), asStringList(value))
		else -> null
	}

	fun decode(type: KType, raw: String): Any? = when {
		type.classifier == Boolean::class -> raw.toBooleanStrictOrNull()
		type.classifier == Int::class -> raw.toIntOrNull()
		type.classifier == String::class -> raw
		isEnum(type) -> enumConstantByName(type, raw)
		isStringList(type) -> runCatching {
			Json.decodeFromString(ListSerializer(String.serializer()), raw)
		}.getOrNull()

		else -> null
	}

	fun isEnum(type: KType): Boolean = (type.classifier as? KClass<*>)?.java?.isEnum == true

	fun isStringList(type: KType): Boolean =
		type.classifier == List::class &&
				type.arguments.singleOrNull()?.type?.classifier == String::class

	@Suppress("UNCHECKED_CAST")
	private fun asStringList(value: Any): List<String> = value as List<String>

	private fun enumConstantByName(type: KType, name: String): Any? {
		val constants = (type.classifier as? KClass<*>)?.java?.enumConstants ?: return null
		return constants.firstOrNull { (it as Enum<*>).name == name }
	}
}
