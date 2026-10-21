# ij-mcp — IntelliJ MCP Server Extension Plugin

## What This Is
IntelliJ plugin that extends the built-in JetBrains MCP server with additional tools.
Registers `McpToolset` implementations via the `com.intellij.mcpServer.mcpToolset` extension point — the built-in server auto-discovers them.

## Project Structure
```
src/main/kotlin/dev/xdark/ijmcp/
  util/PsiUtil.kt              — resolveFile, formatLocation, findSymbolByName, Java/Kotlin class lookup
  util/FilePatternResolver.kt  — Glob/literal file pattern resolution, PSI batch resolution
  settings/Setting.kt          — Setting<T> delegate, setting() factory, SettingsHolder base
  settings/ConfigurableToolset.kt — Opt-in interface: settingsTitle + settingsHolder
  settings/ToolSettingsService.kt — App-level persistence (ijMcpToolSettings.xml) + SettingCodec
  settings/SettingsUiGenerator.kt — editorFor(): reflective Setting → Swing control by KType
  settings/McpToolSettingsConfigurable.kt — Settings > Tools > MCP Toolset Settings UI

  tools/nav/                   — read-only lookup and navigation
    FindUsagesToolset.kt         — find_usages
    FindClassToolset.kt          — find_class
    FindInFilesToolset.kt        — find_in_files
    ImplementationsToolset.kt    — get_implementations
    GoToDeclarationToolset.kt    — go_to_declaration
    TypeInfoToolset.kt           — get_type_info
    FileStructureToolset.kt      — get_file_outline
    ListPackageClassesToolset.kt — list_package_classes
    LibrarySourcesToolset.kt     — check_library_sources

  tools/refactor/              — PSI-level code modification
    MoveClassToolset.kt          — move_class
    RenameMemberToolset.kt       — rename_member
    SafeDeleteToolset.kt         — safe_delete
    ChangeSignatureToolset.kt    — change_method_signature
    InlineToolset.kt             — inline_method
    ExtractMethodToolset.kt      — extract_method
    AddMethodToolset.kt          — add_method
    AddFieldToolset.kt           — add_field
    AddMemberSupport.kt          — AddMemberResult + findJavaClass/findKotlinClass, shared by the two above
    AddImportToolset.kt          — add_imports
    ReplaceMethodBodyToolset.kt  — replace_method_body
    ExtendedRefactoringToolset.kt — optimize_imports
    ShortenReferencesToolset.kt  — shorten_references
    ReformatToolset.kt           — reformat_files
    QuickFixToolset.kt           — apply_quick_fix

  tools/files/                 — file content read/write
    BatchFileTextToolset.kt      — read_files
    BatchProblemsToolset.kt      — get_problems_in_files
    CreateFileToolset.kt         — create_file
    ReplaceLinesToolset.kt       — replace_lines
    BatchReplaceLinesToolset.kt  — batch_replace_lines
    BatchReplaceTextToolset.kt   — batch_replace_text_in_file

  tools/docs/                  — documentation tools
    AddDocumentationToolset.kt   — add_documentation
    GetDocumentationToolset.kt   — get_documentation
    MissingDocumentationToolset.kt — missing_documentation

  tools/build/                 — Gradle / dependency operations
    GradleToolset.kt             — run_gradle_task
    GradleSyncToolset.kt         — gradle_sync
    DownloadSourcesToolset.kt    — download_sources
    GradleSettings.kt            — Gradle settings bean (noisyPatterns) — first ConfigurableToolset consumer

  infra/                       — tool filtering, arg normalization, metrics, menu actions
    ToolFilterToolset.kt         — list_tools_filter
    ToolListService.kt           — App service: enumerates registered tools + built-in tool names (classloader check)
    ArgNormalizingFilterProvider.kt — McpToolFilterProvider: disables filtered tools + wraps tools for arg normalization
    ArgNormalizingMcpTool.kt     — McpTool wrapper that runs ArgumentNormalizers before delegating to the real tool
    ArgumentNormalizer.kt        — fun interface: normalize(args, propertiesSchema)
    UnknownParameterNormalizer.kt — Fails fast (mcpFail) on unknown parameter names
    StringEncodedJsonNormalizer.kt — Parses a JSON-string arg into a real array/object when the schema expects one; mcpFails on a string that opens like a container but is malformed
    SingleElementArrayNormalizer.kt — Wraps a lone value into a single-element array when the schema expects an array and the value validates as one element
    JsonSchemaValidator.kt       — Minimal recursive validator for the MCP JSON-Schema subset (type/items/properties/required/enum); used by SingleElementArrayNormalizer
    ToolFilterState.kt           — Persists disabled tool names (mcpToolFilter.xml)
    ToolFilterAction.kt          — Tools menu UI for toggling tools
    McpMetricsService.kt         — Tool call counting + persistence (also declares McpMetricsListener)
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

### Naming
- **Tool names stay `snake_case`** (`find_in_files`, `replace_method_body`) — that is the MCP convention and the built-in JetBrains tools use it too.
- **Parameter names are `camelCase`** (`filePath`, `searchText`, `maxResults`), matching the built-in 2026.2 tools (`directoryPath`, `includeIgnored`). The platform applies no name transformation: the schema exposes the Kotlin parameter name verbatim.
- The same applies to properties of nested `@Serializable` payload classes (`TextReplacement.oldText`, `Replacement.startLine`), since those become nested schema properties.
- When a `@McpDescription` mentions a parameter by name, keep it in sync — those strings are the model's only hint about spelling.

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
- **Read PSI through indexes** (class lookup, reference search, `resolve()`): `smartReadAction(project) { ... }`
- **Write PSI on EDT (refactoring)**: `withContext(Dispatchers.EDT) { writeIntentReadAction { ... } }`
- **Write PSI on EDT (direct PSI add/remove)**: `withContext(Dispatchers.EDT) { WriteCommandAction.runWriteCommandAction(project) { ... } }`
- **Reference resolution**: `psiFile.findReferenceAt(offset)` — NOT `element.references` (doesn't work for Kotlin)

## Toolset Settings

Declarative, app-global, UI-only user preferences for a toolset (NOT for overriding per-call
tool parameters — those stay MCP args). A toolset opts in by implementing `ConfigurableToolset`
and declaring settings via Kotlin property delegation:
```kotlin
class GradleSettings : SettingsHolder() {
    val noisyPatterns by setting(
        value = listOf(/* defaults */),
        label = "Suppressed output patterns",
        description = "...",            // optional; min/max for Int, multiline for String
    )
}

