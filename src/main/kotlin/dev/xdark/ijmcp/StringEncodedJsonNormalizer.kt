package dev.xdark.ijmcp

import com.intellij.mcpserver.mcpFail
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

class StringEncodedJsonNormalizer : ArgumentNormalizer {
	override fun normalize(args: JsonObject, propertiesSchema: Map<String, JsonElement>): JsonObject {
		var modified = false
		val entries = buildMap {
			for ((key, value) in args) {
				if (value is JsonPrimitive && value.isString) {
					val propSchema = propertiesSchema[key]
					val expected = propSchema?.let { expectedContainer(it) }
					if (expected != null) {
						val rewritten = tryParseContainer(key, value.content, expected)
						if (rewritten != null) {
							put(key, rewritten)
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

	private enum class Container(val opener: Char, val typeName: String) {
		ARRAY('[', "array"),
		OBJECT('{', "object"),
	}

	private fun expectedContainer(schema: JsonElement): Container? {
		if (schema !is JsonObject) return null
		val type = schema["type"]
		if (type !is JsonPrimitive) return null
		return when (type.content) {
			"array" -> Container.ARRAY
			"object" -> Container.OBJECT
			else -> null
		}
	}

	private fun tryParseContainer(key: String, value: String, expected: Container): JsonElement? {
		if (value.trimStart().firstOrNull() != expected.opener) return null
		val parsed = try {
			Json.parseToJsonElement(value)
		} catch (e: SerializationException) {
			mcpFail(
				"Parameter '$key' was sent as a JSON-encoded string, but its content is not valid JSON: " +
						"${e.message}. This parameter expects a JSON ${expected.typeName}; " +
						"send it directly as a JSON ${expected.typeName}, not as a string."
			)
		}
		return when (expected) {
			Container.ARRAY -> parsed as? JsonArray
			Container.OBJECT -> parsed as? JsonObject
		}
	}
}
