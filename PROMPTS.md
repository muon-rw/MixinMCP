# Decompilation Cache Implementation Prompts

Three sequential prompts for implementing DESIGN.md Section 11.
Each prompt builds on the previous one's output.

---

## Prompt 1 of 3: Cache Service + Vineflower Decompilation

```
Read DESIGN.md Section 11 (Decompilation Cache) fully before starting. This is prompt
1 of 3 implementing that section.

Goal: Create the DecompilationCacheService — a project-level service that enumerates
library JARs without sources, decompiles them via Vineflower, and manages the file
cache on disk.


### Step A: Add Vineflower dependency

In build.gradle.kts, add:
  implementation("org.vineflower:vineflower:1.11.2")

Make sure it doesn't conflict with IntelliJ's bundled Fernflower (different package
namespace, should be fine — but verify the dependency resolves cleanly).


### Step B: Create the cache manifest model

Create src/main/kotlin/dev/mixinmcp/cache/DecompilationManifest.kt

- @Serializable data class with a map of artifact-hash → CacheEntry.
- CacheEntry fields: libraryName, classesJarPath, jarSize, jarModified, cachePath,
  decompilerVersion, createdAt (all documented in DESIGN.md Section 11.5 Step 1).
- Methods: load(cacheRoot: Path), save(cacheRoot: Path), isValid(entry, jarFile).
- Artifact hash = SHA-256 of "$jarPath|$jarSize|$jarLastModified" (fast, no content
  hashing).
- Use kotlinx-serialization-json (already a project dependency) for serialization.


### Step C: Create DecompilationCacheService

Create src/main/kotlin/dev/mixinmcp/cache/DecompilationCacheService.kt

This is a project-level service (@Service(Service.Level.PROJECT)).

Key method: refreshCache(project: Project)

1. Enumerate libraries needing decompilation (DESIGN.md Section 11.5 Step 2):
   - Iterate ModuleManager.getInstance(project).modules
   - For each: ModuleRootManager.orderEntries → filter LibraryOrderEntry where
     library.getFiles(OrderRootType.SOURCES) is empty AND
     library.getFiles(OrderRootType.CLASSES) is non-empty.
   - Skip JdkOrderEntry instances.
   - Collect as list of (libraryName: String, classesJarPaths: List<String>).

2. For each JAR, check manifest. If hash matches existing entry → skip.

3. For cache misses, decompile via Vineflower (DESIGN.md Sections 11.5 Step 3 and
   11.7):

       val decompiler = Decompiler.builder()
           .inputs(jarFile)
           .output(DirectoryResultSaver(cacheDir))
           .option(IFernflowerPreferences.REMOVE_SYNTHETIC, "0")
           .logger(IFernflowerLogger.NO_OP)
           .build()
       decompiler.decompile()

4. Update manifest after each successful decompilation.

5. Delete orphaned cache entries (JAR removed or hash changed).

Key method: getCachedRoots(): List<CachedLibraryInfo>

Returns the list of currently-valid cache entries with their VirtualFile root dirs.
This will be consumed by the AdditionalLibraryRootsProvider in Prompt 2.

Threading: refreshCache() will be called from a background task. All file I/O and
Vineflower calls happen on the calling thread (no EDT). ReadAction is needed only for
the library enumeration step.

Cache location: ~/.cache/mixinmcp/decompiled/ — use
System.getProperty("user.home") + "/.cache/mixinmcp/decompiled".


### What NOT to do in this prompt

- Do NOT create the AdditionalLibraryRootsProvider (Prompt 2).
- Do NOT create sync triggers/listeners (Prompt 2).
- Do NOT modify existing tools (Prompt 3).
- Do NOT modify plugin.xml yet (Prompt 2).


### Acceptance criteria

- build.gradle.kts compiles with Vineflower dependency.
- DecompilationManifest can round-trip serialize/deserialize.
- DecompilationCacheService.refreshCache() can enumerate libraries, invoke Vineflower,
  and write .java files to the cache directory.
- getCachedRoots() returns valid entries.
- No linter errors in new files.
```

---

