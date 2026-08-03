# MixinMCP for Claude Code

Agent skills for the [MixinMCP](https://github.com/muon-rw/MixinMCP) IntelliJ plugin's MCP tools.

- **mixinmcp-tools**: when and how to use each `mixin_*` tool for full-classpath search, type hierarchies, call graphs, bytecode inspection, and reference-aware refactoring, plus the query patterns that cost the least context.
- **mixin-writing**: SpongePowered Mixin and MixinExtras reference: injector selection, `@At` targeting, the `@Expression` language, accessor/invoker patterns, and common pitfalls.

## Prerequisites

The skills drive MCP tools served by IntelliJ, so you need:

1. IntelliJ IDEA 2026.2+ with the [MixinMCP plugin](https://plugins.jetbrains.com/plugin/31407-mixinmcp) installed.
2. IntelliJ's built-in MCP Server enabled (Settings → Tools → MCP Server).
3. Claude Code connected to that server (`claude mcp list` should show it; use IntelliJ's Auto-Configure if not).

See the [MixinMCP setup guide](https://github.com/muon-rw/MixinMCP#setup) for the full walkthrough, including the companion Gradle plugin that makes source-less dependencies searchable.

## Install

```
/plugin marketplace add muon-rw/MixinMCP
/plugin install mixinmcp@mixinmcp
```

## Usage

Nothing to invoke manually: Claude applies the skills automatically when a task touches classpath lookup or mixin code in a JVM project. The `mixinmcp-tools` skill applies to any Gradle/Maven project; `mixin-writing` applies to Minecraft mod projects.

## License

GPL-3.0, same as MixinMCP. See [LICENSE](https://github.com/muon-rw/MixinMCP/blob/main/LICENSE).
