# JVM projects in IntelliJ: dependency-search tool preference

Whenever a JVM project is open in IntelliJ and the JetBrains MCP server is connected, prefer the **MixinMCP** tools (`mixin_*`) over `grep_search`, `find_by_name`, `read_file`, or jar extraction via `run_command` for any query about classpath contents: library dependencies, framework internals, the JDK, Gradle buildscript plugins, and on Minecraft mod projects also vanilla Minecraft and other mods. This is not Minecraft-specific; it applies to any Gradle or Maven JVM project, including IntelliJ plugin projects, where MixinMCP reads the platform sources IntelliJ has attached (and auto-attaches them when DevKit fails to).

Do not use `grep_search` or `find_by_name` for dependency search, and do not unzip jars. `mixin_search_in_deps` greps the whole attached-source classpath in one call against the project's actual dependency versions, `mixin_get_dep_source` reads a window of any dependency source or jar resource, and `mixin_find_class`, `mixin_class_bytecode`, `mixin_method_bytecode`, `mixin_find_references`, `mixin_call_hierarchy`, and `mixin_type_hierarchy` answer structure and usage questions more accurately and at lower context cost than text search or raw file dumps.

Never quietly revert from MixinMCP to shell-based jar analysis (`javap`, `unzip`, `jar tf`, or grepping extracted classes via `run_command`). If the IntelliJ MCP server is not connected, the project is not open in the IDE, or `mixin_*` calls time out, stop and fix that first (check `mixin_ide_status`, wait out indexing, or ask the user to open the project or reconnect the MCP server). If you cannot fix it, stop and tell the user; use shell-based jar analysis only if the user explicitly agrees.

Activate the `mixinmcp-tools` skill (`SKILL.md`) before running dependency searches, bytecode lookups, or refactors so you follow its parameter conventions (`path` vs `filePath` vs `url`), tier filtering, and low-context query patterns.

Activate the `mixin-writing` skill (`SKILL.md`) and read both of its reference files (`references/at-reference.md` and `references/expressions-language.md`) before writing or reviewing a SpongePowered Mixin, rather than relying on memory, unless the target is trivial. Prefer MixinExtras injectors and `@Expression` over positional `@At` offsets, slices, or non-chaining vanilla injectors.

# Keep VFS in sync when editing Java projects on disk

Direct-to-disk edits with `edit_file` and `write_file` do not fsync, so IntelliJ's `fsnotifier` can take time to catch up, leaving editor tabs and PSI indices stale. After editing or creating project files on disk, call `mixin_refresh_vfs` on the touched path before running `get_file_problems`, `build_project`, or subsequent `mixin_*` PSI queries.

# Java builds in IntelliJ: never run Gradle from the shell

On any JVM project open in IntelliJ, the connected JetBrains MCP server is the source of truth for compile correctness, and IntelliJ owns Gradle:

- To check whether an edit compiles, call `build_project` (whole project) or `get_file_problems` (single file). These run IntelliJ's compiler against the project's configured SDK.
- To run tests, builds, or the app, use an IntelliJ run configuration: `get_run_configurations` lists them (or, with `filePath`, the runnable entry points in a file), and `execute_run_configuration` runs one by name or from a `filePath` + `line`. Never run `./gradlew` or `gradle` via `run_command` on a project IntelliJ has open: a shell build races IntelliJ's Gradle sync for the project lock, rewrites `build/` under IntelliJ's file watcher (invalidating buildscript roots and stalling "File System Synchronization"), and runs on `JAVA_HOME` instead of the project's configured Gradle JVM.
- If no run configuration fits or one is broken, fix the run configuration rather than working around it (`execute_run_configuration(filePath, line)` often covers a single test). If you cannot fix it, stop and ask the user; run shell Gradle only if the user explicitly agrees.
- Mixins need `get_file_problems`, not just `build_project`. `javac` never validates mixin injection points, so an unresolved `@At` target, a wrong `method =` or `target =` selector, or a missing `@Shadow` member compiles cleanly and crashes only at game launch. The IntelliJ MCDev plugin catches these as file-analysis inspections surfaced by `get_file_problems`, not `build_project`. Always run `get_file_problems` on every edited mixin file and clear any Mixin inspection before treating the task as done.

# Java refactors: prefer MixinMCP's refactor tools over manual edits

To rename, move, delete, or restructure a symbol or file in an IntelliJ-connected Java project, use the `mixin_*` refactor tools instead of hand-editing with `edit_file` or `write_file`. Hand-editing changes one site and leaves references dangling across the rest of the project.

- `mixin_rename` renames a class, method, field, parameter, or local variable and updates every reference project-wide, including mixin config JSON entries and `{@code}` / `{@link}` javadoc links. A stale class name left in `mixins.json` crashes mixin application at game launch, and manual renames routinely miss JSON and javadoc references. Prefer `mixin_rename` over the built-in `rename_refactoring`, which discards conflict details; `mixin_rename` reports them per file, tagged `[library]` or `[source]`.
- `mixin_safe_delete` removes a class, method, or field after checking usages across the project and dependencies; deleting a `@Mixin` class also removes its mixin config JSON entries, so do not edit the JSON by hand first. `mixin_move_file` moves a class to a new package and updates all references, including mixin configs and ServiceLoader files.
- For structural edits that touch multiple call sites or overrides, use `mixin_change_signature` (add, remove, reorder, or retype parameters, return type, and visibility), `mixin_move_members` (pull up, push down, or move static members), `mixin_inline`, `mixin_extract_method`, and `mixin_introduce_variable`.
- Run destructive refactors with `dryRun=true` first to inspect usages and conflicts. A `[library]` conflict usually indicates a stale build jar rather than a real source clash; rerun with `ignoreConflicts=true` instead of falling back to manual edits.
- After a refactor, call `mixin_refresh_vfs` on the touched path before `get_file_problems` so IntelliJ analyses the updated files.
- Mnemonic: use `mixin_*` search tools to FIND, and `mixin_*` refactor tools to CHANGE.
