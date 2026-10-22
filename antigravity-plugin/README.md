# MixinMCP for Antigravity

Agent skills and rules for the [MixinMCP](https://github.com/muon-rw/MixinMCP) IntelliJ plugin's MCP tools, tailored for Google Antigravity (`agy` CLI, Antigravity IDE, and Antigravity 2.0).

- **rules/AGENTS.md**: steers Antigravity to prefer `mixin_*` MCP tools over `grep_search`, `read_file`, `find_by_name`, or manual jar extraction for classpath lookups, and triggers the companion skills when needed.
- **mixinmcp-tools**: when and how to use each `mixin_*` tool for full-classpath search, type hierarchies, call graphs, bytecode inspection, and reference-aware refactoring, plus the query patterns that cost the least context.
- **mixin-writing**: SpongePowered Mixin and MixinExtras reference: injector selection, `@At` targeting, the `@Expression` language, accessor/invoker patterns, and common pitfalls.

## Prerequisites

The skills drive MCP tools served by IntelliJ, so you need:

1. IntelliJ IDEA 2026.2+ with the [MixinMCP plugin](https://plugins.jetbrains.com/plugin/31407-mixinmcp) installed.
2. IntelliJ's built-in MCP Server enabled (Settings → Tools → MCP Server).
3. Antigravity connected to that server (`agy mcp list` or **Additional Options (`...`) → MCP Servers** should show it; if not, run `agy mcp add jetbrains http://127.0.0.1:64342/stream` using the port shown in IntelliJ's MCP Server settings, or configure `~/.gemini/config/mcp_config.json`).

See the [MixinMCP setup guide](https://github.com/muon-rw/MixinMCP#setup) for the full walkthrough, including the companion Gradle plugin that makes source-less dependencies searchable.

## Install

Install directly from GitHub:

```bash
agy plugin install https://github.com/muon-rw/MixinMCP/tree/2026.2+/antigravity-plugin
```

Or install from a local clone of the repository:

```bash
agy plugin install /path/to/MixinMCP/antigravity-plugin
```

Or, if you already have the MixinMCP plugin installed in Claude Code, import it directly:

```bash
agy plugin import claude
```

## Usage

Nothing to invoke manually: Antigravity loads the plugin's rule and activates the skills automatically when a task touches classpath lookup or mixin code in a JVM project. The `mixinmcp-tools` skill applies to any Gradle/Maven project; `mixin-writing` applies to Minecraft mod projects.

## License

GPL-3.0, same as MixinMCP. See [LICENSE](https://github.com/muon-rw/MixinMCP/blob/main/LICENSE).
