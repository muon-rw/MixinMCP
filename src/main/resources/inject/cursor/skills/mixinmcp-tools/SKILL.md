---
name: mixinmcp-tools
description: >
  MixinMCP IntelliJ tooling for searching Minecraft, mod, and dependency sources.
  ALWAYS use this skill instead of grep, read_file, or jar extraction when you need
  to search or read vanilla Minecraft code, mod code, or any dependency on the classpath.
  Use when looking up classes, methods, fields, bytecode, inheritance hierarchies,
  call graphs, mixin conflicts, or dependency sources. Also use when writing @At(target)
  strings, targeting lambdas, or diagnosing "target not found" errors.
---

# MixinMCP Tools

MixinMCP tools run on the **user-jetbrains** MCP server and can intelligently search
Minecraft sources and dependency sources more efficiently than grep or file reading.
They search and read inside dependency jars natively. Dependencies without published
sources are decompiled via the MixinMCP Gradle plugin (Vineflower) so every library
on the classpath is searchable.

**ALWAYS prefer these tools over grep, read_file, or jar extraction.**

## Invoking Tools

```
CallMcpTool(server="user-jetbrains", toolName="<tool>", arguments={...})
```

**Arguments must be valid JSON.** No trailing commas, no single quotes, no
unescaped special characters.

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
CallMcpTool(
  server="user-jetbrains",
  toolName="mixin_find_class",
  arguments={"className": "net.minecraft.world.level.Level", "includeMembers": true}
)
```

Search dependency sources, then read the result:
```
CallMcpTool(
  server="user-jetbrains",
  toolName="mixin_search_in_deps",
  arguments={"regexPattern": "destroyBlock", "fileMask": "Level"}
)
// Pass the url: line from results to mixin_get_dep_source:
CallMcpTool(
  server="user-jetbrains",
  toolName="mixin_get_dep_source",
  arguments={"url": "<url from result>", "lineNumber": 42, "linesBefore": 10, "linesAfter": 20}
)
```

Narrow to Minecraft-only sources with pathPrefix:
```
CallMcpTool(
  server="user-jetbrains",
  toolName="mixin_search_in_deps",
  arguments={"regexPattern": "addEffect\\(", "pathPrefix": "net/minecraft/", "timeout": 25000}
)
```

Read source by known path:
```
CallMcpTool(
  server="user-jetbrains",
  toolName="mixin_get_dep_source",
  arguments={"path": "io/redspace/ironsspellbooks/player/ServerPlayerEvents.java", "lineNumber": 360, "linesBefore": 20, "linesAfter": 20}
)
```

Find field references:
```
CallMcpTool(
  server="user-jetbrains",
  toolName="mixin_find_references",
  arguments={"className": "net.minecraft.world.entity.LivingEntity", "memberName": "DATA_HEALTH_ID"}
)
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
- **ModDevGradle (MDG)** — Forge MDG and NeoForge: Vanilla Minecraft (and loader game API) ship in a **merged JAR** under `build/moddev/artifacts/`. **MixinMCP auto-attaches** that merged jar as a Library SOURCES root after Gradle sync (or uses a Gradle `*-sources.jar` fallback when the merged jar has no `.java` entries). **Try `mixin_search_in_deps` first** for `net/minecraft/`, `net/minecraftforge/`, and `net/neoforged/` paths.
- If search still returns nothing, run **`mixin_list_source_roots`**: check the **MDG merged-jar source auto-attach** section for warnings (failed attach). **Then** fall back to `mixin_find_class(includeSource=true)`, `mixin_method_bytecode`, or broader searches — those are **fallbacks**, not the default path.
- **Fabric Loom**: Run `./gradlew genSources` to generate sources, then `mixin_sync_project`.
- `mixin_list_source_roots` detects the toolchain, shows the last auto-attach run, and uses canaries to confirm vanilla / Forge / NeoForge game API sources.

### MDG: Forge & NeoForge universal API vs loader `-sources.jar`
- **Forge / NeoForge MDG:** MixinMCP **auto-attaches** the merged game artifact (and can fall back to **`net.minecraftforge:forge:*-sources`** or **`net.neoforged:neoforge:*-sources`** under `~/.gradle/caches` when recompilation is off) so universal API should appear in Library SOURCES without manual “Attach Sources”.
- `mixin_list_source_roots` still uses **`net/minecraftforge/event/`** and **`net/neoforged/neoforge/event/`** as **canaries** to verify attachment (same idea as vanilla `net/minecraft/`).
- If `mixin_search_in_deps` is still empty for those prefixes, run `mixin_list_source_roots` and treat **auto-attach warnings** as the first thing to fix or include in a bug report. Use `mixin_find_class(includeSource=true)`, `mixin_search_symbols`, or dropping `pathPrefix` only **after** confirming auto-attach status.

### mixin_get_dep_source
- `url`: copy the exact `url:` string from search results (strip `[rootKind: ...]` suffix).
- `path`: package path with `/` separators and `.java` extension (e.g. `io/redspace/.../Utils.java`). NOT a filesystem path.
- If a path is not found, fall back to `mixin_search_in_deps` then use the returned `url`.
- **Vanilla Minecraft classes** in MDG projects should resolve via `path` once the merged jar is auto-attached as sources. If `path` fails, check `mixin_list_source_roots` (auto-attach), then use `mixin_find_class(includeSource=true)` or `mixin_method_bytecode`.
- **If Minecraft sources are missing entirely** (nothing under `net/minecraft/`, no Minecraft root in `mixin_list_source_roots`):
  - **Fabric Loom:** `./gradlew genSources`
  - **NeoForge MDG:** `./gradlew downloadAssets`
  - **Any loader:** `./gradlew genDependencySources --force` (needs `org.gradle.jvmargs=-Xmx4g`)
  - Then call `mixin_sync_project` to refresh IntelliJ's project model.

### mixin_find_references / mixin_call_hierarchy / mixin_super_methods
- `memberName` supports **both methods and fields**. For fields, no disambiguation is needed. For methods, disambiguate overloads with:
  - `"parameterTypes": ["MobEffectInstance", "Entity"]` — simple type names
  - `"methodDescriptor": "(Lnet/...;)Z"` — JVM descriptor
  - Parameterless: `"parameterTypes": []` or `"methodDescriptor": "()V"`
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
5. After writing any mixin: use `get_file_problems` to validate.
6. After changing build.gradle deps: run `./gradlew genDependencySources` then `mixin_sync_project`.

**Note:** Only `mixin_sync_project` accepts an optional `projectPath` parameter. All other tools automatically use the open project.

## Troubleshooting

**If `mixin_*` tools are not found:**
The `user-jetbrains` MCP server only appears after Cursor connects to IntelliJ's MCP Server.
1. Ensure IntelliJ is running with the project open
2. Check IntelliJ: Settings → Plugins → verify "MCP Server" and "MixinMCP" are enabled
3. **Restart Cursor** — the MCP server list is cached at startup
