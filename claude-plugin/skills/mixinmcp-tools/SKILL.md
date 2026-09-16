---
name: mixinmcp-tools
description: >
  IntelliJ-backed code search and refactoring across a JVM project's entire
  classpath: any dependency, library, JDK source, or Gradle plugin, and on Minecraft
  projects, vanilla and mod sources. ALWAYS use these tools instead of Grep, Read, or
  jar extraction to look up or search code that lives in a dependency: they index
  inside jars Grep cannot see, and add type-hierarchy, call-graph, reference, and
  bytecode tools that beat text search on accuracy and context cost. Use for
  class/method/field lookup, finding usages/implementations/overrides, inheritance
  and call graphs, reading dependency source, bytecode inspection, and
  reference-aware refactors (rename, safe-delete, extract, inline, change-signature,
  move). On Minecraft projects also use for mixin work: @At(target) owners,
  synthetic/lambda names, mapping-namespace conversion, and cross-mod conflicts.
---

# MixinMCP Tools

These tools come from the IntelliJ MCP server and search a JVM project's whole
classpath, including inside dependency jars that Grep cannot see. They add dedicated
type-hierarchy, call-graph, reference-finding, and bytecode tools that are faster and
more accurate than text search, and return structured results that cost far less
context than raw file dumps. Dependencies without published sources are decompiled
(Vineflower, via the MixinMCP Gradle plugin) so every library on the classpath is
searchable.

**Prefer these over Grep, Read, or jar extraction for anything on the classpath.** The
search, hierarchy, bytecode, and refactoring tools work on any Gradle/Maven JVM project.
The mapping, mixin, and Minecraft-toolchain features are used only on Minecraft projects.

## Invoking

Call them as regular MCP tools once IntelliJ is connected, e.g.
`mixin_find_class(className="java.util.concurrent.ConcurrentHashMap")`. Each tool's
description documents its parameters and defaults; read it before calling rather than
guessing.

Two names look alike across tools but are not interchangeable: `path` is a
classpath-relative source path, `filePath` a file on disk, and `url` a `jar://` or
`file://` URL, so do not carry one into a parameter expecting another. `memberName`
takes a method or field name on tools that declare no `methodName` / `fieldName`.

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
| See what is inside a jar | `mixin_list_jar_entries` (`jarPath` for any jar on disk, `jar` for a classpath jar by name or coordinates) |
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
| Diagnose missing or empty source roots | `mixin_list_source_roots` |
| Answer "is jar X attached?" | `mixin_list_source_roots(filter="jade")` (label, jar name, coordinates, URL) |
| **Minecraft:** synthetic/lambda names, `@At(target)` owner | `mixin_class_bytecode(filter="synthetic")`, `mixin_method_bytecode` |
| **Minecraft:** cross-mod mixin conflicts on a target | `mixin_find_targeting_mixins` |
| **Minecraft:** convert a name between mapping namespaces | `mixin_mappings_lookup` |

## Examples

Read one method from a large class instead of the whole file:
```
mixin_find_class(className="com.google.common.collect.Iterables", methodName="partition")
```

Grep dependency source, then read a hit (results are grouped by file with a `url:` line):
```
mixin_search_in_deps(regexPattern="computeIfAbsent\\(", pathPrefix="java/util/")
mixin_get_dep_source(url="<url from result>", lineNumber=42, linesBefore=10, linesAfter=20)
```

Find every usage of a field, including string refs in mixin config JSON:
```
mixin_find_references(className="net.minecraft.world.entity.LivingEntity", memberName="DATA_HEALTH_ID")
```

## Usage notes

Only what isn't obvious from the tool descriptions.

Every `mixin_*` tool rejects parameter names it does not declare and lists the accepted ones; nothing is silently ignored. An unknown-parameter error means the call is wrong, not the tool: use the name it suggests. Passing two spellings of the same parameter together is an error.

Every tool except `mixin_sync_project`, `mixin_refresh_vfs`, `mixin_ide_status`, and `mixin_mappings_lookup` waits up to 30s for a busy IDE (indexing, a Gradle resolve, the project-data import) and then errors naming what it is waiting on. That is not a failed lookup: retry, or call `mixin_ide_status` / `mixin_sync_project(wait=true)` first.

