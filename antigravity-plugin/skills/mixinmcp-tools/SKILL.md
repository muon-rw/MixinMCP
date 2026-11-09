---
name: mixinmcp-tools
description: >
  IntelliJ-backed code search and refactoring across a JVM project's entire
  classpath: any dependency, library, JDK source, or Gradle plugin, and on Minecraft
  projects, vanilla and mod sources. ALWAYS use these tools instead of grep_search,
  read_file, find_by_name, or manual jar extraction via run_command to look up or
  search code that lives in a dependency: they index inside jars built-in file search
  cannot see, and add type-hierarchy, call-graph, reference, and bytecode tools that
  beat text search on accuracy and context cost. Use for class/method/field lookup,
  finding usages/implementations/overrides, inheritance and call graphs, reading
  dependency source, bytecode inspection, and reference-aware refactors (rename,
  safe-delete, extract, inline, change-signature, move). On Minecraft projects also
  use for mixin work: @At(target) owners, synthetic/lambda names, mapping-namespace
  conversion, and cross-mod conflicts.
---

# MixinMCP Tools

IntelliJ MCP tools for full-classpath search, type hierarchies, call graphs, references,
bytecode, and refactoring across any Gradle/Maven JVM project (dependencies without
published sources are decompiled via the MixinMCP Gradle plugin). **Prefer these over
`grep_search`, `read_file`, `find_by_name`, or `run_command` jar extraction for anything
on the classpath.** Mapping, mixin, and Minecraft-toolchain features apply only to
Minecraft projects.

## Invoking

Call them as regular MCP tools once IntelliJ is connected, e.g.
`mixin_find_class(className="java.util.concurrent.ConcurrentHashMap")`. Read each tool's
description for its parameters and defaults before calling.

- **Parameter names are strict:** unknown names (or passing two aliases of the same parameter) are rejected with the accepted list; use the suggested name.
- **Address parameters are distinct:** `path` is a classpath-relative source path (`com/example/Foo.java`), `filePath` is a file on disk, and `url` is a `jar://` or `file://` URL. Do not pass one where another is expected.
- **`memberName`** takes a method or field name on tools that declare no `methodName` / `fieldName`.
- **`projectPath`** is auto-injected by the IntelliJ MCP framework; omit it when one IDE window is open, or pass one of the candidate paths if multiple windows make it ambiguous.
- **Busy IDE:** every tool except `mixin_sync_project`, `mixin_refresh_vfs`, `mixin_ide_status`, and `mixin_mappings_lookup` waits up to 30s for indexing, Gradle resolve, or project import and then errors naming what it is waiting on. Retry or call `mixin_ide_status` / `mixin_sync_project(wait=true)` first.

## Tool selection

