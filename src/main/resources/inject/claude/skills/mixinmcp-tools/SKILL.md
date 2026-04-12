---
name: mixinmcp-tools
description: >
  MixinMCP IntelliJ tooling for searching Minecraft, mod, and dependency sources.
  ALWAYS use this skill instead of Grep, Read, or jar extraction when you need
  to search or read vanilla Minecraft code, mod code, or any dependency on the classpath.
  These tools have full classpath indexing (including inside jars that Grep cannot see),
  dedicated type hierarchy, call graph, and reference-finding tools that are faster and
  more accurate than text search, and return clean structured results that use less context
  than raw file dumps. Use when looking up classes, methods, fields, bytecode, inheritance
  hierarchies, call graphs, mixin conflicts, or dependency sources. Also use when writing
  @At(target) strings, targeting lambdas, or diagnosing "target not found" errors.
---

# MixinMCP Tools

MixinMCP tools are provided by the IntelliJ MCP server and can intelligently search
Minecraft sources and dependency sources. They index the entire classpath including
inside dependency jars (which Grep cannot see), provide dedicated type hierarchy,
call graph, and reference-finding tools that are faster and more accurate than text
search, and return clean structured results that use less context than raw file dumps.
Dependencies without published sources are decompiled via the MixinMCP Gradle plugin
(Vineflower) so every library on the classpath is searchable.

**ALWAYS prefer these tools over Grep, Read, or jar extraction.**

## Invoking Tools

MixinMCP tools are available as regular MCP tools once the IntelliJ MCP server is
connected. Call them directly by name:

```
mixin_find_class(className="net.minecraft.world.level.Level", includeMembers=true)
```

Tool descriptions document all parameters and defaults — read them before calling.

## Tool Selection

| Goal | Tool |
|------|------|
| Look up a class by FQCN | `mixin_find_class` (if SourceKind is "Classes JAR (binary)", use `mixin_get_dep_source` for better source) |
| Search names across classpath | `mixin_search_symbols` |
| Grep dependency sources by regex | `mixin_search_in_deps` → then `mixin_get_dep_source` with returned `url` |
| Read a known dependency file | `mixin_get_dep_source` (pass `path`, e.g. `io/redspace/.../Utils.java`) |
| Inheritance chain | `mixin_type_hierarchy` |
| All implementors | `mixin_find_impls` |
| All usages of a class/method/field | `mixin_find_references` (supports both methods and fields via `memberName`) |
| All call sites across MC + all deps | `mixin_find_references` (more complete than `mixin_search_in_deps` for call-site enumeration) |
| Cross-mod mixin conflicts on a target | `mixin_find_targeting_mixins` — finds all @Mixin classes + their injection points |
| Call graph | `mixin_call_hierarchy` |
| Method origin in hierarchy | `mixin_super_methods` |
| Synthetic/lambda method names | `mixin_class_bytecode` (filter="synthetic") |
| Exact @At(target) for an INVOKE | `mixin_method_bytecode` — read the owner class from INVOKE* instructions |
| Bytecode for a specific method | `mixin_method_bytecode` |
| Diagnose missing source roots | `mixin_list_source_roots` |

## Examples

Look up a class:
```
mixin_find_class(className="net.minecraft.world.level.Level", includeMembers=true)
```

Search dependency sources, then read the result:
```
mixin_search_in_deps(regexPattern="destroyBlock", fileMask="Level")

// Results are grouped by file. Each group shows a url: line once —
// pass that url to mixin_get_dep_source:
mixin_get_dep_source(url="<url from result>", lineNumber=42, linesBefore=10, linesAfter=20)
```

Narrow to Minecraft-only sources with pathPrefix:
```
mixin_search_in_deps(regexPattern="addEffect\\(", pathPrefix="net/minecraft/", timeout=25000)
```

Read source by known path (without searching first):
```
mixin_get_dep_source(path="io/redspace/ironsspellbooks/player/ServerPlayerEvents.java", lineNumber=360, linesBefore=20, linesAfter=20)
```

