# ij-mcp — IntelliJ MCP Server Extension Plugin

## What This Is
IntelliJ plugin that extends the built-in JetBrains MCP server with additional tools.
Registers `McpToolset` implementations via the `com.intellij.mcpServer.mcpToolset` extension point — the built-in server auto-discovers them.

## Project Structure
```
src/main/kotlin/dev/xdark/ijmcp/
  util/PsiUtil.kt              — Shared helpers: resolveFile, formatLocation, findSymbolByName, etc.
  FindUsagesToolset.kt         — find_usages
  QuickFixToolset.kt           — apply_quick_fix
  ExtendedRefactoringToolset.kt — optimize_imports
  ImplementationsToolset.kt    — get_implementations
  TypeInfoToolset.kt           — get_type_info
  ExtractMethodToolset.kt      — extract_method
  LibrarySourcesToolset.kt     — check_library_sources
  GoToDeclarationToolset.kt    — go_to_declaration
  FileStructureToolset.kt      — get_file_outline
  FindClassToolset.kt          — find_class
  GradleToolset.kt             — run_gradle_task (supports debug=true for debugger attach)
  DebuggerToolset.kt           — debug_add_breakpoint, debug_remove_breakpoint, debug_list_breakpoints,
                                 debug_get_state, debug_wait_for_suspension, debug_get_frames,
                                 debug_get_variables, debug_resume, debug_step_over, debug_step_into,
                                 debug_step_out, debug_stop
  MoveClassToolset.kt          — move_class
  ChangeSignatureToolset.kt    — change_method_signature
  SafeDeleteToolset.kt         — safe_delete
  InlineToolset.kt             — inline_method
  ListPackageClassesToolset.kt — list_package_classes
  AddMemberToolset.kt          — add_method, add_field
  AddImportToolset.kt          — add_import
  ReplaceMethodBodyToolset.kt  — replace_method_body
  DocumentationToolset.kt      — add_documentation, get_documentation, missing_documentation
  FindInFilesToolset.kt        — find_in_files
  BatchProblemsToolset.kt      — get_batch_file_problems
  BatchFileTextToolset.kt      — batch_get_file_text
  CreateFileToolset.kt         — create_file
  RenameMemberToolset.kt       — rename_member
  ToolFilterToolset.kt         — list_tools_filter
  FilteredToolsProvider.kt     — McpToolsProvider that replaces built-in, applies filter
  ToolFilterState.kt           — Persists disabled tool names
  ToolFilterAction.kt          — Tools menu UI for toggling tools
  McpMetricsService.kt         — Tool call counting + persistence
  McpMetricsAction.kt          — Tools menu UI for viewing metrics
src/main/resources/META-INF/plugin.xml  — Toolset registrations
```

## Build
- **Build command**: Use run config `ij-mcp [buildPlugin]` (or `./gradlew buildPlugin`)
- After building, user reloads the plugin in the IDE, then reconnects MCP (`/mcp` in Claude Code)
- `platformVersion` in `gradle.properties` MUST match the running IDE version — mismatch causes `NoClassDefFoundError` at runtime

## Writing New Tools

### Skeleton
```kotlin
@file:Suppress("FunctionName", "unused")
package dev.xdark.ijmcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

class MyToolset : McpToolset {
    @Serializable
    data class MyResult(val data: String)

    @McpTool
    @McpDescription("Description here")
    suspend fun my_tool(
        @McpDescription("Param description") param: String,
    ): MyResult {
        val project = currentCoroutineContext().project
        // Read PSI: readAction { ... }
        // Write PSI: withContext(Dispatchers.EDT) { writeIntentReadAction { ... } }
        // Fail: mcpFail("error message")
        return MyResult("ok")
    }
}
```

### Registration
Add to `plugin.xml`:
```xml
<mcpServer.mcpToolset implementation="dev.xdark.ijmcp.MyToolset"/>
```