| Goal | Tool |
|------|------|
| Look up a class by FQCN | `mixin_find_class` (if SourceKind is a binary "Classes JAR", read real source via `mixin_get_dep_source`) |
| Read one method/field, not the whole class | `mixin_find_class(className, methodName=…)` or `fieldName=…` — avoids dumping a 50KB class |
| Search names across the classpath | `mixin_search_symbols` (short names, one at a time) |
| Regex-grep dependency source | `mixin_search_in_deps` → `mixin_get_dep_source` with the returned `url`; `contextLines` captures short bodies inline |
| Narrow a grep to one tier | `roots="game"` (vanilla/loader), `"library"`, `"decompiled"`, `"jdk"`, `"buildscript"` |
| Search build-plugin code (Loom, ModDevGradle, …) | any lookup tool; `mixin_search_in_deps(roots="buildscript")` greps only buildscript sources |
| Read a known dependency file | `mixin_get_dep_source` (by `className`, or `path` e.g. `com/google/common/collect/Lists.java`) |
| Read a jar resource (mods.toml, fabric.mod.json, lang, recipes, mixin config) | `mixin_get_dep_source(jarPath=…, entry=…)`, or the `url` from a search hit |
| See or grep what is inside jars | `mixin_list_jar_entries` (`jarPath` for any jar or folder of jars, `jar` for a classpath jar by name/coordinates; `regexPattern` greps text entries) |
| Find classes referencing a member, class, or string (unindexed, off-classpath, or jar-in-jar) | `mixin_search_bytecode` (`patterns`; optional `jarPath` for a jar/folder, `jar` for a classpath jar, or neither for the whole classpath) |
| Search a mod that is on no classpath | `./gradlew genDependencySources --jar <path>` (or `mixinmcp { extraJars.from(…) }`), then `mixin_sync_project` |
| Inheritance chain / all subtypes | `mixin_type_hierarchy` |
| All implementors of an interface | `mixin_find_impls` |
| All usages of a class/method/field | `mixin_find_references` (methods and fields via `memberName`; more complete than text search) |
| Method origin upward / all overrides downward | `mixin_super_methods` / `mixin_find_overrides` |
| Call graph | `mixin_call_hierarchy` |
| Bytecode of a class or method | `mixin_class_bytecode` / `mixin_method_bytecode`; `jarPath=` reads straight from a jar on disk |
| Pin a lookup to one module's classpath | `module=` (e.g. `common.main`) on `mixin_find_class`, `mixin_get_dep_source`, bytecode tools |
| Rename / delete / move / change-sig / extract / inline / move-members | see **Refactoring** |
| Re-sync Gradle after build-file or dependency changes | `mixin_sync_project` (waits for the import by default) |
| See whether the IDE is busy or the last sync failed | `mixin_ide_status` |
| Make on-disk edits by external tools visible to the IDE | `mixin_refresh_vfs` |
| Diagnose missing/empty source roots or check if jar X is attached | `mixin_list_source_roots` (`filter="jade"` matches label, jar name, coordinates, URL) |
| **Minecraft:** synthetic/lambda names, `@At(target)` owner | `mixin_class_bytecode(filter="synthetic")`, `mixin_method_bytecode` |
| **Minecraft:** cross-mod mixin conflicts on a target | `mixin_find_targeting_mixins` (`jarPath="<pack>/mods"` scans a pack's jars and jar-in-jars from bytecode) |
| **Minecraft:** convert a name between mapping namespaces | `mixin_mappings_lookup` |

## Examples

```
# Read one method from a large class instead of the whole file
mixin_find_class(className="com.google.common.collect.Iterables", methodName="partition")

# Grep dependency source, then read a hit (grouped by file with a url: line)
mixin_search_in_deps(regexPattern="computeIfAbsent\\(", pathPrefix="java/util/")
mixin_get_dep_source(url="<url from result>", lineNumber=42, linesBefore=10, linesAfter=20)

# Find every usage of a field, including string refs in mixin config JSON
mixin_find_references(className="net.minecraft.world.entity.LivingEntity", memberName="DATA_HEALTH_ID")
```

## Usage notes

**Searching dependency source** (`mixin_search_in_deps` / `mixin_get_dep_source`)
- `regexPattern` is Java regex — escape metacharacters (`addEffect\\(`, not `addEffect(`). Unescaped patterns return a fix hint.
- `fileMask` matches internal paths only (never jar names or Maven coordinates). Without `*`/`?`, it is a case-insensitive path substring (`apotheosis` also matches `compat/apotheosis/` in other jars; prefer `pathPrefix`). Only `*` and `?` are wildcards (`{Foo,Bar}*.java` is literal).
- `pathPrefix` is a case-insensitive logical path inside a root (`net/minecraft/`), never a URL or disk path.
- Broad searches without `fileMask` may time out — raise `timeout` to 20000–30000. On timeout the output names the root where scanning stopped so you can rerun with `roots=` pinned to it; unreadable files per root usually mean a jar changed on disk (run `mixin_refresh_vfs`).
- `roots="all"` scans in tier order: `game`, `library` (`-sources.jar`), `decompiled` (which also holds jar text resources: `mods.toml`, `fabric.mod.json`, lang, recipes, `*.mixins.json`; binary entries are skipped), `jdk`, `buildscript`, skipping paths an earlier tier already matched. Narrow with `roots="game"`, `"jdk"`, or `"buildscript"` instead of filtering output.
- Hit headers (`--- path [root] ---`) name the matching root; prefer `Library SOURCES` over decompiled. Set `contextLines` (3–10) to inline short matched bodies, or pass the `url:` line verbatim to `mixin_get_dep_source`.
- `mixin_get_dep_source` accepts `startLine`/`endLine` (1-based inclusive; either alone reads to EOF or from line 1), overriding the `lineNumber`/`linesBefore`/`linesAfter` window.

**Jars, resources, and off-classpath mods**
- **Which tools reach which jars:** Six tools accept any jar/folder on disk via `jarPath` (without waiting for indexing; omit `module` when using `jarPath`): `mixin_get_dep_source`, `mixin_list_jar_entries`, `mixin_search_bytecode`, `mixin_find_targeting_mixins`, `mixin_class_bytecode`, and `mixin_method_bytecode` (`mixin_search_bytecode` and `mixin_find_targeting_mixins` also read jar-in-jar). All other tools see only what the IDE indexes (project + classpath). `mixin_search_in_deps` also requires source per jar, which `genDependencySources` generates for the compile classpath only — runtime-only jars are skipped by design (its "Not searched" note reports this); declare a jar `compileOnly` to search its source, or use `mixin_search_bytecode(jar=…)` / `mixin_list_jar_entries(jar=…, regexPattern=…)`.
- `mixin_get_dep_source` resolves in precedence order: `url` > `jarPath` + `entry` > `className` > `path`. `className` resolves like `mixin_find_class` (attached sources → decompiled cache → IDE decompiler, with the same line numbers as `mixin_find_class(methodName=…)`). A `.class` entry addressed by `url` or `jarPath` is decompiled by the IDE; other binary entries report size only.
- `mixin_list_jar_entries` then `mixin_get_dep_source(jarPath=…, entry=…)` reads any jar on disk without build changes. `mixin_list_jar_entries(jarPath="<pack>/mods", fileMask="mods.toml", regexPattern="(?i)fzzy")` greps every jar in a folder ("which pack mods depend on X"); pass `jar=` to grep runtime-only classpath jars.
- `mixin_search_bytecode(patterns=["actuallyHurt"], jarPath="<pack>/mods")` finds every class in a jar, folder, or classpath (including nested jars) referencing or declaring that name, labeled by role: `declares method` (override or mixin handler), `extends`/`implements`, `method ref Owner.name descriptor`, class ref, or string/constant (mixin annotation targets appear here). Add `kinds=["declares"]` to keep only classes that declare or override the member.
- `mixin_find_targeting_mixins(className=…, methodName=…, jarPath="<pack>/mods")` reads bytecode across a pack (including nested jars) to list every mixin targeting a class and its injectors (`@Inject(method = …, at = @At(…)) -> handler`), catching private targets a caller search misses. Pre-26.x, names are matched across Mojang, intermediary, and Yarn for `mcVersion` (defaults to the project's).
- **Jar-in-jar** (nested under `META-INF/jarjar/` or `META-INF/jars/`): only `mixin_search_bytecode` and `mixin_find_targeting_mixins(jarPath=…)` can read inside nested jars directly. Never manually extract or unzip nested jars; to read, index, or write mixins against one, put it on the compile classpath with `zipTree` and run `mixin_sync_project` (keep the outer jar on `localRuntime` if needed in dev):
  ```groovy
  def burnt = file("../pack/mods/burnt-1.10.5.3-neoforge-1.21.1.jar")
  if (burnt.exists()) {
      compileOnly(files(zipTree(burnt).matching { include "META-INF/jarjar/burnt_basic-*.jar" }))
  }
  ```
- To make an off-classpath mod searchable in `mixin_search_in_deps`, decompile it via `./gradlew genDependencySources --jar ../pack/mods/jade.jar` (repeatable) or `mixinmcp { extraJars.from(…) }`, then `mixin_sync_project` (shown as `[ad hoc jar]` in `mixin_list_source_roots`).

**Reading a class** (`mixin_find_class`)
- Prefer `methodName=` / `fieldName=` over `includeSource=true` on large classes.
- Methods/Fields list only **directly declared** members. Utility classes often put their real API in **Nested classes** (`Tags.Blocks`, `Tags.Items`), which gives ready-to-paste follow-up calls. Inherited members show `(inherited from X)` — follow up on `X` or via `mixin_super_methods`.

**Usages, hierarchy, call graph**
- Disambiguate overloaded methods (on reference, hierarchy, override, and refactoring tools) with `parameterTypes=["Entity","DamageSource"]` (`[]` for parameterless) or `methodDescriptor="(L…;)Z"`. Ambiguity errors list overloads with copy-ready values.
- `mixin_find_references` returns runtime call sites plus string refs in annotations. `mixin_call_hierarchy` output is `owner#name(descriptor)` (paste-ready for `@At`), tags `[lambda]`/`[ctor]`/`[cycle]`, resolves lambdas through `INVOKEDYNAMIC`, and walks Java/Kotlin/Groovy/Scala via UAST; `maxResults` is a global budget (raise it or start at `maxDepth=1`). Callers appear once per calling method; multiple calls in one method show `[xN: ordinal 0 line L, ...]` for `@At(value="INVOKE", ordinal=K)` (`source order` means the class isn't built and ordinals may be off; `INVOKE owner X` is the bytecode owner `@At` must name).
- `mixin_super_methods` marks `[root declaration]` entries (usually the best mixin target); `mixin_find_overrides` is its downward mirror. In `mixin_type_hierarchy`, check both **Direct** and **Inherited** (`(from X)`/`(via X)`) interface sections; `direction="supers"` skips subtypes.

**Bytecode** (`mixin_class_bytecode` / `mixin_method_bytecode`)
- `INVOKE*` instructions show the **real owner class** (which can differ from the source-declared class due to devirtualisation, overrides, or mixins) — use that owner in `@At(target=…)`.
- `filter="synthetic"` reveals lambda/bridge names (`lambda$X$N`) and synthetic fields (`this$0`, `$VALUES`) hidden by decompiled source; it is the only way to target a lambda.
- `mixin_method_bytecode(regexPattern="forEachModifier")` returns only matching instructions with source lines and `[ordinal N of M]` for `@At(ordinal=…)`. A lambda name like `lambda\$register\$3` matches the `INVOKEDYNAMIC` creating it (`-> owner.lambda$register$3(…)`).

**Module pinning and classpath variants**
- `module=` (exact or dot-boundary suffix, e.g. `neoforge.main`) on `mixin_find_class`, `mixin_get_dep_source`, and bytecode tools pins resolution on multi-module classpaths (unknown names error with the available list) and checks compile visibility.
- `mixin_find_class` prints a `Modules:` line tagging non-compile scopes as `(RUNTIME)` or `(TEST)` (`compileOnly` stays untagged). If a class resolves without `module=` but errors with `module=<mixin's module>` ("not in the compile scope of module …"), it is `runtimeOnly` or test-only there and `compileJava` will fail — fix the Gradle dependency declaration, not the mixin.
- Without `module`, tools append a `Variants` block: byte-identical copies merge; patched copies show a structural diff tagged `[loader-patched]`, `[loom-remapped vanilla]`, or `[MDG/NeoForm-recompiled vanilla]` (two-vanilla diffs collapse to a count as toolchain noise; only `[loader-patched]` diffs are real changes). `mixin_method_bytecode` adds a `NOTE:` when a method differs in another variant.

**Minecraft: `net/minecraft/`, `net/minecraftforge/`, or `net/neoforged/` sources come back empty**
1. Run `mixin_list_source_roots` and read the MDG merged-jar auto-attach section.
2. Fix or report any auto-attach warning first; until attachment succeeds, every other fallback fights the wrong fire.
3. Sources genuinely missing for the toolchain? See `references/toolchains.md` for per-toolchain recovery (Loom/neo-loom `genSources`, NeoForge/Forge MDG `downloadAssets`, `genDependencySources --force`), then `mixin_sync_project`.
4. Last resort: `mixin_find_class(includeSource=true)` and `mixin_method_bytecode` work via PSI/classfile and do not need attached source roots.

**Minecraft: `mixin_mappings_lookup`**
- The input symbol must already be in the `from` namespace (e.g. `from="srg"` wants `net/minecraft/src/C_12_`, not the mojmap name). Member inputs include the owner (`net/minecraft/src/C_12_.m_8793_`); descriptor optional.
- MC version auto-detects from `gradle.properties`; pass `mcVersion` if that fails. Namespaces: mojmap / yarn / intermediary / srg / obf.

## Refactoring

Reference-aware across the project (including mixin config JSON and javadoc links), so prefer them over hand-editing with `edit_file` or `write_file`. Shared contract: `dryRun=true` previews the resolved target, per-file usage counts, and conflicts tagged `[library]` (usually a stale build-output jar) or `[source]`; `ignoreConflicts=true` proceeds anyway. Signature, extract, introduce, inline, and move-members tools are Java-source only.

- `mixin_rename`: class/method/field, plus `memberKind="parameter"`/`"local"` (via `variableName`). Unlike built-in `rename_refactoring`, it reports conflicts instead of silently discarding them. For mechanical text replacement across files, run a script via `run_command` and call `mixin_refresh_vfs` on the touched path instead.
- `mixin_safe_delete`: usage-checked across project and dependencies (overrides block as `[override]`); deleting a `@Mixin` class also removes its mixin config JSON entries; handles Kotlin (removes the declaration, leaves the file). `force` is an alias for `ignoreConflicts`.
- `mixin_move_file`: moves a class to a new package, updating the package declaration and all references (including mixin config JSON); broken package-private access is reported as a conflict.
- `mixin_change_signature`: `parametersJson` is a JSON-array string: `{"oldIndex":N}` keeps a parameter, `{"oldIndex":-1,"name":…,"type":…,"defaultValue":…}` adds one at every call site, and omitted indices are removed.
- `mixin_extract_method` / `mixin_introduce_variable`: address a `filePath` plus whole-line `startLine..endLine` range (`newMethodName` / `methodName` names the extracted method); pass `expression=<source text>` (and `occurrenceIndex`) for a sub-expression. A non-matching `expression` lists selectable expressions with positions.
- `mixin_inline`: `kind="method"|"field"|"local"`; refuses recursive methods, enum constants, and non-final fields with write usages.
- `mixin_move_members`: `direction="up"|"down"|"toClass"`, with `members` as `name` or `name#descriptor`. `"down"` pushes into every direct subclass (no `targetClassName`; requires at least one subclass). Moving members into a `@Mixin` class flags external references as blocking `[mixin]` conflicts.

## Mixin workflow

1. **Type hierarchy first.** Run `mixin_type_hierarchy` / `mixin_super_methods` before designing the `@Mixin` — the parent class often declares the method you actually want.
2. **Bytecode for synthetics and `@At(target)` owners.** Use `mixin_class_bytecode(filter="synthetic")` for `lambda$X$N` names, and `mixin_method_bytecode` for the real `INVOKE*` owner and `[ordinal N of M]`.
3. **After writing a mixin, verify twice.** Run `get_file_problems` (JetBrains MCP) to check `@At` targets, `method=` selectors, and `@Shadow` members (`javac` never checks these). Then build through the IDE (`build_project` or a run configuration), since the IDE index can resolve classes `compileJava` cannot see (`runtimeOnly` or another subproject's cache); check `mixin_find_class(className=<target>, module=<mixin's module>)` if compile scope is in doubt.
4. **After dependency changes**, run `./gradlew genDependencySources` then `mixin_sync_project`.

For injector selection (`@ModifyExpressionValue` vs `@WrapOperation` vs `@Inject` vs …), `@At` parameter format, and MixinExtras `@Expression` syntax, switch to the **mixin-writing** skill.

## Troubleshooting

**If `mixin_*` tools are not available:**
1. Ensure IntelliJ is running with the project open.
2. In IntelliJ, Settings → Plugins: verify both "MCP Server" and "MixinMCP" are enabled, and Settings → Tools → MCP Server has **Enable MCP Server** checked.
3. Verify the IntelliJ MCP server is connected in Antigravity: `agy mcp list` (or **Additional Options (`...`) → MCP Servers** in the UI) should show it. If not, use `agy mcp add jetbrains http://127.0.0.1:64342/stream` (using the port shown in IntelliJ's MCP Server settings) or add it to `~/.gemini/config/mcp_config.json`.
4. **Restart the Antigravity session** (or reconnect the MCP server) if IntelliJ started after the session began.
