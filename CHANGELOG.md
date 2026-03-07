<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MixinMCP Changelog
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
