<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MixinMCP Changelog

## [Unreleased]

## [1.0.4]

### Improved

- Adjusted UX for `mixin_super_methods`, output should now be more clear about which classes own which methods

## [1.0.3]

### Added

- `mixin_find_class` now accepts `methodName` and `fieldName`. When set, the tool returns only that method's (or field's) source plus the class header instead of dumping the whole file. Overloads are listed in order; if the name is only inherited, the inherited declaration is shown with an `(inherited from X)` tag.
- `mixin_search_in_deps` now accepts `contextLines` (default 0, max 200). When set, each match is rendered with N surrounding lines, overlapping windows merged per file. Match lines stay highlighted with `||markers||` and are prefixed with `>`; this captures short method bodies inline without a follow-up `mixin_get_dep_source` call.

### Improved

- Multi-project IDE setups now return a more actionable error listing the open project paths and reminding the agent to retry with the IntelliJ's auto-injected `projectPath` argument if they miss it
- Moved Fabric/Forge/Neo vanilla source attachment debugging out of the main mixinmcp-tools skill into its own reference

## [1.0.2]

### Added

- New tool `mixin_refresh_vfs`: Force-refresh IntelliJ's VFS so on-disk changes from external tools become visible. Useful for Claude Code
- Recommend setting the above in CLAUDE.md when using Claude Code if not using the Claude Code IntelliJ plugin

## [1.0.1]

### Improved

- Overall IDE performance while the MCP server is performing broad-scoped reads

## [1.0.0]

### Added

- New tool `mixin_find_overrides`: walks down to every overrider of a method across project and dependencies, complementing `mixin_super_methods` which walks up. Abstract overrides are tagged `[abstract]`; non-overridable methods short-circuit with an explanation.
- New `maxResults` parameter on `mixin_type_hierarchy`: caps the subclasses/implementors listing (default 50). Both `maxResults` and `maxDepth` are now validated instead of silently returning empty output.

### Improved

- `mixin_type_hierarchy` now lists transitively inherited interfaces, each tagged with origin: `from X` for interfaces pulled in by a superclass, `via X` for those reached by extending another interface. Results are deduplicated across the chain.
- `mixin_super_methods` now walks the full super-method chain instead of stopping at the direct super. Output is indented by depth, tags roots and interface entries, and deduplicates across diamond hierarchies.
- `mixin_call_hierarchy` callees now cover constructor calls (`new Foo(...)`), method references (`Foo::bar`, `Foo::new`), and the real synthetic target behind each lambda, resolved through the `INVOKEDYNAMIC`/`LambdaMetafactory` bootstrap handle. Output includes the JVM descriptor ready to paste into `@At(target="...")`; constructors are tagged `[ctor]` and synthetic lambdas (`lambda$X$N`) are tagged `[lambda]`.
- `mixin_call_hierarchy` `maxDepth` now works; it was previously ignored, so the tool always returned exactly one level. Both directions now recurse, with results indented per depth (`[L1]`, `[L2]`…) and cycles marked `[cycle]`. The parameter is validated to `1..10`; the default remains `3`.
- `mixin_call_hierarchy` now handles Kotlin and other JVM languages uniformly via UAST fallbacks. Kotlin/Groovy/Scala callers surface as proper `[L1]` entries instead of `(non-method context)` leaves, and callees of Kotlin source methods are walked via UAST when the Java-PSI body is null. This avoids falling through to bytecode, which often fails for uncompiled in-project source.

## [0.9.0]

### Added

- New tool `mixin_mappings_lookup` — converts class/method/field names between mojmap, yarn, intermediary, srg, and obf. 
- - MC version is auto-detected from `gradle.properties` if a version is not passed explicitly in the tool call. 
- - Mappings are downloaded on demand from canonical sources (Mojang launcher meta, Fabric Maven, Forge/NeoForge Maven) and cached under `~/.cache/mixinmcp/mappings/`, so conversions work across loaders without needing the project to have all mappings locally.

### Improved

- Improved Plugin description and made Setup guide in README easier to follow

