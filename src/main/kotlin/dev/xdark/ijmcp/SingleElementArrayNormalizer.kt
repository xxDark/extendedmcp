package dev.xdark.ijmcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * When a parameter's schema expects an array but the caller sent a single non-array value that
 * already validates as one element of that array, wraps it into a single-element array — recovering
 * the common "forgot the brackets" mistake instead of letting the deserializer throw.
 *
 * Runs after [StringEncodedJsonNormalizer] so a string-encoded array is decoded to a real array
 * first, and not wrapped as a lone string element.
 */
class SingleElementArrayNormalizer : ArgumentNormalizer {
	override fun normalize(args: JsonObject, propertiesSchema: Map<String, JsonElement>): JsonObject {
		var modified = false
		val entries = buildMap {
			for ((key, value) in args) {
				val schema = propertiesSchema[key]
				// Original, spec-faithful form: validate the current value against the schema and wrap only when
				// that fails. Superseded by the guarded version below — the added `value !is JsonArray` check makes
				// that validation always false (a non-array value can never satisfy an array schema), so it was a
				// redundant call. Kept commented out for reference against the stated algorithm:
				//
				// if (schema != null && expectsArray(schema) && !JsonSchemaValidator.validate(value, schema)) {
				// 	val wrapped = JsonArray(listOf(value))
				// 	if (JsonSchemaValidator.validate(wrapped, schema)) {
				// 		put(key, wrapped)
				// 		modified = true
				// 		continue
				// 	}
				// }
				if (schema != null && value !is JsonArray && expectsArray(schema)) {
					val wrapped = JsonArray(listOf(value))
					if (JsonSchemaValidator.validate(wrapped, schema)) {
						put(key, wrapped)
						modified = true
						continue
					}
				}
				put(key, value)
			}
		}
		return if (modified) JsonObject(entries) else args
	}

	private fun expectsArray(schema: JsonElement): Boolean {
		if (schema !is JsonObject) return false
		val type = schema["type"]
		return type is JsonPrimitive && type.content == "array"
	}
}