Find field references:
```
mixin_find_references(className="net.minecraft.world.entity.LivingEntity", memberName="DATA_HEALTH_ID")
```

## Common Pitfalls

### mixin_search_in_deps
- `regexPattern` is **Java regex**. Escape metacharacters: `addEffect\\(` not `addEffect(`.
  If you pass unescaped metacharacters, the tool will return a hint suggesting the fix.
- Each result's `url:` line includes `[rootKind: ...]`. Prefer Library SOURCES hits over decompiled ones.
- `fileMask` matches file path inside jar (e.g. `net/minecraft/world/entity/LivingEntity.java`):
  - No wildcards → case-insensitive **substring** match anywhere in the path
  - With wildcards → glob
  - Does NOT match jar names or Maven coordinates.
  - **Caution:** Short substrings like `apotheosis` will also match paths in *other* mods' compatibility packages (e.g. `compat/apotheosis/`). Use longer path fragments or `pathPrefix` for precision.
- `pathPrefix`: restricts to files whose logical path starts with the prefix (e.g. `net/minecraft/` or `io/redspace/ironsspellbooks/`). Use forward slashes.
- `roots`: `all` (default), `library` (only published -sources.jar), `decompiled` (only MixinMCP cache). When `all`, cache files are skipped if the same path already matched in library sources.
- Broad searches can time out. Increase `timeout` (e.g. 20000–30000) for searches without a fileMask.

### Vanilla Minecraft sources by toolchain
- **ForgeGradle** (older, pre-MDG): Vanilla MC sources are properly attached as Library SOURCES roots. `mixin_search_in_deps` **can** grep `net/minecraft/` files directly.
- **ModDevGradle (MDG)** — Forge MDG and NeoForge: Vanilla Minecraft classes live in a **merged JAR** under `build/moddev/`. This is binary, not a source root. `mixin_search_in_deps` **cannot** grep vanilla MC classes. To read vanilla code:
  - Use `mixin_find_class` with `includeSource=true` (returns Fernflower decompiled source)
  - Use `mixin_method_bytecode` for exact INVOKE targets
  - Use `mixin_search_in_deps` only for searching **mod** sources that *reference* vanilla types
- **Fabric Loom**: Run `./gradlew genSources` to generate sources, then `mixin_sync_project`.
- `mixin_list_source_roots` detects the toolchain and shows whether vanilla MC sources are searchable.

### MDG: Forge & NeoForge universal API vs loader `-sources.jar`
- **Forge:** much of the mod-facing API ships in **forge-*-universal** (often folded into `build/moddev/*-merged.jar`). **Legacy Forge MDG** often attaches **fmlloader / fmlcore / eventbus / …** as Library SOURCES while **not** attaching **forge-*-universal-sources.jar**, so `mixin_search_in_deps` can miss **any** `net/minecraftforge/...` package that lives only in universal (e.g. `net.minecraftforge.event.*` — a common canary, not the only package).
- **NeoForge** (ModDevGradle 1.x / 2.x): the same pattern applies for **neoforge-*-universal** — **bus / coremods / …** may have `-sources.jar` while **`net/neoforged/neoforge/...`** game API (e.g. `net/neoforged/neoforge/event/`) is only in the merged/binary layer until **neoforge-*-universal-sources** is attached as Library SOURCES.
- Universal `-sources.jar` files may still exist under `~/.gradle/caches` even when IntelliJ has no Library SOURCES root for them.
- `mixin_list_source_roots` uses **`net/minecraftforge/event/`** and **`net/neoforged/neoforge/event/`** as **canaries** for Forge vs NeoForge universal attachment (same idea as vanilla `net/minecraft/`).
- If grep under `net/minecraftforge/` or `net/neoforged/` is empty or wrong, use `mixin_find_class(includeSource=true)`, `mixin_search_symbols`, drop `pathPrefix` to search mod sources, or confirm roots with `mixin_list_source_roots`. `mixin_search_in_deps` adds hints on empty searches for those prefixes.