## Prompt 2 of 3: Provider + Triggers + Registration

```
Read DESIGN.md Section 11 (Decompilation Cache) fully before starting. This is prompt
2 of 3. Prompt 1 already created:
  - dev.mixinmcp.cache.DecompilationManifest
  - dev.mixinmcp.cache.DecompilationCacheService

Read those files before starting to understand the API surface.

Goal: Wire the cache into IntelliJ — expose cached sources via
AdditionalLibraryRootsProvider, trigger cache refresh on sync and project open,
and register everything in plugin.xml.


### Step A: Create MixinDecompiledRootsProvider

Create src/main/kotlin/dev/mixinmcp/cache/MixinDecompiledRootsProvider.kt

Extends AdditionalLibraryRootsProvider. Implementation:

- getAdditionalProjectLibraries(project): Query
  DecompilationCacheService.getInstance(project).getCachedRoots(). For each cached
  entry, return a SyntheticLibrary:

      SyntheticLibrary.newImmutableLibrary(
          comparisonId,       // "mixinmcp-decompiled-<artifact-hash>"
          sourceRoots,        // listOf(cacheDir as VirtualFile)
          emptyList(),        // no binary roots
          emptySet(),         // no exclusions
          excludeCondition    // ExcludeFileCondition that only includes .java files
      )

- Use VirtualFileManager.getInstance().findFileByNioPath() to convert cache Path to
  VirtualFile.
- Override equals/hashCode on any custom SyntheticLibrary subclass (the Javadoc on
  SyntheticLibrary.equals() explains why — Project View performance). Using
  newImmutableLibrary with comparisonId handles this automatically.
- getRootsToWatch(project): Return the cache root directories so VFS picks up
  external changes.


### Step B: Create MixinDecompileCacheSyncListener

Create src/main/kotlin/dev/mixinmcp/cache/MixinDecompileCacheSyncListener.kt

Implements ExternalSystemTaskNotificationListener. On onSuccess():
- Check if the task is a project resolve/sync (not a regular Gradle task run).
- Launch cache refresh in background:

      ProgressManager.getInstance().run(object : Task.Backgroundable(
          project, "MixinMCP: Decompiling dependencies...", true
      ) {
          override fun run(indicator: ProgressIndicator) {
              DecompilationCacheService.getInstance(project).refreshCache(project)
              // After refresh, notify IntelliJ to re-query the provider
              ApplicationManager.getApplication().invokeLater {
                  ApplicationManager.getApplication().runWriteAction {
                      AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                          project, null, emptyList(), newRoots, "mixinmcp-decompiled"
                      )
                  }
              }
          }
      })

See DESIGN.md Section 11.8 for the exact fireAdditionalLibraryChanged API.


### Step C: Create MixinDecompileCacheStartupActivity

Create src/main/kotlin/dev/mixinmcp/cache/MixinDecompileCacheStartupActivity.kt

Implements ProjectActivity (suspend fun execute(project: Project)).

On project open:
- Check if manifest exists and has entries → attach existing cached roots immediately
  (via fireAdditionalLibraryChanged).
- Queue a background task to run refreshCache() for any new/changed JARs.


### Step D: Update plugin.xml

Add these registrations (note: AdditionalLibraryRootsProvider uses the default
com.intellij namespace, NOT com.intellij.mcpServer):

    <extensions defaultExtensionNs="com.intellij">
        <additionalLibraryRootsProvider
            implementation="dev.mixinmcp.cache.MixinDecompiledRootsProvider"/>
        <externalSystemTaskNotificationListener
            implementation="dev.mixinmcp.cache.MixinDecompileCacheSyncListener"/>
        <postStartupActivity
            implementation="dev.mixinmcp.cache.MixinDecompileCacheStartupActivity"/>
    </extensions>

Note: If ProjectActivity requires a different registration than postStartupActivity,
check the IntelliJ 2025.2 API. The key requirement is that it runs after the project
model is initialized.


### What NOT to do in this prompt

- Do NOT modify MixinMcpToolset.kt or any existing tools (Prompt 3).
- Do NOT modify DecompilationCacheService or DecompilationManifest unless needed to
  support the provider (e.g., adding a method to get the list of new roots for the
  fire call).


### Acceptance criteria

- MixinDecompiledRootsProvider returns SyntheticLibrary instances for cached entries.
- Sync listener triggers cache refresh after Gradle sync.
- Startup activity attaches existing cache on project open.
- plugin.xml registers all three new components.
- fireAdditionalLibraryChanged is called correctly (under write lock).
- No linter errors in new files.
```

