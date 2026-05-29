@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.jetbrains.plugins.gradle.util.GradleConstants

class GradleSyncToolset : McpToolset {

    @Serializable
    data class GradleSyncResult(
        val success: Boolean,
        val message: String,
    )

    @McpTool
    @McpDescription("""
        |Triggers a Gradle project sync (reimport) in IntelliJ.
        |This refreshes the project model, resolves dependencies, and updates the IDE's view of the project.
        |Equivalent to clicking the "Reload All Gradle Projects" button.
    """)
    suspend fun gradle_sync(
        @McpDescription("If true, forces downloading sources for all dependencies") downloadSources: Boolean = false,
        @McpDescription("Timeout in milliseconds to wait for sync completion (default 300000 = 5 min). 0 = fire and forget.") timeout: Int = 300000,
    ): GradleSyncResult {
        val project = currentCoroutineContext().project

        val spec = ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
        if (downloadSources) {
            spec.withVmOptions("-Didea.gradle.download.sources.force=true")
        }

        if (timeout <= 0) {
            ExternalSystemUtil.refreshProjects(spec)
            return GradleSyncResult(success = true, message = "Gradle sync started.")
        }

        val completionDeferred = CompletableDeferred<Boolean>()
        spec.withCallback { success -> completionDeferred.complete(success) }

        ExternalSystemUtil.refreshProjects(spec)

        val result = withTimeoutOrNull(timeout.toLong()) {
            completionDeferred.await()
        }

        return when (result) {
            true -> GradleSyncResult(success = true, message = "Gradle sync completed successfully.")
            false -> GradleSyncResult(success = false, message = "Gradle sync completed with errors.")
            null -> GradleSyncResult(success = true, message = "Gradle sync still running (timed out after ${timeout}ms).")
        }
    }
}
