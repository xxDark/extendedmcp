package dev.xdark.ijmcp

import kotlinx.serialization.json.*

/**
 * Minimal recursive validator for the JSON Schema subset emitted by MCP tool descriptors
 * (type, items, properties, required, additionalProperties, enum). Intentionally conservative:
 * unknown keywords are ignored rather than treated as failures, so a `true` result means
 * "matches everything we checked", never a false rejection of a structurally valid value.
 */
object JsonSchemaValidator {
	fun validate(value: JsonElement, schema: JsonElement): Boolean {
		if (schema !is JsonObject) {
			// Boolean schema: true accepts everything, false nothing. Anything else: be permissive.
			return (schema as? JsonPrimitive)?.booleanOrNull ?: true
		}

		(schema["enum"] as? JsonArray)?.let { allowed ->
			if (value !in allowed) return false
		}

		val typeNames = when (val type = schema["type"]) {
			is JsonPrimitive -> if (type.isString) listOf(type.content) else null
			is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
			else -> null
		}
		if (typeNames != null) {
			if (typeNames.none { matchesType(value, it, schema) }) return false
		} else {
			// No declared type: still honor structural constraints if present.
			if ((schema.containsKey("properties") || schema.containsKey("required")) && !validateObject(
					value,
					schema
				)
			) return false
			if (schema.containsKey("items") && !validateArray(value, schema)) return false
		}
		return true
	}

	private fun matchesType(value: JsonElement, type: String, schema: JsonObject): Boolean = when (type) {
		"string" -> value is JsonPrimitive && value.isString
		"integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
		"number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
		"boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
		"null" -> value is JsonNull
		"array" -> validateArray(value, schema)
		"object" -> validateObject(value, schema)
		else -> true
	}

	private fun validateArray(value: JsonElement, schema: JsonObject): Boolean {
		if (value !is JsonArray) return false
		val items = schema["items"] ?: return true
		return value.all { validate(it, items) }
	}

	private fun validateObject(value: JsonElement, schema: JsonObject): Boolean {
		if (value !is JsonObject) return false
		(schema["required"] as? JsonArray)?.forEach { req ->
			val name = (req as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
			if (name !in value) return false
		}
		val properties = schema["properties"] as? JsonObject
		val additional = schema["additionalProperties"]
		for ((propName, propValue) in value) {
			val propSchema = properties?.get(propName)
			when {
				propSchema != null -> if (!validate(propValue, propSchema)) return false
				additional is JsonObject -> if (!validate(propValue, additional)) return false
				additional is JsonPrimitive && additional.booleanOrNull == false -> return false
			}
		}
		return true
	}
}