**Searching dependency source** (`mixin_search_in_deps` / `mixin_get_dep_source`)
- `regexPattern` is Java regex — escape metacharacters (`addEffect\\(`, not `addEffect(`). Unescaped, the tool hints the fix.
- `fileMask` without wildcards is a case-insensitive substring on the path, so a short fragment like `apotheosis` also matches `compat/apotheosis/` inside other jars; use a longer fragment or `pathPrefix`. It never matches jar names or Maven coordinates. Everything that is not `*` or `?` is literal, so `{Foo,Bar}*.java` matches that text, not an alternation.
- `pathPrefix` is a logical path inside a root (`net/minecraft/`, case-insensitive), not a URL or a disk path; passing one of those is an error with a hint.
- Broad searches with no `fileMask` can time out — raise `timeout` to 20000–30000.
- Each hit's `--- path [root] ---` header names the matching root; prefer `Library SOURCES` over decompiled. Copy the `url:` line verbatim into `mixin_get_dep_source`.
- `contextLines` (3–10) captures a short matched body inline so you can skip the follow-up read; leave it 0 for long methods.
- `mixin_get_dep_source` takes that `url` verbatim; a package `path` (`.../Foo.java`, not a filesystem path) works too, and if it misses, search first and use the returned `url`.
- To read a known span, pass `startLine`/`endLine` (inclusive, 1-based); they override the `lineNumber`/`linesBefore`/`linesAfter` window. `startLine` alone reads to end of file, `endLine` alone from line 1.
- `roots="all"` scans game roots, then other library `-sources.jar` roots, then the decompiled cache, then the JDK, then the buildscript classpath, skipping paths an earlier tier already matched. Narrow with `roots="game"`, `"jdk"`, or `"buildscript"` instead of filtering the output.
- Decompiled-cache roots also hold the jar's resources, so `pathPrefix="assets/"` or `fileMask="mods.toml"` greps `mods.toml`, `fabric.mod.json`, lang files, recipes, and `*.mixins.json`. Binary entries are skipped.
- The output reports files that could not be read per root (usually a jar that changed on disk: run `mixin_refresh_vfs`), and on timeout names the root the scan stopped in, so you can rerun with `roots=` pinned to it.

**Jars, resources, and off-classpath mods**
- `mixin_get_dep_source` addresses a file four ways, in precedence order: `url`, `jarPath` + `entry`, `className`, `path`. `className` resolves the same way `mixin_find_class` does, so it is the shortest route to a class whose path you don't know.
- `mixin_list_jar_entries` then `mixin_get_dep_source(jarPath=…, entry=…)` reads any jar on disk, on the classpath or not, with no build change: the fastest way to check a mod's `mods.toml`, mixin config, or lang file. A `.class` entry addressed by `url` or `jarPath` is decompiled by the IDE; other binary entries report their size only.
- `jarPath` on `mixin_class_bytecode` / `mixin_method_bytecode` does the same for bytecode, and never waits for indexing. Omit `module` with it.
- To make an off-classpath mod searchable rather than readable one file at a time, decompile it: `./gradlew genDependencySources --jar ../pack/mods/jade.jar` (repeatable) or `mixinmcp { extraJars.from(…) }` in the build script, then `mixin_sync_project`. Those roots show as `[ad hoc jar]` in `mixin_list_source_roots`.

**Reading a class** (`mixin_find_class`)
- For big classes prefer `methodName=`/`fieldName=` over `includeSource=true` (which dumps 50KB+ and forces extra grep).
- Methods/Fields list only members declared **directly** on the class. Utility classes often hide their real API in **nested classes** (e.g. `Tags.Blocks`, `Tags.Items`), so the outer class looks empty; always read the Nested classes section, which gives ready-to-paste follow-up calls. Inherited members show `(inherited from X)` — follow up on `X` or via `mixin_super_methods`.