class GradleToolset : McpToolset, ConfigurableToolset {
    private val mySettings = GradleSettings()
    override val settingsTitle get() = "Gradle"
    override val settingsHolder get() = mySettings
    // read anywhere: mySettings.noisyPatterns
}
```
- **Declaration**: `val x by setting(value = <default>, label, description, min, max, multiline)`.
  No annotations, no per-type subclasses. `setting()` captures the declared type via `typeOf<T>()`.
- **Supported types**: Boolean, Int (min/max), String (`multiline` → text area), Enum,
  `List<String>` (always a multi-line text area, one entry per line).
- **Read**: just read the delegated property; the holder hydrates from persistence lazily on
  first read (`SettingsHolder.ensureHydrated`).
- **Persistence**: `ToolSettingsService` (`@Service(APP)`) stores `"<classFQN>.<name>" -> encoded`
  in `ijMcpToolSettings.xml`, only when a value differs from its default.
  CAUTION: the key comes from `SettingsHolder.settingsId`, which is `javaClass.name`. Moving or
  renaming a settings holder class changes the key and silently orphans any persisted value
  (it falls back to the default). Freeze `settingsId` to a stable string before moving one.
- **UI**: `McpToolSettingsConfigurable` (Settings > Tools > MCP Toolset Settings) generates the
  form reflectively via `editorFor()`; the toolset and the dialog share the same holder instance,
  so applied edits are visible to subsequent tool calls. No plugin.xml change needed to add a
  setting to an already-registered toolset.
- **No `kotlin-reflect`**: dispatch uses only `KType` classifier-identity comparison and
  `KClass.java`. Do NOT call `.members`/`.supertypes`/`.createInstance` (they throw
  `KotlinReflectionNotSupportedError` without the lib).

## Tool Filter Architecture
`ArgNormalizingFilterProvider` implements the `com.intellij.mcpserver.McpToolFilterProvider` extension point — the platform-supported way to modify the live tool list (replaces the old "unregister all other `McpToolsProvider`s" hack):
1. `getFilters()` returns two `McpToolFilter`s applied in order:
   - `ToolDisablingFilter` — reads disabled names from `ToolFilterState` and returns `DisallowMcpTools(...)` for them
   - `ArgNormalizingFilter` — wraps each remaining tool in `ArgNormalizingMcpTool`, which runs the `ArgumentNormalizer` chain (`UnknownParameterNormalizer` → `StringEncodedJsonNormalizer` → `SingleElementArrayNormalizer`) on incoming args before calling the real tool
2. `getUpdates()` returns a `Flow<Unit>` backed by a `MutableSharedFlow`; the platform collects it and re-evaluates the tool list on each emission
3. `ToolFilterState` (APP-level service) persists disabled tool names in `mcpToolFilter.xml`
4. After the dialog's OK (`doOKAction`), `ArgNormalizingFilterProvider.triggerUpdate()` emits on the flow → platform sends `tools/list_changed` → clients refresh automatically
5. `ToolListService` (APP-level service) enumerates tools via `McpToolsProvider.EP` and identifies built-in tools by classloader comparison (`toolset::class.java.classLoader != ourClassLoader`) — used by the filter dialog and `list_tools_filter`

### Argument normalizers (`ArgNormalizingMcpTool`)
- `UnknownParameterNormalizer` — `mcpFail`s when args contain keys not in the tool's `propertiesSchema`, surfacing the valid parameter names (catches model param-name typos that would otherwise silently use defaults).
- `StringEncodedJsonNormalizer` — when the schema declares a param as `"type": "array"` (or `"object"`) but the model sent a JSON string whose content opens with `[` (or `{`), parses it into a real `JsonArray`/`JsonObject`. If the content opens like a container but fails to parse (e.g. a truncated array), it `mcpFail`s with the parse error and offset instead of letting the deserializer throw the cryptic `Expected JsonArray, but had JsonLiteral`.
- `SingleElementArrayNormalizer` — when the schema expects an array, the sent value doesn't validate against it, but wrapping it as a one-element array does (checked via `JsonSchemaValidator`), rewrites the arg to `[value]`. Runs after `StringEncodedJsonNormalizer` so a string-encoded array is decoded first rather than wrapped as a lone string element. (This is why `read_files`/other list params accept a bare path string, a `["..."]` string, or a real array.)

User toggles tools via **Tools > MCP Tool Filter** (checkbox dialog: search, bulk enable/disable, built-in on/off, copy/apply JSON config, per-tool details popup). The `list_tools_filter` MCP tool provides read-only visibility.

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
- **McpToolset type restrictions**: `List<@Serializable data class>` parameters work natively — the schema generator produces `"type": "array"` with `"items"` and `CallableBridge` deserializes correctly. Return types must be `@Serializable data class` with properties — `List<T>` returns cause `IllegalStateException: Properties are expected in return type`. Workaround: wrap list results in a data class.
- **ImportFilter blocks addImport**: `JavaCodeStyleManager.addImport()` delegates to `ImportHelper` which checks `ImportFilter.shouldImport()`. Registered `ImportFilter` extensions can silently prevent imports from being added. For explicit import insertion, use `PsiElementFactory.createImportStatement(psiClass)` + `importList.add()` to bypass filters.
- **Inner class lookup**: `PsiClassOwner.classes` only returns top-level classes. To find inner/nested classes, recursively search `PsiClass.innerClasses` (Java) or `KtClassBody.declarations` (Kotlin).
- **Query.forEach vs Kotlin forEach**: IntelliJ's `Query<T>` implements `Iterable<T>`. Calling `query.forEach { ... }` invokes Kotlin's `Iterable.forEach` which calls `iterator()` → `findAll()`, materializing ALL results. For early termination, use `query.forEach(Processor { ... })` explicitly — return `false` to stop.
- **ReferencesSearch on common names**: Searching for common method names (e.g. `getInstance`, `toString`) with `allScope` triggers expensive Kotlin FIR resolution on thousands of candidate files. Do NOT narrow the scope to files that name the containing class: that drops every usage reached through an inferred type (Kotlin `val h = descriptor.processHandler; h.startNotify()`), and `find_usages` used to fail outright with "No references to class". Bound the cost with `maxResults` early termination instead.
- **Method usages**: search through `searchReferences()` (`util/PsiUtil.kt`), which uses `MethodReferencesSearch` for a `PsiMethod`. Plain `ReferencesSearch` skips the `methodReferencesSearch` executors, so it misses Kotlin property syntax for Java getters (`project.basePath` → `getBasePath()`).
- **Dumb mode**: index lookups (`JavaPsiFacade.findClass`, `PsiShortNamesCache`, `ReferencesSearch`, reference `resolve()`) throw `IndexNotReadyException` inside a plain `readAction` while the IDE indexes. Use `smartReadAction(project)`, which waits for smart mode. Keep plain `readAction` for text and PSI-tree work so file tools stay usable during indexing.
- **Highlighting from a tool**: run main passes through `MainPassesRunner`, never `DaemonCodeAnalyzerImpl.runMainPasses` directly. The latter holds a blocking read lock that a pending write action queues behind, and the EDT queues behind that write: a UI freeze, reported as "Plugin might be slowing things down".
- **Refactoring dialogs**: outside unit-test mode, refactoring processors can open modal dialogs (e.g. `SafeDeleteProcessor` shows `ConflictsDialog` when usages exist), which block the MCP call until someone clicks in the IDE. Run a processor only when the pre-check rules the dialog out.
- **Applying intentions/quick fixes**: use `ShowIntentionActionsHandler.chooseActionAndInvoke(...)`, not `action.invoke(...)`. It wraps the fix in a command (and a write action when the fix needs one); a bare invoke fails with "Must not change document outside command".
- **Indentation**: sources use tabs, but the IDE's Kotlin code style is 4 spaces, so `reformat_files` rewrites every line of a touched file. Write edits tab-indented and skip the reformat.
- **MCP parameter name mismatch**: When a tool parameter has a default value, a name mismatch between what the model sends and what the function declares silently uses the default instead of erroring. Make required parameters truly required (no default) to catch mismatches. E.g. `content: String` not `content: String = ""`.
