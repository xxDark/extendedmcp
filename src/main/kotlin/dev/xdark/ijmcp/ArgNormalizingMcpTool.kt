package dev.xdark.ijmcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import kotlinx.serialization.json.JsonObject

class ArgNormalizingMcpTool(
	private val delegate: McpTool,
	private val normalizers: List<ArgumentNormalizer>,
) : McpTool {
	override val descriptor: McpToolDescriptor get() = delegate.descriptor

	override suspend fun call(args: JsonObject): McpToolCallResult {
		val schema = descriptor.inputSchema.propertiesSchema
		var normalized = args
		for (normalizer in normalizers) {
			normalized = normalizer.normalize(normalized, schema)
		}
		return delegate.call(normalized)
	}
}