### Key APIs
- **Get project**: `currentCoroutineContext().project`
- **Resolve file path**: `resolveFile(project, filePath)` (from `util/PsiUtil.kt`) — returns `ResolvedFile(virtualFile, psiFile, document)`
- **Fail with error**: `mcpFail("message")`
- **Read PSI**: `readAction { ... }`
- **Write PSI on EDT (refactoring)**: `withContext(Dispatchers.EDT) { writeIntentReadAction { ... } }`
- **Write PSI on EDT (direct PSI add/remove)**: `withContext(Dispatchers.EDT) { WriteCommandAction.runWriteCommandAction(project) { ... } }`
- **Reference resolution**: `psiFile.findReferenceAt(offset)` — NOT `element.references` (doesn't work for Kotlin)

## Tool Filter Architecture
`FilteredToolsProvider` intercepts the MCP tool list at the `McpToolsProvider` extension point level:
1. On first `getTools()` call, caches tools from non-toolset providers (built-in tools like `get_file_text_by_path`)
2. Unregisters ALL other `McpToolsProvider` extensions (including `ReflectionToolsProvider`)
3. Becomes the sole provider — reads `McpToolset.EP` directly via `asTools()` and applies the filter
4. `ToolFilterState` persists disabled tool names in `mcpToolFilter.xml`
5. After toggling, `triggerRefresh()` pokes the EP (deprecated register+unregister of dummy) to cause `getMcpTools()` re-evaluation
6. MCP server sends `tools/list_changed` notification — clients update automatically

User toggles tools via **Tools > MCP Tool Filter** (checkbox dialog). The `list_tools_filter` MCP tool provides read-only visibility.

## Gotchas

- **Kotlin Duration ABI**: Don't use `Int.milliseconds` from `kotlin.time` — causes `fromRawValue` errors at runtime due to inline class ABI mismatch between plugin Kotlin and IDE Kotlin. Use `Long` directly (e.g. `withTimeoutOrNull(timeout.toLong())`).
- **Moving Kotlin files**: Use `MoveFilesOrDirectoriesProcessor` — it triggers Kotlin's `MoveFileHandler` EP which updates the `package` declaration. `MoveClassesOrPackagesProcessor` works with light classes and does NOT update Kotlin package declarations.
- **Kotlin synthetic filtering**: Light classes expose synthetic methods (componentN, copy, getters/setters, serializer, etc.) and classes (Companion, $serializer). Filter them in tools that show structure.
- **Refactoring processors**: `BaseRefactoringProcessor.run()` manages its own write actions — do NOT wrap in `writeIntentReadAction`. Just call on EDT.
- **Direct PSI mutations**: `PsiClass.add()`, `KtClassBody.addBefore()`, etc. require `WriteCommandAction.runWriteCommandAction(project)` — plain `writeIntentReadAction` causes "Must not change PSI outside command" error.
- **Kotlin plugin dependency**: Bundled plugin `org.jetbrains.kotlin` — requires `<supportsKotlinPluginMode supportsK2="true"/>` in plugin.xml.
- **PsiClassOwner**: `(psiFile as? PsiClassOwner)?.classes` works for both Java and Kotlin files.
- **Windows path relativization**: `relativizeIfPossible` throws `IllegalArgumentException` when paths are on different drives (e.g. JDK on D:\ vs project on F:\). Always wrap in try-catch.
- **Dependency scoping**: The `api("io.modelcontextprotocol:kotlin-sdk-server:...")` dependency in `build.gradle.kts` is for source browsing only. Using `api` or `implementation` causes `buildPlugin` to embed it into the plugin jar, creating classloader conflicts with the built-in MCP server plugin (e.g. "is not serializable" errors). Keep it as `compileOnly` or accept the embedding risk.
- **McpToolset type restrictions**: `ReflectionToolsProvider` requires tool parameters to be simple types (`String`, `Boolean`, `Int`) — `List<T>` parameters silently prevent the tool from registering. Return types must be `@Serializable data class` with properties — `List<T>` returns cause `IllegalStateException: Properties are expected in return type`. Workaround: use delimited strings (e.g. semicolon-separated) for multi-value params, and wrap list results in a data class.
- **ImportFilter blocks addImport**: `JavaCodeStyleManager.addImport()` delegates to `ImportHelper` which checks `ImportFilter.shouldImport()`. Registered `ImportFilter` extensions can silently prevent imports from being added. For explicit import insertion, use `PsiElementFactory.createImportStatement(psiClass)` + `importList.add()` to bypass filters.
- **Inner class lookup**: `PsiClassOwner.classes` only returns top-level classes. To find inner/nested classes, recursively search `PsiClass.innerClasses` (Java) or `KtClassBody.declarations` (Kotlin).
- **Query.forEach vs Kotlin forEach**: IntelliJ's `Query<T>` implements `Iterable<T>`. Calling `query.forEach { ... }` invokes Kotlin's `Iterable.forEach` which calls `iterator()` → `findAll()`, materializing ALL results. For early termination, use `query.forEach(Processor { ... })` explicitly — return `false` to stop.
- **ReferencesSearch on common names**: Searching for common method names (e.g. `getInstance`, `toString`) with `allScope` triggers expensive Kotlin FIR resolution on thousands of candidate files. Workaround: two-phase search — first find files referencing the containing class (unique name = fast), then search for the method only within those files using `GlobalSearchScope.filesScope()`.
- **MCP parameter name mismatch**: When a tool parameter has a default value, a name mismatch between what the model sends and what the function declares silently uses the default instead of erroring. Make required parameters truly required (no default) to catch mismatches. E.g. `content: String` not `content: String = ""`.
- **Debugger callback bridging**: `XValue.computePresentation()`, `XValueContainer.computeChildren()`, and `XExecutionStack.computeStackFrames()` are all callback-based. Bridge to coroutines with `suspendCancellableCoroutine` + `AtomicBoolean` guard to prevent double-resume. These must be called from EDT.