**Usages, hierarchy, call graph**
- Disambiguate overloaded methods (for `mixin_find_references` / `_call_hierarchy` / `_super_methods` / `_find_overrides` / `_rename` / `_safe_delete` / `_change_signature` / `_inline`) with `parameterTypes=["Entity","DamageSource"]` or `methodDescriptor="(L…;)Z"` (parameterless: `parameterTypes=[]`). On failure the error lists overloads with ready-to-copy values.
- `mixin_find_references` returns runtime call sites plus string refs in annotations. `mixin_call_hierarchy` output is `owner#name(descriptor)` (paste-ready for `@At`), tags `[lambda]`/`[ctor]`/`[cycle]`, resolves lambdas through the INVOKEDYNAMIC handle, and walks Java/Kotlin/Groovy/Scala via UAST; `maxResults` is a global budget, so raise it for wide graphs or start at `maxDepth=1`.
- `mixin_super_methods` marks `[root declaration]` entries — usually the best mixin target; `mixin_find_overrides` is its downward mirror. `mixin_type_hierarchy`: check both **Direct** and **Inherited** interface sections (inherited tagged `(from X)`/`(via X)`) before concluding a class doesn't implement something; `direction="supers"` skips the subtype list.

**Bytecode** (`mixin_class_bytecode` / `mixin_method_bytecode`)
- INVOKE* instructions show the **real owner class**, not the source-declared one — use that owner in `@At(target=…)`.
- `filter="synthetic"` reveals lambda/bridge names (`lambda$X$N`) and synthetic fields (`this$0`, `$VALUES`) that decompiled source hides; it is the only way to target a lambda.

**Module pinning and classpath variants**
- Multi-module classpaths carry several copies of a class. `module=` (exact or dot-boundary suffix, e.g. `neoforge.main`) on `mixin_find_class`/`_get_dep_source`/bytecode tools pins resolution; unknown names error with the available list.
- `module=` is also a compile-visibility check. `mixin_find_class` prints a `Modules:` line naming the modules whose classpath provides the class, tagging any non-compile scope (`neoforge.main, common.main (RUNTIME)`). A class that resolves without `module=` but errors with it ("not in the compile scope of module …") is declared `runtimeOnly` or test-only there, so a mixin in that module will fail the Gradle build however clean the inspections look; fix the dependency declaration, not the mixin.
- Without `module`, tools append a Variants block: byte-identical copies merge; patched copies get a structural diff tagged by provenance (`[loader-patched]`, `[loom-remapped vanilla]`, `[MDG/NeoForm-recompiled vanilla]`). Two-vanilla diffs collapse to a count (toolchain noise); only `[loader-patched]` diffs are real changes. `mixin_method_bytecode` adds a `NOTE:` when a method differs in another variant.

**Minecraft: `net/minecraft/`, `net/minecraftforge/`, or `net/neoforged/` sources come back empty**
1. Run `mixin_list_source_roots` and read the MDG merged-jar auto-attach section.
2. Fix or report any auto-attach warning first; until attachment succeeds, every other fallback fights the wrong fire.
3. Sources genuinely missing for the toolchain? See `references/toolchains.md` for per-toolchain recovery (Loom/neo-loom `genSources`, NeoForge/Forge MDG `downloadAssets`, `genDependencySources --force`), then `mixin_sync_project`.
4. Last resort: `mixin_find_class(includeSource=true)` and `mixin_method_bytecode` work via PSI/classfile and do not need attached source roots.

**Minecraft: `mixin_mappings_lookup`**
- The input symbol must already be in the `from` namespace (e.g. `from="srg"` wants `net/minecraft/src/C_12_`, not the mojmap name). Member inputs include the owner (`net/minecraft/src/C_12_.m_8793_`); descriptor optional.
- MC version auto-detects from `gradle.properties`; pass `mcVersion` if that fails. Namespaces: mojmap / yarn / intermediary / srg / obf.

## Refactoring

Reference-aware: they update or check every reference project-wide (including mixin
config JSON and javadoc links), so prefer them over hand-editing, which fixes one site
and leaves the rest dangling. Shared contract: `dryRun=true` reports the resolved target,
per-file usage counts, and conflicts without changing anything; conflicts are listed one
per line tagged `[library]`/`[source]` (`[library]` usually means a stale build-output
jar, not a real clash); `ignoreConflicts=true` proceeds anyway, the headless equivalent
of the IDE's "Continue". One exception: `mixin_move_file` takes neither flag (all its
checks run up front; there is no preview).
The signature/extract/introduce/inline/move-members tools are Java-source only. Read
each tool's description for parameters; below is only what isn't obvious there.

