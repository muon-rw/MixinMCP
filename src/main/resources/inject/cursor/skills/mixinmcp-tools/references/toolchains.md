# Toolchain reference: getting Minecraft, Forge, and NeoForge sources searchable

Consult this when `mixin_search_in_deps` returns nothing for `net/minecraft/`,
`net/minecraftforge/`, or `net/neoforged/`, or when `mixin_get_dep_source` cannot
resolve a vanilla path. The SKILL.md covers triage; this covers the per-toolchain
recovery commands and what each toolchain ships.

## Diagnose first

Always start with `mixin_list_source_roots`. It reports:
- Which Library SOURCES roots are attached
- Which roots came from MixinMCP's auto-attach pass
- Whether vanilla / Forge / NeoForge game-API canaries (e.g.
  `net/minecraft/world/level/Level.java`, `net/minecraftforge/event/entity/EntityEvent.java`,
  `net/neoforged/neoforge/event/Event.java`) resolve
- The last MDG merged-jar auto-attach run, including warnings

Auto-attach warnings are almost always the root cause when MDG searches come up empty.
Fix those before reaching for fallbacks; if you can't, include them verbatim in any
bug report.

## ForgeGradle (older, pre-MDG)

Vanilla MC sources are properly attached as Library SOURCES roots out of the box.
`mixin_search_in_deps` can grep `net/minecraft/` directly with no extra steps.

## ModDevGradle (MDG): vanilla mode, Forge, and NeoForge

Vanilla Minecraft (plus, on Forge/NeoForge, the loader game API) ships in a **merged JAR**
under `build/moddev/artifacts/`; naming varies by mode and version
(`vanilla-*-merged.jar`, `minecraft-patched-*-merged.jar`, `forge-*-merged.jar`,
`neoforge-*-minecraft-merged.jar`). MixinMCP auto-attaches the merged jar as a Library
SOURCES root after each Gradle sync. This covers MDG's NeoForm-only vanilla mode too,
as used by the common module of multiloader projects. When the merged jar has no `.java`
entries (MDG recompilation disabled), MixinMCP falls back to the sibling `*-sources.jar`
next to the merged jar, then to a Gradle-cache `*-sources.jar`
(`net.minecraftforge:forge` or `net.neoforged:neoforge`).

After auto-attach completes, `mixin_search_in_deps` is the primary tool for
`net/minecraft/`, `net/minecraftforge/`, and `net/neoforged/` paths.

If search is still empty:
1. Run `mixin_list_source_roots` and read the **MDG merged-jar source auto-attach** section.
2. If warnings appear there, fix or report those first; further fallbacks won't change anything until attachment succeeds.
3. Only then fall back to `mixin_find_class(includeSource=true)`, `mixin_method_bytecode`, or `mixin_search_symbols`.

## Fabric Loom

`./gradlew genSources` materialises Loom's own Minecraft sources jar; after
`mixin_sync_project`, IntelliJ attaches it as the vanilla Library SOURCES root.
Without genSources output, MixinMCP's `genDependencySources` decompiles the Loom
minecraft jar into the cache instead, so vanilla stays searchable either way. Once
the real sources jar appears, the decompiled copy is dropped automatically; the two
never produce duplicate results.

## Loom-style NeoForge/Forge (neo-loom, Architectury Loom)

Minecraft ships as regular `net.minecraft` GAV dependencies (`minecraft-fml`, a
patched `minecraft-merged`, and on newer versions a separate `neoforgeuniversal`
artifact) resolved from a loom-cache maven, NOT as MDG artifacts.
`mixin_list_source_roots` shows no "MDG merged artifacts" section on these projects;
that is expected, not a failure. Recovery is the Loom flow: `./gradlew genSources`
then `mixin_sync_project`, with `genDependencySources` covering the gap via
decompilation until then. NeoForge game API sources live inside `minecraft-merged`
on 1.21.x and in the `neoforgeuniversal` artifact on newer versions; the sentinel
checks scan every SOURCES root, so either layout passes.

## Corrupt or truncated downloads

Repositories without checksums (cursemaven) can leave truncated jars in the Gradle
cache; these surface as "artifact(s) could not be resolved" or as remap/decompile
failures. `genDependencySources` detects and purges them automatically, warning
"purged from the Gradle cache; re-sync to re-download". No manual cache surgery is
needed: re-syncing (or re-running the task) retries the download until it lands whole.

## "Sources are missing entirely" recovery

If `mixin_list_source_roots` shows no Minecraft root at all (nothing under
`net/minecraft/`, no merged jar, no `-sources.jar`):

| Toolchain     | Command                                                       |
| ------------- | ------------------------------------------------------------- |
| Loom toolchains (Fabric, Architectury, neo-loom) | `./gradlew genSources`     |
| NeoForge/Forge via MDG | `./gradlew downloadAssets`                           |
| Any loader    | `./gradlew genDependencySources --force` (needs `org.gradle.jvmargs=-Xmx4g`) |

Then run `mixin_sync_project` to refresh IntelliJ's project model.

## Notes on `mixin_get_dep_source` for vanilla paths

In MDG projects, vanilla Minecraft classes resolve via `path` once the merged jar is
auto-attached as Library SOURCES. If `path` fails, the issue is almost always
attachment, not the path itself; re-run `mixin_list_source_roots` and follow the
diagnose-first flow above. As a last resort, `mixin_find_class(includeSource=true)`
goes via PSI rather than the source roots and works even when attachment failed.