### Fixed

- `mixin_find_targeting_mixins` missing injector types `@ModifyReceiver`, `@ModifyReturnValue` , `@WrapWithCondition`, `@WrapMethod`

## [0.8.2]

### Added

- Attempt to auto-attach MDG merged sources again post ide sync, just in-case MDG failed

### Improved

- Improved fallback/error messages for issues with MDG merged sources access

## [0.8.1]

### Added

- Fallback hints and skill notes for agents attempting to access Forge/Neoforge merged sources

### Improved

- Improve adherence to MCP tools over built-in system-prompt/Cursor server tools for stupider agents

## [0.8.0]

### Added

- **Claude Code support** — skill and rule injection now works with Claude Code in addition to Cursor
- Automatically clean up old injected rules on startup
- **`mixin_find_references` field support** — `memberName` now resolves fields (e.g. `DATA_HEALTH_ID`), not just methods
- **`mixin_search_in_deps` new parameters** — `pathPrefix` (e.g. `net/minecraft/`) to scope searches, `roots` to select `library`/`decompiled`/`all`; automatic deduplication when library sources and decompiled cache overlap
- **`mixin_call_hierarchy` bytecode fallback** — callees direction now extracts INVOKE targets from bytecode when the source body is unavailable (binary merged JAR classes)
- **`mixin_list_source_roots` toolchain detection** — detects MDG merged JARs, categorizes roots by type, and auto-detects whether vanilla MC is searchable or binary-only

### Fixed

- `mixin_find_class` SourceKind no longer labels MDG/Forge merged JARs as "Project source" — now correctly reports "MDG/Forge merged artifact"
- `mixin_type_hierarchy` no longer emits `null` entries for anonymous/inner classes in subclass listings
- `mixin_super_methods` / `mixin_call_hierarchy` / `mixin_find_references` no longer show duplicate identical overloads in disambiguation errors; now includes declaring class
- `mixin_method_bytecode` error for non-existent methods now shows fuzzy matches and truncates large method lists instead of dumping hundreds of names

### Improved

- Migrate rule injection to use skills + references instead of inline rules
- `mixin_search_in_deps` regex errors now include a hint suggesting which metacharacters to escape
- SKILL.md rewritten with toolchain-specific guidance (ForgeGradle vs MDG vs Fabric Loom), new parameter docs, and field reference examples

## [0.7.0]

- Rewrite decompilation cache to use proper content-based hash, fixing unnecessary re-decompilation
- Improve regex handling in tools
- Fix compileOnly dependencies without published source jars not being indexed or decompiled
- Fix runtimeOnly dependencies being decompiled and indexed
- Downgrade required JDK to 17

## [0.6.7]

- More mixin reference tips

## [0.6.6]

- Improve some default mixin reference tips in the injected ruleset

## [0.6.5]

- Mirror published `-sources.jar` into the decompilation cache, to ensure dependencies using
  transformed/remapped classpath jars are always searchable
- Also index `.kt` sources from published source jars

## [0.6.4]

- Fix issues with decompilation state
- Rename `mixinDecompile` gradle task -> `genDependencySources`
- Add `cleanSourcesCache`

## [0.6.3]

- Fix unresolvable dependencies preventing full gradle sync due to decompile task
- Show pop-up warning when some dependencies were not decompiled

## [0.6.2]

- Auto gitignore injected rules

## [0.6.1]

- Only refresh the cursor rules dir when injecting rules

## [0.6.0]

- Automatically inject recommended rules for Cursor into Minecraft projects on open
- Warn when opening a Minecraft project that doesn't contain the MixinMCP-Decompile plugin
- Clean up tool descriptions a bit more
- Renamed `mixin_debug_roots` ->  `mixin_list_source_roots`

## [0.5.2]

- Automatically run decompile on project sync
- Detect and warn if the decompile task is likely to fail due to OOM
- Properly skip sources jars for decompilation

## [0.5.1]

