# MixinMCP: IntelliJ MCP Extension for Minecraft Mod Development

## Design & Implementation Reference

> **Status:** Core tools complete and tested end-to-end on Fabric 1.21 and Forge 1.20.1.
> Decompilation cache implemented (Section 11): Gradle plugin decompiles, IDE reads.

---

## Table of Contents

1. [What This Plugin Does](#1-what-this-plugin-does)
2. [Architecture](#2-architecture)
3. [The McpToolset Contract](#3-the-mcptoolset-contract)
4. [Project Structure](#4-project-structure)
5. [Build Configuration](#5-build-configuration)
6. [Plugin Registration (plugin.xml)](#6-plugin-registration-pluginxml)
7. [Shared Utilities](#7-shared-utilities)
8. [Tool Definitions](#8-tool-definitions)
9. [Acceptance Test](#9-acceptance-test)
10. [Known Pitfalls & Edge Cases](#10-known-pitfalls--edge-cases)
11. [Decompilation Cache](#11-decompilation-cache)
    - [11.10 Timing — Tools vs. Background Decompilation](#1110-resolved-timing--tools-vs-background-decompilation)
    - [11.11 Gradle Plugin for Decompilation](#1111-gradle-plugin-for-decompilation)
12. [License](#12-license)

---

## 1. What This Plugin Does

MixinMCP is an IntelliJ Platform plugin that extends the **built-in MCP Server** (available
since IntelliJ IDEA 2025.2) with 12 tools purpose-built for Minecraft mod development —
particularly mixin authoring, dependency navigation, and bytecode inspection.

It registers tools via the `com.intellij.mcpServer` extension point so they appear
alongside the built-in tools with **zero additional configuration** for the MCP client
(Cursor, Claude Code, Claude Desktop, etc.). Users install the plugin, restart IntelliJ,
and the tools are immediately available.

### Why build this?

The built-in MCP Server has ~15 tools but they operate almost entirely on **project files**.
File search, symbol info, and inspections explicitly exclude libraries and external
dependencies. For Minecraft mod development with 50–100 dependencies (remapped Minecraft
sources, mod APIs, libraries), the LLM is blind to the vast majority of the codebase it
needs to understand.

Additionally, **no existing MCP tool** exposes bytecode-level information. Mixin development
frequently requires targeting synthetic methods (lambdas, bridge methods) that exist only in
compiled `.class` files and are invisible in decompiled source.

---

## 2. Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     IntelliJ IDEA 2025.2+                       │
│                                                                 │
│  ┌──────────────────────┐    ┌────────────────────────────────┐ │
│  │  Built-in MCP Server │    │  MixinMCP Plugin               │ │
│  │                      │    │                                │ │
│  │  • get_symbol_info   │    │  Registers via EP:             │ │
│  │  • get_file_problems │◄───│  com.intellij.mcpServer        │ │
│  │  • search_in_files   │    │                                │ │
│  │  • execute_terminal  │    │  ┌──────────────────────────┐  │ │
│  │  • rename_refactoring│    │  │  MixinMcpToolset         │  │ │
│  │  • ... (15+ tools)   │    │  │  (single class, 12 tools)│  │ │
│  │                      │    │  │                          │  │ │
│  │  SSE / Stdio         │    │  │  Source Navigation:      │  │ │
│  │  transport            │    │  │  • mixin_find_class      │  │ │
│  └──────┬───────────────┘    │  │  • mixin_search_symbols  │  │ │
│         │                    │  │  • mixin_search_in_deps  │  │ │
│         │  Exposes all       │  │  • mixin_get_dep_source  │  │ │
│         │  tools unified     │  │                          │  │ │
│         │                    │  │  Semantic Navigation:    │  │ │
│         ▼                    │  │  • mixin_type_hierarchy  │  │ │
│  ┌──────────────┐            │  │  • mixin_find_impls      │  │ │
│  │ MCP Protocol │            │  │  • mixin_find_references │  │ │
│  │ (to Cursor)  │            │  │  • mixin_call_hierarchy  │  │ │
│  └──────────────┘            │  │  • mixin_super_methods   │  │ │
│                              │  │                          │  │ │
│                              │  │  Bytecode:               │  │ │
│                              │  │  • mixin_class_bytecode  │  │ │
│                              │  │  • mixin_method_bytecode │  │ │
│                              │  │                          │  │ │
│                              │  │  Project:                │  │ │
│                              │  │  • mixin_sync_project    │  │ │
│                              │  └──────────────────────────┘  │ │
│                              │                                │ │
│                              │  ┌──────────────────────────┐  │ │
│                              │  │  Shared Utilities        │  │ │
│                              │  │  • FqcnResolver          │  │ │
│                              │  │  • ClassFileLocator      │  │ │
│                              │  │  • MethodResolver        │  │ │
│                              │  │  • BytecodeAnalyzer      │  │ │
│                              │  └──────────────────────────┘  │ │
│                              └────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

Because we register via the `com.intellij.mcpServer` extension point, our tools appear in
the **same** MCP server the user already has configured. No second port, no second
connection.

---

## 3. The McpToolset Contract

All 12 tools are defined as annotated suspend functions on a single `McpToolset`
implementation. The MCP framework discovers tools via `@McpTool` annotations at runtime.

### The Pattern

```kotlin
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.projectOrNull
import kotlin.coroutines.coroutineContext

class MixinMcpToolset : McpToolset {

    @McpTool
    @McpDescription("Description the LLM sees when choosing tools.")
    suspend fun mixin_tool_name(
        requiredParam: String,
        optionalParam: Int = 42,
        projectPath: String? = null,
    ): McpToolCallResult {
        val project = coroutineContext.projectOrNull
            ?: return McpToolCallResult.error("No project open")

        // PSI access MUST be wrapped in ReadAction.compute { }
        return McpToolCallResult.text("result")
    }
}
```

### Critical Details

- **Function name** becomes the MCP tool name (e.g. `mixin_find_class`).
- **Parameters** become JSON parameters; default values make them optional.
- **Project resolution** via `coroutineContext.projectOrNull` (not a function parameter).
- **Return type** is always `McpToolCallResult` — use `.text()` for success, `.error()` for errors.
- **Threading:** Tool functions are suspend. PSI access requires `ReadAction.compute<T, Throwable> { }`.
  Write operations (e.g. sync) require EDT dispatch via `ApplicationManager.getApplication().invokeLater { }`.
- **`@McpDescription`:** The string in this annotation is what the LLM sees. Dollar signs must
  be escaped (`\$`) or Kotlin treats them as string templates.

### Registration in plugin.xml

```xml
<depends>com.intellij.mcpServer</depends>

<extensions defaultExtensionNs="com.intellij.mcpServer">
    <mcpToolset implementation="dev.mixinmcp.tools.MixinMcpToolset"/>
</extensions>
```

One `<mcpToolset>` entry registers the class; all `@McpTool`-annotated methods are
discovered automatically.

**IMPORTANT:** The extension namespace is `com.intellij.mcpServer` (capital S). Lowercase
silently fails to register tools.

---

## 4. Project Structure

```
MixinMCP/
├── build.gradle.kts                   # IntelliJ plugin build
├── settings.gradle.kts                # Includes mixinmcp-gradle module
├── gradle.properties
├── gradle/libs.versions.toml          # Version catalog
├── DESIGN.md                          ← This file
├── README.md                          # User-facing docs + cursor rule
├── src/                               # IntelliJ plugin (IDE-side)
│   └── main/
│       ├── kotlin/dev/mixinmcp/
│       │   ├── tools/
│       │   │   └── MixinMcpToolset.kt # All 12 tools in one class
│       │   ├── cache/
│       │   │   ├── DecompilationCacheService.kt  # Read-only cache consumer
│       │   │   ├── DecompilationManifest.kt      # Manifest format (kotlinx-serialization)
│       │   │   ├── MixinDecompiledRootsProvider.kt # SyntheticLibrary roots
│       │   │   ├── MixinDecompileCacheSyncListener.kt  # Re-index after sync
│       │   │   └── MixinDecompileCacheStartupActivity.kt # Attach roots on open
│       │   └── util/
│       │       ├── FqcnResolver.kt    # FQCN → PsiClass (allScope)
│       │       ├── MethodResolver.kt  # Class + name → PsiMethod
│       │       ├── ClassFileLocator.kt# FQCN → raw .class bytes
│       │       └── BytecodeAnalyzer.kt# ASM-based bytecode parsing
│       └── resources/
│           └── META-INF/
│               └── plugin.xml
├── mixinmcp-gradle/                   # Gradle plugin (decompilation)
│   ├── build.gradle.kts
│   └── src/main/kotlin/dev/mixinmcp/gradle/
│       ├── MixinDecompilePlugin.kt    # Plugin entry point
│       ├── MixinDecompileTask.kt      # ./gradlew mixinDecompile
│       ├── DecompilationManifest.kt   # Manifest format (Gson)
│       └── CacheEntry.kt             # Cache entry data class
└── src/test/kotlin/dev/mixinmcp/
    └── ...
```

All tools live in `MixinMcpToolset.kt` rather than separate files per tool. This avoids
registration boilerplate and keeps the entire tool surface in one place.

The `cache/` package in the IntelliJ plugin is a **read-only consumer** of the
decompilation cache populated by the Gradle plugin. See Section 11 for details.

---

## 5. Build Configuration

Key aspects of the build:

- **Platform:** IntelliJ IDEA Community 2025.2+ (`pluginSinceBuild = 252`)
- **Bundled plugins:** `com.intellij.java` (Java PSI), `com.intellij.mcpServer` (MCP API)
- **Dependencies:** ASM 9.7.1 (`asm`, `asm-util`) for bytecode analysis,
  `kotlinx-serialization-json` for MCP framework compatibility
- **Java:** 21, **Kotlin:** 2.1.0+

ASM note: IntelliJ bundles its own ASM. The plugin classloader handles this correctly in
practice. If runtime errors occur with ASM classes, the fallback is to use `javap` via
process execution.

---

## 6. Plugin Registration (plugin.xml)

```xml
<idea-plugin>
    <id>dev.mixinmcp</id>
    <name>MixinMCP - Mixin Development Tools for MCP Server</name>
    <vendor>mixinmcp</vendor>

    <description><![CDATA[
    Extends the built-in MCP Server with tools for Minecraft mod development:
    class/symbol lookup in dependencies, type hierarchy traversal,
    call graph navigation, and bytecode inspection for mixin target resolution.
    ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.java</depends>
    <depends>com.intellij.mcpServer</depends>

    <extensions defaultExtensionNs="com.intellij.mcpServer">
        <mcpToolset implementation="dev.mixinmcp.tools.MixinMcpToolset"/>
    </extensions>
</idea-plugin>
```

---

## 7. Shared Utilities

### 7.1 FqcnResolver

Resolves fully-qualified class names to PsiClass instances **including dependency/library
classes** via `GlobalSearchScope.allScope(project)`. Supports inner classes by progressively
converting dots to dollar signs.

### 7.2 MethodResolver

Resolves a method within a class by name and optional parameter type list. Falls back to
`psiClass.methods` if `findMethodsByName` returns empty (happens with some JDK classes).
Returns all overloads when `parameterTypes` is null; filters by signature when provided.

### 7.3 ClassFileLocator

Locates raw `.class` file bytes for a given FQCN. Validates bytes start with `0xCAFEBABE`.
Strategy:
1. Via `ClsFileImpl.virtualFile.contentsToByteArray()` (works for library classes)
2. JAR fallback: parses `jar:///path/to.jar!/entry/Class.class` URL and reads directly
3. For project source classes: looks for `.class` in output directories

### 7.4 BytecodeAnalyzer

ASM-based analysis producing structured `ClassAnalysis` with method/field info, access
flags, synthetic detection, and lambda source method parsing. Also provides single-method
bytecode extraction via `analyzeMethod()` returning javap-style output.

---

## 8. Tool Definitions

### Source Navigation

| Tool | Parameters | Scope |
|------|-----------|-------|
| `mixin_find_class` | `className`, `includeMembers=true`, `includeSource=false` | All classes (project + deps + JDK) |
| `mixin_search_symbols` | `query`, `kind=class`, `scope=all`, `caseSensitive=false`, `maxResults=50` | All indexed symbols |
| `mixin_search_in_deps` | `regexPattern`, `fileMask?`, `caseSensitive=true`, `maxResults=100`, `timeout=15000` | Sources jars + decompiled cache |
| `mixin_get_dep_source` | `url?` or `path?`, `lineNumber=1`, `linesBefore=30`, `linesAfter=70` | Sources jars + decompiled cache |

**Important:** `mixin_search_in_deps` and `mixin_get_dep_source` only search/read from
`OrderRootType.SOURCES` (attached `-sources.jar` files). They do **not** search decompiled
content from compiled-only jars. For classes without sources, use `mixin_find_class` with
`includeSource=true` (returns decompiled source by FQCN) or the bytecode tools.

### Semantic Navigation

| Tool | Parameters |
|------|-----------|
| `mixin_type_hierarchy` | `className`, `direction=both`, `maxDepth=10`, `includeInterfaces=true` |
| `mixin_find_impls` | `className`, `maxResults=50` |
| `mixin_find_references` | `className`, `memberName?`, `parameterTypes?`, `maxResults=100` |
| `mixin_call_hierarchy` | `className`, `methodName`, `parameterTypes?`, `direction=callers`, `maxDepth=3`, `maxResults=50` |
| `mixin_super_methods` | `className`, `methodName`, `parameterTypes?` |

Tools that take `parameterTypes` require it for overloaded methods. For parameterless
methods, pass `parameterTypes: []` (empty array). Tool descriptions include this guidance.

### Bytecode Inspection

| Tool | Parameters |
|------|-----------|
| `mixin_class_bytecode` | `className`, `filter=all`, `includeInstructions=false` |
| `mixin_method_bytecode` | `className`, `methodName`, `methodDescriptor?` |

`filter` accepts: `all`, `synthetic`, `methods`, `fields`. Use `synthetic` for mixin lambda
target discovery.

### Project Management

| Tool | Parameters |
|------|-----------|
| `mixin_sync_project` | `projectPath?` |

Triggers Gradle sync via `ExternalSystemUtil.refreshProject()` with
`ProgressExecutionMode.START_IN_FOREGROUND_ASYNC`. Falls back to Maven if Gradle fails.
Runs on EDT via `invokeLater`.

---

## 9. Acceptance Test

Tested on both **Fabric 1.21** and **Forge 1.20.1** with real Minecraft mod projects.

**Scenario (Forge 1.20.1 — Otherworld-Core):**

```
Step 1: mixin_find_class
  Args: { "className": "net.minecraft.world.level.Level", "includeMembers": true }
  → Found Level with CapabilityProvider superclass (Forge), all methods listed

Step 2: mixin_class_bytecode (filter=synthetic)
  → Returned lambda$fillReportDetails$4, lambda$fillReportDetails$3,
    lambda$getEntities$2, lambda$getEntities$1, lambda$new$0

Step 3: mixin_method_bytecode
  Args: { "methodName": "lambda$fillReportDetails$4" }
  → Returned bytecode: dimension().location().toString()

Step 4: mixin_super_methods
  Args: { "methodName": "fillReportDetails", "parameterTypes": ["net.minecraft.CrashReport"] }
  → Confirmed: declared in Level, not inherited

Step 5: Created LevelMixin targeting lambda$fillReportDetails$4
Step 6: get_file_problems → No errors
```

**Fabric 1.21 difference:** Synthetic methods use `method_XXXXX` names (Loom intermediary)
instead of `lambda$...`. The tools work correctly for both patterns.

---

## 10. Known Pitfalls & Edge Cases

### Extension Point Namespace
The namespace is `com.intellij.mcpServer` (capital S). Lowercase silently fails.

### McpDescription Dollar Signs
Kotlin treats `$` in annotation strings as template expressions. Escape with `\$`:
`lambda\$tick\$0` not `lambda$tick$0`.

### Remapped Names
Modding toolchains remap names between obfuscated and mapped. Our tools see **dev-time
mapped names**. Fabric/Loom uses intermediary names for synthetics (`method_XXXXX`);
Forge/MojMap uses `lambda$methodName$index`.

### ReadAction Requirements
ALL PSI access MUST be wrapped in `ReadAction.compute<T, Throwable> { }`. Tool functions
are called on a background thread without a read lock.

### ProgressExecutionMode
For project sync, use `ProgressExecutionMode.START_IN_FOREGROUND_ASYNC` (not
`IN_FOREGROUND_ASYNC_PLAIN` which doesn't exist).

### MethodResolver and Overloads
`resolveSingle()` returns null for ambiguous overloads without `parameterTypes`.
`findMethodsByName()` can return empty for JDK classes — the resolver falls back to
`psiClass.methods` filtered by name.

### ClassInheritorsSearch Performance
Can be slow with 100+ dependencies. Always use `maxResults` limits.

### Decompiled Source Search
`mixin_search_in_deps` and `mixin_get_dep_source` search both SOURCES roots (attached
`-sources.jar`) and decompiled cache roots (from `AdditionalLibraryRootsProvider`).
Run `./gradlew mixinDecompile` to populate the cache for compiled-only dependencies.

### Large Output
Full decompiled source or `includeInstructions=true` can produce very large responses.
Tool descriptions guide LLMs toward targeted queries.

### Synthetic Method Naming
Lambda synthetic names follow `lambda$<method>$<index>` (compiler convention, not spec).
Fabric/Loom intermediary mappings use `method_XXXXX` instead. BytecodeAnalyzer's
`lambdaSourceMethod` parser handles the standard `lambda$` pattern.

---

## 11. Decompilation Cache

> **Status:** Implemented. IDE-side cache is read-only (Sections 11.5 Steps 4–5).
> Gradle plugin `dev.mixinmcp.decompile` implemented (Section 11.11).
> `MixinDecompiledRootsProvider` uses the 5-param `newImmutableLibrary` overload
> with `comparisonId` + `ExcludeFileCondition`.

### 11.1 Problem

`mixin_search_in_deps` and `mixin_get_dep_source` only operate on SOURCES roots
(`-sources.jar` files). Many dependencies ship without published sources. IntelliJ can
decompile `.class` files on demand, but the output is ephemeral — it lives in the PSI
tree only while a file is open and is never persisted or indexed for search.

This means there is no regex search or file-style reading for compiled-only dependencies.
The LLM must know the exact FQCN to use `mixin_find_class(includeSource=true)`, which
requires already knowing what to look for.

### 11.2 Existing Decompilation in the Ecosystem

Loom, ModDevGradle, and ForgeGradle already decompile **Minecraft itself** during the
build process and attach the result as a `-sources.jar` on the library. This is why
`mixin_search_in_deps` works for Minecraft classes — the sources are already present as
`OrderRootType.SOURCES` roots.

However, these toolchains do **not** decompile other dependencies (mod APIs, shaded
libraries, closed-source mod deps). The Gradle cache (`~/.gradle/caches/`) only stores
artifacts as published — if no `-sources.jar` was published, none is cached.

### 11.3 Solution: Synthetic Sources via Gradle Decompilation + IDE Exposure

A **Gradle plugin** (`dev.mixinmcp.decompile`) decompiles all library JARs that lack
SOURCES roots and writes the output to a persistent file cache. Running
`./gradlew mixinDecompile` completes decompilation as a blocking step — before the IDE
opens or the LLM invokes tools — giving deterministic, CI-friendly results.

An `AdditionalLibraryRootsProvider` in the IntelliJ plugin exposes the cached
decompiled sources as `SyntheticLibrary` roots, making them visible in
`GlobalSearchScope.allScope()` and indexed by IntelliJ — without modifying the
Gradle-managed project model.

This two-layer split (Gradle writes, IDE reads) solves the timing problem inherent in
background decompilation: tools never run against a half-populated cache because
decompilation finishes before the project model is built. See Section 11.10 for the timing analysis and Section 11.11 for the Gradle plugin
design.

### 11.4 Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  GRADLE SIDE (blocking, runs before IDE)                            │
│                                                                     │
│  ./gradlew mixinDecompile                                           │
│        │                                                            │
│        ▼                                                            │
│  ┌────────────────────────────────────────────────────────────┐     │
│  │  Gradle Plugin  (dev.mixinmcp.decompile)                   │     │
│  │                                                            │     │
│  │  1. Resolve configurations → find JARs without -sources    │     │
│  │  2. Compute artifact hash per JAR                          │     │
│  │  3. Check manifest.json for cache hits                     │     │
│  │  4. Decompile misses via Vineflower → write .java to disk  │     │
│  │  5. Update manifest.json                                   │     │
│  └────────────────────────────────────────────────────────────┘     │
│        │                                                            │
│        ▼                                                            │
│  ┌────────────────────────┐                                         │
│  │  File cache on disk    │                                         │
│  │  ~/.cache/mixinmcp/    │                                         │
│  │  └── decompiled/       │                                         │
│  │      ├── manifest.json │                                         │
│  │      └── <hash>/       │                                         │
│  │          └── com/...   │                                         │
│  │              └── *.java│                                         │
│  └────────────────────────┘                                         │
└─────────────────────────────────────────────────────────────────────┘
                │
                │  reads (no decompilation)
                ▼
┌─────────────────────────────────────────────────────────────────────┐
│  IDE SIDE (IntelliJ plugin, read-only cache consumer)               │
│                                                                     │
│  ┌────────────────────────────────────────────────────────────┐     │
│  │  DecompilationCacheService  (project-level service)        │     │
│  │  Reads manifest.json, returns CachedLibraryInfo list       │     │
│  └────────────────────────────────────────────────────────────┘     │
│        │                                                            │
│        ▼                                                            │
│  ┌────────────────────────────────────────────────────────────┐     │
│  │  MixinDecompiledRootsProvider                              │     │
│  │  (AdditionalLibraryRootsProvider impl)                     │     │
│  │                                                            │     │
│  │  Returns SyntheticLibrary per cached artifact.             │     │
│  │  Source roots = cache dirs → indexed, in allScope().       │     │
│  └────────────────────────────────────────────────────────────┘     │
│        │                                                            │
│        ▼                                                            │
│  ┌────────────────────────────────────────────────────────────┐     │
│  │  Existing tools (no changes needed)                        │     │
│  │  mixin_search_in_deps ◄─── collectAllSourceRoots() already│     │
│  │  mixin_get_dep_source ◄─── queries synthetic library roots│     │
│  │  mixin_find_class     ◄─── works automatically (allScope) │     │
│  │  mixin_search_symbols ◄─── works automatically (indexed)  │     │
│  └────────────────────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────────────────────┘
```

**Why `AdditionalLibraryRootsProvider` instead of `LibraryEx.modifiableModel`:**
Gradle sync rebuilds the project model from scratch and **discards** manually-added
library roots. IntelliJ's own warning: "A Library is imported from Gradle. Any changes
made in its configuration might be lost after reimporting." The
`AdditionalLibraryRootsProvider` / `SyntheticLibrary` mechanism is explicitly designed
for synthetic roots that exist outside the build system's project model — they survive
sync because they're never part of the Gradle model to begin with.

**Why Vineflower as a library dependency instead of IntelliJ's built-in decompiler:**
IntelliJ's `IdeaDecompiler` is designed for on-demand single-file use in the editor.
Batch-invoking it from a background thread has undocumented threading constraints and
couples us to IntelliJ's internal decompiler version. Vineflower as an explicit library
dependency gives us full control: deterministic versions, no threading surprises, no
dependency on editor state. It's also the same decompiler IntelliJ bundles (Fernflower
→ Vineflower lineage), so output quality is equivalent.

### 11.5 Implementation Steps

> **Note:** Steps 1–3 and 5 describe the original IDE-side decompilation approach.
> With the Gradle plugin (Section 11.11), Steps 2–3 move to the Gradle task and
> Step 5 is simplified — the IDE only reads the cache, not writes it. Step 1 (cache
> format) and Step 4 (roots provider) remain unchanged.

#### Step 1: Cache Directory & Manifest

- Cache location: `~/.cache/mixinmcp/decompiled/` (XDG-compatible, outside project).
- `manifest.json` maps artifact identity → cache entry:
  ```json
  {
    "<artifact-hash>": {
      "libraryName": "org.example:foo:1.0",
      "classesJarPath": "/path/to/foo-1.0.jar",
      "jarSize": 123456,
      "jarModified": 1700000000000,
      "cachePath": "/home/user/.cache/mixinmcp/decompiled/a1b2c3d4/",
      "decompilerVersion": "vineflower-1.10.1",
      "createdAt": 1700000000000
    }
  }
  ```
- Artifact hash: SHA-256 of `(jarPath + jarSize + jarLastModified)`. Fast to compute
  (no need to hash file contents), sufficient for invalidation.

#### Step 2: Library Enumeration

- After Gradle sync, iterate `ModuleManager.getInstance(project).modules` →
  `ModuleRootManager.orderEntries` → filter `LibraryOrderEntry` instances where
  `library.getFiles(OrderRootType.SOURCES)` is empty but
  `library.getFiles(OrderRootType.CLASSES)` is non-empty.
- Skip JDK/platform libraries (sources available via JDK install) — filter by
  checking if the order entry is a `JdkOrderEntry`.
- Collect the list of JARs to process.

#### Step 3: Decompilation via Vineflower

- Add Vineflower as a compile dependency in `build.gradle.kts`:
  `implementation("org.vineflower:vineflower:1.11.2")` (or `-slim` variant).
- For each JAR needing decompilation:
  1. Check manifest — if a valid cache entry exists with matching hash, skip.
  2. Create a cache subdirectory: `<cacheRoot>/<artifact-hash>/`.
  3. Invoke Vineflower's `Decompiler` API — pass the JAR `File` as input and a
     `DirectoryResultSaver(<cacheDir>)` as output. Vineflower handles JAR
     enumeration, class parsing, and package-structured `.java` file output
     internally. One call decompiles the entire JAR (see Section 11.7 for API
     details and code sample).
  4. Update manifest entry with artifact hash, cache path, decompiler version.
- **Threading:** Run on a background thread via `ProgressManager.getInstance()
  .run(Task.Backgroundable(...))`. Vineflower is a pure library with no IntelliJ
  threading constraints — safe to call from any thread.
- **Progress:** Report per-JAR progress in the IDE's background task indicator.
  Optionally implement `IFernflowerLogger` to capture per-class progress from
  Vineflower.
- **Limits:** Cap total decompilation time per sync (configurable, default 120s).
  Process largest/most-important JARs first (heuristic: JARs from the project's
  direct dependencies before transitive ones).

#### Step 4: Expose via AdditionalLibraryRootsProvider

- Register `MixinDecompiledRootsProvider extends AdditionalLibraryRootsProvider` in
  `plugin.xml`:
  ```xml
  <extensions defaultExtensionNs="com.intellij">
      <additionalLibraryRootsProvider
          implementation="dev.mixinmcp.cache.MixinDecompiledRootsProvider"/>
  </extensions>
  ```
- `getAdditionalProjectLibraries(project)` reads the manifest and returns a
  `SyntheticLibrary` for each cached artifact using the **5-parameter overload**
  that accepts a `comparisonId` and `ExcludeFileCondition`:
  ```kotlin
  SyntheticLibrary.newImmutableLibrary(
      "mixinmcp-${info.artifactHash}",   // comparisonId — stable per-artifact, enables incremental rescan
      listOf(info.root),                  // sourceRoots — decompiled .java dirs
      emptyList(),                        // binaryRoots — none needed
      emptySet(),                         // excludedRoots — none
      ExcludeFileCondition { isDir, filename, _, _, _ ->
          !isDir && !filename.endsWith(".java")
      }
  )
  ```
  **Important:** The 4-parameter overload (without `comparisonId`, using `Condition`)
  is deprecated. Per the `SyntheticLibrary` Javadoc: "Non-null [Condition] value blocks
  from incremental rescanning of library changes." Using the 5-parameter overload with
  `comparisonId` + `ExcludeFileCondition` allows IntelliJ to rescan only changed
  libraries instead of re-indexing all synthetic roots on every change.

  Note on parameter order: `newImmutableLibrary(sourceRoots, binaryRoots, ...)` — the
  first list is `sourceRoots`, not `binaryRoots`. Decompiled `.java` directories belong
  in `sourceRoots` so they are indexed as library source files and visible to
  `GlobalSearchScope.allScope()`.
- After decompilation completes, call
  `AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(...)` to trigger
  IntelliJ to re-query the provider and re-index the new roots.
- **Effect on existing tools:**
  - `mixin_find_class`, `mixin_search_symbols`: work automatically — the decompiled
    files are in `allScope()` and indexed by `PsiShortNamesCache`.
  - `mixin_search_in_deps`, `mixin_get_dep_source`: `collectAllSourceRoots()` already
    queries `AdditionalLibraryRootsProvider.EP_NAME.extensionList` to include synthetic
    library source roots alongside `OrderRootType.SOURCES` on library order entries.
    No further tool changes needed — decompiled content is searchable once the
    `SyntheticLibrary` roots are exposed and indexed.

#### Step 5: Sync Trigger & Invalidation

- **Trigger on sync:** Register an `ExternalSystemTaskNotificationListener` (via
  `com.intellij.externalSystemTaskNotificationListener` EP) and trigger cache refresh
  in `onSuccess()` when the task is a project resolve/sync. Alternatively, use
  `ExternalSystemExecutionAware` which receives the task type and listener.
- **Trigger on project open:** Implement `ProjectActivity` to run cache refresh on
  startup (checks manifest, attaches existing cache entries, queues decompilation for
  any new/changed JARs).
- **Invalidation:** On each trigger, re-enumerate libraries. For each:
  - If JAR hash matches manifest → skip (already cached and exposed).
  - If JAR hash changed → delete old cache entry, re-decompile, update manifest.
  - If library was removed → delete orphaned cache entry, remove from provider.
- **Manual trigger:** Extend `mixin_sync_project` — after Gradle sync completes,
  also trigger the decompilation cache refresh.
- **Notification:** After cache changes, call
  `AdditionalLibraryRootsListener.fireAdditionalLibraryChanged()` so IntelliJ
  re-queries the provider and re-indexes.

#### Step 6: User Configuration (Optional)

- Settings in plugin preferences:
  - Enable/disable decompilation cache (default: enabled)
  - Cache size limit (default: 2 GB)
  - Excluded library patterns (e.g., `org.jetbrains.*`)
  - Maximum decompilation time per sync

### 11.6 Resolved Questions

1. ~~**Decompiler API access**~~ → **Resolved: Use Vineflower as a library dependency.**
   Avoids IntelliJ threading constraints and gives deterministic version control. Invoke
   on background threads without restriction.

2. ~~**Library model modification**~~ → **Resolved: Do NOT modify library roots.**
   Gradle sync destroys manually-added roots. Use `AdditionalLibraryRootsProvider` /
   `SyntheticLibrary` instead — these exist outside the Gradle project model and
   survive sync. Source roots are in `GlobalSearchScope.allScope()` and indexed.

3. ~~**Gradle sync listener API**~~ → **Resolved: Use
   `ExternalSystemTaskNotificationListener`** registered via the
   `com.intellij.externalSystemTaskNotificationListener` extension point. The
   `onSuccess()` callback fires after sync completes. Combine with `ProjectActivity`
   for project-open trigger.

4. ~~**Timing: tools vs. background decompilation**~~ → **Resolved: Move
   decompilation to a Gradle task** (Section 11.10, 11.11). Background decompilation
   in the IDE creates a race condition where MCP tools execute before decompilation
   finishes. The Gradle plugin (`./gradlew mixinDecompile`) makes decompilation a
   blocking build step, eliminating the timing gap. The IntelliJ plugin becomes a
   read-only cache consumer.

5. ~~**Concurrent access**~~ → **Resolved: Gradle daemon serialization + shared
   cache.** The Gradle plugin runs within the Gradle daemon, which serializes task
   execution per project. Multiple projects sharing `~/.cache/mixinmcp/` is safe
   because the artifact hash includes the full JAR path — different projects
   referencing the same JAR at the same path produce the same hash and the same
   cache entry (idempotent). Different JAR paths produce different hashes. Manifest
   file-locking is still recommended for robustness but not critical for correctness.

6. ~~**Tool modification scope**~~ → **Resolved: Already implemented.**
   `collectAllSourceRoots()` in `MixinMcpToolset` already queries
   `AdditionalLibraryRootsProvider.EP_NAME.extensionList` to include synthetic
   library source roots. No tool changes needed — decompiled `.java` files in
   synthetic roots are searchable/readable via the same `VirtualFile` APIs.

### 11.7 Resolved: Vineflower API

Vineflower (v1.11.2) has a clean programmatic API at
`org.jetbrains.java.decompiler.api.Decompiler`. The simplest invocation for our use
case — decompile an entire JAR to a directory:

```kotlin
val decompiler = Decompiler.builder()
    .inputs(jarFile)                              // File — Vineflower handles JAR enumeration
    .output(DirectoryResultSaver(cacheDir))       // writes .java preserving package structure
    .option(IFernflowerPreferences.REMOVE_BRIDGE, "1")
    .option(IFernflowerPreferences.REMOVE_SYNTHETIC, "0")  // keep synthetic for mixin use
    .logger(IFernflowerLogger.NO_OP)              // or a progress-reporting impl
    .build()

decompiler.decompile()                            // blocking, run on background thread
```

Key findings from reading the source:

- **Input:** `Builder.inputs(File)` accepts JAR files directly. No need to enumerate
  `.class` entries ourselves — Vineflower does it internally.
- **Output:** `DirectoryResultSaver(File root)` writes decompiled `.java` files to
  the given directory, preserving package structure. `saveClassFile()` receives the
  `content` as a `String` and writes it via `BufferedWriter`.
- **No in-memory needed:** We want files on disk anyway (for the cache), so
  `DirectoryResultSaver` is the exact match. No custom `IResultSaver` required.
- **Whole-JAR decompilation:** Processing the entire JAR in one call lets Vineflower
  use cross-class context for better decompilation quality (resolved generics,
  inlined constants, etc.) vs. per-class decompilation.
- **Artifact:** `org.vineflower:vineflower:1.11.2` on Maven Central. A `-slim`
  variant exists without plugins (smaller footprint).
- **Thread-safe:** Pure library with no IntelliJ dependencies. Safe to call from any
  background thread.

### 11.8 Resolved: AdditionalLibraryRootsListener API

The notification method to trigger re-indexing after cache changes:

```kotlin
// Must be called under write lock
ApplicationManager.getApplication().runWriteAction {
    AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
        project,
        "MixinMCP Decompiled: org.example:foo:1.0",  // presentableLibraryName (for UI)
        emptyList(),                                   // oldRoots (empty on first attach)
        listOf(cacheDirVirtualFile),                   // newRoots (cache dir to index)
        "mixinmcp-decompiled"                          // libraryNameForDebug (for logs)
    )
}
```

Key details from the source (`AdditionalLibraryRootsListener.java`):

- **`@RequiresWriteLock`** — must be called inside `runWriteAction { }`.
- `presentableLibraryName` is nullable, used for progress titles during indexing.
- `oldRoots` / `newRoots` are `Collection<VirtualFile>`.
- For initial attachment: empty `oldRoots`, cache dirs as `newRoots`.
- For cache invalidation/update: old cache dirs as `oldRoots`, new as `newRoots`.
- Can also pass empty `oldRoots` + empty `newRoots` to force a full re-read of all
  `AdditionalLibraryRootsProvider` instances.
- **`@ApiStatus.Experimental`** — the API is marked experimental, but it's the
  canonical approach used by JetBrains' own plugins. Worth noting for future
  compatibility checks.
- Per the Javadoc: "In particular `newRoots` would be indexed, and the Project View
  tree would be refreshed."

### 11.9 Open Questions

Remaining items before implementation:

1. **Re-indexing cost:** `SyntheticLibrary` roots are indexed by IntelliJ. Need to
   measure whether exposing 50+ decompiled-source directories via the provider causes
   noticeable IDE slowdown. The `comparisonId` mechanism should enable incremental
   rescanning (only re-index changed libraries), but needs verification.

2. **Decompiler quality & faithfulness:** Decompiled output differs from original
   sources (different variable names, restructured control flow, lost comments/Javadoc).
   Acceptable for search/navigation but the LLM should know the source is decompiled.
   Consider adding a `// DECOMPILED — not original source` header to each file, or
   implementing a custom `IResultSaver` that prepends this header before writing.

3. **Loom/MDG interaction:** Loom and MDG already decompile Minecraft and attach
   sources. Verify that our provider doesn't create duplicate synthetic libraries for
   JARs that already have SOURCES roots (the enumeration in Step 2 should filter
   these out, but needs testing with real Fabric/Forge projects).

4. **Scale estimate:** How many libraries lack sources in a typical Minecraft mod
   project? Determines whether background decompilation takes seconds or minutes,
   and whether priority ordering is needed. Check in IntelliJ: Project Structure →
   Libraries → count those without a sources JAR attached.

5. **Gradle Plugin Portal publishing:** The `dev.mixinmcp.decompile` Gradle plugin
   needs its own publishing pipeline. Determine whether to publish to the Gradle
   Plugin Portal, Maven Central, or both. Coordinate versioning with the IntelliJ
   plugin.

### 11.10 Resolved: Timing — Tools vs. Background Decompilation

The original design (Section 11.5 Step 5) triggers decompilation as a
`Task.Backgroundable` after Gradle sync completes, and again via `ProjectActivity`
on project open. This creates a **timing gap**: MCP tools can execute before
decompilation finishes, causing `mixin_search_in_deps` to return incomplete results
for decompiled content.

**How the gap manifests:**

1. User opens project → `MixinDecompileCacheStartupActivity` attaches cached roots
   from the manifest immediately, then queues a background `refreshCache()` for
   new/changed JARs.
2. User triggers Gradle sync → `MixinDecompileCacheSyncListener.onSuccess()` queues
   another background `refreshCache()`.
3. LLM calls `mixin_search_in_deps` → `collectAllSourceRoots()` queries the
   `AdditionalLibraryRootsProvider`, but decompilation is still in progress. Only
   previously-cached roots are visible; newly-added dependencies are missing.

**Why there is no good in-process fix:** The MCP tool has no mechanism to block
until decompilation finishes. Adding one would stall the LLM on every tool call.
Exposing a "decompilation in progress" status adds complexity to the tool protocol
and shifts the burden to the LLM to retry.

**Resolution: Move decompilation to Gradle (Section 11.11).** A Gradle task
(`./gradlew mixinDecompile`) runs decompilation as a blocking build step. The cache
is fully populated before the IDE opens or the user starts working. The IntelliJ
plugin becomes a read-only cache consumer — it never decompiles, only reads the
manifest and exposes roots. This eliminates the timing gap entirely.

**Fallback for the hybrid approach:** If the plugin retains background decompilation
as a convenience fallback (see Section 11.11), the timing gap still exists for that
path. The mitigation is to treat it as best-effort: previously-cached results are
available immediately, and newly-decompiled content appears after the background task
finishes and `fireAdditionalLibraryChanged()` triggers re-indexing. The Gradle task
remains the "guaranteed complete" path.

### 11.11 Gradle Plugin for Decompilation

> **Status:** Implemented in `mixinmcp-gradle/` module. Published as
> `dev.mixinmcp.decompile`. IDE-side decompilation removed; the IntelliJ
> plugin is a read-only cache consumer.

#### 11.11.1 Motivation

| Concern | Background decompilation (current) | Gradle task |
|---------|-----------------------------------|-------------|
| **Determinism** | Tools can run before decompilation finishes | Task completes before IDE opens |
| **Visibility** | Silent background task; errors logged to `idea.log` | Progress and errors in Gradle console output |
| **CI support** | Requires a running IDE | `./gradlew mixinDecompile` in any environment |
| **User control** | Automatic on sync; no way to run on demand | Explicit: run when needed, skip when not |
| **Reproducibility** | Cache state depends on when the IDE was last open | Cache state depends on dependency resolution — reproducible |

#### 11.11.2 User-Facing Design

Users apply the plugin in their `build.gradle.kts`:

```kotlin
plugins {
    id("dev.mixinmcp.decompile") version "..."
}
```

The plugin registers a `mixinDecompile` task:

```bash
./gradlew mixinDecompile
```

After dependency resolution, this task:
1. Enumerates resolved configurations for JARs without `-sources.jar` counterparts.
2. For each JAR, computes the artifact hash using the same algorithm as
   `DecompilationManifest.computeArtifactHash` (SHA-256 of `jarPath|jarSize|jarModified`).
3. Checks `~/.cache/mixinmcp/decompiled/manifest.json` for a cache hit.
4. On miss: invokes Vineflower to decompile to `~/.cache/mixinmcp/decompiled/<hash>/`.
5. Updates `manifest.json` with the new entry.
6. Deletes orphaned cache entries for JARs no longer in the dependency graph.

The task is **incremental**: re-running it after a dependency change only decompiles
new/changed JARs. Unchanged entries are served from cache.

#### 11.11.3 Shared Cache Layout

The Gradle plugin writes to the **exact same** directory structure and manifest format
as the existing `DecompilationCacheService`:

```
~/.cache/mixinmcp/decompiled/
├── manifest.json
├── <artifact-hash-1>/
│   └── com/example/Foo.java
├── <artifact-hash-2>/
│   └── net/minecraft/...
└── ...
```

This means:
- `MixinDecompiledRootsProvider` reads from this cache **without any changes**.
- `DecompilationCacheService.getCachedRoots()` works as-is — it reads `manifest.json`
  and returns `CachedLibraryInfo` entries with VirtualFile roots.
- No protocol or format changes between the Gradle plugin and the IntelliJ plugin.

#### 11.11.4 Dependency Enumeration in Gradle

In the IntelliJ plugin, library enumeration uses `ModuleRootManager.orderEntries` to
find `LibraryOrderEntry` instances without SOURCES roots. In the Gradle plugin, the
equivalent is resolving Gradle configurations:

```kotlin
// Pseudo-code for the Gradle task action
val resolvedArtifacts = project.configurations
    .filter { it.isCanBeResolved }
    .flatMap { it.resolvedConfiguration.resolvedArtifacts }

val withoutSources = resolvedArtifacts.filter { artifact ->
    val sourcesClassifier = "${artifact.name}-sources"
    // Check if a -sources.jar exists for this artifact
    !resolvedArtifacts.any { it.classifier == "sources" && it.name == artifact.name }
}
```

JARs from the JDK and JARs that already have attached `-sources.jar` are excluded,
matching the IntelliJ-side enumeration logic.

#### 11.11.5 Impact on the IntelliJ Plugin

With the Gradle plugin as the primary decompilation path:

- **`DecompilationCacheService`** becomes read-only. `refreshCache()` no longer
  enumerates libraries or invokes Vineflower — it only reads `manifest.json` and
  returns cached roots. The Vineflower dependency can be removed from the IntelliJ
  plugin's `build.gradle.kts`, reducing plugin size.
- **`MixinDecompileCacheSyncListener`** is simplified or removed. After Gradle sync,
  it only calls `fireAdditionalLibraryChanged()` to re-read the (already-populated)
  cache — no background decompilation task.
- **`MixinDecompileCacheStartupActivity`** remains: on project open, it reads the
  manifest and attaches existing cached roots immediately.
- **`MixinDecompiledRootsProvider`** remains unchanged: reads manifest, returns
  `SyntheticLibrary` per cached artifact.

#### 11.11.6 Hybrid Option

As an alternative to making the IntelliJ plugin fully read-only:

- **Gradle task** is the "guaranteed complete" path — deterministic, CI-friendly.
- **IntelliJ plugin** retains background decompilation as a **convenience fallback**
  for users who haven't run `./gradlew mixinDecompile` yet.
- The plugin checks the manifest before decompiling: if the Gradle task has already
  populated a cache entry for a given JAR hash, the plugin skips it.
- The Gradle task is authoritative: if it has run, the plugin defers entirely.

**Recommendation:** Start with the Gradle plugin as the sole decompilation path
(simpler, no timing issues, smaller plugin). Add the hybrid fallback only if user
feedback indicates the explicit Gradle step is too much friction.

#### 11.11.7 Gradle Plugin Implementation Sketch

The plugin would be a separate Gradle project/module, published to the Gradle Plugin
Portal alongside the IntelliJ plugin on JetBrains Marketplace:

```
mixinmcp-gradle/
├── build.gradle.kts          # Gradle plugin project
├── src/main/kotlin/
│   └── dev/mixinmcp/gradle/
│       ├── MixinDecompilePlugin.kt    # Plugin entry point
│       ├── MixinDecompileTask.kt      # Task implementation
│       └── DecompilationManifest.kt   # Shared manifest format (duplicated or extracted)
└── src/main/resources/
    └── META-INF/gradle-plugins/
        └── dev.mixinmcp.decompile.properties
```

The `DecompilationManifest` format is shared between the Gradle plugin and IntelliJ
plugin. Options for sharing:
1. **Duplicate the data class** — simplest; the format is ~60 lines and changes rarely.
2. **Extract to a shared module** — cleaner but adds a multi-module build.

Recommendation: duplicate for now; extract if the format evolves.

### 11.12 Alternatives Considered

**Scoped on-demand decompilation tool:** A new `mixin_search_decompiled` tool that
takes a `packagePrefix`, decompiles matching classes on the fly, and runs regex over
the result. Simpler to implement but adds another tool to context, requires the LLM to
know which tool to use when, and re-decompiles on every invocation. Rejected in favor
of the transparent cache approach that makes existing tools work everywhere.

**IntelliJ's ephemeral decompiler cache:** IntelliJ already decompiles on demand but
doesn't persist results. We could hook into the decompiler's output and cache it, but
this only covers classes the user has explicitly navigated to — not useful for search
across classes never opened.

---

## 12. License

Apache-2.0 (matching JetBrains ecosystem conventions).
