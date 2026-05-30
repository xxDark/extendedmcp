package dev.xdark.ijmcp

import kotlinx.serialization.json.*

class StringEncodedArrayNormalizer : ArgumentNormalizer {
	override fun normalize(args: JsonObject, propertiesSchema: Map<String, JsonElement>): JsonObject {
		var modified = false
		val entries = buildMap {
			for ((key, value) in args) {
				if (value is JsonPrimitive && value.isString) {
					val propSchema = propertiesSchema[key]
					if (propSchema != null && isArrayType(propSchema)) {
						val parsed = tryParseJsonArray(value.content)
						if (parsed != null) {
							put(key, parsed)
							modified = true
							continue
						}
					}
				}
				put(key, value)
			}
		}
		return if (modified) JsonObject(entries) else args
	}

	private fun isArrayType(schema: JsonElement): Boolean {
		if (schema !is JsonObject) return false
		val type = schema["type"]
		return type is JsonPrimitive && type.content == "array"
	}

	private fun tryParseJsonArray(value: String): JsonArray? {
		if (!value.startsWith("[")) return null
		return try {
			Json.parseToJsonElement(value) as? JsonArray
		} catch (_: Exception) {
			null
		}
	}
}
