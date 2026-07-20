---
name: mixinmcp-tools
description: >
  IntelliJ-backed code search and refactoring across a JVM project's entire
  classpath: any dependency, library, or JDK source, and on Minecraft projects
  also vanilla and mod sources. ALWAYS use these tools instead of grep, read_file,
  or jar extraction to look up or search code that lives in a dependency: they
  index inside jars grep cannot see, and add type-hierarchy, call-graph, reference,
  and bytecode tools that beat text search on accuracy and context cost. Use for
  class/method/field lookup, finding usages/implementations/overrides, inheritance
  and call graphs, reading dependency source, bytecode inspection, and
  reference-aware refactors (rename, safe-delete, extract, inline, change-signature,
  move). On Minecraft projects also use for mixin work: @At(target) owners,
  synthetic/lambda names, mapping-namespace conversion, and cross-mod conflicts.
---

# MixinMCP Tools

These tools run on the **user-jetbrains** MCP server and search a JVM project's whole
classpath, including inside dependency jars that grep cannot see. They add dedicated
type-hierarchy, call-graph, reference-finding, and bytecode tools that are faster and
more accurate than text search, and return structured results that cost far less
context than raw file dumps. Dependencies without published sources are decompiled
(Vineflower, via the MixinMCP Gradle plugin) so every library on the classpath is
searchable.

**Prefer these over grep, read_file, or jar extraction for anything on the classpath.**
The search, hierarchy, bytecode, and refactoring tools work on any Gradle/Maven JVM
project. The mapping, mixin, and Minecraft-toolchain features are used only on Minecraft projects.

## Invoking

Call them through the MCP interface, e.g.:
```
CallMcpTool(
  server="user-jetbrains",
  toolName="mixin_find_class",
  arguments={"className": "java.util.concurrent.ConcurrentHashMap"}
)
```
**Arguments must be valid JSON** (no trailing commas, no single quotes). Each tool's
description documents its parameters and defaults; read it before calling rather than guessing.

## Tool selection

| Goal | Tool |
|------|------|
| Look up a class by FQCN | `mixin_find_class` (if SourceKind is a binary "Classes JAR", read real source via `mixin_get_dep_source`) |
| Read one method/field, not the whole class | `mixin_find_class` with `methodName` or `fieldName` — avoids dumping a 50KB class |
| Search names across the classpath | `mixin_search_symbols` (short names, one at a time) |
| Regex-grep dependency source | `mixin_search_in_deps` → `mixin_get_dep_source` with the returned `url`; `contextLines` captures short bodies inline |
| Read a known dependency file | `mixin_get_dep_source` (by `path`, e.g. `com/google/common/collect/Lists.java`) |
| Inheritance chain / all subtypes | `mixin_type_hierarchy` |
| All implementors of an interface | `mixin_find_impls` |
| All usages of a class/method/field | `mixin_find_references` (methods and fields via `memberName`; more complete than text search) |
| Method origin upward / all overrides downward | `mixin_super_methods` / `mixin_find_overrides` |
| Call graph | `mixin_call_hierarchy` |
| Bytecode of a class or method | `mixin_class_bytecode` / `mixin_method_bytecode` |
| Pin a lookup to one module's classpath | `"module"` (e.g. `"common.main"`) on `mixin_find_class`, `mixin_get_dep_source`, bytecode tools |
| Rename / delete / move / change-sig / extract / inline / move-members | see **Refactoring** |
| Re-sync Gradle/Maven after build-file or dependency changes | `mixin_sync_project` |
| Make on-disk edits by external tools visible to the IDE | `mixin_refresh_vfs` |
| Diagnose missing or empty source roots | `mixin_list_source_roots` |
| **Minecraft:** synthetic/lambda names, `@At(target)` owner | `mixin_class_bytecode` with `"filter": "synthetic"`, `mixin_method_bytecode` |
| **Minecraft:** cross-mod mixin conflicts on a target | `mixin_find_targeting_mixins` |
| **Minecraft:** convert a name between mapping namespaces | `mixin_mappings_lookup` |

## Examples

Read one method from a large class instead of the whole file:
```
CallMcpTool(
  server="user-jetbrains",
  toolName="mixin_find_class",
  arguments={"className": "com.google.common.collect.Iterables", "methodName": "partition"}
)
```

Grep dependency source, then read a hit (results are grouped by file with a `url:` line):
```
CallMcpTool(
  server="user-jetbrains",
  toolName="mixin_search_in_deps",
  arguments={"regexPattern": "computeIfAbsent\\(", "pathPrefix": "java/util/"}
)
CallMcpTool(
  server="user-jetbrains",
  toolName="mixin_get_dep_source",
  arguments={"url": "<url from result>", "lineNumber": 42, "linesBefore": 10, "linesAfter": 20}
)
```

