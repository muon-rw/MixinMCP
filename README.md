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

**Arguments must be valid JSON.** No trailing commas, no single quotes, no
unescaped special characters. An empty or malformed `arguments` object will
produce a parse error.

Tool descriptions document all parameters and defaults — read them before calling.

### Examples

Look up a class:

    CallMcpTool(
      server="user-jetbrains",
      toolName="mixin_find_class",
      arguments={"className": "net.minecraft.world.level.Level", "includeMembers": true}
    )

Search dependency sources, then read the result:

    CallMcpTool(
      server="user-jetbrains",
      toolName="mixin_search_in_deps",
      arguments={"regexPattern": "destroyBlock", "fileMask": "*Level*"}
    )
    // Results include `url:` lines — pass that url to mixin_get_dep_source:
    CallMcpTool(
      server="user-jetbrains",
      toolName="mixin_get_dep_source",
      arguments={
        "url": "<url from mixin_search_in_deps result>",
        "lineNumber": 42, "linesBefore": 10, "linesAfter": 20
      }
    )

Read source by known path (without searching first):

    CallMcpTool(
      server="user-jetbrains",
      toolName="mixin_get_dep_source",
      arguments={
        "path": "io/redspace/ironsspellbooks/player/ServerPlayerEvents.java",
        "lineNumber": 360, "linesBefore": 20, "linesAfter": 20
      }
    )

## Tool Selection

| Goal | Tool |
|------|------|
| Look up a class by FQCN | mixin_find_class |
| Search names across classpath | mixin_search_symbols |
| Grep dependency sources by regex | mixin_search_in_deps → then mixin_get_dep_source with returned `url` |
| Read a known dependency file | mixin_get_dep_source (pass `path`, e.g. `io/redspace/.../Utils.java`) |
| Inheritance chain | mixin_type_hierarchy |
| All implementors | mixin_find_impls |
| All usages of a class/method | mixin_find_references |
| Call graph | mixin_call_hierarchy |
| Method origin in hierarchy | mixin_super_methods |
| Synthetic/lambda method names | mixin_class_bytecode (filter="synthetic") |
| Bytecode for a specific method | mixin_method_bytecode |

## Common Pitfalls

**mixin_search_in_deps:**
- fileMask is a glob matched against the **file path inside the jar** (e.g.
  `net/minecraft/world/entity/LivingEntity.java`). It does NOT match jar names
  or Maven coordinates.
  - `*LivingEntity*` — good, matches that class specifically
  - `*minecraft*` — broad, matches any path containing "minecraft"; prefer
    narrower masks when you know the class name
  - `*.java` — effectively no filter
- Prefer simple single-term regex patterns. Complex alternation (e.g.
  `addEffect.*dimension|changeDimension`) is fragile — make separate calls.
- Broad searches can time out. Increase `timeout` (e.g. 20000–30000) for
  searches without a fileMask or on projects with 50+ dependencies.

**mixin_get_dep_source:**
- `url`: copy the exact `url:` string from mixin_search_in_deps output. This is
  the most reliable method after a search.
- `path`: use the class's package path with `/` separators and `.java` extension
  (e.g. `io/redspace/ironsspellbooks/api/util/Utils.java`). This is NOT a
  filesystem path. Convenient when you already know the class location.
- If a path is not found, fall back to mixin_search_in_deps to locate the file,
  then use the returned `url`.

**mixin_class_bytecode:**
- Decompiled source does NOT show synthetic method names. If you need to target
  a lambda in @Redirect or @Inject, you MUST use this tool with filter="synthetic".

## Mixin Workflow
1. Before writing @Mixin: ALWAYS check mixin_type_hierarchy first.
2. When targeting lambdas: ALWAYS use mixin_class_bytecode with filter="synthetic".
   Decompiled source DOES NOT show synthetic method names.
3. When unsure about method origin: use mixin_super_methods.
4. After writing any mixin: use the built-in get_file_problems to validate.
5. After changing build.gradle deps: run `./gradlew mixinDecompile` to decompile
   new dependencies, then call mixin_sync_project to refresh IntelliJ's project model.

**Note:** All tools accept an optional `projectPath` parameter. You don't need
it when only one project is open in IntelliJ (the common case). If you have
multiple projects open and tools target the wrong one, pass the workspace root
path as `projectPath` to disambiguate.

## Troubleshooting
**If `mixin_*` tools are not found or the server is unavailable:**
The `user-jetbrains` MCP server only appears after Cursor connects to IntelliJ's
MCP Server. If you get "MCP server does not exist: user-jetbrains", try:
1. Ensure IntelliJ is running with the project open
2. Check IntelliJ: Settings → Plugins → verify both "MCP Server" and "MixinMCP"
   are enabled
3. **Restart Cursor** — the MCP server list is cached at startup and may not
   reflect a newly-started IntelliJ instance
```

Replace `[Fabric/NeoForge/Forge]` with your actual loader.

### Claude Code / Claude Desktop

Add equivalent instructions to your `CLAUDE.md` or MCP client system prompt.
The MCP server name will depend on your client's configuration — check your
MCP client docs for the identifier assigned to the IntelliJ/JetBrains server.
The tool descriptions are self-documenting, but the workflow rules and common
pitfalls above significantly improve mixin authoring accuracy.

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