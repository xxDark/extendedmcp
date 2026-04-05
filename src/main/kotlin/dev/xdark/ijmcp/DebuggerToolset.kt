@file:Suppress("FunctionName", "unused")

package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpointManager
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class DebuggerToolset : McpToolset {

    // ========== Result types ==========

    @Serializable
    data class BreakpointResult(
        val added: Boolean,
        val filePath: String,
        val line: Int,
        val message: String = "",
    )

    @Serializable
    data class RemoveBreakpointResult(
        val removed: Boolean,
        val count: Int = 0,
        val message: String = "",
    )

    @Serializable
    data class BreakpointInfo(
        val filePath: String,
        val line: Int,
        val enabled: Boolean,
        val condition: String = "",
        val temporary: Boolean = false,
    )

    @Serializable
    data class BreakpointListResult(val breakpoints: String)

    @Serializable
    data class DebugStateResult(
        val status: String,
        val filePath: String = "",
        val line: Int = 0,
        val threadName: String = "",
    )

    @Serializable
    data class WaitResult(
        val suspended: Boolean,
        val filePath: String = "",
        val line: Int = 0,
        val threadName: String = "",
        val message: String = "",
    )

    @Serializable
    data class FrameInfo(
        val index: Int,
        val filePath: String,
        val line: Int,
        val functionName: String = "",
    )

    @Serializable
    data class FramesResult(val threadName: String, val frames: String)

    @Serializable
    data class VariableInfo(
        val name: String,
        val type: String = "",
        val value: String,
        val hasChildren: Boolean = false,
    )

    @Serializable
    data class VariablesResult(val frameIndex: Int, val variables: String)

    @Serializable
    data class ControlResult(val success: Boolean, val message: String = "")

    // ========== Helpers ==========

    private fun getActiveSession(project: Project): XDebugSession {
        return XDebuggerManager.getInstance(project).currentSession
            ?: mcpFail("No active debug session. Start one first (e.g. run_gradle_task with debug=true).")
    }

    private fun resolveVirtualFile(project: Project, filePath: String): VirtualFile {
        val basePath = project.basePath ?: mcpFail("Cannot determine project base path")
        val resolved = Path.of(basePath).resolve(filePath).toString().replace('\\', '/')
        return LocalFileSystem.getInstance().findFileByPath(resolved)
            ?: mcpFail("File not found: $filePath")
    }

    private fun relativeFilePath(project: Project, file: VirtualFile?): String {
        if (file == null) return ""
        val projectDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            ?: return file.path
        return VfsUtilCore.getRelativePath(file, projectDir) ?: file.path
    }

    private fun suspensionInfo(project: Project, session: XDebugSession): Triple<String, Int, String> {
        val pos = session.currentPosition
        val stack = session.suspendContext?.activeExecutionStack
        return Triple(
            relativeFilePath(project, pos?.file),
            (pos?.line ?: -1) + 1,
            stack?.displayName ?: "",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun addBreakpointRaw(
        manager: XBreakpointManager,
        type: XLineBreakpointType<*>,
        fileUrl: String,
        line: Int,
    ): XLineBreakpoint<*> {
        // Type erasure makes this safe at runtime when properties is null
        return manager.addLineBreakpoint(
            type as XLineBreakpointType<Nothing>,
            fileUrl, line, null, false,
        )
    }

    // --- Async bridges for callback-based debugger APIs ---

    private suspend fun XExecutionStack.computeFramesAsync(maxFrames: Int = 30): List<XStackFrame> {
        val topFrame = getTopFrame()
        return suspendCancellableCoroutine { cont ->
            val result = mutableListOf<XStackFrame>()
            val resumed = AtomicBoolean(false)
            fun resumeOnce() {
                if (resumed.compareAndSet(false, true)) {
                    cont.resume(result.take(maxFrames))
                }
            }
            if (topFrame != null) result.add(topFrame)
            computeStackFrames(if (topFrame != null) 1 else 0, object : XExecutionStack.XStackFrameContainer {
                override fun isObsolete() = !cont.isActive
                override fun addStackFrames(stackFrames: List<XStackFrame>, last: Boolean) {
                    result.addAll(stackFrames)
                    if (last || result.size >= maxFrames) resumeOnce()
                }

                override fun errorOccurred(errorMessage: String) {
                    resumeOnce()
                }
            })
        }
    }

    private suspend fun XValueContainer.computeChildrenAsync(maxChildren: Int = 50): List<Pair<String, XValue>> {
        return suspendCancellableCoroutine { cont ->
            val result = mutableListOf<Pair<String, XValue>>()
            val resumed = AtomicBoolean(false)
            fun resumeOnce() {
                if (resumed.compareAndSet(false, true)) {
                    cont.resume(result.take(maxChildren))
                }
            }
            computeChildren(object : XCompositeNode {
                override fun isObsolete() = !cont.isActive
                override fun addChildren(children: XValueChildrenList, last: Boolean) {
                    for (i in 0 until children.size()) {
                        result.add(children.getName(i) to children.getValue(i))
                    }
                    if (last || result.size >= maxChildren) resumeOnce()
                }

                @Deprecated("Deprecated in Java")
                override fun tooManyChildren(remaining: Int) {
                    resumeOnce()
                }

                override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) {
                    resumeOnce()
                }

                override fun setAlreadySorted(alreadySorted: Boolean) {}
                override fun setErrorMessage(errorMessage: String) { resumeOnce() }
                override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) { resumeOnce() }
                override fun setMessage(message: String, icon: javax.swing.Icon?, attributes: com.intellij.ui.SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
            })
        }
    }

    private data class ValuePresentation(val type: String, val value: String, val hasChildren: Boolean)

    private suspend fun XValue.computePresentationAsync(): ValuePresentation {
        return suspendCancellableCoroutine { cont ->
            computePresentation(object : XValueNode {
                private val resumed = AtomicBoolean(false)
                private fun resumeOnce(vp: ValuePresentation) {
                    if (resumed.compareAndSet(false, true)) {
                        cont.resume(vp)
                    }
                }

                override fun isObsolete() = !cont.isActive
                override fun setPresentation(icon: javax.swing.Icon?, type: String?, value: String, hasChildren: Boolean) {
                    resumeOnce(ValuePresentation(type ?: "", value, hasChildren))
                }

                override fun setPresentation(icon: javax.swing.Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                    val type = presentation.type ?: ""
                    val renderer = CollectingTextRenderer()
                    presentation.renderValue(renderer)
                    resumeOnce(ValuePresentation(type, renderer.sb.toString(), hasChildren))
                }

                override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {
                    // Ignore — we already have the truncated value from setPresentation
                }
            }, XValuePlace.TREE)
        }
    }

    private class CollectingTextRenderer : XValuePresentation.XValueTextRenderer {
        val sb = StringBuilder()
        override fun renderValue(value: String) { sb.append(value) }
        override fun renderStringValue(value: String) { sb.append('"').append(value).append('"') }
        override fun renderNumericValue(value: String) { sb.append(value) }
        override fun renderKeywordValue(value: String) { sb.append(value) }
        override fun renderValue(value: String, key: TextAttributesKey) { sb.append(value) }
        override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) {
            sb.append('"').append(value).append('"')
        }
        override fun renderComment(comment: String) { sb.append("/* ").append(comment).append(" */") }
        override fun renderSpecialSymbol(symbol: String) { sb.append(symbol) }
        override fun renderError(error: String) { sb.append("ERROR: ").append(error) }
    }

    private suspend fun waitForSuspensionInternal(
        project: Project,
        session: XDebugSession,
        timeoutMs: Long,
    ): WaitResult {
        // Add listener BEFORE checking isSuspended to avoid race condition
        val deferred = CompletableDeferred<Boolean>()
        val listener = object : XDebugSessionListener {
            override fun sessionPaused() {
                deferred.complete(true)
            }

            override fun sessionStopped() {
                deferred.complete(false)
            }
        }
        session.addSessionListener(listener)

        try {
            // Already suspended — return immediately
            if (session.isSuspended) {
                val (path, line, thread) = suspensionInfo(project, session)
                return WaitResult(suspended = true, filePath = path, line = line, threadName = thread)
            }

            val result = withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }

            if (result == null) {
                return WaitResult(suspended = false, message = "Timeout after ${timeoutMs}ms")
            }
            if (!result) {
                return WaitResult(suspended = false, message = "Debug session stopped")
            }

            val (path, line, thread) = suspensionInfo(project, session)
            return WaitResult(suspended = true, filePath = path, line = line, threadName = thread)
        } finally {
            session.removeSessionListener(listener)
        }
    }

    // ========== Breakpoint tools ==========

    @McpTool
    @McpDescription("""
        |Adds a line breakpoint at the specified file and line number.
        |The breakpoint persists across debug sessions until explicitly removed.
        |
        |IMPORTANT: Place breakpoints on executable statements only — not on field declarations,
        |method signatures, or blank lines. For a method, put the breakpoint on the first line of the
        |method body. Reason: JIT compiler will be disabled and the program will run only using the interpreter.
    """)
    suspend fun debug_add_breakpoint(
        @McpDescription("File path relative to project root") filePath: String,
        @McpDescription("Line number (1-based)") line: Int,
        @McpDescription("Optional condition expression — breakpoint only suspends when this evaluates to true") condition: String = "",
    ): BreakpointResult {
        val project = currentCoroutineContext().project
        val lineIndex = line - 1

        return withContext(Dispatchers.EDT) {
            val vFile = resolveVirtualFile(project, filePath)

            val breakpointTypes = XDebuggerUtil.getInstance().lineBreakpointTypes
            val type = breakpointTypes.firstOrNull { it.canPutAt(vFile, lineIndex, project) }
                ?: mcpFail("Cannot set breakpoint at $filePath:$line — no suitable breakpoint type found.")

            val manager = XDebuggerManager.getInstance(project).breakpointManager

            @Suppress("UNCHECKED_CAST")
            val existing = manager.findBreakpointsAtLine(
                type as XLineBreakpointType<Nothing>, vFile, lineIndex,
            )
            if (existing.isNotEmpty()) {
                return@withContext BreakpointResult(
                    added = false, filePath = filePath, line = line,
                    message = "Breakpoint already exists at $filePath:$line",
                )
            }

            val bp = addBreakpointRaw(manager, type, vFile.url, lineIndex)
            if (condition.isNotBlank()) {
                bp.conditionExpression = XDebuggerUtil.getInstance().createExpression(
                    condition, null, null, EvaluationMode.EXPRESSION,
                )
            }

            BreakpointResult(added = true, filePath = filePath, line = line)
        }
    }

    @McpTool
    @McpDescription("""
        |Removes all breakpoints at the specified file and line number.
    """)
    suspend fun debug_remove_breakpoint(
        @McpDescription("File path relative to project root") filePath: String,
        @McpDescription("Line number (1-based)") line: Int,
    ): RemoveBreakpointResult {
        val project = currentCoroutineContext().project
        val lineIndex = line - 1

        return withContext(Dispatchers.EDT) {
            val vFile = resolveVirtualFile(project, filePath)
            val manager = XDebuggerManager.getInstance(project).breakpointManager
            val toRemove = manager.allBreakpoints
                .filterIsInstance<XLineBreakpoint<*>>()
                .filter { it.fileUrl == vFile.url && it.line == lineIndex }

            if (toRemove.isEmpty()) {
                return@withContext RemoveBreakpointResult(
                    removed = false, message = "No breakpoint found at $filePath:$line",
                )
            }

            toRemove.forEach { manager.removeBreakpoint(it) }
            RemoveBreakpointResult(removed = true, count = toRemove.size)
        }
    }

    @McpTool
    @McpDescription("""
        |Lists all line breakpoints in the project.
    """)
    suspend fun debug_list_breakpoints(): BreakpointListResult {
        val project = currentCoroutineContext().project
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val bps = manager.allBreakpoints
            .filterIsInstance<XLineBreakpoint<*>>()
            .mapNotNull { bp ->
                val vFile = VirtualFileManager.getInstance().findFileByUrl(bp.fileUrl) ?: return@mapNotNull null
                val path = relativeFilePath(project, vFile)
                val cond = bp.conditionExpression?.expression ?: ""
                BreakpointInfo(
                    filePath = path,
                    line = bp.line + 1,
                    enabled = bp.isEnabled,
                    condition = cond,
                    temporary = bp.isTemporary,
                )
            }

        if (bps.isEmpty()) return BreakpointListResult("No breakpoints set.")

        val sb = StringBuilder()
        for (bp in bps) {
            sb.append("${bp.filePath}:${bp.line}")
            if (!bp.enabled) sb.append(" [disabled]")
            if (bp.temporary) sb.append(" [temporary]")
            if (bp.condition.isNotBlank()) sb.append(" condition=${bp.condition}")
            sb.append('\n')
        }
        return BreakpointListResult(sb.toString().trimEnd())
    }

    // ========== State tools ==========

    @McpTool
    @McpDescription("""
        |Returns the current debugger state: no_session, running, suspended, or stopped.
        |When suspended, also returns the current file, line, and thread name.
    """)
    suspend fun debug_get_state(): DebugStateResult {
        val project = currentCoroutineContext().project
        val sessions = XDebuggerManager.getInstance(project).debugSessions
        if (sessions.isEmpty()) {
            return DebugStateResult(status = "no_session")
        }

        val session = XDebuggerManager.getInstance(project).currentSession ?: sessions.first()
        if (session.isStopped) {
            return DebugStateResult(status = "stopped")
        }
        if (session.isSuspended) {
            val (path, line, thread) = suspensionInfo(project, session)
            return DebugStateResult(status = "suspended", filePath = path, line = line, threadName = thread)
        }
        return DebugStateResult(status = "running")
    }

    @McpTool
    @McpDescription("""
        |Blocks until the debugger suspends (breakpoint hit, step completed, etc.) or times out.
        |If the session is already suspended, returns immediately.
    """)
    suspend fun debug_wait_for_suspension(
        @McpDescription("Timeout in milliseconds (default 30000)") timeoutMs: Int = 30000,
    ): WaitResult {
        val project = currentCoroutineContext().project
        val session = getActiveSession(project)
        return waitForSuspensionInternal(project, session, timeoutMs.toLong())
    }

    // ========== Inspection tools ==========

    @McpTool
    @McpDescription("""
        |Returns the stack frames for the active (or specified) thread.
        |The debugger must be suspended.
    """)
    suspend fun debug_get_frames(
        @McpDescription("Max number of frames to return (default 20)") maxFrames: Int = 20,
    ): FramesResult {
        val project = currentCoroutineContext().project
        val session = getActiveSession(project)
        if (!session.isSuspended) mcpFail("Debugger is not suspended.")

        val suspendContext = session.suspendContext ?: mcpFail("No suspend context available.")
        val stack = suspendContext.activeExecutionStack ?: mcpFail("No active execution stack.")

        val frames = withContext(Dispatchers.EDT) {
            stack.computeFramesAsync(maxFrames)
        }

        val frameInfos = frames.mapIndexed { index, frame ->
            val pos = frame.sourcePosition
            FrameInfo(
                index = index,
                filePath = relativeFilePath(project, pos?.file),
                line = (pos?.line ?: -1) + 1,
                functionName = buildFrameName(frame),
            )
        }

        val sb = StringBuilder()
        for (f in frameInfos) {
            sb.append("#${f.index} ${f.functionName} (${f.filePath}:${f.line})\n")
        }

        return FramesResult(
            threadName = stack.displayName,
            frames = sb.toString().trimEnd(),
        )
    }

    private fun buildFrameName(frame: XStackFrame): String {
        // Try to extract a readable name from the frame's presentation
        val component = SimpleColoredTextContainer()
        frame.customizePresentation(component)
        val text = component.getText()
        return if (text.isNotBlank()) text else "(unknown)"
    }

    @McpTool
    @McpDescription("""
        |Returns local variables and parameters for the specified stack frame.
        |The debugger must be suspended. Frame index 0 is the top (current) frame.
    """)
    suspend fun debug_get_variables(
        @McpDescription("Stack frame index (0 = top frame, default 0)") frameIndex: Int = 0,
        @McpDescription("Max number of variables to return (default 30)") maxVariables: Int = 30,
    ): VariablesResult {
        val project = currentCoroutineContext().project
        val session = getActiveSession(project)
        if (!session.isSuspended) mcpFail("Debugger is not suspended.")

        val suspendContext = session.suspendContext ?: mcpFail("No suspend context available.")
        val stack = suspendContext.activeExecutionStack ?: mcpFail("No active execution stack.")

        val frames = withContext(Dispatchers.EDT) {
            stack.computeFramesAsync(frameIndex + 1)
        }

        if (frameIndex >= frames.size) {
            mcpFail("Frame index $frameIndex out of range (${frames.size} frames available).")
        }
        val frame = frames[frameIndex]

        val children = withContext(Dispatchers.EDT) {
            frame.computeChildrenAsync(maxVariables)
        }

        val variables = children.map { (name, value) ->
            val pres = withContext(Dispatchers.EDT) {
                value.computePresentationAsync()
            }
            VariableInfo(
                name = name,
                type = pres.type,
                value = pres.value,
                hasChildren = pres.hasChildren,
            )
        }

        val sb = StringBuilder()
        for (v in variables) {
            val typePrefix = if (v.type.isNotBlank()) "(${v.type}) " else ""
            sb.append("${v.name} = $typePrefix${v.value}")
            if (v.hasChildren) sb.append(" {...}")
            sb.append('\n')
        }

        return VariablesResult(
            frameIndex = frameIndex,
            variables = sb.toString().trimEnd(),
        )
    }

    // ========== Control tools ==========

    @McpTool
    @McpDescription("""
        |Resumes execution of the debug session. Does not wait for next suspension.
        |Use debug_wait_for_suspension afterwards to wait for the next breakpoint.
    """)
    suspend fun debug_resume(): ControlResult {
        val project = currentCoroutineContext().project
        val session = getActiveSession(project)
        if (!session.isSuspended) mcpFail("Debugger is not suspended.")
        withContext(Dispatchers.EDT) { session.resume() }
        return ControlResult(success = true, message = "Resumed")
    }

    @McpTool
    @McpDescription("""
        |Steps over the current line and waits for the debugger to suspend at the next line.
    """)
    suspend fun debug_step_over(): WaitResult {
        val project = currentCoroutineContext().project
        val session = getActiveSession(project)
        if (!session.isSuspended) mcpFail("Debugger is not suspended.")
        withContext(Dispatchers.EDT) { session.stepOver(false) }
        return waitForSuspensionInternal(project, session, 30_000L)
    }

    @McpTool
    @McpDescription("""
        |Steps into the method call at the current line and waits for suspension.
    """)
    suspend fun debug_step_into(): WaitResult {
        val project = currentCoroutineContext().project
        val session = getActiveSession(project)
        if (!session.isSuspended) mcpFail("Debugger is not suspended.")
        withContext(Dispatchers.EDT) { session.stepInto() }
        return waitForSuspensionInternal(project, session, 30_000L)
    }

    @McpTool
    @McpDescription("""
        |Steps out of the current method and waits for suspension in the caller.
    """)
    suspend fun debug_step_out(): WaitResult {
        val project = currentCoroutineContext().project
        val session = getActiveSession(project)
        if (!session.isSuspended) mcpFail("Debugger is not suspended.")
        withContext(Dispatchers.EDT) { session.stepOut() }
        return waitForSuspensionInternal(project, session, 30_000L)
    }

    @McpTool
    @McpDescription("""
        |Stops the debug session and terminates the debugged process.
    """)
    suspend fun debug_stop(): ControlResult {
        val project = currentCoroutineContext().project
        val session = getActiveSession(project)
        withContext(Dispatchers.EDT) { session.stop() }
        return ControlResult(success = true, message = "Debug session stopped")
    }
}

/**
 * Minimal [com.intellij.ui.ColoredTextContainer] implementation
 * to extract text from [XStackFrame.customizePresentation].
 */
private class SimpleColoredTextContainer : com.intellij.ui.ColoredTextContainer {
    private val parts = mutableListOf<String>()

    override fun append(fragment: String, attributes: com.intellij.ui.SimpleTextAttributes) {
        parts.add(fragment)
    }

    fun getText(): String = parts.joinToString("")
}
