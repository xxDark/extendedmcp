package dev.xdark.ijmcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import kotlinx.serialization.json.*

class ArgNormalizingMcpTool(private val delegate: McpTool) : McpTool {
	override val descriptor: McpToolDescriptor get() = delegate.descriptor

	override suspend fun call(args: JsonObject): McpToolCallResult {
		return delegate.call(normalizeArgs(args))
	}

	private fun normalizeArgs(args: JsonObject): JsonObject {
		val schema = descriptor.inputSchema.propertiesSchema
		var modified = false
		val entries = buildMap {
			for ((key, value) in args) {
				if (value is JsonPrimitive && value.isString) {
					val propSchema = schema[key]
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