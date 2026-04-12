<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MixinMCP Changelog
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
