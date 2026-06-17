@file:Suppress("unused")

package dev.xdark.ijmcp.settings

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * A single declarative setting. The instance doubles as the property delegate: reading the
 * delegated property returns [value] (lazily hydrating the owning holder from persistence first).
 *
 * [type] is the declared type captured via [typeOf]; the UI generator and persistence codec
 * dispatch on it. Only classifier-identity comparison and `KClass.java` access are performed,
 * so no `kotlin-reflect` library is required.
 */
class Setting<T : Any> internal constructor(
	val name: String,
	val type: KType,
	val label: String,
	val description: String,
	val default: T,
	val min: Int?,
	val max: Int?,
	val multiline: Boolean,
) : ReadOnlyProperty<Any?, T> {

	@Volatile
	var value: T = default

	override fun getValue(thisRef: Any?, property: KProperty<*>): T {
		(thisRef as? SettingsHolder)?.ensureHydrated()
		return value
	}
}

/**
 * Produced by [setting]; captures everything except the property name, which is supplied by
 * [provideDelegate] when the delegate is bound.
 */
class SettingProvider<T : Any>(
	val type: KType,
	val value: T,
	val label: String,
	val description: String,
	val min: Int?,
	val max: Int?,
	val multiline: Boolean,
) {
	operator fun provideDelegate(thisRef: SettingsHolder, property: KProperty<*>): Setting<T> {
		val setting = Setting(property.name, type, label, description, value, min, max, multiline)
		thisRef.register(setting)
		return setting
	}
}

/**
 * Declares a setting. Usage inside a [SettingsHolder]:
 * ```
 * val timeout by setting(value = 120_000, label = "Timeout (ms)", min = 0)
 * ```
 * [typeOf]`<T>()` records the declared type (including generics such as `List<String>`) for
 * UI/codec dispatch. [multiline] only affects a `String` setting (text field vs text area); a
 * `List<String>` is always edited as a multi-line text area (one entry per line).
 */
inline fun <reified T : Any> setting(
	value: T,
	label: String,
	description: String = "",
	min: Int? = null,
	max: Int? = null,
	multiline: Boolean = false,
): SettingProvider<T> = SettingProvider(typeOf<T>(), value, label, description, min, max, multiline)

/**
 * Base class for a toolset's settings. Each `by setting(...)` property registers itself here in
 * declaration order. Values are hydrated from persistence lazily on first read (or by the UI).
 */
abstract class SettingsHolder {

	/** Stable storage id for this holder (its fully qualified class name). */
	val settingsId: String get() = javaClass.name

	private val _settings = mutableListOf<Setting<*>>()
	val settings: List<Setting<*>> get() = _settings

	internal fun register(setting: Setting<*>) {
		_settings += setting
	}

	private var hydrated = false

	@Synchronized
	internal fun ensureHydrated() {
		if (hydrated) return
		hydrated = true
		ToolSettingsService.getInstance().load(this)
	}
}
