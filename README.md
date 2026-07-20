# MixinMCP

<!-- Plugin description -->
Extends IntelliJ's built-in MCP Server with tools for
Minecraft mod development, with the goal of improving mixin writing and inter-mod compatibility. 

GitHub:
https://github.com/muon-rw/MixinMCP

For all features, you will also need the Gradle plugin - see https://github.com/muon-rw/MixinMCP#setup

## Key features:
### 1. Broad-scoped search:
- Full type hierarchy: supertypes, subtypes, and all implementations of an interface or abstract class
- Find all overrides of a method, plus its original super-method declaration
- Call hierarchy: callers and callees of any method
- All references to a class, method, or field
- All `@Mixin` classes targeting a given class method. Helps identify cross-mod conflicts
- Symbol search by name pattern, plus regex grep across all dependency sources

**Regex search across your *entire* classpath including dependencies:**

- Alternative tools have a central problem: You can not perform broad, text-based search like regex on files that aren't in your project's own source. 
This is exactly the use case that Minecraft development (and mixin writing) needs so desperately - 100 other projects might be interacting with what you are, and you need to know what and how.
While other tools might be able to read individual files in Minecraft sources, loader or mod APIs, libraries, or other mods you've added for integration or compatibility, they don't have a good way to *search through them*.
- With this plugin, agents can easily scan the whole project dependency network on their own, including dependencies without published sources. 
- Circumvents the need to manually copy and paste snippets into context, or for agents to find jars in your gradle cache and unzip/analyze them manually one by one. 

### 2. Class/Method Bytecode lookup:
- Get the actual compiled bytecode for a given class or method
- Useful to find a precise target when writing mixins, especially for ordinals or synthetic lambdas

### 3. Reference-aware refactoring:
- Rename, safe-delete, move, inline, extract, change signatures, and move members, with every reference updated project-wide in one atomic operation
- Update non-compiled references: mixin config JSON entries, `mods.toml`, ServiceLoader files, and javadoc links
- Conflicts are reported per file instead of just discarded. Most tools support a dry-run preview

### 4. Built-in Agent Skills for enhanced Mixin Writing:
- Improve compatibility of written mixins by favoring MixinExtras injectors which LLMs often hallucinate or fail to use in the first place
- Favor precise modification for the exact target for the task without workarounds, slices, or shift by's, thanks to MixinExtras' robust `@Expression` annotation

### 5. Automatic Mappings lookup:
- Easily convert any class, method, or field name between SRG, Intermediary, Yarn, Mojmap, and obf
- Mappings are downloaded on demand (Mojang launcher meta, Fabric Maven, Forge/NeoForge Maven) and cached under `~/.cache/mixinmcp/mappings/` 
- This allows you to retrieve mapping data for (almost) any version or loader, even those not present in the current project
<!-- Plugin description end -->

## Setup

MixinMCP has two parts. You need **both** for full-classpath search to work:

- **IntelliJ plugin**: registers the `mixin_*` tools on IntelliJ's built-in MCP Server.
- **Gradle plugin**: decompiles dependencies that don't publish sources so the search tools cover every JAR on your classpath.

**Prerequisites:** IntelliJ IDEA 2026.2+

### 1. Install the IntelliJ plugin

**From IntelliJ**: **Settings → Plugins → Marketplace** → search "MixinMCP" → **Install**

### 2. Enable IntelliJ's MCP Server

- **Settings → Plugins** → search "MCP Server" and confirm it's enabled. *(Bundled by default on recent IntelliJ versions.)*
- **Settings → Tools → MCP Server** → check **Enable MCP Server**.

### 3. Connect your MCP client

Use IntelliJ's **Auto-Configure** option for your client (or configure manually using the ip address), then restart the client. The auto-configured server name is usually **`user-jetbrains`**

> [!CAUTION]
> MixinMCP exposes full project and dependency source, classpath metadata, and most importantly, *file editing and refactoring tools*, to whatever connects to the MCP server. These tools aren't hardened for remote access. 
> 
> IntelliJ binds the server to localhost only by default, and you should leave it that way unless you have a specific reason and you know the risks. 

