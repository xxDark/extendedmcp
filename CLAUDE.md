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
  GradleToolset.kt             — run_gradle_task
  MoveClassToolset.kt          — move_class
  ChangeSignatureToolset.kt    — change_method_signature
src/main/resources/META-INF/plugin.xml  — Toolset registrations
mcp-server/                    — Decompiled built-in MCP server sources (reference only)
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
- **Write PSI on EDT**: `withContext(Dispatchers.EDT) { writeIntentReadAction { ... } }`
- **Reference resolution**: `psiFile.findReferenceAt(offset)` — NOT `element.references` (doesn't work for Kotlin)

## Gotchas

- **Kotlin Duration ABI**: Don't use `Int.milliseconds` from `kotlin.time` — causes `fromRawValue` errors at runtime due to inline class ABI mismatch between plugin Kotlin and IDE Kotlin. Use `Long` directly (e.g. `withTimeoutOrNull(timeout.toLong())`).
- **Moving Kotlin files**: Use `MoveFilesOrDirectoriesProcessor` — it triggers Kotlin's `MoveFileHandler` EP which updates the `package` declaration. `MoveClassesOrPackagesProcessor` works with light classes and does NOT update Kotlin package declarations.
- **Kotlin synthetic filtering**: Light classes expose synthetic methods (componentN, copy, getters/setters, serializer, etc.) and classes (Companion, $serializer). Filter them in tools that show structure.
- **Refactoring processors**: `BaseRefactoringProcessor.run()` manages its own write actions — do NOT wrap in `writeIntentReadAction`. Just call on EDT.
- **PsiClassOwner**: `(psiFile as? PsiClassOwner)?.classes` works for both Java and Kotlin files.