- Fix decompilation cache handling in multiloader environments
- Each Gradle subproject now writes its own manifest; decompiled output is shared via a global content-addressed store
- Replace destructive per-run orphan cleanup with 30-day time-based eviction of untouched cache entries
- IntelliJ plugin merges per-project manifests with backward-compatible fallback to the legacy global manifest

## [0.5.0]

- New tools for finding mixins affecting target class
- Improvements to error message feedback
- Improve detection of vanilla sources in cases of loader patching

## [0.4.3]

- Changes to Decompile plugin for compatibility

## [0.4.2]

- More tool documentation improvements

## [0.4.1]

- Minor optimizations to documentation/regex lookup

## [0.4.0]

- Decompilation cache is now handled via a gradle plugin - see the README
- Fixed all known issues with decompiled pseudo-source search resolution

## [0.3.1]

- Bump supported version to 2025.3.3

## [0.3.0]

- Add decompilation + cache

## [0.2.0]

- Add path-based file resolution
- Improve tool call error handling 
- Update tool documentation and README

## [0.1.0]

- Initial Alpha

[Unreleased]: https://github.com/muon-rw/MixinMCP/compare/1.0.4...HEAD
[1.0.4]: https://github.com/muon-rw/MixinMCP/compare/1.0.3...1.0.4
[1.0.3]: https://github.com/muon-rw/MixinMCP/compare/1.0.2...1.0.3
[1.0.2]: https://github.com/muon-rw/MixinMCP/compare/1.0.1...1.0.2
[1.0.1]: https://github.com/muon-rw/MixinMCP/compare/1.0.0...1.0.1
[1.0.0]: https://github.com/muon-rw/MixinMCP/compare/0.9.0...1.0.0
[0.9.0]: https://github.com/muon-rw/MixinMCP/compare/0.8.2...0.9.0
[0.8.2]: https://github.com/muon-rw/MixinMCP/compare/0.8.1...0.8.2
[0.8.1]: https://github.com/muon-rw/MixinMCP/compare/0.8.0...0.8.1
[0.8.0]: https://github.com/muon-rw/MixinMCP/compare/0.7.0...0.8.0
[0.7.0]: https://github.com/muon-rw/MixinMCP/compare/0.6.7...0.7.0
[0.6.7]: https://github.com/muon-rw/MixinMCP/compare/0.6.6...0.6.7
[0.6.6]: https://github.com/muon-rw/MixinMCP/compare/0.6.5...0.6.6
[0.6.5]: https://github.com/muon-rw/MixinMCP/compare/0.6.4...0.6.5
[0.6.4]: https://github.com/muon-rw/MixinMCP/compare/0.6.3...0.6.4
[0.6.3]: https://github.com/muon-rw/MixinMCP/compare/0.6.2...0.6.3
[0.6.2]: https://github.com/muon-rw/MixinMCP/compare/0.6.1...0.6.2
[0.6.1]: https://github.com/muon-rw/MixinMCP/compare/0.6.0...0.6.1
[0.6.0]: https://github.com/muon-rw/MixinMCP/compare/0.5.2...0.6.0
[0.5.2]: https://github.com/muon-rw/MixinMCP/compare/0.5.1...0.5.2
[0.5.1]: https://github.com/muon-rw/MixinMCP/compare/0.5.0...0.5.1
[0.5.0]: https://github.com/muon-rw/MixinMCP/compare/0.4.3...0.5.0
[0.4.3]: https://github.com/muon-rw/MixinMCP/compare/0.4.2...0.4.3
[0.4.2]: https://github.com/muon-rw/MixinMCP/compare/0.4.1...0.4.2
[0.4.1]: https://github.com/muon-rw/MixinMCP/compare/0.4.0...0.4.1
[0.4.0]: https://github.com/muon-rw/MixinMCP/compare/0.3.1...0.4.0
[0.3.1]: https://github.com/muon-rw/MixinMCP/compare/0.3.0...0.3.1
[0.3.0]: https://github.com/muon-rw/MixinMCP/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/muon-rw/MixinMCP/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/muon-rw/MixinMCP/commits/0.1.0