For adherence and other optimizations, see [Bundled Rules and Skills](#bundled-rules-and-skills).

### 4. Set up the Gradle plugin

> [!IMPORTANT]
> Without this step, `mixin_search_in_deps` and `mixin_get_dep_source` can only see dependencies that published a `-sources.jar`.
> Local jar dependencies, Cursemaven dependencies, and many Modrinth Maven dependencies will be invisible to these tools. 
> 
> You'll also be totally on your own to ensure your *loader's* generated sources jar is actually attached, which often has to be done manually.
> 
> The Gradle plugin ensures most common loader-merged-jars are attached automatically, and decompiles any remaining dependencies without sources via [Vineflower](https://github.com/Vineflower/vineflower) 
> into a cache that all tools also read, ensuring all dependencies can be indexed fully. 


**1. Add the MixinMCP maven repository to your mod project's `settings.gradle` or `settings.gradle.kts`:**

```kotlin
// .kts format
pluginManagement {
    repositories {
        maven { url = uri("https://maven.muon.rip/releases") }
        gradlePluginPortal()
        // ... your existing repos (maven, fabricmc, neoforged, etc.)
    }
}
```

**2. Apply the plugin in your mod project's `build.gradle` or `build.gradle.kts`:**

```kotlin
plugins {
    // ... your existing plugins ...
    id("dev.mixinmcp.decompile") version "1.2.0"
}
```
**3. Increase Gradle process memory:** (**Strongly** Recommended)

> [!CAUTION]
> If you skip this step, you might experience OOM errors during gradle sync


 Add to your project level `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4g
# You may need to set this as high as 6g if you need 
# to decompile an extremely large jar (e.g. Cataclysm)
```

> [!WARNING]
> Setting `org.gradle.parallel=true` (default for Neoforge template) can increase likelihood of OOMs during big decomps

For more info, see [Decompilation cache details](#decompilation-cache-details)

**4. Run decompilation:** 

```bash
./gradlew genDependencySources
```
*(This task already runs automatically after every gradle sync, but it can sometimes need to rerun manually)*

The IntelliJ plugin reads the dependency sources cache on project open and after every Gradle sync/task run. 

MixinMCP warns on project open when the Gradle plugin is missing. If you don't need full-classpath decompilation or source auto-attach and want to silence this, disable **Warn when Gradle plugin is not detected** in **Settings → Tools → MixinMCP**.

For local development against an unpublished build, see [Decompilation cache details](#decompilation-cache-details) below.

### 5. Verify

Ask the model to list MCP tools from the JetBrains server.  The `mixin_*` tools should appear. If they don't: (1) confirm MixinMCP is installed, (2) confirm the MCP Server plugin is enabled, (3) confirm your client is connected (most common failure)

## Bundled Rules and Skills

<details>
<summary>Built-in adherence tools and context for agents (click to expand)</summary>

On project open for Minecraft mod projects (Fabric, Forge, NeoForge, Quilt, Architectury), MixinMCP copies bundled assistant files: **Cursor** rules and skills under `.cursor/`, and **Claude Code** skills under `.claude/skills/`. 

These provide better context for the LLM about why they should use these tools, and when and how to use each tool, common pitfalls, and a mixin workflow checklist. 

Injected files are automatically added to `.gitignore` under a `# MixinMCP auto-injected rules` block. 

You can edit these manually, but if you use the same name and do not change the configuration, they will be overwritten on project open. You can disable this override (or choose not to inject anything at all) in **Settings → Tools → MixinMCP**. 

</details>

## Tool reference

<details>
<summary>All 25 tools (click to expand)</summary>

### Source Navigation

| Tool | Description |
|------|-------------|
| `mixin_find_class` | Look up any class by FQCN across project, libraries, and JDK. Optionally include members, decompiled source, or just one named method/field via `methodName` / `fieldName`. |
| `mixin_search_symbols` | Find classes, methods, or fields by name substring across project and all dependencies. |
| `mixin_search_in_deps` | Regex search across all dependency sources, both published and auto-decompiled. Like grep for your entire classpath. Pass `contextLines` to capture short method bodies inline. |
| `mixin_get_dep_source` | Read source from dependency jars or decompiled cache. Pass `url` (from search results) or `path` (e.g. io/redspace/.../Utils.java). |
| `mixin_list_source_roots` | Lists all source roots searched by dependency tools. Use to diagnose missing sources. |

### Semantic Navigation

| Tool                          | Description                                                                                                                                                                                                                                       |
|-------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `mixin_type_hierarchy`        | Full inheritance chain (supertypes and subtypes). Essential before writing mixins.                                                                                                                                                                |
| `mixin_find_impls`            | Find all implementations of an interface or abstract class.                                                                                                                                                                                       |
| `mixin_find_references`       | Find all usages of a class, method, or field.                                                                                                                                                                                                     |
| `mixin_call_hierarchy`        | Callers and callees of a method, recursive to `maxDepth` (default 3, cap 10), traces execution flow across levels with cycle detection. Callees cover direct calls, `new Foo(...)`, `Foo::bar`, and the real synthetic target behind each lambda. |
| `mixin_super_methods`         | Find where a method is originally declared in the hierarchy.                                                                                                                                                                                      |
| `mixin_find_overrides`        | Find all overrides of a method across the class hierarchy (project, mods, loader, libraries).                                                                                                                                                     |
| `mixin_find_targeting_mixins` | Find all `@Mixin` classes that target a given class/method, for cross-mod conflict analysis.                                                                                                                                                      |

### Bytecode Inspection

| Tool | Description |
|------|-------------|
| `mixin_class_bytecode` | Bytecode-level class overview including synthetic methods. Use `filter="synthetic"` for lambda/bridge mixin targets. |
| `mixin_method_bytecode` | Full bytecode instructions for a specific method. INVOKE* instructions show the real owner class for `@At(target)`. |

### Mappings

| Tool | Description |
|------|-------------|
| `mixin_mappings_lookup` | Convert a class/method/field name between mojmap, yarn, intermediary, srg, and obf. MC version auto-detected from `gradle.properties`. Mapping files are downloaded on demand and cached. |

### Project Management

| Tool | Description |
|------|-------------|
| `mixin_sync_project` | Trigger Gradle sync. The decompilation cache is re-read automatically after sync. |
| `mixin_refresh_vfs` | Force-refresh IntelliJ's VFS so on-disk changes from external tools become visible. Optional `path` scopes the refresh; file paths refresh the parent directory (catching edits, creates, and deletes), deleted paths walk up to the nearest existing ancestor, and directory paths refresh recursively. Defaults to the project root. |

### Refactoring

Reference-aware: each tool checks or updates every reference project-wide, including string references in mixin config JSON, `mods.toml`, and ServiceLoader files where language plugins contribute PSI references. Shared contract: `dryRun=true` reports the resolved target, usages, and conflicts without changing anything; conflicts are tagged `[library]` (usually a stale build jar) or `[source]`, and `ignoreConflicts=true` proceeds anyway. Two exceptions: `mixin_safe_delete` uses `force=true` instead, and `mixin_move_file` takes neither flag, running all its checks up front. The signature, extract, introduce, inline, and move-members tools operate on Java sources only.

| Tool | Description |
|------|-------------|
| `mixin_rename` | Rename a class, method, field, parameter, or local variable, updating every reference project-wide. Renaming an override renames the source super method and all overriders together. Reports conflicts instead of silently discarding them. Local-variable renames are Java sources only. |
| `mixin_safe_delete` | Delete a class, method, or field after checking for usages across project and dependencies. Resolves by FQCN; pass `methodName` (with `parameterTypes`/`methodDescriptor` for overloads) or `fieldName` to narrow to a member. Method overrides count as blocking usages and are tagged `[override]`. `force=true` deletes despite usages; `dryRun=true` only reports. |
| `mixin_move_file` | Move a class to a new package, updating its package declaration and every import/reference across the project. Resolves the source by FQCN; Kotlin files with multiple top-level declarations move together. Non-Java string references are also rewritten, so mixin configs and ServiceLoader entries follow along. Errors if a file with the same name already exists in the target package. |
| `mixin_change_signature` | Change a method's signature atomically: rename, return type, visibility, and add/remove/reorder/retype parameters, with every call site and override updated. New parameters take a `defaultValue` expression inserted at existing call sites. |
| `mixin_extract_method` | Extract a statement range or sub-expression into a new method; IntelliJ's control-flow analysis derives parameters, return value, and thrown exceptions. |
| `mixin_introduce_variable` | Introduce a local variable for an expression, optionally replacing all other occurrences in scope. |
| `mixin_inline` | Inline a method into every call site, a constant field into every read, or a local variable into its usages. Refuses recursive methods and non-final fields with writes. |
| `mixin_move_members` | Move members between classes: pull up into a superclass or interface, or move static members to any class. Moving members into a `@Mixin` class flags external references as blocking `[mixin]` conflicts. Push down is unavailable on 2026.2+, which sealed the platform's push-down engine with no headless replacement. |

</details>

## Decompilation cache details

<details>
<summary>How the cache works, memory tuning, local dev (click to expand)</summary>

### How it works

- `./gradlew genDependencySources` scans your resolved dependencies for JARs without
  a corresponding `-sources.jar`.
- Dependencies that **do** publish `-sources.jar` still get those jars unpacked into the
  same cache. Gradle/IntelliJ often use a remapped/transformed classes JAR on the classpath
  without attaching sources; mirroring fixes search and MCP tools for those libraries.
- Each missing-sources JAR is decompiled to `~/.cache/mixinmcp/decompiled/<hash>/`.
- A manifest (`manifest.json`) tracks artifact identity so unchanged JARs are
  never re-decompiled (incremental).
- The IntelliJ plugin reads this cache on project open and after every Gradle
  sync, exposing the decompiled `.java` files as `SyntheticLibrary` roots. These are indexed
  and searchable just like real sources.

Decompilation is a **blocking Gradle task**, not a background IDE operation. This
means tools never run against a half-populated cache. By the time you open the
project, every dependency is searchable.

### Memory tuning

Vineflower's SSA analysis can use significant memory on large JARs. The task
defaults to 2 decompiler threads to keep memory usage reasonable.

**Pre-flight check:** Before decompiling large uncached JARs (≥15MB), the task
checks whether the Gradle daemon heap is likely sufficient. If not, it blocks
and prompts for confirmation. In non-interactive environments (CI, IntelliJ sync),
the task fails with recommendations instead of hanging. Use `--force` to skip this
check and proceed regardless:

```bash
# Manually set thread count. Setting threads=1 may cause decompilation to freeze entirely.
./gradlew genDependencySources --threads=3 # Default=2

# Skip OOM pre-flight confirmation (e.g. when you know heap is sufficient)
./gradlew genDependencySources --force
```


*Add to your mod project's gradle.properties:*
```properties
# Increase memory available to Gradle
org.gradle.jvmargs=-Xmx4g

# Disable parallelization (which may increase risk of OOM)
org.gradle.parallel=false
```

On large modded projects (50+ dependencies, some JARs over 100MB), you might need to set gradle process memory as high as `-Xmx6g`. 

The task saves progress after each JAR, so if things do crash, just resync Gradle or run the task manually and Decompile will pick up where it left off.

### Clearing the cache

```bash
# Delete this project's cache entries and manifest
./gradlew cleanSourcesCache

# Delete the entire cache, for all projects
./gradlew cleanSourcesCache --global
```

Cache entries untouched for 30 days are also evicted automatically, so manual cleanup is
rarely needed.

**Prefer native sources when available.** Decompiled output lacks comments,
meaningful parameter names, and local variable names. If a library publishes
sources (Maven Central, JitPack, etc.), add the `-sources` classifier in your
build script so IntelliJ attaches the real sources and MixinMCP skips
decompilation for that JAR entirely.

### Local development against an unpublished Gradle plugin build

```bash
# In the MixinMCP project
./gradlew :mixinmcp-gradle:publishToMavenLocal
```

Then swap `maven { url = uri("https://maven.muon.rip/releases") }` for
`mavenLocal()` in your mod project's `pluginManagement.repositories`.

</details>

## Building from Source

<details>
<summary>How to build and use locally (click to expand)</summary>

### Building:
First clone the project and build:
```bash
git clone https://github.com/muon-rw/MixinMCP.git
cd MixinMCP
./gradlew buildPlugin # IntelliJ plugin
./gradlew :mixinmcp-gradle:build # Gradle plugin
```
Recommended: Publish the Gradle Plugin locally
```bash
./gradlew :mixinmcp-gradle:publishToMavenLocal
```

### Using: 
Option 1: Run a sandboxed instance with the plugin installed
```bash
./gradlew runIde
```

Option 2:
After `buildPlugin`, the plugin ZIP will be at `build/distributions/MixinMCP-<version>.zip`.

In IntelliJ: **Settings → Plugins** → ⚙ → **Install Plugin from Disk…**
</details>

## License

GPL-3.0. See [LICENSE](LICENSE).
