@file:Suppress("unused")

package dev.xdark.ijmcp

import dev.xdark.ijmcp.settings.SettingsHolder
import dev.xdark.ijmcp.settings.setting

/**
 * User-editable settings for [GradleToolset]. The default [noisyPatterns] reproduce the
 * previously-hardcoded NOISY_LINE alternatives verbatim, so out-of-the-box output filtering is
 * unchanged.
 */
class GradleSettings : SettingsHolder() {

	val noisyPatterns by setting(
		value = listOf(
			"""Downloading |Download |\s*>\s*(Downloading|Download)\b|\.+$""",
			"""[<>]\s+\S+\.\S+.*[KMB]?/s\s*$""",
			"Starting Gradle Daemon",
			"Gradle Daemon started in",
			"Consider enabling configuration cache",
			"The Daemon will expire",
			"The project memory settings",
			"The daemon will restart",
			"These settings can be adjusted",
			"The currently configured max heap",
			"For more information on how to set these",
			"To disable this warning, set",
			"Daemon will be stopped",
		),
		label = "Suppressed output patterns",
		description = "Regex fragments; output lines matching ^(pattern) are dropped from Gradle output. One per line.",
	)
}
