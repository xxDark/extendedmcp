@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.util.Key
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import org.jetbrains.plugins.gradle.util.GradleConstants

class GradleToolset : McpToolset {

    companion object {
        private val NOISY_LINE = Regex(
            """^(""" +
                """Downloading |Download |\s*>\s*(Downloading|Download)\b|\.+$""" +  // download
                """|[<>]\s+\S+\.\S+.*[KMB]?/s\s*$""" +  // progress
                """|Starting Gradle Daemon""" +
                """|Gradle Daemon started in """ +
                """|Consider enabling configuration cache""" +
                """|The Daemon will expire """ +
                """|The project memory settings """ +
                """|The daemon will restart """ +
                """|These settings can be adjusted """ +
                """|The currently configured max heap """ +
                """|For more information on how to set these """ +
                """|To disable this warning, set """ +
                """|Daemon will be stopped """ +
            """)"""
        )
    }

    private fun isNoisyLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        return NOISY_LINE.containsMatchIn(trimmed)
    }

    @Serializable
    data class GradleTaskResult(
        val exitCode: Int? = null,
        val success: Boolean,
        val output: String,
        val timedOut: Boolean = false,
    )

    @McpTool
    @McpDescription("""
        |Executes a Gradle task in the current project.
        |
        |This is equivalent to running "./gradlew <tasks> <arguments>" but through IntelliJ's Gradle integration,
        |which uses the project's configured Gradle wrapper, JDK, and settings.
        |
        |Examples:
        |  tasks="build", arguments=""
        |  tasks="test", arguments="--tests com.example.MyTest"
        |  tasks="clean build", arguments="--info"
        |  tasks="dependencies", arguments="--configuration runtimeClasspath"
    """)
    suspend fun run_gradle_task(
        @McpDescription("Space-separated Gradle task names (e.g. 'build', 'clean test', 'dependencies')") tasks: String,
        @McpDescription("Additional command-line arguments (e.g. '--info', '--tests com.example.MyTest')") arguments: String = "",
        @McpDescription("Timeout in milliseconds (default 120000)") timeout: Int = 120000,
        @McpDescription("If true (default), only prints 'BUILD SUCCESSFUL in Xs' on success. Full output on failure.") compact: Boolean = true,
    ): GradleTaskResult {
        val project = currentCoroutineContext().project

        val projectPath = project.basePath ?: mcpFail("Cannot determine project path")

        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalSystemIdString = GradleConstants.SYSTEM_ID.id
            externalProjectPath = projectPath
            taskNames = tasks.trim().split("\\s+".toRegex())
            scriptParameters = arguments
        }

        val runnerAndConfigSettings = ExternalSystemUtil.createExternalSystemRunnerAndConfigurationSettings(
            settings, project, GradleConstants.SYSTEM_ID
        ) ?: mcpFail("Failed to create Gradle run configuration")

        val executor = DefaultRunExecutor.getRunExecutorInstance()
            ?: mcpFail("Execution is not supported in this environment")

        val exitCodeDeferred = CompletableDeferred<Int>()
        val outputBuilder = StringBuilder()

        withContext(Dispatchers.EDT) {
            val runner = ProgramRunner.getRunner(executor.id, runnerAndConfigSettings.configuration)
                ?: mcpFail("No suitable runner found for Gradle task execution")

            val callback = object : ProgramRunner.Callback {
                override fun processNotStarted(error: Throwable?) {
                    exitCodeDeferred.completeExceptionally(
                        error ?: IllegalStateException("Gradle task not started")
                    )
                }

                override fun processStarted(descriptor: RunContentDescriptor) {
                    val processHandler = descriptor.processHandler
                    if (processHandler == null) {
                        exitCodeDeferred.completeExceptionally(
                            IllegalStateException("Process handler is null")
                        )
                        return
                    }

                    processHandler.addProcessListener(object : ProcessListener {
                        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                            if (outputType == ProcessOutputTypes.SYSTEM) return
                            val text = event.text
                            if (text.lines().all { isNoisyLine(it) || it.isEmpty() }) return
                            outputBuilder.append(text)
                        }

                        override fun processTerminated(event: ProcessEvent) {
                            exitCodeDeferred.complete(event.exitCode)
                        }

                        override fun processNotStarted() {
                            exitCodeDeferred.completeExceptionally(
                                IllegalStateException("Gradle process not started")
                            )
                        }
                    })
                    processHandler.startNotify()
                }
            }

            val environment = ExecutionEnvironmentBuilder.create(project, executor, runnerAndConfigSettings.configuration).build()
            environment.callback = callback
            runner.execute(environment)
        }

        val exitCode = withTimeoutOrNull(timeout.toLong()) {
            try {
                exitCodeDeferred.await()
            } catch (e: Exception) {
                mcpFail("Gradle execution failed: ${e.message}")
            }
        }

        val output = outputBuilder.toString()
        val success = exitCode == 0
        val displayOutput = if (compact && success) {
            "BUILD SUCCESSFUL"
        } else {
            output
        }
        return GradleTaskResult(
            exitCode = exitCode,
            success = success,
            output = displayOutput,
            timedOut = exitCode == null,
        )
    }
}