- `mixin_rename`: class/method/field, plus `memberKind="parameter"`/`"local"` (with `variableName` naming the variable inside the method). Unlike the built-in `rename_refactoring`, it reports conflicts instead of silently discarding them.
- `mixin_safe_delete`: usage-checked across project and dependencies; overrides count as blocking usages tagged `[override]`; handles Kotlin (a Kotlin class delete removes the declaration but leaves the file). `force` is accepted as an alias of `ignoreConflicts`.
- `mixin_move_file`: moves a class to a new package, updating the package declaration and every reference (including mixin config JSON).
- `mixin_change_signature`: `parametersJson` is a JSON-array string: `{"oldIndex":N}` keeps a parameter, `{"oldIndex":-1,"name":…,"type":…,"defaultValue":…}` adds one (inserted at every call site), omitted parameters are removed.
- `mixin_extract_method` / `mixin_introduce_variable`: `newMethodName` (alias `methodName`) names the method being created; both address a `filePath` plus `startLine..endLine` range, taken as whole lines; `expression=<its source text>` targets a sub-expression within it, `occurrenceIndex` picks among repeats. A non-matching `expression` lists the selectable expressions with their positions, so let a miss tell you the exact text instead of guessing.
- `mixin_inline`: `kind="method"|"field"|"local"`; refuses recursive methods, enum constants, and non-final fields with write usages.
- `mixin_move_members`: `direction="up"|"down"|"toClass"`; `members` are `name` or `name#descriptor`. `direction="down"` pushes into every direct subclass and takes no `targetClassName`; it refuses when the class has no direct subclass. Moving members *into* a `@Mixin` class flags external references to them as blocking `[mixin]` conflicts, since mixin members can't be referenced from ordinary classes at runtime.
- `mixin_rename` is for reference-following renames. A mechanical text rewrite across many files (one string replaced everywhere, a generated block regenerated) is script territory instead: run the script, then `mixin_refresh_vfs` on the touched path so the IDE sees it.

## Mixin workflow

1. **Type hierarchy first.** Run `mixin_type_hierarchy` before designing the @Mixin. The target's parent often declares the method you actually want; injecting into the subclass means rewriting once you find the real declaration site.
2. **Bytecode for synthetics.** Decompiled source hides lambda and bridge names; `mixin_class_bytecode(filter="synthetic")` is the only way to get the `lambda$X$N` symbol for `method = "…"`.
3. **Bytecode owner for `@At(target)`.** The owner in an INVOKE instruction can differ from the source-declared class (devirtualisation, override resolution, mixin re-application). It is what runs at injection time, so read it from `mixin_method_bytecode`.
4. **Origin questions.** `mixin_super_methods` walks up to the original declaration; `mixin_find_overrides` walks down to every implementation.
5. **After writing a mixin, verify twice.** `get_file_problems` (JetBrains MCP) checks that the injectors resolve: `@At` targets, `method=` selectors, `@Shadow` members. The compiler never checks any of that, so it is not optional. Then build through the IDE (`build_project`, or a Gradle run configuration): the IDE model can resolve a class `compileJava` cannot see (a `runtimeOnly` dependency, or a jar another subproject's decompile cache made visible), so a clean inspection is not a compile proof. If `mixin_find_class(className=<target>, module=<the mixin's module>)` answers "not in the compile scope", fix the Gradle declaration, not the mixin.
6. **After dependency changes**, run `./gradlew genDependencySources` then `mixin_sync_project` so the index matches the new classpath. It waits for the import by default and reports the outcome; a failure names the build error.

For injector selection (`@ModifyExpressionValue` vs `@WrapOperation` vs `@Inject` vs …),
`@At` parameter format, and MixinExtras `@Expression` syntax, switch to the
**mixin-writing** skill once the tools above have pinned down the target.

**Note:** the IntelliJ MCP framework auto-injects a `projectPath` parameter into every
tool. Pass it when multiple IDE windows are open; with one window it can be omitted. An
ambiguous project produces an error listing the candidate paths.

## Troubleshooting

**If `mixin_*` tools are not available:**
1. Ensure IntelliJ is running with the project open.
2. In IntelliJ, Settings → Plugins: verify both "MCP Server" and "MixinMCP" are enabled.
3. Verify the IntelliJ MCP server is connected in Claude Code: `claude mcp list` should show it. If not, use IntelliJ's Auto-Configure (Settings → Tools → MCP Server) or `claude mcp add`.
4. **Restart Claude Code** if IntelliJ started after the Claude Code session began.