---

## Prompt 3 of 3: Tool Modifications

```
Read DESIGN.md Section 11 (Decompilation Cache) fully before starting — especially
Sections 11.4 (architecture diagram showing which tools need changes) and 11.9 item 4
(tool modification scope). This is prompt 3 of 3.

Prompts 1-2 already created the decompilation cache infrastructure:
  - dev.mixinmcp.cache.DecompilationCacheService
  - dev.mixinmcp.cache.DecompilationManifest
  - dev.mixinmcp.cache.MixinDecompiledRootsProvider
  - dev.mixinmcp.cache.MixinDecompileCacheSyncListener
  - dev.mixinmcp.cache.MixinDecompileCacheStartupActivity

Read MixinDecompiledRootsProvider.kt to understand how it exposes cached sources.
Read MixinMcpToolset.kt fully before making changes.

Goal: Update the two tools that iterate library SOURCES roots — mixin_search_in_deps
and mixin_get_dep_source — so they also search decompiled sources from the
SyntheticLibrary roots. Also update mixin_sync_project to trigger cache refresh.


### Step A: Extract a shared helper for collecting all source roots

Currently mixin_search_in_deps iterates:
  ModuleManager → modules → orderEntries → LibraryOrderEntry →
  library.getFiles(OrderRootType.SOURCES)

Create a private helper method in MixinMcpToolset:

    private fun collectAllSourceRoots(project: Project): List<VirtualFile>

This method should:
1. Collect SOURCES roots from library order entries (existing logic).
2. ALSO collect source roots from synthetic libraries:
   AdditionalLibraryRootsProvider.EP_NAME.extensionList
       .flatMap { it.getAdditionalProjectLibraries(project) }
       .flatMap { it.sourceRoots }
3. Return the combined, deduplicated list.

All of this must happen inside a ReadAction.


### Step B: Update mixin_search_in_deps

Refactor to use the new collectAllSourceRoots() helper instead of directly iterating
module order entries. The regex matching logic stays the same — only the source of
VirtualFile roots changes.

Keep the existing behavior for real SOURCES jar roots unchanged. The decompiled files
are plain .java files in directories, so the same VirtualFile traversal and content
reading logic works.


### Step C: Update mixin_get_dep_source

The locateDepSourceByPath() private method currently only searches
OrderRootType.SOURCES on library order entries. Update it to also search synthetic
library source roots (same pattern as Step A).

When the tool returns results from decompiled sources, the URL will be a file:// path
into the cache directory instead of a jar:// URL. This is fine — the tool already
handles both URL and path-based lookups.


### Step D: Update mixin_sync_project

After triggering Gradle sync, also trigger the decompilation cache refresh. Add a
note in the tool's response that decompilation cache will refresh in the background.

This can be as simple as adding after the sync trigger:

    DecompilationCacheService.getInstance(project).scheduleRefresh(project)

(or whatever the public API from Prompt 1 is — read the file to check).


### What NOT to do in this prompt

- Do NOT modify DecompilationCacheService, the manifest, or the provider.
- Do NOT change any tool's MCP-visible API (parameter names, descriptions, etc.)
  unless strictly necessary.
- Do NOT add new MCP tools. The goal is that existing tools transparently gain
  coverage of decompiled sources.


### Acceptance criteria

- mixin_search_in_deps finds regex matches in decompiled sources from the cache.
- mixin_get_dep_source can read files from the cache by URL or path.
- mixin_sync_project triggers cache refresh after Gradle sync.
- Existing behavior for real -sources.jar files is unchanged.
- No linter errors in modified files.
```
