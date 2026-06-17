package dev.xdark.ijmcp.settings

/**
 * Implemented by an [com.intellij.mcpserver.McpToolset] that exposes user-editable settings.
 * Discovered via the existing `mcpToolset` EP, so adding settings to a toolset needs no extra
 * plugin.xml registration.
 */
interface ConfigurableToolset {
	/** Display name for this toolset's settings section, e.g. "Gradle". */
	val settingsTitle: String

	/** The live holder the toolset also reads from; UI edits mutate this same instance on apply. */
	val settingsHolder: SettingsHolder
}
