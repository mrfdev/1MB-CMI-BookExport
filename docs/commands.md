# Commands

The registered command is `/bookexport`; `/bexport` is an alias for every route.
Help and tab completion are permission-aware. Commands described as
console/player can be run by either sender type.

## Author and Public Commands

| Command | Context | Permission | Description |
| --- | --- | --- | --- |
| `/bookexport` | Player | `bookexport.export` | Process the held signed book using its signed title. It stages in staged mode and publishes directly in direct mode. |
| `/bookexport export [title]` | Player | `bookexport.export`; a supplied title also needs `bookexport.export.custom-title` | Explicitly follow the configured workflow. A book and quill requires a supplied title. |
| `/bookexport stage [title]` | Player | `bookexport.export`; a supplied title also needs `bookexport.export.custom-title` | Always create a staged draft, even when the configured workflow is direct. A book and quill requires a supplied title. |
| `/bookexport <title>` | Player | `bookexport.export` and `bookexport.export.custom-title` | Legacy custom-title shorthand that follows the configured workflow. Prefer the explicit `export` or `stage` form. |
| `/bookexport info` | Console/player | `bookexport.info` | Show the player-facing introduction, installed version/build, compatibility, current workflow, useful starting commands, and canonical docs link. |
| `/bookexport help` | Console/player | `bookexport.help` | Show only commands the sender may use. |
| `/bookexport ?` | Console/player | `bookexport.help` | Alias for `/bookexport help`. |

Use the explicit route when a title is also a reserved subcommand. For example,
`/bookexport stage info` stages a book with the requested title `info`, while
`/bookexport info` opens plugin information.

## File Lists

| Command | Context | Permission | Description |
| --- | --- | --- | --- |
| `/bookexport list [page]` | Console/player | `bookexport.admin.list` | List published text filenames. A bare positive number selects a published-list page. |
| `/bookexport list published [page]` | Console/player | `bookexport.admin.list` | List published filenames. |
| `/bookexport list staged [page]` | Console/player | `bookexport.admin.list.staged` | List staged drafts and their review state. |
| `/bookexport list archive [page]` | Console/player | `bookexport.admin.list.archive` | List archived published-draft copies. |
| `/bookexport list backups [page]` | Console/player | `bookexport.admin.list.backups` | List backups created before replacements. |
| `/bookexport admin list [published\|staged\|archive\|backups] [page]` | Console/player | Permission matching the selected scope | Administrative form of the same scoped list command. With no scope, it lists published files. |

Pages must be positive integers. Player list output has clickable navigation and
copyable filenames. Staged entries show only actions allowed by the sender's
permissions. Review opens read-only details; Approve, Changes, and Publish place a
command in chat for deliberate submission. A suggested Publish action always uses
the safe `fail` mode.

## Review and Publication

| Command | Context | Permission | Description |
| --- | --- | --- | --- |
| `/bookexport admin review <staged-file>` | Console/player | `bookexport.admin.review` | Recalculate current integrity and show content-free draft and review metadata. It does not display or change the book text. |
| `/bookexport admin approve <staged-file>` | Console/player | `bookexport.admin.approve` | Approve the staged file's current exact bytes. A later change invalidates that approval. |
| `/bookexport admin changes <staged-file>` | Console/player | `bookexport.admin.approve` | Record a changes-requested decision and block publication until the current draft is approved. |
| `/bookexport admin publish <staged-file> [fail\|unique\|replace]` | Console/player | `bookexport.admin.publish`; `replace` also needs `bookexport.admin.replace` | Publish a verified staged draft. An omitted mode uses the configured default. |
| `/bookexport admin history [page]` | Console/player | `bookexport.admin.history` | List retained publication records newest first. |
| `/bookexport admin history show <manifest-id>` | Console/player | `bookexport.admin.history` | Show one retained record using its complete UUID. |

The `.txt` extension may be omitted from a staged filename; BookExport resolves a
single direct staged text file case-insensitively. Filenames containing spaces are
accepted. Nested paths, traversal, symbolic links, non-text files, and ambiguous
case variants are rejected.

Publication modes behave as follows:

| Mode | Behavior |
| --- | --- |
| `fail` | Stop if a case-insensitive published name exists; otherwise publish under the staged filename. |
| `unique` | Add `_1`, `_2`, and later suffixes until an unused published name is available. |
| `replace` | Back up and atomically replace an existing published file. It is denied when no target exists. |

`replace-with-backup` is the configuration spelling and is also accepted as the
command-mode equivalent of `replace`; player help and completion use `replace`.
Publication does not reload CMI. After verifying the result, an administrator must
run the appropriate CMI refresh command.

## Status, Reload, and Diagnostics

| Command | Context | Permission | Description |
| --- | --- | --- | --- |
| `/bookexport admin` | Console/player | `bookexport.admin.status` | Show BookExport version/build, validated workflow, directory health, manifest health, output settings, and the last recorded failure. |
| `/bookexport admin status` | Console/player | `bookexport.admin.status` | Explicit form of `/bookexport admin`. |
| `/bookexport reload` | Console/player | `bookexport.admin.reload` | Reload and validate `config.yml`. |
| `/bookexport admin reload` | Console/player | `bookexport.admin.reload` | Administrative form of the same safe reload. |
| `/bookexport debug` or `/bookexport debug runtime` | Console/player | `bookexport.admin.debug` | Show version/build, Java, Paper, artifact, workflow, destination health, and the last failure. |
| `/bookexport debug book` | Player | `bookexport.admin.debug` | Inspect main-hand material, signed state, title/author presence, pages, and UTF-16 units without showing book content. |
| `/bookexport debug cmi` | Console/player | `bookexport.admin.debug` | Show detected CMI, CMILib, and PlaceholderAPI versions plus the selected CMI output settings. |
| `/bookexport debug workflow` | Console/player | `bookexport.admin.debug` | Show workflow mode, collision default, scope health/counts, manifest health, and the last failure. |
| `/bookexport debug preview [title]` | Player | `bookexport.admin.debug`; a supplied title also needs `bookexport.export.custom-title` | Preview destination scope, proposed filename, page count, UTF-16 units, and UTF-8 bytes without writing a file. |
| `/bookexport admin debug [runtime\|book\|cmi\|workflow\|preview [title]]` | Same as selected diagnostic | `bookexport.admin.debug`; preview title rule still applies | Administrative form of the debug family. Omitting a detail selects `runtime`. |

A rejected configuration reload leaves the previously validated runtime settings
active. Installing or updating the plugin JAR still requires a clean server
restart; Bukkit `/reload` and hot-reload plugin managers are unsupported.

## Console and Completion Notes

- Export, stage, held-book diagnostics, and preview require a player because they
  read the main-hand item.
- Console can use information, help, lists, review decisions, publication,
  history, status, reload, and non-book diagnostics.
- Staged-filename completion requires both the action permission and
  `bookexport.admin.list.staged`, preventing an action-only sender from enumerating
  private draft names.
- Replacement completion appears only with `bookexport.admin.replace`.
- History detail completion exposes complete record IDs only to senders with
  `bookexport.admin.history`.