Find every usage of a field, including string refs in mixin config JSON:
```
CallMcpTool(
  server="user-jetbrains",
  toolName="mixin_find_references",
  arguments={"className": "net.minecraft.world.entity.LivingEntity", "memberName": "DATA_HEALTH_ID"}
)
```

## Usage notes

Only what isn't obvious from the tool descriptions.

**Searching dependency source** (`mixin_search_in_deps` / `mixin_get_dep_source`)
- `regexPattern` is Java regex — escape metacharacters (`addEffect\\(`, not `addEffect(`). Unescaped, the tool hints the fix.
- `fileMask` without wildcards is a case-insensitive substring on the path, so a short fragment like `apotheosis` also matches `compat/apotheosis/` inside other jars; use a longer fragment or `pathPrefix`. It never matches jar names or Maven coordinates.
- Broad searches with no `fileMask` can time out — raise `timeout` to 20000–30000.
- Each hit's `--- path [root] ---` header names the matching root; prefer `Library SOURCES` over decompiled. Copy the `url:` line verbatim into `mixin_get_dep_source`.
- `contextLines` (3–10) captures a short matched body inline so you can skip the follow-up read; leave it 0 for long methods.
- `mixin_get_dep_source` takes that `url` verbatim, or a package `path` (`.../Foo.java`, not a filesystem path). If `path` misses, search first and use the returned `url`.

**Reading a class** (`mixin_find_class`)
- For big classes prefer `methodName`/`fieldName` over `"includeSource": true` (which dumps 50KB+ and forces extra grep).
- Methods/Fields list only members declared **directly** on the class. Utility classes often hide their real API in **nested classes** (e.g. `Tags.Blocks`, `Tags.Items`), so the outer class looks empty; always read the Nested classes section, which gives ready-to-paste follow-up calls. Inherited members show `(inherited from X)` — follow up on `X` or via `mixin_super_methods`.

**Usages, hierarchy, call graph**
- Disambiguate overloaded methods (for `mixin_find_references` / `_call_hierarchy` / `_super_methods` / `_find_overrides` / `_rename` / `_safe_delete` / `_change_signature` / `_inline`) with `"parameterTypes": ["Entity","DamageSource"]` or `"methodDescriptor": "(L…;)Z"` (parameterless: `"parameterTypes": []`). On failure the error lists overloads with ready-to-copy values.
- `mixin_find_references` returns runtime call sites plus string refs in annotations. `mixin_call_hierarchy` output is `owner#name(descriptor)` (paste-ready for `@At`), tags `[lambda]`/`[ctor]`/`[cycle]`, resolves lambdas through the INVOKEDYNAMIC handle, and walks Java/Kotlin/Groovy/Scala via UAST; `maxResults` is a global budget, so raise it for wide graphs or start at `"maxDepth": 1`.
- `mixin_super_methods` marks `[root declaration]` entries — usually the best mixin target; `mixin_find_overrides` is its downward mirror. `mixin_type_hierarchy`: check both **Direct** and **Inherited** interface sections (inherited tagged `(from X)`/`(via X)`) before concluding a class doesn't implement something; `"direction": "supers"` skips the subtype list.

**Bytecode** (`mixin_class_bytecode` / `mixin_method_bytecode`)
- INVOKE* instructions show the **real owner class**, not the source-declared one — use that owner in `@At(target=…)`.
- `"filter": "synthetic"` reveals lambda/bridge names (`lambda$X$N`) and synthetic fields (`this$0`, `$VALUES`) that decompiled source hides; it is the only way to target a lambda.

**Module pinning and classpath variants**
- Multi-module classpaths carry several copies of a class. `"module"` (exact or dot-boundary suffix, e.g. `"neoforge.main"`) on `mixin_find_class`/`_get_dep_source`/bytecode tools pins resolution; unknown names error with the available list.
- Without `module`, tools append a Variants block: byte-identical copies merge; patched copies get a structural diff tagged by provenance (`[loader-patched]`, `[loom-remapped vanilla]`, `[MDG/NeoForm-recompiled vanilla]`). Two-vanilla diffs collapse to a count (toolchain noise); only `[loader-patched]` diffs are real changes. `mixin_method_bytecode` adds a `NOTE:` when a method differs in another variant.

**Minecraft: `net/minecraft/`, `net/minecraftforge/`, or `net/neoforged/` sources come back empty**
1. Run `mixin_list_source_roots` and read the MDG merged-jar auto-attach section.
2. Fix or report any auto-attach warning first; until attachment succeeds, every other fallback fights the wrong fire.
3. Sources genuinely missing for the toolchain? See `references/toolchains.md` for per-toolchain recovery (Loom/neo-loom `genSources`, NeoForge/Forge MDG `downloadAssets`, `genDependencySources --force`), then `mixin_sync_project`.
4. Last resort: `mixin_find_class` with `"includeSource": true` and `mixin_method_bytecode` work via PSI/classfile and do not need attached source roots.

