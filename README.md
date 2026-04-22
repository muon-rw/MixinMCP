# MixinMCP

<!-- Plugin description -->
Extends IntelliJ's built-in MCP Server with tools for
Minecraft mod development.

### Key features:
#### 1. Robust broad-scope search:
- Find all overrides of a given method, across your entire classpath
- Find all mixins targeting a given method or class, across your entire classpath
- Search all uses of a field or method, across your entire classpath

#### 2. Class/Method Bytecode lookup:
- Find the *exact* target before writing a mixin, including synthetic lambdas

#### 3. Searches across your *entire* classpath including dependencies:
Alternative tools search only *your* project code — not remapped Minecraft sources, loader or mod APIs, libraries, or other mods you've added for integration or compatibility.
In the best case, they can only additionally see your currently active open file.

With this plugin, agents can easily scan your entire dependency network. This greatly speeds up development and debugging, and circumvents the need to find jars in your gradle cache and unzip/analyze them manually

#### 4. Built-in Skills for enhanced Mixin Writing:
- Improves compatibility by favoring MixinExtras injectors LLMs often fail to understand
- Favor precise modification without workarounds or slices via MixinExtras' robust `@Expression` annotation

#### 5. (Planned, in-development) Automatic Mappings lookup:
- Easily convert between any SRG, Intermediary, Yarn, or Mojmap mapped class, method, or field name

<!-- Plugin description end -->

## Setup

MixinMCP has two parts, and you need both for full-classpath search to work:

- **IntelliJ plugin** — registers the `mixin_*` tools on IntelliJ's built-in MCP Server.
- **Gradle plugin** — decompiles dependencies that don't publish sources so the search tools cover every JAR on your classpath.

**Prerequisites:** IntelliJ IDEA 2025.3+ (Community or Ultimate).

### 1. Install the IntelliJ plugin

**From Disk (local development):**

1. Build the plugin:
   ```bash
   ./gradlew buildPlugin
   ```
2. The plugin ZIP is at `build/distributions/mixin-mcp-<version>.zip`
3. In IntelliJ: **Settings → Plugins**, click the **⚙️** gear icon at the top, then choose **Install Plugin from Disk…**
4. Select the ZIP, restart IntelliJ

**From JetBrains Marketplace** *(not yet published)*: **Settings → Plugins → Marketplace** → search "MixinMCP" → **Install**

### 2. Enable IntelliJ's MCP Server

- **Settings → Plugins** → search "MCP Server" — confirm it's enabled. *(Bundled by default on recent IntelliJ versions.)*
- **Settings → Tools → MCP Server** → check **Enable MCP Server**.

### 3. Connect your MCP client

IntelliJ has an Auto-Configure option for most clients, which should work in most cases.

Once connected, MixinMCP tools appear alongside the built-in MCP tools automatically. *Most clients require a restart* after you first enable/auto-configure the server to connect.

**Verifying:** ask the model to list MCP tools from the JetBrains server. The `mixin_*` tools should appear. If they don't: (1) confirm MixinMCP is installed, (2) confirm the MCP Server plugin is enabled, (3) confirm your client is connected.

**Cursor:** the server is named **`user-jetbrains`** (the `user-` prefix is added by Cursor to all user-configured servers).

**Claude Code / Claude Desktop:** no extra config — skills auto-activate on Minecraft mod projects once the IntelliJ plugin detects the project layout (see auto-injection below).

**Auto-injection for assistant files.** When MixinMCP detects a Minecraft mod project (Fabric, Forge, NeoForge, Quilt, Architectury), it copies bundled resources on project open: **Cursor** files under `.cursor/` (rules and skills), and **Claude Code** skills under `.claude/skills/`. These teach the LLM when and how to use each tool, common pitfalls, and a mixin workflow checklist. New paths are appended under a `# MixinMCP auto-injected rules` block in `.gitignore`. Configure in **Settings → Tools → MixinMCP**:

| Setting | Default | Description |
|---------|---------|-------------|
| Automatically add Cursor and Claude project files | On | Master toggle — disables all injection (`.cursor/` and `.claude/`) |
| Overwrite existing files on project open | On | When off, only writes files that don't already exist |
| Warn when Gradle plugin is not detected | On | Shows a notification if `dev.mixinmcp.decompile` is missing |

For manual setup (other clients, non-Minecraft projects), copy the trees from [`src/main/resources/inject/cursor/`](src/main/resources/inject/cursor/) and [`src/main/resources/inject/claude/`](src/main/resources/inject/claude/) into your project.

### 4. Set up the Gradle plugin

Without this step, `mixin_search_in_deps` and `mixin_get_dep_source` can only see dependencies that published a `-sources.jar`. This means local jar dependencies, Cursemaven dependencies, and many from Modrinth Maven dependencies are totally invisible. The Gradle plugin decompiles the rest via [Vineflower](https://github.com/Vineflower/vineflower) into a local cache that the IntelliJ plugin indexes automatically.

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
    id("dev.mixinmcp.decompile") version "0.8.0"
}
```

**3. Run decompilation:** 
*(Unless disabled, this task already runs automatically after every gradle sync)*

```bash
./gradlew genDependencySources
```

The IntelliJ plugin reads the cache on project open and after every Gradle sync. Re-run `./gradlew genDependencySources` after changing dependencies. MixinMCP warns on project open if the Gradle plugin is missing.

For local development against an unpublished build, see [Decompilation cache details](#decompilation-cache-details) below.

## Tool reference

<details>
<summary>All 13 tools (click to expand)</summary>

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
| `mixin_call_hierarchy` | Callers and callees of a method — trace execution flow. |
| `mixin_super_methods` | Find where a method is originally declared in the hierarchy. |
| `mixin_find_targeting_mixins` | Find all `@Mixin` classes that target a given class/method — cross-mod conflict analysis. |

### Bytecode Inspection

| Tool | Description |
|------|-------------|
| `mixin_class_bytecode` | Bytecode-level class overview including synthetic methods. Use `filter="synthetic"` for lambda/bridge mixin targets. |
| `mixin_method_bytecode` | Full bytecode instructions for a specific method. INVOKE* instructions show the real owner class for `@At(target)`. |

### Project Management

| Tool | Description |
|------|-------------|
| `mixin_sync_project` | Trigger Gradle sync. The decompilation cache is re-read automatically after sync. |

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
  sync, exposing the decompiled `.java` files as `SyntheticLibrary` roots — indexed
  and searchable just like real sources.

Decompilation is a **blocking Gradle task**, not a background IDE operation. This
means tools never run against a half-populated cache — by the time you open the
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
# Reduce threads (less memory, slower)
./gradlew genDependencySources --threads=1

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

## Publishing

<details>
<summary>Local distribution and JetBrains Marketplace steps (click to expand)</summary>

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

</details>

## License

Apache-2.0
