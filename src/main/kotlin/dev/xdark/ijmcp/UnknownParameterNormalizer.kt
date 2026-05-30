package dev.xdark.ijmcp

import com.intellij.mcpserver.mcpFail
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class UnknownParameterNormalizer : ArgumentNormalizer {
	override fun normalize(args: JsonObject, propertiesSchema: Map<String, JsonElement>): JsonObject {
		val unknown = args.keys - propertiesSchema.keys
		if (unknown.isNotEmpty()) {
			mcpFail(
				"Unknown parameter(s): ${unknown.joinToString(", ")}. " +
					"Valid parameters: ${propertiesSchema.keys.joinToString(", ")}"
			)
		}
		return args
	}
}
