# MixinMCP

<!-- Plugin description -->
An IntelliJ Platform plugin that extends the built-in MCP Server with tools for
Minecraft mod development — dependency navigation, semantic code analysis,
bytecode inspection, and automatic decompilation of compiled-only dependencies.

IntelliJ's built-in MCP Server excludes libraries and dependencies from its tools. MixinMCP
adds 12 tools that search across your entire classpath, resolve type hierarchies,
and inspect bytecode — including the synthetic lambda methods that are invisible in
decompiled source but essential for mixin targeting. A companion Gradle plugin
decompiles dependencies without published sources via Vineflower so every library
is searchable.
<!-- Plugin description end -->

## Why?

Minecraft mod projects often have 50+ dependencies (remapped Minecraft
sources, mod APIs, libraries). The built-in MCP Server's tools explicitly exclude
all of them — your LLM can't look up a Minecraft class, search mod APIs, or trace
inheritance chains across libraries.

Mixin development makes this worse. Targeting a lambda in a Minecraft method
requires knowing the synthetic method name (e.g. `lambda$tick$0`), which only
exists in compiled bytecode. No existing MCP plugin exposes this.

## Requirements

- IntelliJ IDEA 2025.3+ (Community or Ultimate)
- The built-in **MCP Server** plugin must be enabled
  (Settings → Plugins → search "MCP Server")
- An MCP client connected to IntelliJ's MCP Server
  (Cursor, Claude Code, Claude Desktop, etc.)

## Installation

### From Disk (local development)

1. Build the plugin:
   ```bash
   ./gradlew buildPlugin
   ```
2. The plugin ZIP is at `build/distributions/mixin-mcp-<version>.zip`
3. In IntelliJ: **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
4. Select the ZIP, restart IntelliJ
5. Verify: **Settings → Tools → MCP Server** should list the `mixin_*` tools

### From JetBrains Marketplace (Once published)

*(This plugin is not yet published!)*
**Settings → Plugins → Marketplace** → search "MixinMCP" → **Install**

## Tools

### Source Navigation

| Tool | Description |
|------|-------------|
| `mixin_find_class` | Look up any class by FQCN — project, library, or JDK. Optionally include members or decompiled source. |
| `mixin_search_symbols` | Find classes, methods, or fields by name pattern across project and all dependencies. |
| `mixin_search_in_deps` | Regex search across all dependency sources — published *and* auto-decompiled. Like grep for your entire classpath. |
| `mixin_get_dep_source` | Read source from dependency jars or decompiled cache. Pass `url` (from search results) or `path` (e.g. io/redspace/.../Utils.java). |

### Semantic Navigation

| Tool | Description |
|------|-------------|
| `mixin_type_hierarchy` | Full inheritance chain (supertypes and subtypes). Essential before writing mixins. |
| `mixin_find_impls` | Find all implementations of an interface or abstract class. |
| `mixin_find_references` | Find all usages of a class, method, or field. |
| `mixin_call_hierarchy` | Callers and callees of a method — trace execution flow. |
| `mixin_super_methods` | Find where a method is originally declared in the hierarchy. |

### Bytecode Inspection

| Tool | Description |
|------|-------------|
| `mixin_class_bytecode` | Bytecode-level class overview including synthetic methods. Use `filter="synthetic"` for lambda/bridge mixin targets. |
| `mixin_method_bytecode` | Full bytecode instructions for a specific method. |

### Project Management

| Tool | Description |
|------|-------------|
| `mixin_sync_project` | Trigger Gradle sync. The decompilation cache is re-read automatically after sync. |

## Decompilation Cache

