# MixinMCP

<!-- Plugin description -->
Extends IntelliJ's built-in MCP Server with tools for
Minecraft mod development, with the goal of improving mixin writing and inter-mod compatibility. 

GitHub:
https://github.com/muon-rw/MixinMCP

For all features, you will also need the Gradle plugin - see https://github.com/muon-rw/MixinMCP#Setup

## Key features:
### 1. Broad-scoped search:
- Full type hierarchy: supertypes, subtypes, and all implementations of an interface or abstract class
- Find all overrides of a method, plus its original super-method declaration
- Call hierarchy: callers and callees of any method
- All references to a class, method, or field
- All `@Mixin` classes targeting a given class method. Helps identify cross-mod conflicts
- Symbol search by name pattern, plus regex grep across all dependency sources

**Searches across your *entire* classpath including dependencies:**

- Alternative tools generally search only *your* project code. They don't search remapped Minecraft sources, loader or mod APIs, libraries, or other mods you've added for integration or compatibility.
At best, these tools might additionally see your currently active open file.
- With this plugin, agents can easily scan the whole project dependency network on their own, including dependencies without published sources. 
- Circumvents the need to manually copy and paste snippets into context, or for agents to find jars in your gradle cache and unzip/analyze them manually

### 2. Class/Method Bytecode lookup:
- Get the actual compiled bytecode for a given class or method
- Useful to find a precise target when writing mixins, especially for ordinals or synthetic lambdas

### 3. Built-in Agent Skills for enhanced Mixin Writing:
- Improve compatibility of written mixins by favoring MixinExtras injectors which LLMs often hallucinate or fail to use in the first place
- Favor precise modification for the exact target for the task without workarounds, slices, or shift by's, thanks to MixinExtras' robust `@Expression` annotation

### 4. Automatic Mappings lookup:
- Easily convert any class, method, or field name between SRG, Intermediary, Yarn, Mojmap, and obf
- Mappings are downloaded on demand (Mojang launcher meta, Fabric Maven, Forge/NeoForge Maven) and cached under `~/.cache/mixinmcp/mappings/` 
- This allows you to retrieve mapping data for (almost) any version or loader, even those not present in the current project
<!-- Plugin description end -->

## Setup

MixinMCP has two parts. You need **both** for full-classpath search to work:

- **IntelliJ plugin**: registers the `mixin_*` tools on IntelliJ's built-in MCP Server.
- **Gradle plugin**: decompiles dependencies that don't publish sources so the search tools cover every JAR on your classpath.

**Prerequisites:** IntelliJ IDEA 2025.3+

### 1. Install the IntelliJ plugin

**From JetBrains Marketplace**: **Settings → Plugins → Marketplace** → search "MixinMCP" → **Install**

