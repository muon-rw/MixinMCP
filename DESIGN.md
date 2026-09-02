# MixinMCP: IntelliJ MCP Extension for Minecraft Mod Development

## Design & Implementation Reference

---

## Table of Contents

1. [What This Plugin Does](#1-what-this-plugin-does)
2. [Architecture](#2-architecture)
3. [The McpToolset Contract](#3-the-mcptoolset-contract)
4. [Project Structure](#4-project-structure)
5. [Build Configuration](#5-build-configuration)
6. [Plugin Registration (plugin.xml)](#6-plugin-registration-pluginxml)
7. [Shared Utilities](#7-shared-utilities-devmixinmcpresolve)
8. [Tool Definitions](#8-tool-definitions)
9. [Mappings Subsystem](#9-mappings-subsystem-devmixinmcpmappings)
10. [Decompilation Cache](#10-decompilation-cache)
11. [Source Auto-Attach](#11-source-auto-attach)
12. [Agent Skills Distribution (Claude Code Plugin)](#12-agent-skills-distribution-claude-code-plugin)
13. [Settings](#13-settings)
14. [Testing](#14-testing)
15. [Known Pitfalls & Edge Cases](#15-known-pitfalls--edge-cases)
16. [Buildscript Classpath Coverage](#16-buildscript-classpath-coverage)
17. [License](#17-license)

---

## 1. What This Plugin Does

MixinMCP is an IntelliJ Platform plugin that extends the IDE's built-in MCP Server with
25 tools for Minecraft mod development: mixin authoring, dependency navigation, bytecode
inspection, mappings lookup, and reference-aware refactoring. A companion Gradle plugin
(`dev.mixinmcp.decompile`) decompiles dependencies without published sources into a cache
the IntelliJ plugin indexes, so the tools see the full compiled classpath.

Tools register via the `com.intellij.mcpServer` extension point and appear in the same
MCP server the client (Cursor, Claude Code, Claude Desktop, etc.) already has configured:
no second port, no extra client configuration.

### Why build this?

The built-in MCP Server's tools operate almost entirely on project files. File search,
symbol info, and inspections exclude libraries and external dependencies. A Minecraft mod
project has 50 to 100 dependencies (remapped Minecraft sources, mod APIs, libraries) that
are invisible to the LLM through those tools.

No built-in tool exposes bytecode. Mixin development frequently targets synthetic methods
(lambdas, bridge methods) that exist only in compiled `.class` files and are invisible in
decompiled source.

---

## 2. Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  IntelliJ IDEA (built-in MCP Server, SSE/stdio to the client)    │
│                                                                  │
│  MixinMCP plugin, registered via com.intellij.mcpServer EP:      │
│  ├── SourceNavigationToolset    (5 tools)                        │
│  ├── SemanticNavigationToolset  (7 tools)                        │
│  ├── BytecodeInspectionToolset  (2 tools)                        │
│  ├── ProjectManagementToolset   (2 tools)                        │
│  ├── MappingsToolset            (1 tool)                         │
│  ├── refactor/: SymbolRefactor (3), ChangeSignature (1),         │
│  │       Extract (2), Inline (1), MemberMove (1) Toolsets        │
│  │                                                               │
│  ├── resolve/   shared PSI/bytecode resolution (Section 7)       │
│  ├── mappings/  mappings download + query      (Section 9)       │
│  ├── cache/     decompilation cache reader,                      │
│  │              source auto-attach             (Sections 10, 11) │
│  ├── startup/   plugin checks + legacy cleanup (Section 12)      │
│  └── settings/  Settings > Tools > MixinMCP    (Section 13)      │
└──────────────────────────────────────────────────────────────────┘
                    ▲  reads manifests + decompiled sources
┌──────────────────────────────────────────────────────────────────┐
│  mixinmcp-gradle (dev.mixinmcp.decompile)                        │
│  genDependencySources: decompiles or mirrors dependency sources  │
│  into ~/.cache/mixinmcp/decompiled; runs on every IDE sync       │
└──────────────────────────────────────────────────────────────────┘
```

The MCP client sees a flat tool list; the toolset split exists to keep each file small
enough to read end-to-end.

---

## 3. The McpToolset Contract

Tools are annotated suspend functions on `McpToolset` implementations, one class per
category. The MCP framework discovers `@McpTool` methods at runtime.

### The Pattern

```kotlin
class SourceNavigationToolset : McpToolset {

    @McpTool
    @McpDescription("Description the LLM sees when choosing tools.")
    suspend fun mixin_tool_name(
        requiredParam: String,
        optionalParam: Int = 42,
    ): McpToolCallResult {
        val project = coroutineContext.requireProject { return it }

        val text = smartReadAction(project) {
            // PSI access
        }

        return McpToolCallResult.text(text)
    }
}
```

### Critical Details

- **Function name** becomes the MCP tool name (e.g. `mixin_find_class`). Parameters
  become JSON parameters; default values make them optional.
- **Project resolution.** The framework injects an optional top-level `projectPath`
  argument into every tool schema; tools do not declare it themselves
  (`mixin_sync_project` is the one exception, using it as the external-system path).
  When several IDE windows are open and `projectPath` is omitted,
  `coroutineContext.projectOrNull` throws `McpExpectedError` instead of returning null.
  `requireProject` (`tools/ProjectResolution.kt`) converts that into an error listing
  every open project path and instructing a retry with `projectPath`; `softProject()`
  returns null instead, for tools where the project is optional
  (`mixin_mappings_lookup`).
- **Return type** is always `McpToolCallResult`: `.text()` for success, `.error()` for
  errors.
- **Threading.** Tool functions run on background threads without a read lock. PSI reads
  go through `smartReadAction(project) { }`, which yields to pending writers and waits
  out indexing instead of failing in dumb mode. Write operations dispatch to the EDT:
  `WriteCommandAction` for PSI mutation, `RefactorSupport.runRefactoringOnEdt` for the
  refactor processors, `invokeLater` for sync.
- **`@McpDescription`** is what the LLM sees. Dollar signs must be escaped (`\$`) or
  Kotlin treats them as string templates.

### Registration in plugin.xml

A single `<mcpToolsProvider>` entry, `MixinMcpToolsProvider` (see Section 6), lists every
toolset class and reflects its `@McpTool` methods with the framework's own `asTools`
helper, the same code path the built-in `mcpToolset` extension point runs. Each reflected
tool is wrapped in `UnknownParameterRejectingTool` before the server sees it. A new
category means a new class added to the provider's list; a new tool in an existing
category is just another method.

The wrapper exists because the framework decodes arguments with `ignoreUnknownKeys`: a
call carrying a misspelled or invented parameter binds only the names it recognises and
runs the tool on defaults, so the caller gets a plausible but wrong answer instead of an
error. `McpTool.call(JsonObject)` is the only point that sees the raw argument object, and
only a `mcpToolsProvider` constructs `McpTool` instances, hence the registration choice.
The wrapper diffs the argument keys against the descriptor's input schema plus the
framework-injected `projectPath` and rejects the call naming the accepted parameters, with
a suggestion when a name differs only by case, underscores, or hyphens.

**IMPORTANT:** The extension namespace is `com.intellij.mcpServer` (capital S). Lowercase
silently fails to register tools.

---

## 4. Project Structure

```
MixinMCP/
├── build.gradle.kts                   # IntelliJ plugin build
├── settings.gradle.kts                # includes mixinmcp-gradle
├── gradle/libs.versions.toml          # version catalog
├── DESIGN.md                          # this file
├── README.md                          # user-facing docs
├── src/main/kotlin/dev/mixinmcp/
│   ├── tools/
│   │   ├── MixinMcpToolsProvider.kt   # mcpToolsProvider: reflects + wraps every toolset
│   │   ├── UnknownParameterRejectingTool.kt  # rejects undeclared argument names
│   │   ├── ProjectResolution.kt       # requireProject / softProject
│   │   ├── ClassContentDeduper.kt     # classpath-variant dedup + diff
│   │   ├── BytecodeInspectionToolset.kt
│   │   ├── ProjectManagementToolset.kt
│   │   ├── source/
│   │   │   ├── SourceNavigationToolset.kt
│   │   │   ├── SourceWindow.kt        # mixin_get_dep_source line selection
│   │   │   └── DepSearchHelpers.kt    # root collection, regex scan, hints
│   │   ├── semantic/
│   │   │   ├── SemanticNavigationToolset.kt
│   │   │   ├── CallHierarchyExpander.kt
│   │   │   └── MixinAnnotationHelpers.kt
│   │   ├── mappings/
│   │   │   └── MappingsToolset.kt
│   │   └── refactor/
│   │       ├── RefactorSupport.kt     # shared: conflicts, ranges, EDT execution
│   │       ├── SymbolRefactorToolset.kt   # rename, safe delete, move file
│   │       ├── ChangeSignatureToolset.kt
│   │       ├── ExtractToolset.kt      # extract method, introduce variable
│   │       ├── InlineToolset.kt
│   │       └── MemberMoveToolset.kt
│   ├── buildscript/                   # optional Gradle module (Section 16)
│   │   ├── BuildscriptClasspathSnapshot.kt   # project service; sole caller of the enumeration
│   │   ├── BuildscriptClasspathRoots.kt      # GradleBuildClasspathManager enumeration + helpers
│   │   ├── BuildscriptClasspathRootsProvider.kt
│   │   ├── BuildscriptClasspathStartupActivity.kt
│   │   └── BuildscriptClasspathSyncListener.kt
│   ├── resolve/                       # shared utilities (Section 7)
│   │   ├── FqcnResolver.kt
│   │   ├── MethodResolver.kt
│   │   ├── ClassFileLocator.kt
│   │   ├── BytecodeAnalyzer.kt
│   │   ├── DescriptorParser.kt
│   │   ├── PsiDescriptors.kt
│   │   └── ClassVariants.kt           # classpath-variant provenance
│   ├── mappings/                      # mappings subsystem (Section 9)
│   ├── cache/                         # cache reader + auto-attach (10, 11)
│   │   ├── DecompilationCacheService.kt
│   │   ├── DecompilationManifest.kt
│   │   ├── MixinDecompiledRootsProvider.kt
│   │   ├── MixinDecompileCacheSyncListener.kt
│   │   ├── MixinDecompileCacheStartupActivity.kt
│   │   └── SourceAutoAttacher.kt
│   ├── startup/
│   │   └── StartupChecksActivity.kt
│   └── settings/
│       ├── MixinMcpSettings.kt
│       ├── MixinMcpAppSettings.kt
│       └── MixinMcpSettingsConfigurable.kt
├── src/main/resources/
│   ├── META-INF/plugin.xml
│   └── META-INF/mixinmcp-buildscript.xml  # registrations behind the optional Gradle dependency
├── .claude-plugin/marketplace.json    # Claude Code marketplace (Section 12)
├── claude-plugin/                     # Claude Code plugin (Section 12)
│   ├── .claude-plugin/plugin.json
│   ├── README.md
│   └── skills/{mixinmcp-tools, mixin-writing}/
├── src/test/kotlin/dev/mixinmcp/      # unit tests (Section 14)
└── mixinmcp-gradle/                   # Gradle plugin (Section 10)
    └── src/main/kotlin/dev/mixinmcp/gradle/
        ├── MixinDecompilePlugin.kt
        ├── MixinDecompileTask.kt      # genDependencySources
        ├── CleanCacheTask.kt          # cleanSourcesCache
        ├── DecompilationManifest.kt   # Gson twin of the IDE manifest
        └── CacheEntry.kt
```

Category-scoped helpers sit beside their toolset. The `cache/` package is a read-only
consumer of the cache the Gradle plugin populates.

---

## 5. Build Configuration

- **Platform:** IntelliJ IDEA 2026.2+ (`pluginSinceBuild = 262`). 2026.1 is not supported:
  `McpToolset` gained `isExperimental()` and `alwaysIncluded()` in 2026.2, and Kotlin emits a
  compatibility bridge for every inherited interface default, so a 261 runtime would
  `NoSuchMethodError` on every toolset. `untilBuild` is unset for forward compatibility.
- **Bundled plugins:** `com.intellij.java` (Java PSI), `com.intellij.mcpServer` (MCP API).
- **Libraries** (versions pinned in `gradle/libs.versions.toml`):
  - ASM `asm` + `asm-util`: IntelliJ bundles ASM, but `asm-util`'s `Textifier` is not
    accessible to plugins.
  - `net.fabricmc:mapping-io`: parses tiny v1/v2, tsrg/tsrg2, and ProGuard mapping files.
  - `kotlinx-serialization-json` is NOT declared: the IDE bundles it
    (`lib/intellij.libraries.kotlinx.serialization.json.jar`, on the compile classpath through
    the platform dependency) and the MCP framework's API names `Json`/`JsonObject` in
    signatures. A bundled copy makes the plugin classloader resolve those types to different
    classes than the mcpserver plugin, and calls such as `asTools()` fail with a `LinkageError`.
- **Java toolchain:** 17. Kotlin version comes from the catalog.
- `runtimeClasspath` excludes `kotlin-stdlib` and `org.jetbrains:annotations`: the IDE
  provides both, and transitive dependencies must not ship their own copies.
- The Marketplace description is extracted from README.md between the plugin-description
  markers; change notes render from CHANGELOG.md via the changelog plugin. Signing and
  publishing credentials come from environment variables.
- `mixinmcp-gradle` builds in the same Gradle project, shares the version from the
  Claude plugin manifest (Section 12),
  and publishes plugin id `dev.mixinmcp.decompile` to `https://maven.muon.rip/releases`
  via `maven-publish`. Its dependencies are Vineflower and Gson.

---

## 6. Plugin Registration (plugin.xml)

```xml
<idea-plugin>
    <id>dev.mixinmcp</id>
    <name>MixinMCP</name>
    <vendor>mixinmcp</vendor>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.java</depends>
    <depends>com.intellij.mcpServer</depends>

    <extensions defaultExtensionNs="com.intellij.mcpServer">
        <mcpToolsProvider implementation="dev.mixinmcp.tools.MixinMcpToolsProvider"/>
    </extensions>

    <extensions defaultExtensionNs="com.intellij">
        <additionalLibraryRootsProvider
            implementation="dev.mixinmcp.cache.MixinDecompiledRootsProvider"/>
        <externalSystemTaskNotificationListener
            implementation="dev.mixinmcp.cache.MixinDecompileCacheSyncListener"/>
        <postStartupActivity
            implementation="dev.mixinmcp.cache.MixinDecompileCacheStartupActivity"/>
        <postStartupActivity
            implementation="dev.mixinmcp.startup.StartupChecksActivity"/>
        <projectConfigurable parentId="tools" id="dev.mixinmcp.settings"
            displayName="MixinMCP"
            instance="dev.mixinmcp.settings.MixinMcpSettingsConfigurable"/>
        <notificationGroup id="MixinMCP" displayType="BALLOON"/>
    </extensions>
</idea-plugin>
```

---

## 7. Shared Utilities (dev.mixinmcp.resolve)

### 7.1 FqcnResolver

Resolves fully qualified class names to `PsiClass` over `GlobalSearchScope.allScope`
(project + dependencies + JDK). `resolveNested` handles inner classes written with dots
by progressively converting dots to dollars from the right, and accepts `$` input as-is.

### 7.2 MethodResolver

Resolves a method by name plus optional `parameterTypes` (simple or canonical names) or
`methodDescriptor` (JVM format; takes precedence when both are given). `resolveDetailed`
returns a sealed `Resolution`: `Found(PsiMethod)` or `Error(message)`, where errors list
the available overloads with ready-to-paste `parameterTypes` values. `@RequiresReadLock`;
callers wrap it in `smartReadAction`.

- `findMethodsByName(name, checkBases = true)` with a `psiClass.methods` fallback for JDK
  classes where the index returns nothing.
- Multiple matches sharing one canonical signature (an override plus its inherited
  declaration) are not an ambiguity: the most-derived method wins.
- Descriptor resolution matches PSI parameters against canonical names from
  `DescriptorParser`. When PSI matching fails, a bytecode fallback finds the exact
  name + descriptor via `BytecodeAnalyzer` and maps back to PSI by parameter count.

### 7.3 ClassFileLocator

Locates raw `.class` bytes for an FQCN, validating the `0xCAFEBABE` magic.
`locateDetailed` returns a sealed `LocateResult`: `Found(bytes, maybeStale)`, `NotFound`,
or `NotBuilt`.

1. Library classes: `ClsFileImpl` file contents; when the virtual file yields decompiled
   text instead of class bytes, the jar entry is re-read directly from the `jar:` URL.
2. Files with a `.class` extension: read directly.
3. Project classes: the module's compiler output (main and test), probing sibling
   `classes/kotlin` and `classes/groovy` directories in Gradle layouts; the newest
   `.class` wins. Missing output yields `NotBuilt` so tools can ask for a build instead
   of claiming the class does not exist. `maybeStale` is set when the editor has unsaved
   changes or the source mtime is newer than the `.class`.

### 7.4 BytecodeAnalyzer

ASM-based analysis.

- `analyze(bytes, includeInstructions)` returns a structured `ClassAnalysis` (version,
  access, names, methods, fields). Methods carry access flags plus `isSynthetic`,
  `isBridge`, `isLambda`, and `lambdaSourceMethod` parsed from the
  `lambda$method$index` convention; fields carry `isSynthetic`. Instructions are
  textified only on request (expensive).
- `analyzeMethod(bytes, name, descriptor?)` returns javap-style output for one method.
- `extractCallees(bytes, name, descriptor?)` returns each outgoing call as
  `CalleeRef(owner, name, descriptor, kind)`. Covers
  `INVOKEVIRTUAL/STATIC/SPECIAL/INTERFACE` and resolves `INVOKEDYNAMIC` whose bootstrap
  is `LambdaMetafactory` back to the implementation handle, so the real lambda or
  method-reference target surfaces. Kind is `LAMBDA` only for `lambda$` synthetics and
  `CONSTRUCTOR` for `<init>` targets; non-lambda `INVOKEDYNAMIC` (string concat, switch
  bootstraps) is skipped. Returns null when the method is absent from the class and an
  empty list for abstract/native methods; deduplication is the caller's job.

### 7.5 DescriptorParser

Parses JVM method descriptors into canonical Java type names (null on malformed input),
handling objects, primitives, and nested arrays, and converts between canonical,
internal, and simple-name forms for error messages.

### 7.6 PsiDescriptors

Converts PSI methods and types into JVM descriptors and internal names, erasing generics
via `TypeConversionUtil.erasure` so PSI `List<String>` becomes `Ljava/util/List;`,
matching what the compiler emits. This keeps the call-hierarchy tool's source-walker and
bytecode paths emitting identical owner/name/descriptor triples, and makes output
paste-ready for `@At(target = "...")`.

---

## 8. Tool Definitions

### Source Navigation

| Tool | Parameters |
|------|-----------|
| `mixin_find_class` | `className`, `includeMembers=true`, `includeSource=false`, `methodName?`, `fieldName?`, `module?` |
| `mixin_search_symbols` | `query`, `kind=class`, `scope=all`, `caseSensitive=false`, `maxResults=50` |
| `mixin_search_in_deps` | `regexPattern`, `fileMask?`, `caseSensitive=true`, `maxResults=100`, `timeout=15000`, `pathPrefix?`, `roots=all`, `contextLines=0` |
| `mixin_get_dep_source` | `url?` or `path?`, `lineNumber=1`, `linesBefore=30`, `linesAfter=70`, `startLine?`, `endLine?`, `module?` |
| `mixin_list_source_roots` | `maxSamplesPerRoot=5`, `verbose=false` |

`mixin_search_in_deps` and `mixin_get_dep_source` cover two root sets: library SOURCES
roots (published `-sources.jar` files plus anything else attached as SOURCES, such as
auto-attached merged game jars) and decompiled cache roots (Section 10). `roots`
selects `all`, `library`, or `decompiled`. In `all` mode library roots are scanned first
and cache files whose logical path already matched are skipped, so results never
duplicate. Roots attached as library SOURCES that physically live under the decompilation
cache keep the decompiled label, so the `roots` contract holds.

**`mixin_find_class`** resolves any class by FQCN and reports header info plus a
`SourceKind` classification (library sources, decompiled cache, project source, binary).
`methodName`/`fieldName` focus the output to one member's source with line numbers,
overload counts, inherited-member tags, and similar-name suggestions on a miss; binary
members get a pointer to the bytecode tools. `includeMembers` lists methods, fields, and
nested classes with ready-to-copy follow-up calls; `includeSource` appends the full file.
`module` (exact name or dot-boundary suffix) pins resolution to one module's classpath;
without it, a class with several classpath copies gets a Variants block in which
byte-identical copies are merged and patched copies get a provenance-tagged structural
diff (`ClassContentDeduper`). The same parameter and Variants handling apply to
`mixin_get_dep_source` and the bytecode tools.

**`mixin_search_symbols`** is a short-name substring search over `PsiShortNamesCache`.
Queries that look like FQCNs are simplified to the trailing simple name with an
explanatory note. Each kind section (classes, methods, fields) has its own `maxResults`
budget.

**`mixin_search_in_deps`** is a regex grep across dependency sources. `fileMask` is a
case-insensitive substring of the logical path, or a glob when it contains `*`/`?` (`*`
crosses `/`). `contextLines` (0 to 200) renders match windows with overlapping windows
merged; matches are highlighted with `||...||` markers. Hits are grouped per file with a
`url:` line consumable by `mixin_get_dep_source`. Regex syntax errors return escape
hints; empty results return hints that distinguish "no files under pathPrefix" from "no
lines matched" and add toolchain-specific guidance for vanilla/Forge/NeoForge paths.

**`mixin_get_dep_source`** reads a window around `lineNumber`, marking the requested
line, or an explicit inclusive range when `startLine`/`endLine` are given. Line selection
lives in `resolveSourceWindow` (`SourceWindow.kt`): the range overrides the window, a range
that runs past the file is clamped with a note, and one that starts past the file is an
error. `url` (from search output) takes precedence over `path` (a package path like
`net/minecraft/world/level/Level.java`, resolved across all roots).

**`mixin_list_source_roots`** is the coverage diagnostic: all roots grouped into library
SOURCES and decompiled cache with sample paths, MDG merged-jar detection, the auto-attach
report (Section 11), and sentinel canary checks for vanilla, Forge, and NeoForge sources
with per-toolchain remediation guidance.

### Semantic Navigation

| Tool | Parameters |
|------|-----------|
| `mixin_type_hierarchy` | `className`, `direction=both`, `maxDepth=10`, `includeInterfaces=true`, `maxResults=50` |
| `mixin_find_impls` | `className`, `maxResults=50` |
| `mixin_find_references` | `className`, `memberName?`, `parameterTypes?`, `methodDescriptor?`, `maxResults=100` |
| `mixin_call_hierarchy` | `className`, `methodName`, `parameterTypes?`, `methodDescriptor?`, `direction=callers`, `maxDepth=3`, `maxResults=50` |
| `mixin_super_methods` | `className`, `methodName`, `parameterTypes?`, `methodDescriptor?` |
| `mixin_find_overrides` | `className`, `methodName`, `parameterTypes?`, `methodDescriptor?`, `maxResults=50` |
| `mixin_find_targeting_mixins` | `className`, `methodName?`, `maxResults=50` |

Tools that take `parameterTypes` also accept `methodDescriptor` (JVM format, e.g.
`(Lnet/minecraft/world/effect/MobEffectInstance;)Z`), which takes precedence. For
parameterless methods, pass `parameterTypes: []`. Error messages list available overloads
with ready-to-copy `parameterTypes` values (Section 7.2).

**`mixin_type_hierarchy` interface listing.** With `includeInterfaces=true`, the supers
direction emits **Direct interfaces** (the class's own `implements`/`extends` clause) and
**Inherited interfaces** (the transitive closure from walking the superclass chain within
`maxDepth` and following the super-interface extension graph). Inherited entries are
deduplicated by qualified name and tagged with origin: `from X` means introduced by
superclass X, `via X` means reached by extending interface X. First seen wins, so closer
origins are preferred.

**`mixin_call_hierarchy` callee coverage.** The source walker visits
`PsiMethodCallExpression`, `PsiNewExpression`, and `PsiMethodReferenceExpression`, so
direct calls, constructor invocations, and method references all surface. Body discovery
per node tries Java PSI, then UAST (covering Kotlin and other JVM languages), then
bytecode via `BytecodeAnalyzer.extractCallees`. Only the bytecode path surfaces the real
synthetic `lambda$X$N` target behind `INVOKEDYNAMIC`; the source walkers see the lambda
body lexically. Tagging is consistent across paths: `[ctor]` for constructors (including
`Foo::new` and `INVOKEDYNAMIC` impls named `<init>`), `[lambda]` only for compiler
synthetics whose impl name starts with `lambda$`; method references resolving to a
non-synthetic method surface untagged, identical to a direct call, since that is what a
mixin would target. Output is `owner#name(descriptor)` in JVM format, deduplicated by the
full triple so overloads are not merged.

**`mixin_call_hierarchy` recursion.** Both directions recurse to `maxDepth` (valid range
1 to 10; out-of-range values are rejected, not clamped). Callers: a depth-first walk
where each node runs `MethodReferencesSearch` and each reference's enclosing method
becomes the next root; enclosing-method resolution tries Java PSI, then UAST, so
Kotlin/Groovy/Scala callers participate. References with no enclosing method (field or
static initializers) are terminal `(non-method context)` leaves. Callees: each discovered
`CalleeRef` is re-resolved to PSI and walked. Leaves with no body in source or bytecode
are labelled abstract; an abstract method whose class bytecode is present simply ends the
branch. `maxResults` is a single budget shared across all depths and branches; each
caller or callee line consumes a slot, and exhaustion halts the walk with a truncation
notice.
Cycle detection keys on the `owner#name(descriptor)` triple with the target pre-seeded,
so self-recursion is caught immediately; re-encounters emit a `[cycle]` marker without
recursing. Lines are indented per depth with `[L1]`, `[L2]`, ... tags.

**`mixin_find_references`** searches class references when `memberName` is absent. With
`memberName`, a matching field wins outright when no type filters were passed; otherwise
method resolution runs, falling back to field references (with a note) when a field
exists but no method matches.

**`mixin_super_methods`** prints the declaration site, then the full super-method chain
(most specific to most general) with `[root declaration]` and `[interface]` tags and
per-entry source locations. When the queried class only inherits the method, an explicit
note recommends mixing into the declaring class.

**`mixin_find_overrides`** detects unoverridable targets (constructor, static, private,
final method, final class) and explains why instead of returning an empty list; otherwise
it lists overrides with `[abstract]` tags and source locations.

**`mixin_find_targeting_mixins`** finds mixins targeting a class via
`AnnotatedElementsSearch` over all scope, reading both the `value` and `targets`
attributes of `@Mixin` (class literals, arrays, string literals). It recognizes 13
injector annotations: `@Inject`, `@Redirect`, `@Overwrite`, `@ModifyArg`, `@ModifyArgs`,
`@ModifyConstant`, `@ModifyVariable` (SpongePowered), plus `@ModifyExpressionValue`,
`@ModifyReturnValue`, `@ModifyReceiver`, `@WrapOperation`, `@WrapWithCondition`,
`@WrapMethod` (MixinExtras); `@Accessor`/`@Invoker` are deliberately excluded.
`methodName` filters by parsing each injector's `method` attribute. A regex fallback over
the dependency source roots (library SOURCES plus decompiled cache, `.java` files) runs
only when the annotation search finds nothing, returning FQCN and path only.

### Bytecode Inspection

| Tool | Parameters |
|------|-----------|
| `mixin_class_bytecode` | `className`, `filter=all`, `includeInstructions=false`, `module?` |
| `mixin_method_bytecode` | `className`, `methodName`, `methodDescriptor?`, `module?` |

`filter` accepts `all`, `synthetic`, `methods`, `fields`; `synthetic` restricts both
methods and fields to synthetic members (including fields like `this$0` and `$VALUES`).
Whenever synthetics exist, a synthetic-method summary section is appended regardless of
filter, tagging each as lambda (with source method), bridge, or synthetic.

Both tools work on the project's own classes after a build: `ClassFileLocator` reads
compiler output, returns a build-and-retry error when output is missing (`NotBuilt`), and
prefixes a staleness warning when the source is newer than the `.class` or the editor has
unsaved changes. `mixin_method_bytecode` failures are diagnostic: a missing name lists
similar and available method names; a name with no descriptor match lists every overload.

### Project Management

| Tool | Parameters |
|------|-----------|
| `mixin_sync_project` | `projectPath?` |
| `mixin_refresh_vfs` | `path?` |

**`mixin_sync_project`** saves all documents, then triggers
`ExternalSystemUtil.refreshProject` with `ProgressExecutionMode.START_IN_FOREGROUND_ASYNC`
on the EDT and returns immediately (fire and forget). A Maven retry fires only when the
Gradle call throws synchronously; async sync failures are not reported. When the Gradle
plugin is applied, sync also re-runs `genDependencySources` (Section 10).

**`mixin_refresh_vfs`** resolves a refresh target via
`LocalFileSystem.refreshAndFindFileByIoFile`, then calls
`VfsUtil.markDirtyAndRefresh(async=false, recursive, reloadChildren=true, vf)`. Target
selection handles three input shapes: an existing directory (project root when `path` is
omitted) refreshes recursively; an existing file refreshes its parent non-recursively
(`reloadChildren` picks up the content change plus created and deleted siblings); a
missing path walks up to the nearest existing ancestor, non-recursively, so deletions are
noticed without fanning out. Explicit dirty-marking forces a re-read even when VFS
believes the entry is fresh. Synchronous: returns after IntelliJ has re-scanned. Use
after external tools mutate files that later MCP calls will query.

### Mappings

| Tool | Parameters |
|------|-----------|
| `mixin_mappings_lookup` | `symbol`, `kind`, `from`, `to`, `mcVersion?` |

Converts a class/method/field name between mapping namespaces. See Section 9.

### Refactoring

Five toolsets under `tools/refactor/` (SymbolRefactorToolset, ChangeSignatureToolset,
ExtractToolset, InlineToolset, MemberMoveToolset) share `RefactorSupport.kt` and one
contract. `dryRun=true` reports the resolved target, usages, and conflicts without
modifying anything. Conflicts are rendered one per line with a project-relative path and
a `[library]` or `[source]` tag (`[library]` usually means a stale build jar; member
moves into a `@Mixin` class add `[mixin]`) and block execution unless
`ignoreConflicts=true`, the headless equivalent of the IDE conflict dialog's Continue.
Two exceptions: `mixin_safe_delete` uses `force` instead, and `mixin_move_file` takes
neither flag, refusing everything it can detect at validation time. Mutations run on the
EDT; most tools go through `runRefactoringOnEdt`, which commits and saves all documents,
while SymbolRefactorToolset inlines the same invokeAndWait-plus-commit pattern and
extract mutates through `DuplicatesMethodExtractor` first, using `runRefactoringOnEdt`
only to save. The processor-driven tools replace the conflict dialog with a stub that
captures the conflict MultiMap instead of opening a modal that would deadlock the MCP
call (pull-up conflicts are precomputed via `PullUpConflictsUtil`; `mixin_move_file`
instead proceeds past late processor conflicts after its own validation). Because
`BaseRefactoringProcessor.doRun` can bail out silently (preview escalation, dumb mode,
canceled progress), post-run read actions verify the change actually applied and return
an explicit error otherwise; `mixin_move_file` is again the exception. The signature,
extract, introduce, inline, and move-members tools are Java sources only:
`guardJavaSourceTarget` (for the range-addressed tools, `resolveFileRange`) refuses
compiled, library, Kotlin, and non-writable targets.
`RefactorSupport` also carries the shared identifier and visibility validation,
whole-line range resolution (`resolveRange`), and sub-expression picking
(`pickExpression`, matching by a whitespace-insensitive token stream with
`occurrenceIndex` disambiguation and a candidate listing on a miss).

| Tool | Parameters |
|------|-----------|
| `mixin_rename` | `className`, `newName`, `memberName?`, `memberKind?`, `variableName?`, `parameterTypes?`, `methodDescriptor?`, `ignoreConflicts=false`, `dryRun=false` |
| `mixin_safe_delete` | `className`, `methodName?`, `fieldName?`, `parameterTypes?`, `methodDescriptor?`, `force=false`, `dryRun=false` |
| `mixin_move_file` | `className`, `targetPackage` |
| `mixin_change_signature` | `className`, `methodName`, `parameterTypes?`, `methodDescriptor?`, `newName?`, `newVisibility?`, `newReturnType?`, `parametersJson?`, `ignoreConflicts=false`, `dryRun=false` |
| `mixin_extract_method` | `filePath`, `startLine`, `endLine`, `methodName`, `expression?`, `occurrenceIndex?`, `visibility="private"`, `makeStatic?`, `ignoreConflicts=false`, `dryRun=false` |
| `mixin_introduce_variable` | `filePath`, `startLine`, `endLine`, `name?`, `expression?`, `occurrenceIndex?`, `replaceAllOccurrences=false`, `ignoreConflicts=false`, `dryRun=false` |
| `mixin_inline` | `kind`, `className`, `memberName?`, `parameterTypes?`, `methodDescriptor?`, `methodName?`, `localName?`, `deleteDeclaration=true`, `ignoreConflicts=false`, `dryRun=false` |
| `mixin_move_members` | `direction`, `className`, `members`, `targetClassName?`, `makeAbstract=false`, `ignoreConflicts=false`, `dryRun=false` |

**`mixin_rename`** renames a class, method, field, parameter, or local variable and
updates every reference project-wide, including string references in mixin configs and
javadoc where language plugins contribute PSI references; it exists because the built-in
`rename_refactoring` discards conflict details on failure. `memberKind` disambiguates
method vs field (inferred when omitted) or selects `parameter`/`local`, which rename the
variable named by `variableName` inside the method `memberName`. Method renames
substitute the deepest super declaration so `RenameProcessor` expands every overrider;
multiple unrelated supers and library supers are refused, as are compiled, non-writable,
and mismatched Kotlin light targets, and local renames support Java bodies only.
Success is verified by re-reading the element name
before committing, so a silent processor bail-out returns an error instead of a false
success.

**`mixin_safe_delete`** deletes a class, method, or field after a usage check.
Preparation resolves the target (member resolution per Section 7.2, Kotlin light elements
unwrapped to their source declaration), then collects `ReferencesSearch` usages plus, for
methods, `OverridingMethodsSearch` results tagged `[override]`; a top-level class's
own-file references are excluded, and deleting the sole top-level declaration removes the
file. `dryRun` reports what would happen; existing usages block deletion unless `force`.
The delete is a direct `PsiElement.delete()` in a named `WriteCommandAction` on the EDT
(not `SafeDeleteProcessor`), then commits and saves all documents.

**`mixin_move_file`** moves a top-level class to another package under the same source
root (inner classes are rejected; target directories are created; same-name collisions
refuse). The move runs through a `MoveFilesOrDirectoriesProcessor` subclass that
suppresses the modal conflict dialog (which would deadlock an MCP call) with
`searchForReferences = true` and `searchInNonJavaFiles = true`, so string references in
mixin configs and service files update where language plugins contribute PSI references.
Files with several top-level classes move as a unit.

**`mixin_change_signature`** applies a rename, visibility change, return-type change, and
parameter add/remove/reorder/retype as one atomic refactoring, updating every call site
and override. `parametersJson` describes the complete new parameter list in order:
`{"oldIndex":N}` keeps a parameter (name/type override the old ones when given),
`{"oldIndex":-1,"name":...,"type":...,"defaultValue":...}` adds one with `defaultValue`
inserted at every existing call site, and omitted parameters are removed everywhere.
Types must parse and resolve in the method's context. Weakening visibility while
overriders exist is refused up front (the platform would open an interactive propagation
dialog), and covariant-overrider processing is off. `dryRun` runs `findUsages` plus every
`ChangeSignatureUsageProcessor` extension's `findConflicts` and prints the new signature
and per-file usage counts; a post-run read action verifies name, parameters, visibility,
and return type all match the request.

**`mixin_extract_method`** and **`mixin_introduce_variable`** address code by `filePath`
plus a 1-based, inclusive, whole-line `startLine`/`endLine` range; `expression` (matched
whitespace-insensitively against its source text) selects a sub-expression inside the
range, `occurrenceIndex` picks among repeats, and a non-matching `expression` lists the
selectable candidates. Extract runs the modern extract-method pipeline (`ExtractSelector`
then `ExtractMethodPipeline`, with `withForcedStatic` backing `makeStatic`), checks name
clashes via `ConflictsUtil.checkMethodConflicts` on a preview method, pins the fragment
with a `RangeMarker` across the read/write gap, and mutates through
`DuplicatesMethodExtractor`; `dryRun` prints the derived signature, target class, and
output kind. Introduce-variable reuses the platform's introduce checks (assignments, void
and non-denotable types, enum constants in switch labels, and escaping pattern variables
are refused), gathers occurrences with `ExpressionOccurrenceManager`, and drives
`VariableExtractor` with a dialog-free settings object; `replaceAllOccurrences` is
refused when occurrences mix reads with writes or a replacement would leave a bare
expression statement.

**`mixin_inline`** dispatches on `kind`. Methods run a headless `InlineMethodProcessor`
into every call site; constructors, bodiless and recursive methods, and zero-usage
targets are refused (the last pointing at `mixin_safe_delete`). Fields run
`InlineConstantFieldProcessor`; enum constants, initializer-less fields, and non-final
fields with write usages are refused. Locals run `InlineLocalHandler` as a batch
ModCommand inside the method named by `methodName`. `deleteDeclaration=false` keeps the
declaration; a deleting run re-checks that the element became invalid so a silent
processor bail-out surfaces as an error.

**`mixin_move_members`** drives the real IntelliJ processors headlessly per `direction`:
`up` a `PullUpProcessor` whose duplicate-replacement dialog is skipped (the target must
be in the source's inheritance chain; `makeAbstract` pulls methods up as abstract
declarations and keeps the implementations), `toClass` a `MoveMembersProcessor` for static
members. `direction="down"` is refused: 2026.2 sealed the whole
`com.intellij.refactoring.memberPushDown` package as `@ApiStatus.Internal` and left no
headless entry point, since the only public one always shows a modal dialog and
`JavaPushDownDelegate` takes a `PushDownData` whose constructors are package-private.
`members` entries
are a simple `name` or `name#descriptor` for overloads, resolved against members declared
directly on `className`. Moves into a `@Mixin` class report external references to the
moved members as blocking `[mixin]` conflicts, since mixin members are unreachable from
ordinary classes at runtime; other mixin-involved moves proceed with advisory notes on
`@Unique` conventions. A post-run read action verifies the members actually left the
source class.

---

## 9. Mappings Subsystem (dev.mixinmcp.mappings)

`mixin_mappings_lookup` translates symbols between five namespaces: `obf`, `mojmap`,
`intermediary`, `yarn`, `srg`. The input symbol must be in the `from` namespace. Members
are owner-qualified (dots or slashes), with an optional JVM descriptor after `(` for
methods and `:` for fields; an omitted descriptor matches all overloads and can return an
ambiguous listing. Results include the owning class's names in the other loaded
namespaces.

- **SymbolParser** normalizes dots to slashes and splits owner, member, and descriptor.
- **McVersionDetector** supplies `mcVersion` when omitted, reading `gradle.properties` in
  the project root and first-level subdirectories (keys: `minecraft_version`,
  `mc_version`, `minecraftVersion`, `mcVersion`, `minecraft.version`, `mc.version`).
- **MappingsService** is an application-level service caching one `MemoryMappingTree` per
  (mcVersion, namespace set) with a per-key mutex.
- **MappingsLoader** builds a mapping-io `MemoryMappingTree` with source namespace `obf`
  and the requested destination namespaces. Yarn tiny files already carry intermediary,
  so intermediary loads separately only when Yarn is not requested; ProGuard mojmap files
  are source-switched to obf orientation; srg loads from tsrg/tsrg2, picking the first
  non-`id` destination namespace.
- **MappingsDownloader** caches per version under `~/.cache/mixinmcp/mappings/<ver>/` as
  `mojmap.txt`, `intermediary.tiny`, `yarn.tiny`, `srg.tsrg`. Sources: Mojang piston-meta
  (launcher manifest cached with a 24h TTL) for mojmap, FabricMC maven for intermediary,
  FabricMC meta for the newest Yarn build, and Forge `mcp_config` with a NeoForm fallback
  for srg. Downloads stream to a `.part` file and move atomically; connect/read timeouts
  apply; failures produce explanatory errors (e.g. versions Fabric does not cover).
- **MappingsResolver** returns `Single`, `Ambiguous`, or `NotFound`; a missing member
  lists up to 40 available names in the `from` namespace.

---

## 10. Decompilation Cache

### 10.1 Problem

Many dependencies ship no `-sources.jar`. IntelliJ can decompile `.class` files on
demand, but the output is ephemeral: it exists in the PSI tree while a file is open and
is never persisted or indexed. Without intervention there is no regex search or
file-style reading for compiled-only dependencies, and the LLM must already know the
exact FQCN to see any source.

### 10.2 Solution

The Gradle plugin decompiles (or mirrors) dependency sources into a persistent cache; the
IntelliJ plugin exposes the cache as indexed `SyntheticLibrary` roots. The IDE side is a
read-only consumer: it has no decompiler dependency and never writes the cache.
Decompilation completes inside the Gradle invocation (sync or manual task run) before
tools query, so tools never race a half-populated cache. This also makes the cache
CI-friendly and reproducible from dependency resolution alone.

### 10.3 Cache Layout

```
~/.cache/mixinmcp/decompiled/          # global, content-addressed, shared by all projects
└── <sha256-of-jar-bytes>/
    └── com/example/Foo.java

<gradleProjectDir>/.gradle/mixinmcp/   # per Gradle (sub)project
├── manifest.json                      # {"entries": {"<hash>": CacheEntry}}
├── hash-memo.json                     # (path|size|mtime) -> content hash
└── unresolved.txt                     # resolution-failure count (transient)
```

`CacheEntry` fields: `libraryName`, `classesJarPath`, `jarSize`, `jarModified`,
`cachePath`, `decompilerVersion` (`vineflower-<version>` or `published-sources`),
`createdAt`. The artifact hash is the SHA-256 of the jar's bytes, so renamed jars and
multiple projects sharing a dependency hit the same entry; the persisted memo avoids
re-hashing unchanged jars. Each subproject writes its own manifest; the IDE merges the
root manifest with those of first-level subdirectories, falling back to a legacy global
manifest at the cache root when none exist. Cache directories untouched for 30 days are
evicted; every cache hit refreshes the directory mtime to keep in-use entries alive. The
manifest format is duplicated between the two modules (kotlinx-serialization on the IDE
side, Gson on the Gradle side) writing identical JSON.

### 10.4 Gradle Plugin (dev.mixinmcp.decompile)

`MixinDecompilePlugin` registers two tasks and, when the `idea.sync.active` system
property is set, appends `genDependencySources` to the Gradle start parameters so every
IntelliJ sync populates the cache (the same technique MDG and Loom use).

**`genDependencySources`** (options `--threads=N`, default 2, and `--force`):

1. Resolves `compileClasspath` through a lenient artifact view, so artifact-transform
   failures are collected instead of failing the task. Only module components with `.jar`
   files are processed; JDK jars are skipped.
2. Artifacts with a published `-sources.jar` (found via an `ArtifactResolutionQuery` with
   `SourcesArtifact`) are mirrored: the sources jar is unpacked into the cache under the
   classes jar's content hash (zip-slip defended), entry marked `published-sources`. This
   covers toolchains that hand IntelliJ a transformed classes jar without linking
   sources. Group `net.minecraft` is excluded from mirroring: the toolchain's own sources
   jar is attached directly when it exists, and plain decompilation remains the fallback
   for a genSources-free Loom workflow; the manifest prune drops the decompiled entry
   once a real sources jar appears. This covers all Loom-family forks; neo-loom
   publishes every game artifact (fml, merged, neoforgeuniversal) under this group.
3. Remaining artifacts are decompiled with Vineflower
   (`Decompiler.builder().inputs(jar).output(DirectoryResultSaver(cacheDir))`, thread
   count from `--threads`, `REMOVE_SYNTHETIC = "0"` to keep mixin targets visible).
   Whole-jar decompilation lets Vineflower use cross-class context.
4. A cache hit is a non-empty hash directory; hits only add a manifest entry and touch
   the directory. Jars process smallest first.
5. Corrupt-download guard: the raw cached jars under
   `~/.gradle/caches/modules-2/files-2.1` are zip-validated on every run; a corrupt
   version directory is deleted so the next resolution re-fetches. Checksum-less
   repositories (e.g. cursemaven) serve chunked responses that Gradle caches as complete,
   and cleanly truncated jars can survive streaming remap transforms as valid but
   classless output.
6. Memory: jars of 15 MB or more are skipped when max heap is below
   `threads x 800 MB + 500 MB` unless `--force`; an `OutOfMemoryError` deletes the
   partial output and continues.
7. The manifest is saved after every successful decompile or mirror (crash-resumable),
   then pruned at the end to hashes still on the classpath; the hash memo is pruned the
   same way. Orphaned cache
   directories are left to the 30-day eviction, since other projects may share them.
8. Resolution failures are summarized, matching corrupt cached artifacts are purged by
   coordinate, and the failure count is written to `unresolved.txt` (deleted when zero);
   the IDE turns the marker into a notification.

**`cleanSourcesCache`** deletes the cache directories referenced by this project's
manifest plus the manifest itself; `--global` wipes the entire store.

### 10.5 IDE Components (dev.mixinmcp.cache)

- **`DecompilationCacheService`** (project service): `getCachedRoots()` merges the
  per-project manifests and resolves cache directories to `VirtualFile`s; `refreshVfs()`
  synchronously refreshes the cache root so directories created outside the IDE resolve;
  `isDecompiledCachePath` is the shared predicate used by the search tools and the
  auto-attacher, and `normalizeJarDiskPath` backs the roots provider's duplicate
  suppression.
- **`MixinDecompiledRootsProvider`** (`AdditionalLibraryRootsProvider`): one
  `SyntheticLibrary` per cached artifact via the five-parameter `newImmutableLibrary`
  overload, with a stable `mixinmcp-<hash>` `comparisonId` enabling incremental rescans
  and an `ExcludeFileCondition` admitting only `.java`/`.kt` files. Cache roots whose
  classes jar already has library SOURCES attached are skipped; the filter re-evaluates
  on every roots query, so a cache root disappears as soon as real sources are attached
  (e.g. Loom's genSources output) and returns if they are detached. `getRootsToWatch`
  returns the same roots.
- **`MixinDecompileCacheStartupActivity`** (`ProjectActivity`): refreshes the cache VFS,
  fires `AdditionalLibraryRootsListener.fireAdditionalLibraryChanged` (under a write
  action) for existing roots, and schedules the source auto-attacher.
- **`MixinDecompileCacheSyncListener`** (`ExternalSystemTaskNotificationListener`): on
  each successful project resolve, does the same refresh + fire + schedule, then reads
  the `unresolved.txt` markers and raises a warning balloon pointing at
  `./gradlew genDependencySources`.

Existing tools need no changes: `mixin_find_class` and `mixin_search_symbols` see the
roots through `allScope()` and the index; the dep-search helpers query
`AdditionalLibraryRootsProvider.EP_NAME` alongside library SOURCES roots.

### 10.6 Design Rationale

**`AdditionalLibraryRootsProvider` instead of modifying library roots.** Gradle sync
rebuilds the project model and discards manually added library roots.
`SyntheticLibrary` roots exist outside the build system's model, so they survive sync.

**Vineflower as a library instead of IntelliJ's built-in decompiler.** `IdeaDecompiler`
is designed for on-demand single-file editor use; batch invocation has undocumented
threading constraints and couples the cache to the IDE's decompiler version. Vineflower
is a pure library, safe from any thread, with deterministic versions, and it is the same
Fernflower lineage IntelliJ bundles, so output quality is equivalent.

**A Gradle task instead of IDE background decompilation.** Background decompilation races
the MCP tools: a tool can run against a half-populated cache, and there is no good way
for a tool to block on it. Running inside Gradle makes population a blocking step of
sync/build, visible in console output, and usable headlessly in CI.

**Alternatives rejected.** A scoped on-demand `mixin_search_decompiled` tool would add
another tool to context and re-decompile per call. Hooking IntelliJ's ephemeral
decompiler output would only cover classes the user has opened.

---

## 11. Source Auto-Attach

`SourceAutoAttacher` (a project service in `cache/`) fills the gaps where a toolchain
leaves an obvious sources artifact unattached. It is scheduled with a 1.5 s debounce
after project open and after every successful Gradle sync; a new schedule cancels the
pending run. One read action collects candidates (including a capped probe for `.java`
entries inside merged jars), sources are resolved on disk outside any lock, and all
attach operations commit in a single EDT write action wrapped in
`ProjectRootManagerEx.mergeRootsChangesDuring`, so N library edits produce one
rootsChanged event instead of N rescans. Every run stores a report (reason, attached
roots, warnings) surfaced by `mixin_list_source_roots`; the tool prints it only when
merged game jars (MDG and similar toolchains) are detected in the project. Libraries are
enumerated from both the project library table
and module order entries, because Gradle game jars often appear only on the latter.

Loom-family toolchains (Fabric Loom, Architectury Loom, neo-loom) are intentionally
out of scope: their game jars are proper GAV libraries whose sources attach via the
maven `-sources.jar` convention after genSources, and their artifact names never match
the predicates below.

### 11.1 MDG merged game jars

ModDevGradle does not attach sources in IntelliJ: its `-merged` artifact is a combined
classes+sources jar, and the intended manual "Attach Sources" flow does not survive
re-sync. The attacher targets any library CLASSES root whose path contains both
`moddev/artifacts` and `-merged.jar`; the match is filename-agnostic, so every MDG naming
variant matches, including NeoForm-only vanilla mode used by multiloader common modules.
Resolution order:

1. The merged jar itself, when it contains `.java` entries (the normal case; the probe
   visits at most 5000 jar entries).
2. The sibling `*-sources.jar` next to it, for `disableRecompilation` setups.
3. For `forge-*-merged.jar` / `neoforge-*-merged.jar` filenames only: the newest
   `*-sources.jar` under the Gradle cache for `net.minecraftforge:forge` /
   `net.neoforged:neoforge` (newest by mtime across hash directories, because changing
   versions accumulate one directory per build; `GRADLE_USER_HOME` is honored).

Candidates come only from library CLASSES roots, never directory scans, so stale artifact
versions accumulating in `build/moddev/artifacts` are inert. Attaches are deduplicated
against the library's existing SOURCES roots and across candidates, keeping repeated
syncs and project opens idempotent (the workspace-model `addRoot` appends without
checking). Per-library failures become report warnings; remaining operations still run.

### 11.2 IntelliJ Platform sources (plugin projects)

IPGP resolves the platform from installer dists that have no sources variant, and
DevKit's manually downloaded sources jar is detached by the next re-import. When exactly
one IPGP dist library is on the classpath (installer or Maven coordinate forms; multiple
dists skip with a warning), the attacher re-attaches the DevKit-downloaded platform
sources jar from the Gradle cache to the dist library and every `bundledPlugin:` /
`bundledModule:` library after each sync. Libraries whose only SOURCES roots are
decompiled-cache stubs count as unattached, so stale stubs never block the real jar; when
the jar has not been downloaded yet, the report warns and names the DevKit download
action, and a watch polls for roughly eighteen minutes (15s, 30s, 60s, 2m, 5m, 10m) and
attaches the jar as soon as it lands. That matters after a `platformVersion` bump, where
the new sources jar is hundreds of megabytes and its download routinely outlives the sync
that triggered the attach; without the watch the jar would sit unattached until the next
sync or an IDE restart, and dependency search would silently return nothing for platform
classes.

---

## 12. Agent Skills Distribution (Claude Code Plugin)

Skills ship as a Claude Code plugin rooted at `claude-plugin/`
(`.claude-plugin/plugin.json` plus `skills/mixinmcp-tools` and `skills/mixin-writing`).
The repository doubles as a Claude Code marketplace via `.claude-plugin/marketplace.json`
at the root, so `/plugin marketplace add muon-rw/MixinMCP` followed by
`/plugin install mixinmcp@mixinmcp` installs straight from GitHub. The plugin is also
submitted to the official community marketplace (clau.de/plugin-directory-submission),
whose catalog pins commit SHAs and auto-bumps them as commits land.
`claude-plugin/.claude-plugin/plugin.json` is the single version source for the whole
build: Claude Code and the community marketplace read the committed JSON directly, so
both Gradle modules parse their version out of it instead of a `pluginVersion` property,
and a release is versioned by bumping that one field.

Releases are one task: `publishPlugin` also depends on `publishClaudePlugin` (strict
`claude plugin validate` of plugin and marketplace, then `claude plugin tag --push`,
cutting `mixinmcp--v<version>` at the release commit) and on `:mixinmcp-gradle:publish`
(the Gradle plugin to maven.muon.rip). The release workflow installs the claude CLI and
supplies `MAVEN_USERNAME`/`MAVEN_PASSWORD` secrets.

`StartupChecksActivity` (a `postStartupActivity`) no longer injects anything. Once per
project (a `PropertiesComponent` latch) it migrates files that older versions injected
into `.cursor` and `.claude`. A file is claimed only when attributable to the plugin:
listed in the injection manifest, under a skill whose SKILL.md carries the
`<!-- mixinmcp-skill-version: ... -->` stamp, or an exact known path listed in a
`.gitignore` bearing the `# MixinMCP auto-injected rules` marker (the signal that covers
pre-stamp 1.1.x-and-earlier injections). Claimed files are moved to a backup under the
IDE system directory (`mixinmcp/removed-skills/<locationHash>`) with a notification
naming it; unattributable files are never touched. Every delete path is
containment-checked against the real project root (traversal- and symlink-safe, junction
aware), emptied injection directories are pruned without following links, stripped
`.gitignore` entries plus the marker are dropped once none remain, and the manifest and
latch finalize only on a failure-free pass so a locked file is retried next open.

On projects detected as Minecraft mods (`fabric.mod.json`, `mods.toml` /
`neoforge.mods.toml`, an existing `.gradle/mixinmcp/manifest.json`, or a known modding
plugin id in the build file), the activity warns (balloon, with opt-out) when Claude Code
is present (`~/.claude` exists) but the plugin is missing from
`~/.claude/plugins/cache/<marketplace>/mixinmcp/<version>/`, or when the newest
semver-like cached version trails the IDE plugin version. SHA-named community-marketplace
cache directories are excluded from the version comparison, and Claude Code auto-updates
its own installs, so the version warning is best-effort. The Gradle plugin
missing/outdated warnings are unchanged.

---

## 13. Settings

`MixinMcpSettings` is a project-level `PersistentStateComponent` (storage
`mixinmcp.xml`) holding `warnMissingGradlePlugin` and `indexBuildscriptClasspath`, both
defaulting to true. `MixinMcpAppSettings` is its application-level twin holding
`warnMissingClaudePlugin` (default true). `MixinMcpSettingsConfigurable` (Kotlin UI DSL
`BoundConfigurable`) exposes all three at Settings > Tools > MixinMCP.

---

## 14. Testing

Automated tests are JUnit unit tests of static helpers (the auto-attacher's matchers are
`internal` specifically to be testable):

- `SourceAutoAttacherTest`: MDG merged-jar matching and sibling sources-jar naming.
- `IjPlatformCoordinateTest`: IPGP dist coordinate parsing and bundled-library detection.
- `SymbolParserTest`: mappings symbol parsing for all three kinds plus namespace and kind
  parsing.
- `GameJarProvenanceTest`: game-jar provenance classification behind the Variants tags
  (loom-remapped vanilla, MDG/NeoForm-recompiled vanilla, loader-patched, decompile
  cache).
- `ToolchainPathClassificationTest`: toolchain classification of dependency-search paths
  for the empty-result hints.
- `SourceWindowTest`: `mixin_get_dep_source` line selection (window clamping, out-of-range
  notes, explicit ranges and their validation).
- `UnknownParameterRejectingToolTest`: the unknown-parameter guard every tool is wrapped
  in (pass-through of declared names and `projectPath`, rejection message, near-name
  suggestions).

Beyond those helpers the MCP tools have no automated coverage; end-to-end verification is
manual against real Fabric and Forge/NeoForge projects. The canonical smoke test for the
bytecode workflow:

1. `mixin_find_class` on a game class: members and superclass resolve.
2. `mixin_class_bytecode` with `filter=synthetic`: lambda targets are discovered.
3. `mixin_method_bytecode` on a synthetic: its body reads back.
4. `mixin_super_methods`: the declaring class is confirmed.
5. Write a mixin targeting the synthetic; `get_file_problems` comes back clean.

Synthetic names differ per toolchain (Loom intermediary uses `method_XXXXX`, MojMap
toolchains use `lambda$method$index`); the tools handle both.

---

## 15. Known Pitfalls & Edge Cases

### Extension Point Namespace
The namespace is `com.intellij.mcpServer` (capital S). Lowercase silently fails.

### McpDescription Dollar Signs
Kotlin treats `$` in annotation strings as template expressions. Escape with `\$`:
`lambda\$tick\$0` not `lambda$tick$0`.

### Remapped Names
Modding toolchains remap names between obfuscated and mapped. The tools see dev-time
mapped names. Loom uses intermediary names for synthetics (`method_XXXXX`); MojMap
toolchains use `lambda$methodName$index`. `BytecodeAnalyzer.lambdaSourceMethod` parses
the `lambda$` convention. `mixin_mappings_lookup` translates between namespaces.

### Threading
Tool functions are called on background threads without a read lock. PSI reads go through
`smartReadAction(project) { }`; PSI writes go through `WriteCommandAction` or a headless
refactoring processor on the EDT.

### Multi-Window Sessions
With several projects open and no `projectPath` argument, the framework throws
`McpExpectedError` instead of returning null. `requireProject` converts this into an
error listing the open projects so the agent can retry.

### ProgressExecutionMode
For project sync, use `ProgressExecutionMode.START_IN_FOREGROUND_ASYNC` (not
`IN_FOREGROUND_ASYNC_PLAIN`, which does not exist).

### MethodResolver and Overloads
`resolveSingle()` returns null for ambiguous overloads without `parameterTypes`.
`findMethodsByName()` can return empty for JDK classes; the resolver falls back to
`psiClass.methods` filtered by name. An override plus its inherited declaration does not
count as ambiguity: the most-derived method wins.

### ClassInheritorsSearch Performance
Can be slow with 100+ dependencies. Always use `maxResults` limits.

### Decompiled Source Search
`mixin_search_in_deps` and `mixin_get_dep_source` search both library SOURCES roots and
decompiled cache roots. Run `./gradlew genDependencySources` to populate the cache for
compiled-only dependencies. Cache roots duplicating an attached sources jar are not
exposed at all, so results never double up.

### Stale Bytecode
Project classes are read from compiler output. The bytecode tools warn when the source is
newer than the `.class` or the editor has unsaved changes, and return a build-and-retry
error when output is missing entirely.

### Large Output
Full decompiled source or `includeInstructions=true` can produce very large responses.
Tool descriptions guide LLMs toward targeted queries.

---

## 16. Buildscript Classpath Coverage

All search tools cover the Gradle buildscript classpath (plugins applied via `plugins {}` or
`buildscript {}`, their transitives, buildSrc runtime, Gradle distribution) on both script DSLs.
Everything Gradle-API-touching lives in `dev.mixinmcp.buildscript`, registered only through
`META-INF/mixinmcp-buildscript.xml` behind an optional plugin dependency on
`org.jetbrains.plugins.gradle`; always-loaded code reaches it exclusively through the base-module
`LabeledSyntheticRootsProvider` interface on the EP-registered provider. A bytecode-scan test
(`OptionalGradleModuleIsolationTest`) enforces that isolation.

`BuildscriptClasspathRoots` enumerates roots from `GradleBuildClasspathManager`, keyed per linked
build via `ExternalSystemApiUtil` module paths. It runs only inside `BuildscriptClasspathSnapshot`,
a project service that recomputes on a background coroutine at project open, after Gradle sync,
and when the indexing setting changes, then fires `AdditionalLibraryRootsListener` if the indexed
root set changed. `BuildscriptClasspathRootsProvider` serves the snapshot and nothing else. The
reason is a platform trap: the workspace file index queries providers while VFS events are applied
under the write action, and every `GradleBuildClasspathManager` query calls `checkRootsValidity`,
which `reload()`s with a synchronous `refreshAndFindFileByPath` per classpath path once any cached
root is invalid (a rebuilt `build/` output dir on the classpath suffices). A synchronous refresh
from inside event processing cannot complete, so the IDE froze for about 30 s per occurrence.
Three further platform constraints shape the enumeration: the manager calls stay outside read
actions for the same reason; `getModuleClasspathEntries` never triggers the classpath map's lazy
initial load (only `getAllClasspathEntries()` does; without priming, the startup snapshot is
empty); and `AdditionalLibraryRootsProvider` binary roots are stub-indexed only when contributed
as `JavaSyntheticLibrary` (plain `SyntheticLibrary` binary roots are watched but never indexed).
Kotlin-DSL builds are excluded from indexing (the K2 Kotlin plugin already indexes their script
classpath through the workspace file index) but still feed text search via `textSearchOnlyRoots`.
FQCN resolution defaults to `everythingScope` so `GradleClassFinder` resolves buildscript classes
even with indexing opted out; index-backed searches keep `allScope` since unindexed content has no
stub entries under any scope.

Sources resolve in three tiers: sources jars already in the flat classpath (paired by name within
a module-cache version dir), per-dependency paths from `BuildScriptClasspathData` DataNodes, then
a sibling `-sources.jar` probe in the Gradle module cache. Build plugins without sources anywhere
are decompiled by the Gradle plugin (1.3.0+), which resolves `buildscript.configurations.classpath`
of the applying project and its ancestors and tags manifest entries `classpathKind=buildscript`;
the roots provider dedups those cache mirrors against live sources jars, and the indexing opt-out
setting governs them. Each manifest also stamps the writing plugin's version; the IDE compares the
newest stamp (or the version declared in root build files) against
`DecompilationCacheService.REQUIRED_GRADLE_PLUGIN_VERSION` and warns when outdated.

Out of scope by design: annotationProcessor paths, Maven build plugins, Groovy settings-only
plugins, and text search over the Gradle API (the `-bin` distribution ships no sources).

## 17. License

GPL-3.0 (see `LICENSE`).