### mixin_get_dep_source
- `url`: copy the exact `url:` string from search results (strip `[rootKind: ...]` suffix).
- `path`: package path with `/` separators and `.java` extension (e.g. `io/redspace/.../Utils.java`). NOT a filesystem path.
- If a path is not found, fall back to `mixin_search_in_deps` then use the returned `url`.
- **Vanilla Minecraft classes** may not resolve via `path` (they live in the merged jar). Use `mixin_find_class` with `includeSource=true`, or `mixin_method_bytecode`.
- **If Minecraft sources are missing entirely** (nothing under `net/minecraft/`, no Minecraft root in `mixin_list_source_roots`):
  - **Fabric Loom:** `./gradlew genSources`
  - **NeoForge MDG:** `./gradlew downloadAssets`
  - **Any loader:** `./gradlew genDependencySources --force` (needs `org.gradle.jvmargs=-Xmx4g`)
  - Then call `mixin_sync_project` to refresh IntelliJ's project model.

### mixin_find_references / mixin_call_hierarchy / mixin_super_methods
- `memberName` supports **both methods and fields**. For fields, no disambiguation is needed. For methods, disambiguate overloads with:
  - `parameterTypes=["MobEffectInstance", "Entity"]` — simple type names
  - `methodDescriptor="(Lnet/...;)Z"` — JVM descriptor
  - Parameterless: `parameterTypes=[]` or `methodDescriptor="()V"`
- If disambiguation fails, the error lists all overloads with ready-to-copy `parameterTypes` and declaring class.
- `mixin_find_references` returns both runtime call sites AND string references in mixin annotations.
- For dedicated mixin conflict analysis, prefer `mixin_find_targeting_mixins`.
- `mixin_call_hierarchy` callees: if the method body is not available in source (binary class), the tool automatically falls back to bytecode analysis to extract INVOKE targets.

### mixin_search_symbols
- Searches **short names** only, not FQCNs. Pass `LivingEntity` not the full package path.
  (FQCNs are auto-simplified with a note — use `mixin_find_class` for exact FQCN lookup.)
- Required parameter is `query` (a single name substring). Search one name at a time.
- Method results include parameter types for disambiguation.

### mixin_class_bytecode
- Decompiled source does NOT show synthetic method names. To target a lambda in @Redirect or @Inject, you MUST use this tool with `filter="synthetic"`.

### mixin_method_bytecode
- Each INVOKE* instruction shows the **real owner class**, not the declaring class from source. Always use this owner when writing `@At(target = "...")`.

### mixin_find_targeting_mixins
- Increase `maxResults` (default 50) for heavily-targeted classes like `LivingEntity` or `Player`.

## Mixin Workflow

1. Before writing @Mixin: **ALWAYS** check `mixin_type_hierarchy` first.
2. When targeting lambdas: **ALWAYS** use `mixin_class_bytecode` with `filter="synthetic"`.
3. When writing @At(target): **ALWAYS** use `mixin_method_bytecode` to get the exact INVOKE* owner from bytecode.
4. When unsure about method origin: use `mixin_super_methods`.
5. After writing any mixin: validate for errors.
6. After changing build.gradle deps: run `./gradlew genDependencySources` then `mixin_sync_project`.

**Note:** Only `mixin_sync_project` accepts an optional `projectPath` parameter. All other tools automatically use the open project.

## Troubleshooting

**If `mixin_*` tools are not available:**
The tools are provided by IntelliJ's MCP server. If they don't appear:
1. Ensure IntelliJ is running with the project open
2. Check IntelliJ: Settings → Plugins → verify both "MCP Server" and "MixinMCP" are enabled
3. Verify the IntelliJ MCP server is configured in your Claude Code MCP settings
   (`~/.claude/settings.json` or project `.claude/settings.json` under `mcpServers`)
4. **Restart Claude Code** if IntelliJ was started after the Claude Code session began
