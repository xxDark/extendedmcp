package dev.xdark.ijmcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

fun interface ArgumentNormalizer {
	fun normalize(args: JsonObject, propertiesSchema: Map<String, JsonElement>): JsonObject
}
