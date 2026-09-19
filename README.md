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

### 4. Agent Skills for enhanced Mixin Writing (companion Claude Code plugin):
- Install once in Claude Code: `/plugin marketplace add muon-rw/MixinMCP`, then `/plugin install mixinmcp@mixinmcp`
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

For better agent adherence, also install the Claude Code plugin; see [Agent Skills](#agent-skills-claude-code-plugin).

### 4. Set up the Gradle plugin

> [!IMPORTANT]
> Without this step, `mixin_search_in_deps` and `mixin_get_dep_source` can only see dependencies that published a `-sources.jar`.
> Local jar dependencies, Cursemaven dependencies, and many Modrinth Maven dependencies will be invisible to these tools. 
> 
> You'll also be totally on your own to ensure your *loader's* generated sources jar is actually attached, which often has to be done manually.
> 
> The Gradle plugin ensures most common loader-merged-jars are attached automatically, and decompiles any remaining dependencies without sources via [Vineflower](https://github.com/Vineflower/vineflower) 
> into a cache that all tools also read, ensuring all dependencies can be indexed fully. 
> 
> This includes the Gradle buildscript classpath: build plugins (Loom, ModDevGradle, etc.) without published sources are only source-searchable with the Gradle plugin applied. 


**1. Add the MixinMCP maven repository to your mod project's `settings.gradle` or `settings.gradle.kts`:**

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven { url = uri("https://maven.muon.rip/releases") }
        gradlePluginPortal()
        // ... your existing repos (maven, fabricmc, neoforged, etc.)
    }
}
```

```groovy
// settings.gradle
pluginManagement {
    repositories {
        maven { url = 'https://maven.muon.rip/releases' }
        gradlePluginPortal()
        // ... your existing repos (maven, fabricmc, neoforged, etc.)
    }
}
```

**2. Apply the plugin in your mod project's `build.gradle` or `build.gradle.kts`:**

```kotlin
// build.gradle.kts
plugins {
    // ... your existing plugins ...
    id("dev.mixinmcp.decompile") version "1.5.0"
}
```

```groovy
// build.gradle
plugins {
    // ... your existing plugins ...
    id 'dev.mixinmcp.decompile' version '1.5.0'
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

MixinMCP warns on project open when the Gradle plugin is missing or older than the installed MixinMCP release requires. If you don't need full-classpath decompilation or source auto-attach and want to silence this, disable **Warn when the MixinMCP Gradle plugin is missing or outdated** in **Settings → Tools → MixinMCP**. Buildscript-classpath indexing can also be toggled there.

For local development against an unpublished build, see [Decompilation cache details](#decompilation-cache-details) below.

### 5. Verify

Ask the model to list MCP tools from the JetBrains server.  The `mixin_*` tools should appear. If they don't: (1) confirm MixinMCP is installed, (2) confirm the MCP Server plugin is enabled, (3) confirm your client is connected (most common failure)

## Agent Skills (Claude Code plugin)

MixinMCP's agent skills ship as a [Claude Code plugin](https://code.claude.com/docs/en/plugins), installed once per machine instead of copied into every project:

- **mixinmcp-tools**: why, when, and how to use each `mixin_*` tool, common pitfalls, and the query patterns that cost the least context.
- **mixin-writing**: injector selection, `@At` targeting, the MixinExtras `@Expression` reference, and a mixin workflow checklist.

Install in Claude Code:

```
/plugin marketplace add muon-rw/MixinMCP
/plugin install mixinmcp@mixinmcp
```

The IntelliJ plugin warns on opening a Minecraft project when Claude Code is present but the plugin is missing or outdated; toggle this in **Settings → Tools → MixinMCP**.

Older MixinMCP versions injected these skills into `.cursor/` and `.claude/` and gitignored them. On first project open, files MixinMCP can attribute to itself (via its version stamp, its injection manifest, or its `.gitignore` entries) are moved to a backup folder under the IDE system directory and their `.gitignore` entries are removed; anything it cannot attribute is left alone. Cursor rule/skill injection is discontinued for now.

<details>
<summary>Team distribution (click to expand)</summary>

To have Claude Code prompt every teammate to install the plugin when they trust your mod repo, commit this to the mod project's `.claude/settings.json`:

```json
{
  "extraKnownMarketplaces": {
    "mixinmcp": { "source": { "source": "github", "repo": "muon-rw/MixinMCP" } }
  },
  "enabledPlugins": { "mixinmcp@mixinmcp": true }
}
```

</details>

## Tool reference

<details>
<summary>All 27 tools (click to expand)</summary>

Undeclared parameter names are rejected before the call runs: common synonyms are accepted outright when they can only mean one thing on that tool (`limit` for `maxResults`, `class` for `className`), anything else gets a suggestion in the error. Three renamed parameters keep their old names: `path` on `mixin_refresh_vfs`, `force` on `mixin_safe_delete`, `methodName` on `mixin_extract_method`.

### Source Navigation

| Tool | Description |
|------|-------------|
| `mixin_find_class` | Look up any class by FQCN across project, libraries, JDK, and the Gradle buildscript classpath. Optionally include members, decompiled source, or just one named method/field via `methodName` / `fieldName`; a binary-only class reads from its decompiled-cache copy when one exists, so line numbers match the other tools. A class that is not found lists the classes sharing its simple name. A `Modules:` line names the modules whose classpath provides the class, tagged `(RUNTIME)` where a module cannot compile against the class or `(TEST)` where only its test sources can; `module=` pins resolution and doubles as a compile-visibility check for that module. |
| `mixin_search_symbols` | Find classes, methods, or fields by name substring across project and all dependencies. |
| `mixin_search_in_deps` | Regex search across all dependency sources, both published and auto-decompiled. Like grep for your entire classpath, JDK `src.zip` and buildscript classpath included. Pass `contextLines` to capture short method bodies inline; `roots` narrows the scan to `library`, `game`, `decompiled`, `jdk`, or `buildscript`. The default order scans game roots first and the JDK second to last, so vanilla and mod hits lead. |
| `mixin_get_dep_source` | Read source and text resources from dependency jars, the decompiled cache, or any jar on disk. Address the file with `url` (from search results), `jarPath` + `entry`, `className`, or `path` (e.g. io/redspace/.../Utils.java); pick lines with a `lineNumber` window or an explicit `startLine`/`endLine` range. Reads `mods.toml`, `fabric.mod.json`, lang files, recipes, and mixin configs as readily as `.java`. |
| `mixin_list_jar_entries` | List or grep the entries inside jars. `jarPath` takes any jar on disk, on the classpath or not, or a directory of jars such as a pack's mods folder; `jar` takes a substring of a classpath jar's file name or coordinates. Narrow with `pathPrefix` / `fileMask`; `.class` entries need `includeClasses=true`. `regexPattern` greps the text entries instead, e.g. every `mods.toml` in a mods folder that mentions a dependency; it is also the way to search runtime-only jars, which `mixin_search_in_deps` cannot see. |
| `mixin_list_source_roots` | Lists all source roots searched by dependency tools. Use to diagnose missing sources, or pass `filter` (a substring or glob over label, jar name, coordinates, and URL) to answer "is jar X attached" in one call. |

### Semantic Navigation

| Tool                          | Description                                                                                                                                                                                                                                       |
|-------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `mixin_type_hierarchy`        | Full inheritance chain (supertypes and subtypes). Essential before writing mixins.                                                                                                                                                                |
| `mixin_find_impls`            | Find all implementations of an interface or abstract class.                                                                                                                                                                                       |
| `mixin_find_references`       | Find all usages of a class, method, or field.                                                                                                                                                                                                     |
| `mixin_call_hierarchy`        | Callers and callees of a method, recursive to `maxDepth` (default 3, cap 10), traces execution flow across levels with cycle detection. Callees cover direct calls, `new Foo(...)`, `Foo::bar`, and the real synthetic target behind each lambda. A method that calls the target more than once lists each call's Mixin `INVOKE` ordinal and line. |
| `mixin_super_methods`         | Find where a method is originally declared in the hierarchy.                                                                                                                                                                                      |
| `mixin_find_overrides`        | Find all overrides of a method across the class hierarchy (project, mods, loader, libraries).                                                                                                                                                     |
| `mixin_find_targeting_mixins` | Find all `@Mixin` classes that target a given class/method, for cross-mod conflict analysis.                                                                                                                                                      |

### Bytecode Inspection

| Tool | Description |
|------|-------------|
| `mixin_class_bytecode` | Bytecode-level class overview including synthetic methods. Use `filter="synthetic"` for lambda/bridge mixin targets. `jarPath` reads the class straight from a jar on disk, with no classpath and no indexing wait. |
| `mixin_method_bytecode` | Full bytecode instructions for a specific method. INVOKE* instructions show the real owner class for `@At(target)`. `regexPattern` keeps only the matching instructions, each with its source line and its Mixin ordinal when the same target repeats; an `INVOKEDYNAMIC` matches on its bootstrap arguments, so a lambda name finds the instruction that creates it. `jarPath` works here too. |

### Mappings

| Tool | Description |
|------|-------------|
| `mixin_mappings_lookup` | Convert a class/method/field name between mojmap, yarn, intermediary, srg, and obf. MC version auto-detected from `gradle.properties`. Mapping files are downloaded on demand and cached. |

### Project Management

Every other `mixin_*` tool waits up to 30 seconds for a busy IDE (indexing, a Gradle resolve, or the project-data import) and then returns an error naming what it is waiting on, instead of hanging. The three tools below and `mixin_mappings_lookup` are exempt.

| Tool | Description |
|------|-------------|
| `mixin_sync_project` | Trigger a Gradle sync and, by default, wait for the resolve and the project-data import that follows it, reporting success, failure with the error text, cancellation, or timeout (`timeoutMs`, default 90000, max 600000). `wait=false` returns once the resolve has started, or after 30 s if it has not. `projectPath` accepts either separator form and must be a linked Gradle root or a directory inside one; the error lists the linked roots. Maven is not supported; use the IDE's Maven reload. The decompilation cache is re-read automatically after sync. |
| `mixin_ide_status` | Reports whether the IDE can answer classpath questions right now: indexing, a Gradle resolve or project-data import in flight, the last sync outcome with its error text, and the linked Gradle roots. |
| `mixin_refresh_vfs` | Force-refresh IntelliJ's VFS so on-disk changes from external tools become visible. Optional `filePath` (alias `path`, absolute or relative to the project) scopes the refresh; file paths refresh the parent directory (catching edits, creates, and deletes), deleted paths walk up to the nearest existing ancestor, and directory paths refresh recursively. Defaults to the project root. |

### Refactoring

Reference-aware: each tool checks or updates every reference project-wide, including string references in mixin config JSON, `mods.toml`, and ServiceLoader files where language plugins contribute PSI references. Shared contract: `dryRun=true` reports the resolved target, usages, and conflicts without changing anything; conflicts are tagged `[library]` (usually a stale build jar) or `[source]`, and `ignoreConflicts=true` proceeds anyway. The signature, extract, introduce, inline, and move-members tools operate on Java sources only.

| Tool | Description |
|------|-------------|
| `mixin_rename` | Rename a class, method, field, parameter, or local variable, updating every reference project-wide. Renaming an override renames the source super method and all overriders together. Reports conflicts instead of silently discarding them. Local-variable renames are Java sources only. |
| `mixin_safe_delete` | Delete a class, method, or field after checking for usages across project and dependencies. Resolves by FQCN; pass `methodName` (with `parameterTypes`/`methodDescriptor` for overloads) or `fieldName` to narrow to a member. Method overrides count as blocking usages and are tagged `[override]`. `ignoreConflicts=true` (`force` is accepted as an alias) deletes despite usages; `dryRun=true` only reports. |
| `mixin_move_file` | Move a class to a new package, updating its package declaration and every import/reference across the project. Resolves the source by FQCN; Kotlin files with multiple top-level declarations move together. Non-Java string references are also rewritten, so mixin configs and ServiceLoader entries follow along. Errors if a file with the same name already exists in the target package, and refuses when the move would break package-private access unless `ignoreConflicts=true`. |
| `mixin_change_signature` | Change a method's signature atomically: rename, return type, visibility, and add/remove/reorder/retype parameters, with every call site and override updated. New parameters take a `defaultValue` expression inserted at existing call sites. |
| `mixin_extract_method` | Extract a statement range or sub-expression into a new method named by `newMethodName` (`methodName` is accepted as an alias); IntelliJ's control-flow analysis derives parameters, return value, and thrown exceptions. |
| `mixin_introduce_variable` | Introduce a local variable for an expression, optionally replacing all other occurrences in scope. |
| `mixin_inline` | Inline a method into every call site, a constant field into every read, or a local variable into its usages. Refuses recursive methods and non-final fields with writes. |
| `mixin_move_members` | Move members between classes: pull up into a superclass or interface, push down into every direct subclass, or move static members to any class. Moving members into a `@Mixin` class flags external references as blocking `[mixin]` conflicts. |

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
- Local jar dependencies declared with `files(...)` are decompiled too, named by
  their file name. Project (subproject) dependencies are skipped; their sources are
  already in the build.
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

### Jars outside the classpath

To search a mod that is on no classpath at all (one sitting in a modpack folder),
name it explicitly:

```bash
# Repeatable; relative paths resolve against the project directory
./gradlew genDependencySources --jar ../pack/mods/jade.jar --jar ../pack/mods/jei.jar
```

Or keep the list in the build script:

```kotlin
mixinmcp {
    extraJars.from(file("../pack/mods/jade.jar"))
}
```

These entries are recorded in `.gradle/mixinmcp/adhoc-manifest.json`, separate from the
classpath manifest: every run that names extra jars rebuilds it, a run that names none
leaves it alone, and it is never pruned against the classpath. `mixin_list_source_roots`
marks those roots `[ad hoc jar]`. Stale entries fall to the same 30 day eviction as the
rest of the cache.

Both need `dev.mixinmcp.decompile` 1.5.0 or newer.

For a one-off look there is no need to decompile at all: `mixin_list_jar_entries`,
`mixin_get_dep_source(jarPath=..., entry=...)`, and `jarPath` on the bytecode tools read
any jar on disk directly.

### Clearing the cache

```bash
# Delete this project's cache entries and both manifests (classpath and ad hoc)
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

### Testing the Claude Code plugin locally:

```bash
# One-off session with the local plugin. Pass claude-plugin/, not the repo root:
# the root is the marketplace, and --plugin-dir silently loads nothing from it.
claude --plugin-dir /path/to/MixinMCP/claude-plugin

# Or install from the local checkout as a marketplace:
claude plugin marketplace add /path/to/MixinMCP
claude plugin install mixinmcp@mixinmcp
```
</details>

## License

GPL-3.0. See [LICENSE](LICENSE).