Many Minecraft mod dependencies ship without published sources (`-sources.jar`).
MixinMCP includes a **Gradle plugin** that decompiles these compiled-only JARs
using [Vineflower](https://github.com/Vineflower/vineflower) so that
`mixin_search_in_deps` and `mixin_get_dep_source` cover your *entire* classpath
— not just libraries that happened to publish source artifacts.

### Setup

**1. Add the MixinMCP maven repository to your mod project's `settings.gradle.kts`:**

```kotlin
pluginManagement {
    repositories {
        maven { url = uri("https://maven.muon.rip/releases") }
        gradlePluginPortal()
        // ... your existing repos (maven, fabricmc, neoforged, etc.)
    }
}
```

**2. Apply the plugin in your mod project's `build.gradle.kts`:**

```kotlin
plugins {
    // ... your existing plugins ...
    id("dev.mixinmcp.decompile") version "0.4.0"
}
```

**3. Run decompilation:**

```bash
./gradlew mixinDecompile
```

**For local development** (before publishing), you can use `mavenLocal()` instead:

```bash
# In the MixinMCP project
./gradlew :mixinmcp-gradle:publishToMavenLocal
# Then add mavenLocal() to pluginManagement.repositories in your mod project
```

### How it works

- `./gradlew mixinDecompile` scans your resolved dependencies for JARs without
  a corresponding `-sources.jar`.
- Each missing-sources JAR is decompiled to `~/.cache/mixinmcp/decompiled/<hash>/`.
- A manifest (`manifest.json`) tracks artifact identity so unchanged JARs are
  never re-decompiled (incremental).
- The IntelliJ plugin reads this cache on project open and after every Gradle
  sync, exposing the decompiled `.java` files as `SyntheticLibrary` roots — indexed
  and searchable just like real sources.

Decompilation is a **blocking Gradle task**, not a background IDE operation. This
means tools never run against a half-populated cache — by the time you open the
project, every dependency is searchable. Re-run `./gradlew mixinDecompile` after
changing dependencies.

### Memory tuning

Vineflower's SSA analysis can use significant memory on large JARs. The task
defaults to 2 decompiler threads to keep memory usage reasonable. If you hit
`OutOfMemoryError`, you have two knobs:

```bash
# Reduce threads (less memory, slower)
./gradlew mixinDecompile --threads=1

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

## Configuring Your MCP Client

MixinMCP is an **IntelliJ plugin** — it must be installed in IntelliJ and the
built-in **MCP Server** plugin must be enabled and connected. MixinMCP tools are
registered dynamically at runtime; they appear alongside the built-in MCP tools
with no extra configuration once both plugins are active.

**Verifying the connection:** ask the model to list MCP tools from the JetBrains
server. If the `mixin_*` tools don't appear, check that (1) MixinMCP is
installed in IntelliJ, (2) the MCP Server plugin is enabled, and (3) your MCP
client is connected.

For best results, add a rule to your mod project that teaches the LLM when and
how to use each tool.

### Cursor

In Cursor, IntelliJ's MCP server is named **`user-jetbrains`** (the `user-`
prefix is added by Cursor to all user-configured servers). Tools are invoked via
`CallMcpTool` with `server: "user-jetbrains"`.

Create `.cursor/rules/mixinmcp.mdc` in your **mod project**:

```markdown
---
alwaysApply: true
---
This is a Minecraft mod project using [Fabric/NeoForge/Forge] with extensive
mixin usage and many dependencies.

You have access to MixinMCP tools via the IntelliJ MCP server. **Prefer these
over grep, read_file, or jar extraction** when working with mod/dependency code.
They search and read inside dependency jars natively; grep cannot see jar contents.
Dependencies without published sources are decompiled via the MixinMCP Gradle
plugin (Vineflower) so every library on the classpath is searchable. Native
sources are always preferred — decompiled output lacks comments and meaningful
variable names. Run `./gradlew mixinDecompile` after adding new dependencies.

## Invoking Tools

MixinMCP tools are on the **user-jetbrains** MCP server. Use CallMcpTool:

    CallMcpTool(server="user-jetbrains", toolName="<tool>", arguments={...})

Example — look up a class:

    CallMcpTool(
      server="user-jetbrains",
      toolName="mixin_find_class",
      arguments={"className": "net.minecraft.world.level.Level", "includeMembers": true}
    )

Example — search dependency sources:

    CallMcpTool(
      server="user-jetbrains",
      toolName="mixin_search_in_deps",
      arguments={"regexPattern": "destroyBlock", "fileMask": "*minecraft*"}
    )

Example — read source at a URL returned by search:

    CallMcpTool(
      server="user-jetbrains",
      toolName="mixin_get_dep_source",
      arguments={"url": "<url from mixin_search_in_deps>", "lineNumber": 42, "linesBefore": 10, "linesAfter": 20}
    )

**If `mixin_*` tools are not found or the server is unavailable:**
The `user-jetbrains` MCP server only appears after Cursor connects to IntelliJ's
MCP Server. If you get "MCP server does not exist: user-jetbrains", try:
1. Ensure IntelliJ is running with the project open
2. Check IntelliJ: Settings → Plugins → verify both "MCP Server" and "MixinMCP" are enabled
3. **Restart Cursor** — the MCP server list is cached at startup and may not
   reflect a newly-started IntelliJ instance

## When to Reach for MixinMCP First
- **Finding usages** (e.g. setBlock, destroyBlock, BreakEvent) across mods → mixin_search_in_deps
- **Reading mod source** (Iron's Spellbooks, Traveloptics, etc.) → mixin_search_in_deps first, then mixin_get_dep_source with the `url` from results (or pass `path` directly)
- **Looking up a class** in any dependency (including compiled-only mods) → mixin_find_class
- **Understanding mixin target logic** when the mod has no sources → mixin_method_bytecode
- **Checking inheritance** before writing a mixin → mixin_type_hierarchy

## Dependency Navigation
- mixin_find_class: Look up any class by FQCN (Minecraft, mods, Java stdlib).
  Use includeMembers=true for API overview, includeSource=true for full code.
- mixin_search_symbols: Find classes/methods by name pattern across everything.
- mixin_search_in_deps: Regex search across all library/dependency sources
  (published and auto-decompiled). Use fileMask (e.g. "*irons*", "*traveloptics*")
  to scope to specific mods.
- mixin_get_dep_source: Read source from dependency jars or decompiled cache.
  **Required: `url`** (from mixin_search_in_deps results) **or** `path`
  (e.g. io/redspace/ironsspellbooks/api/util/Utils.java). Workflow:
  (1) mixin_search_in_deps to find matches; (2) copy the `url` from the result
  and pass to mixin_get_dep_source — or pass `path` directly if you know it.
  Optional: lineNumber, linesBefore, linesAfter.

## Semantic Navigation
- mixin_type_hierarchy: See inheritance chains. Essential before writing mixins.
- mixin_find_impls: Find all implementors of an interface.
- mixin_find_references: Find all usages of a class/method.
- mixin_call_hierarchy: See callers/callees of a method.
- mixin_super_methods: Find where a method is originally declared.

## Bytecode (for Mixin Targets)
- mixin_class_bytecode: Get bytecode info including synthetic methods.
  Use filter="synthetic" for lambda/bridge method mixin targets.
- mixin_method_bytecode: Get instructions for a specific method.
  **Use when the target mod has no sources** (e.g. Traveloptics in to-tweaks jar)
  to see exact call sites for @Redirect/@Inject.

## Workflow Rules
- When writing @Mixin: ALWAYS check mixin_type_hierarchy first.
- When targeting lambdas: ALWAYS use mixin_class_bytecode with filter="synthetic".
  Decompiled source DOES NOT show synthetic method names.
- When unsure about method origin: use mixin_super_methods.
- After writing any mixin: use the built-in get_file_problems to validate.
- After changing build.gradle deps: run `./gradlew mixinDecompile` to decompile
  new dependencies, then call mixin_sync_project to refresh IntelliJ's project model.
```

Replace `[Fabric/NeoForge/Forge]` with your actual loader.

### Claude Code / Claude Desktop

Add equivalent instructions to your `CLAUDE.md` or MCP client system prompt.
The MCP server name will depend on your client's configuration — check your
MCP client docs for the identifier assigned to the IntelliJ/JetBrains server.
The tool descriptions are self-documenting, but the workflow rules above
significantly improve mixin authoring accuracy.

## Building from Source

```bash
git clone https://github.com/yourname/mixin-mcp.git
cd mixin-mcp
./gradlew buildPlugin
```

The plugin ZIP will be at `build/distributions/mixin-mcp-<version>.zip`.

To launch a sandboxed IntelliJ instance with the plugin installed:

```bash
./gradlew runIde
```

## Publishing

### Local / Team Distribution

Build and share the ZIP file directly. Recipients install via
**Install Plugin from Disk** as described above.

For team-wide distribution without the Marketplace, host the ZIP on an internal
server and configure a
[Custom Plugin Repository](https://plugins.jetbrains.com/docs/intellij/custom-plugin-repositories.html).

### JetBrains Marketplace

#### 1. Sign the plugin (recommended)

Without signing, IntelliJ shows a warning dialog when users install the plugin.
Generate a key pair:

```bash
# Generate RSA private key (you'll set a password)
openssl genpkey -aes-256-cbc -algorithm RSA \
  -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096

# Convert to RSA form
openssl rsa -in private_encrypted.pem -out private.pem

# Generate self-signed certificate
openssl req -key private.pem -new -x509 -days 365 -out chain.crt
```

Set environment variables (never commit these):

```bash
export CERTIFICATE_CHAIN="$(cat chain.crt)"
export PRIVATE_KEY="$(cat private.pem)"
export PRIVATE_KEY_PASSWORD="your-password"
```

The IntelliJ Platform Plugin Template already configures the `signPlugin` task
to read these variables. Build the signed ZIP with:

```bash
./gradlew signPlugin
```

#### 2. First upload (manual)

The first version of a plugin must be uploaded manually:

1. Log in to [JetBrains Marketplace](https://plugins.jetbrains.com) with your
   JetBrains account
2. Go to your profile → **Add new plugin**
3. Upload the ZIP from `build/distributions/`
4. Fill in the plugin page: description, tags, screenshots, license
5. Submit — JetBrains will manually review before it goes live

#### 3. Subsequent versions (automated)

After the first manual upload, use Gradle for future releases:

```bash
export PUBLISH_TOKEN="your-marketplace-token"
./gradlew publishPlugin
```

Get your token from your JetBrains Marketplace profile → **My Tokens** →
**Generate Token**.

To publish to a beta channel first:

```kotlin
// build.gradle.kts
intellijPlatform {
    publishing {
        channels = listOf("beta")
    }
}
```

Users would need to add `https://plugins.jetbrains.com/plugins/beta/<pluginId>`
as a custom plugin repository to receive beta builds.

## License

Apache-2.0