**Minecraft: `mixin_mappings_lookup`**
- The input symbol must already be in the `from` namespace (e.g. `"from": "srg"` wants `net/minecraft/src/C_12_`, not the mojmap name). Member inputs include the owner (`net/minecraft/src/C_12_.m_8793_`); descriptor optional.
- MC version auto-detects from `gradle.properties`; pass `mcVersion` if that fails. Namespaces: mojmap / yarn / intermediary / srg / obf.

## Refactoring

Reference-aware: they update or check every reference project-wide (including mixin
config JSON and javadoc links), so prefer them over hand-editing, which fixes one site
and leaves the rest dangling. Shared contract: `"dryRun": true` reports the resolved
target, per-file usage counts, and conflicts without changing anything; conflicts are
listed one per line tagged `[library]`/`[source]` (`[library]` usually means a stale
build-output jar, not a real clash); `"ignoreConflicts": true` proceeds anyway, the
headless equivalent of the IDE's "Continue". Exceptions: `mixin_safe_delete` takes
`"force": true` instead, and `mixin_move_file` takes neither flag (all its checks run up
front; there is no preview). The signature/extract/introduce/inline/move-members tools
are Java-source only. Read each tool's description for parameters; below is only what
isn't obvious there.

- `mixin_rename` — class/method/field, plus `"memberKind": "parameter"`/`"local"` (with `variableName` naming the variable inside the method). Unlike the built-in `rename_refactoring`, it reports conflicts instead of silently discarding them.
- `mixin_safe_delete` — usage-checked across project and dependencies; overrides count as blocking usages tagged `[override]`; handles Kotlin (a Kotlin class delete removes the declaration but leaves the file).
- `mixin_move_file` — moves a class to a new package, updating the package declaration and every reference (including mixin config JSON).
- `mixin_change_signature` — `parametersJson` is a JSON-array string: `{"oldIndex": N}` keeps a parameter, `{"oldIndex": -1, "name": …, "type": …, "defaultValue": …}` adds one (inserted at every call site), omitted parameters are removed.
- `mixin_extract_method` / `mixin_introduce_variable` — address a `filePath` plus `startLine`..`endLine` range, taken as whole lines; `"expression": "<its source text>"` targets a sub-expression within it, `occurrenceIndex` picks among repeats. A non-matching `expression` lists the selectable expressions with their positions, so let a miss tell you the exact text instead of guessing.
- `mixin_inline` — `"kind": "method"|"field"|"local"`; refuses recursive methods, enum constants, and non-final fields with write usages.
- `mixin_move_members` — `"direction": "up"|"down"|"toClass"`; `members` are `name` or `name#descriptor`. Moving members *into* a `@Mixin` class flags external references to them as blocking `[mixin]` conflicts, since mixin members can't be referenced from ordinary classes at runtime.

## Mixin workflow

1. **Type hierarchy first.** Run `mixin_type_hierarchy` before designing the @Mixin. The target's parent often declares the method you actually want; injecting into the subclass means rewriting once you find the real declaration site.
2. **Bytecode for synthetics.** Decompiled source hides lambda and bridge names; `mixin_class_bytecode` with `"filter": "synthetic"` is the only way to get the `lambda$X$N` symbol for `method = "…"`.
3. **Bytecode owner for `@At(target)`.** The owner in an INVOKE instruction can differ from the source-declared class (devirtualisation, override resolution, mixin re-application). It is what runs at injection time, so read it from `mixin_method_bytecode`.
4. **Origin questions.** `mixin_super_methods` walks up to the original declaration; `mixin_find_overrides` walks down to every implementation.
5. **After writing a mixin**, validate for errors before committing.
6. **After dependency changes**, run `./gradlew genDependencySources` then `mixin_sync_project` so the index matches the new classpath.

For injector selection (`@ModifyExpressionValue` vs `@WrapOperation` vs `@Inject` vs …),
`@At` parameter format, and MixinExtras `@Expression` syntax, switch to the
**mixin-writing** skill once the tools above have pinned down the target.

**Note:** the IntelliJ MCP framework auto-injects a `projectPath` argument into every
tool. Pass it when multiple IDE windows are open; with one window it can be omitted. An
ambiguous project produces an error listing the candidate paths.

## Troubleshooting

**If the `mixin_*` tools are not available:**
1. Ensure IntelliJ is running with the project open.
2. In IntelliJ, Settings → Plugins: verify both "MCP Server" and "MixinMCP" are enabled.
3. Verify the IntelliJ MCP server (`user-jetbrains`) is configured in your Cursor MCP settings.
4. **Restart Cursor** if IntelliJ started after Cursor's MCP connection was established.