**From Disk:** See [Building from Source](#building-from-source) below.

### 2. Enable IntelliJ's MCP Server

- **Settings → Plugins** → search "MCP Server" and confirm it's enabled. *(Bundled by default on recent IntelliJ versions.)*
- **Settings → Tools → MCP Server** → check **Enable MCP Server**.

### 3. Connect your MCP client

Use IntelliJ's **Auto-Configure** option for your client (or configure manually using the ip address), then restart the client. The auto-configured server name is usually **`user-jetbrains`**

> [!CAUTION]
> MixinMCP exposes full project and dependency source, classpath metadata, and bytecode to whatever connects to the MCP server. These tools aren't hardened for remote access. IntelliJ binds the server to localhost by default — leave it that way unless you have a specific reason, and expose it more broadly at your own risk.

For adherence and other optimizations, see [Bundled Rules and Skills](#bundled-rules-and-skills).

### 4. Set up the Gradle plugin

> [!IMPORTANT]
> Without this step, `mixin_search_in_deps` and `mixin_get_dep_source` can only see dependencies that published a `-sources.jar`.
> This means local jar dependencies, Cursemaven dependencies, and many from Modrinth Maven dependencies are totally invisible.
> The Gradle plugin decompiles these via [Vineflower](https://github.com/Vineflower/vineflower) into a local cache that the IntelliJ plugin indexes automatically.


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
    id("dev.mixinmcp.decompile") version "0.9.0"
}
```
**3. Increase Gradle process memory:** (**Strongly** Recommended)

> [!CAUTION]
> If you skip this step, you might experience OOM errors during gradle sync


 Add to your project level `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4g
# You may need to set this as high as 6g if you need to decompile an extremely large jar (e.g. Cataclysm)
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

MixinMCP warns on project open when the Gradle plugin is missing. If you don't need full-classpath decompilation and want to silence this, disable **Warn when Gradle plugin is not detected** in **Settings → Tools → MixinMCP**.

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

### Claude Code tip: stay connected to IntelliJ

Claude Code's `Write`, `Edit`, and `MultiEdit` tools do not fsync when writing directly to disk, so IntelliJ's file-change notifier can take minutes to notice an edit; during that window your editor tabs still show the pre-edit contents even though the file on disk is already updated. This is mainly a UX issue (the `mixin_*` tools query classpath and dependency state, so their results are generally unaffected), but it's disorienting to watch. The cleanest fix is to keep the Claude Code session connected to IntelliJ via the [Claude Code IntelliJ plugin](https://plugins.jetbrains.com/plugin/28813-claude-code): when connected, edits route through the IDE and the editor updates immediately.

Recommendations:

- Launch Claude Code from IntelliJ's integrated terminal so the plugin auto-connects.
- Run `/ide` at the start of a session (and after long idles) to confirm the connection is live; reconnect if not.
- If you cannot reconnect, `mixin_refresh_vfs` with the edited path is a manual fallback that forces IntelliJ to pick up the change without waiting on fsnotifier.

## Tool reference

<details>
<summary>All 16 tools (click to expand)</summary>

### Source Navigation

| Tool | Description |
|------|-------------|
| `mixin_find_class` | Look up any class by FQCN — project, library, or JDK. Optionally include members or decompiled source. |
| `mixin_search_symbols` | Find classes, methods, or fields by name pattern across project and all dependencies. |
| `mixin_search_in_deps` | Regex search across all dependency sources — published *and* auto-decompiled. Like grep for your entire classpath. |
| `mixin_get_dep_source` | Read source from dependency jars or decompiled cache. Pass `url` (from search results) or `path` (e.g. io/redspace/.../Utils.java). |
| `mixin_list_source_roots` | Lists all source roots searched by dependency tools. Use to diagnose missing sources. |

### Semantic Navigation

| Tool | Description |
|------|-------------|
| `mixin_type_hierarchy` | Full inheritance chain (supertypes and subtypes). Essential before writing mixins. |
| `mixin_find_impls` | Find all implementations of an interface or abstract class. |
| `mixin_find_references` | Find all usages of a class, method, or field. |
| `mixin_call_hierarchy` | Callers and callees of a method, recursive to `maxDepth` (default 3, cap 10) — trace execution flow across levels with cycle detection. Callees cover direct calls, `new Foo(...)`, `Foo::bar`, and the real synthetic target behind each lambda. |
| `mixin_super_methods` | Find where a method is originally declared in the hierarchy. |
| `mixin_find_overrides` | Find all overrides of a method across the class hierarchy (project, mods, loader, libraries). |
| `mixin_find_targeting_mixins` | Find all `@Mixin` classes that target a given class/method — cross-mod conflict analysis. |

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

# Increase heap (more memory available)
# Add to your mod project's gradle.properties:
org.gradle.jvmargs=-Xmx4g
```

On large modded projects (50+ dependencies, some JARs over 100MB), `--threads=2`
with `-Xmx4g` is a good starting point. The task saves progress after each JAR,
so if it does crash you can re-run and it picks up where it left off.

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
git clone https://github.com/muon-rw/mixin-mcp.git
cd mixin-mcp
./gradlew buildPlugin # IntelliJ plugin
./gradlew build # Gradle Plugin
```
Recommended: Publish the Gradle Plugin locally
```bash
./gradlew publishToMavenLocal
```

### Using: 
Option 1: Run a sandboxed instance with the plugin installed
```bash
./gradlew runIde
```

Option 2:
After `buildPlugin`, The plugin ZIP will be at `build/distributions/mixin-mcp-<version>.zip`.

In IntelliJ: **Settings → Plugins** → ⚙ → **Install Plugin from Disk…**
</details>
