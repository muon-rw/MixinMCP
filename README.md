# MixinMCP

<!-- Plugin description -->
An IntelliJ Platform plugin that extends the built-in MCP Server with tools for
Minecraft mod development — dependency navigation, semantic code analysis, and
bytecode inspection for mixin authoring.

The built-in MCP Server excludes libraries and dependencies from its tools. MixinMCP
adds 12 tools that search across your entire classpath, resolve type hierarchies,
and inspect bytecode — including the synthetic lambda methods that are invisible in
decompiled source but essential for mixin targeting.
<!-- Plugin description end -->

## Why?

Minecraft mod projects typically have 50-100 dependencies (remapped Minecraft
sources, mod APIs, libraries). The built-in MCP Server's tools explicitly exclude
all of them — your LLM can't look up a Minecraft class, search mod APIs, or trace
inheritance chains across libraries.

Mixin development makes this worse. Targeting a lambda in a Minecraft method
requires knowing the synthetic method name (e.g. `lambda$tick$0`), which only
exists in compiled bytecode. No existing MCP tool exposes this.

## Requirements

- IntelliJ IDEA 2025.2+ (Community or Ultimate)
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

### From JetBrains Marketplace

*(Once published)*

**Settings → Plugins → Marketplace** → search "MixinMCP" → **Install**

## Tools

### Source Navigation

| Tool | Description |
|------|-------------|
| `mixin_find_class` | Look up any class by FQCN — project, library, or JDK. Optionally include members or decompiled source. |
| `mixin_search_symbols` | Find classes, methods, or fields by name pattern across project and all dependencies. |
| `mixin_search_in_deps` | Regex search across dependency sources — like grep for your entire classpath. |
| `mixin_get_dep_source` | Retrieve decompiled source for a file path returned by `mixin_search_in_deps`. |

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
| `mixin_sync_project` | Trigger Gradle sync to re-index after dependency changes. |

## Configuring Your MCP Client

The tools are available automatically through IntelliJ's built-in MCP Server.
Connect your MCP client to IntelliJ the same way you would for the built-in
tools — MixinMCP tools appear alongside them with no extra configuration.

For best results, add a rule to your mod project that teaches the LLM when and
how to use each tool.

### Cursor

Create `.cursor/rules/mixinmcp.mdc` in your **mod project** (not the plugin
project):

```markdown
---
description: "MixinMCP tool usage for Minecraft modding with mixin support"
alwaysApply: true
---

This is a Minecraft mod project using [Fabric/NeoForge/Forge] with extensive
mixin usage and many dependencies.

You have access to the IntelliJ MCP server with MixinMCP tools. Use them:

## Dependency Navigation
- mixin_find_class: Look up any class by FQCN (Minecraft, mods, Java stdlib).
  Use includeMembers=true for API overview, includeSource=true for full code.
- mixin_search_symbols: Find classes/methods by name pattern across everything.
- mixin_search_in_deps: Regex search across all library/dependency sources.
- mixin_get_dep_source: Read dependency source files from search results.

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

## Workflow Rules
- When writing @Mixin: ALWAYS check mixin_type_hierarchy first.
- When targeting lambdas: ALWAYS use mixin_class_bytecode with filter="synthetic".
  Decompiled source DOES NOT show synthetic method names.
- When unsure about method origin: use mixin_super_methods.
- After writing any mixin: use the built-in get_file_problems to validate.
- After changing build.gradle deps: call mixin_sync_project.
```

Replace `[Fabric/NeoForge/Forge]` with your actual loader.

### Claude Code / Claude Desktop

Add equivalent instructions to your `CLAUDE.md` or MCP client system prompt.
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