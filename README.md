# BookExport

BookExport is a Paper 26.2 administration plugin that turns a written book or book and quill into a UTF-8 `.txt` file. Its CMI output profile preserves Minecraft page order, colors, decorations, blank lines, Unicode text, and placeholder-looking tokens while producing valid CMI CustomText pagination.

Fresh installations use a review-first workflow: an author stages a draft, a trusted administrator reviews it, and an administrator publishes it into CMI's CustomText directory. Existing version 2 configurations continue in direct compatibility mode until an administrator deliberately migrates them.

The 2.0.1 build modernizes the original 1.21 plugin with Java 25 bytecode, Paper 26.2 APIs, Adventure messages, Gradle, correct CMI pagination, granular permissions, validated workflow directories, collision-safe publication, content-free draft manifests, diagnostics, and regression tests.

Canonical player documentation: [docs.1moreblock.com/custom-server-plugins/bookexport/](https://docs.1moreblock.com/custom-server-plugins/bookexport/)

## Compatibility

| Component | Supported target |
| --- | --- |
| Server | Paper 26.2 only |
| Compile API | `io.papermc.paper:paper-api:26.2.build.60-beta` |
| Java bytecode | Java 25 |
| Tested runtimes | Oracle Java 25.0.2 and 26.0.1 |
| Build tool | Gradle 9.4.1 wrapper |
| BookExport | `2.0.1` (repository build `017`) |

Older Minecraft, Paper, Spigot, and Java releases are intentionally unsupported.

## Documentation

The README is the complete technical overview. Focused source pages are available for readers and for the namespace-safe central documentation importer:

| Page | Audience and purpose |
| --- | --- |
| [Player guide](docs/player-guide.md) | Friendly introduction, trusted-author workflow, commands, limits, and important consequences |
| [Commands](docs/commands.md) | Complete command, alias, console/player, and click-action reference |
| [Permissions](docs/permissions.md) | Defaults, inheritance, replacement separation, and legacy aliases |
| [Placeholders](docs/placeholders.md) | Filename and pagination tokens plus PlaceholderAPI behavior |
| [Configuration](docs/configuration.md) | Every setting, validation rule, persistence behavior, reload, and version 2 migration |
| [Installation](docs/installation.md) | Java/Paper requirements, build, clean install, update, and readiness checks |
| [Integrations](docs/integrations.md) | Exact CMI, CMILib, PlaceholderAPI, LuckPerms, and Vault relationships |
| [Troubleshooting](docs/troubleshooting.md) | Controlled failure states and recovery guidance |
| [Importer manifest](docs/plugin-docs.yml) | Stable project identity for the central 1MoreBlock documentation system |

## Feature introduction

Use Minecraft's book editor as an in-game content authoring tool. Hold a finished book and run `/bookexport`. On a fresh installation, BookExport writes a staged draft for staff review instead of immediately changing a live CMI text. A publisher can inspect the draft outside Minecraft, verify its content-free manifest in-game, record an approval or changes request, and publish it with an explicit command.

With the default CMI profile, the result starts with `<AutoPage>` and places `<NextPage>` only between Minecraft pages, so CMI page 1 matches Minecraft page 1.

Typical uses include:

- Drafting and publishing CMI CustomText rules, guides, news, help, and event pages
- Reviewing player-authored server copy before it becomes live
- Archiving published revisions and backing up replaced texts
- Archiving written books as readable UTF-8 text
- Converting Minecraft colors into CMI, MiniMessage, ampersand, section-sign, or plain text

## Features

- Supports signed written books and unsigned book-and-quill items in the main hand.
- Uses the signed title automatically or an explicit custom title.
- Provides unambiguous `/bookexport export [title]` and `/bookexport stage [title]` routes for titles that match subcommands.
- Stages normal exports by default on config version 3; explicit `stage` always stages, even in direct mode.
- Creates an atomic `<draft>.bookexport-manifest.properties` sidecar for each managed staged draft.
- Claims a content-free `<draft>.bookexport-creating` marker before exposing a native staged `.txt`, so an interrupted pair creation fails closed instead of being mistaken for a legacy draft.
- Records stable manifest ID, origin, intended and actual filenames, creator/adopter and book-author metadata, UTC creation/adoption time, page/unit/byte counts, and SHA-256 without storing page content.
- Records exact review and publication states, actors, timestamps, checksums, collision mode, final filename, archive, backup, and outcome.
- Provides review, approval, changes-requested, and paginated history commands while keeping approval recommended and publication backward compatible.
- Publishes reviewed drafts with `fail`, `unique`, or backed-up `replace` collision behavior.
- Detects managed drafts changed after staging or approval and blocks unsafe publication until their manifest is reviewed and approved again.
- Attempts an atomic manifest checkpoint immediately after each committed live publication and before archival; a stored pending record blocks repeat publication, and an archival failure retains the draft.
- Preserves page order and page boundaries without reflowing or truncating valid book content.
- Produces correct CMI pagination: controlled `<AutoPage>` first line and `<NextPage>` only between pages.
- Preserves CMI and PlaceholderAPI-looking tokens for CMI to resolve for the eventual viewer.
- Converts validated Minecraft colors and decorations to `vanilla`, `legacy`, `strip`, `cmi`, or `mini` output.
- Uses normalized Unicode-aware filenames with configurable templates, lowercasing, length limits, and collision suffixes.
- Writes complete UTF-8 temporary files before collision-safe moves.
- Lists published, staged, archived, or backup files with chat-safe pagination and clickable controls for players.
- Shows staged review status and safe Review, Approve, Changes, and Publish suggestions without executing a mutating action immediately.
- Validates all workflow paths and configuration during startup and reload.
- Offers player-safe `info` and `help`, plus trusted `admin` and `debug` diagnostics.
- Retains the old `exportbook.*` permission nodes as compatibility aliases.
- Treats compiler warnings as errors and includes unit tests for high-risk conversion and storage logic.

## Requirements and optional integrations

Paper is the only production/plugin API dependency. JUnit and the Paper-aligned Adventure API are used only by the test suite and are not bundled. BookExport does not call the APIs of CMI, CMILib, PlaceholderAPI, Vault, or LuckPerms.

The current test-server versions, audited on 2026-07-14, are:

| Plugin | Tested version | Relationship to BookExport |
| --- | --- | --- |
| CMI | 9.8.8.5 | Optional consumer of published CustomText files |
| CMILib | 1.5.9.9 | CMI's dependency, not BookExport's dependency |
| PlaceholderAPI | 2.12.3 | Optional; CMI can resolve preserved tokens at display time |
| LuckPerms | 5.5.59 | Optional Bukkit permission provider |
| Vault CMI build | Manifest version 1.7.3-CMI | Unrelated to BookExport |

Do not add these plugins to BookExport's Gradle dependencies unless BookExport later begins calling their APIs.

## Installation

1. Build the plugin:

   ```bash
   ./gradlew clean build
   ```

2. Copy `build/libs/1MB-BookExport-v2.0.1-017-j25-26.2.jar` to the Paper 26.2 server's `plugins/` directory.
3. Remove any older BookExport JAR so Paper does not discover two copies.
4. Restart Paper cleanly. Do not use Bukkit `/reload` or a hot-reload plugin.
5. Confirm `/version BookExport` reports `2.0.1`.
6. Confirm `/bookexport info` reports build `017` and opens the canonical player documentation.
7. Run `/bookexport admin status` and verify config version 3, workflow `staged`, four writable workflow directories, and collision mode `fail`.

If the server already has a version 2 `config.yml`, BookExport intentionally starts in direct compatibility mode. It does not rewrite the config or move existing files. Follow [Migrating a version 2 configuration](#migrating-a-version-2-configuration) when ready to enable staged-by-default exports.

## Admin-player quick start

1. Give a trusted staff player the non-replacing master node:

   ```text
   /lp user <player> permission set bookexport.admin true
   ```

2. Put a written book or book and quill in the player's main hand.
3. Preview the rendered output without writing a file:

   ```text
   /bookexport debug preview Server Rules
   ```

4. Stage the book. With the packaged version 3 config, `/bookexport` and `/bookexport export` also stage:

   ```text
   /bookexport stage Server Rules
   ```

5. List the generated draft, then inspect its manifest and verify its current SHA-256:

   ```text
   /bookexport list staged
   /bookexport admin review server_rules.txt
   ```

6. After reviewing the `.txt` content, record approval of its current bytes:

   ```text
   /bookexport admin approve server_rules.txt
   ```

   Approval is recommended but deliberately non-blocking for an unchanged unreviewed or older untracked draft. If a managed draft changes, is marked changes-requested, or has a corrupt manifest, BookExport blocks publication until it is reviewed and approved or repaired.

7. Click the draft's Publish action. It fills in a command but does not run it. Review the command and press Enter, or type it directly:

   ```text
   /bookexport admin publish server_rules.txt fail
   ```

8. Reload CMI and open the published text:

   ```text
   /cmi reload
   /cmi ctext server_rules <player>
   ```

BookExport never runs `/cmi reload` automatically.

An unchanged unreviewed or legacy draft remains publishable for compatibility. That publication records the publisher as the implicit approving actor, including the exact published SHA-256. Explicit approval is still the recommended staff workflow because it makes the review boundary and reviewed bytes visible before publication.

The `bookexport.admin` master node does **not** include `bookexport.admin.replace`. Grant the latter separately only to administrators who may replace a live text after BookExport creates a backup.

Only grant export access to trusted authors. Book content is intentionally preserved, so CMI directives, interactive tags, and PlaceholderAPI tokens written into a book may become active when CMI displays the published file.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/bexport ...` | Alias for any `/bookexport ...` route | Same as the routed command |
| `/bookexport` | Process a signed written book using its title; stages in staged mode and publishes directly in direct mode | `bookexport.export` |
| `/bookexport export [title]` | Explicit normal-workflow export; title is required for book and quill | `bookexport.export`; a custom title also needs `bookexport.export.custom-title` |
| `/bookexport stage [title]` | Always create a staged draft, regardless of workflow mode | `bookexport.export`; a custom title also needs `bookexport.export.custom-title` |
| `/bookexport <title>` | Legacy shorthand for a custom-title normal-workflow export | `bookexport.export` and `bookexport.export.custom-title` |
| `/bookexport info` | Introduce BookExport, show installed version/build and compatibility, suggest starting commands, and open the canonical docs or source | `bookexport.info` |
| `/bookexport help` or `/bookexport ?` | Show only commands the sender may use | `bookexport.help` |
| `/bookexport admin [status]` | Show validated workflow settings and directory health | `bookexport.admin.status` |
| `/bookexport admin list [page]` | List published `.txt` files; published is the backward-compatible default scope | `bookexport.admin.list` |
| `/bookexport admin list <published\|staged\|archive\|backups> [page]` | List files in an explicit workflow scope | Matching `bookexport.admin.list...` permission |
| `/bookexport admin review <staged-file>` | Show the draft manifest, review state, and current integrity without displaying content | `bookexport.admin.review` |
| `/bookexport admin approve <staged-file>` | Approve the draft's current SHA-256; later changes invalidate that approval | `bookexport.admin.approve` |
| `/bookexport admin changes <staged-file>` | Mark a managed draft changes-requested and revoke its approval | `bookexport.admin.approve` |
| `/bookexport admin history [page]` | List pending/finalized publication records newest first | `bookexport.admin.history` |
| `/bookexport admin history show <manifest-id>` | Show one retained publication record by its stable UUID | `bookexport.admin.history` |
| `/bookexport admin publish <staged-file> [fail\|unique\|replace]` | Publish a staged draft; omitted mode uses `publish-collision-mode` | `bookexport.admin.publish`; `replace` also needs `bookexport.admin.replace` |
| `/bookexport admin reload` | Reload and validate `config.yml` | `bookexport.admin.reload` |
| `/bookexport admin debug [runtime\|book\|cmi\|workflow\|preview [title]]` | Use the complete read-only debug family through the admin route | `bookexport.admin.debug`; a custom preview title also needs `bookexport.export.custom-title` |
| `/bookexport list [page]` | Backward-compatible published-list shortcut | `bookexport.admin.list` |
| `/bookexport list <published\|staged\|archive\|backups> [page]` | Scoped list shortcut | Matching list permission |
| `/bookexport reload` | Compatibility shortcut for admin reload | `bookexport.admin.reload` |
| `/bookexport debug [runtime]` | Show Java, Paper, build, and failure diagnostics | `bookexport.admin.debug` |
| `/bookexport debug book` | Inspect held-book type and size without showing content | `bookexport.admin.debug` |
| `/bookexport debug cmi` | Show detected CMI stack and renderer settings | `bookexport.admin.debug` |
| `/bookexport debug workflow` | Show config compatibility mode, workflow directories, counts, and collision mode | `bookexport.admin.debug` |
| `/bookexport debug preview [title]` | Preview destination scope, sanitized filename candidate, pages, UTF-16 units, and bytes without writing | `bookexport.admin.debug`; a custom title also needs `bookexport.export.custom-title` |

All information, administration, review, history, list, publish, and debug commands work from the console except held-book inspection, preview, and export/stage.

When multiple list pages exist, player Previous/Next controls run the adjacent list command and preserve the selected scope. A filename can be clicked to copy it. Staged rows identify unreviewed, approved, changes-requested, changed, already-published, association-mismatch, missing, corrupt, or legacy state and expose only actions the sender may use. Review is read-only; Approve, Changes, and Publish controls suggest commands for deliberate submission. Publish continues to suggest `/bookexport admin publish <file> fail`, never executes immediately, and never inherits a configured replacement policy. Console output remains readable, and console publishers type the explicit command manually.

## Command examples

```text
# Stage a signed book using its signed title in the default workflow
/bookexport

# Explicitly stage any held book under a chosen title
/bookexport stage July News

# Follow the configured workflow under a chosen title
/bookexport export July News

# Export a title that is also a reserved subcommand
/bookexport export info

# Inspect runtime and workflow health
/bookexport info
/bookexport admin status
/bookexport debug runtime
/bookexport debug cmi
/bookexport debug workflow

# Preview without writing
/bookexport debug preview July News

# List workflow scopes and preserve scope while paging
/bookexport list
/bookexport list published 2
/bookexport list staged
/bookexport admin list archive 1
/bookexport admin list backups

# Inspect, approve, or request changes without exposing draft content
/bookexport admin review july_news.txt
/bookexport admin approve july_news.txt
/bookexport admin changes july_news.txt

# Browse content-free manifest history and inspect a stable record
/bookexport admin history 2
/bookexport admin history show 123e4567-e89b-12d3-a456-426614174000

# Publish only when no case-insensitive target exists
/bookexport admin publish july_news.txt fail

# Publish under july_news_1.txt when july_news.txt already exists
/bookexport admin publish july_news.txt unique

# Replace july_news.txt after creating a timestamped backup
/bookexport admin publish july_news.txt replace

# Apply an edited config safely
/bookexport admin reload
```

## Permissions

| Permission | Default | Purpose |
| --- | --- | --- |
| `bookexport.admin` | OP | Grants every documented non-replacing capability, including staging, review decisions, history, scoped lists, and publication; deliberately excludes replacement |
| `bookexport.export` | OP | Process the held book through the configured workflow or explicit `stage` route |
| `bookexport.export.custom-title` | OP | Override the signed title or name a writable-book export |
| `bookexport.info` | Everyone | View public plugin and compatibility information |
| `bookexport.help` | Everyone | View permission-filtered help |
| `bookexport.admin.status` | OP | View paths, settings, and output health |
| `bookexport.admin.list` | OP | List published filenames |
| `bookexport.admin.list.staged` | OP | List staged draft filenames and receive publish suggestions |
| `bookexport.admin.list.archive` | OP | List archived draft filenames |
| `bookexport.admin.list.backups` | OP | List replacement backup filenames |
| `bookexport.admin.review` | OP | View manifest metadata, review state, and current checksum integrity |
| `bookexport.admin.approve` | OP | Approve the current draft bytes or mark a draft changes-requested |
| `bookexport.admin.history` | OP | List and inspect retained publication records |
| `bookexport.admin.publish` | OP | Publish staged drafts with `fail` or `unique`, and enter the publish workflow |
| `bookexport.admin.replace` | False | Use the backed-up `replace` collision mode; independent and not inherited by `bookexport.admin` |
| `bookexport.admin.reload` | OP | Reload configuration |
| `bookexport.admin.debug` | OP | View runtime, workflow, CMI, book-size, and preview diagnostics |

Legacy aliases remain available for existing LuckPerms data:

| Legacy node | Default | Grants |
| --- | --- | --- |
| `exportbook.command` | False | `bookexport.admin`, therefore every non-replacing capability but not `bookexport.admin.replace` |
| `exportbook.export` | False | `bookexport.export` and `bookexport.export.custom-title` |
| `exportbook.list` | False | Published listing through `bookexport.admin.list` |
| `exportbook.help` | False | `bookexport.help` |
| `exportbook.reload` | False | `bookexport.admin.reload` |

BookExport relies on Bukkit permission inheritance. It does not manually bypass a denied child node when a master node is present. Replacement is intentionally outside both the current and legacy master nodes.

## Staged publishing workflow

### Workflow modes

| Mode | Normal `/bookexport` and `export` behavior | Explicit `stage` behavior |
| --- | --- | --- |
| `staged` | Write a unique draft into `staging-directory` | Write a unique draft into `staging-directory` |
| `direct` | Write immediately into `exported-books-directory` with a unique suffix when needed | Still write a unique draft into `staging-directory` |

Fresh config version 3 files default to `staged`. Existing version 2 files load in `direct` compatibility mode, regardless of any undeclared workflow key, until deliberately migrated.

### File scopes

| Scope | Default directory | Contents | List permission |
| --- | --- | --- | --- |
| `published` | `~/plugins/CMI/CustomText/` | Live CMI CustomText files | `bookexport.admin.list` |
| `staged` | `plugins/BookExport/staging/` | Drafts awaiting review | `bookexport.admin.list.staged` |
| `archive` | `plugins/BookExport/archive/` | Timestamped copies of successfully published drafts | `bookexport.admin.list.archive` |
| `backups` | `plugins/BookExport/backups/` | Timestamped copies created before replacement | `bookexport.admin.list.backups` |

`/bookexport list [page]` and `/bookexport admin list [page]` continue to mean the published scope for backward compatibility.

Manifest sidecars are workflow metadata rather than a fifth `.txt` scope. They are excluded from normal file lists and are never copied into CMI's CustomText directory.

### Draft manifests and review state

A managed staged file such as `server_rules.txt` has a strict UTF-8 properties sidecar named `server_rules.txt.bookexport-manifest.properties`. The sidecar uses schema version 1, a monotonically increasing revision, and a stable UUID so its record remains unambiguous even when `unique` publication changes the final filename or a later draft reuses the intended filename.

Each manifest records content-free provenance and integrity data:

| Group | Recorded fields |
| --- | --- |
| Identity | Schema version, stable manifest UUID, revision, and `native` or `legacy-adopted` origin |
| Names | Intended filename and actual staged filename |
| Source | Native creator name/UUID, signed book author when present, and UTC creation time; a legacy record instead identifies the adopter and adoption time |
| Size | Source pages, Java UTF-16 units, and rendered UTF-8 bytes; unavailable legacy source counts remain explicitly unknown |
| Initial integrity | SHA-256 of the staged `.txt` bytes |
| Review | `unreviewed`, `approved`, or `changes-requested`; decision actor/time, exact reviewed byte count and SHA-256, and whether approval was implicit |
| Publication | `staged`, `published-archive-pending`, or `published`; publisher, UTC publication time, collision mode, and final filename |
| Outcome | Published SHA-256 (the exact effective review fingerprint), archive filename, and replacement backup filename/byte count/SHA-256 when applicable |

The manifest never contains Minecraft page text or the rendered CustomText body. Server-log audit messages likewise omit those bodies; they intentionally include private metadata such as filenames, actors, stable IDs, counts, and checksums.

Review is recommended, not a new mandatory step for every existing workflow:

- A newly staged managed draft starts `unreviewed` with publication state `staged`.
- `/bookexport admin review <file>` recalculates integrity and shows manifest metadata without showing the draft body.
- `/bookexport admin approve <file>` approves its current bytes and stores the reviewing actor, time, and SHA-256.
- `/bookexport admin changes <file>` records changes-requested and revokes approval. It must be approved before publication.
- An unchanged `unreviewed` managed draft may still be published for compatibility. Publication records the publisher as the implicit approving actor.
- A staged legacy `.txt` file without a sidecar may still be published. Its new record uses a legacy origin, preserves unknown source fields as unknown, and records the publisher's implicit approval.
- If a managed draft's bytes no longer match the checksum that defines its current review state, publication stops and retains the draft until an administrator reviews and approves the changed bytes.
- A corrupt managed manifest fails closed. Repair or restore the sidecar before publication; BookExport does not silently treat it as an untracked legacy file.
- A malformed or case-ambiguous active sidecar is omitted only from history discovery so unrelated valid publication records remain listable. Direct review, approval, changes, and publication still fail closed for that draft, and status counts the error.
- A leftover `.bookexport-creating` marker also fails closed. It identifies an interrupted native stage operation whose source metadata must not be silently replaced by legacy adoption.

`/bookexport admin history [page]` lists retained pending or finalized publication records newest first. `/bookexport admin history show <manifest-id>` shows the content-free lifecycle metadata, including whether archival is still pending, the final filename, and the publication outcome.

### Publication and collisions

The optional command mode overrides the configured `publish-collision-mode` for that one publication. Command `replace` maps to configuration value `replace-with-backup`.

| Mode | When a case-insensitive published name exists | When no target exists |
| --- | --- | --- |
| `fail` | Stop without changing the published file; retain the staged draft | Publish with the staged filename |
| `unique` | Publish as `_1`, `_2`, and so on | Publish with the staged filename |
| `replace` / `replace-with-backup` | Copy the current published file to `backup-directory`, then atomically replace it | Stop and retain the staged draft; use `fail` or `unique` for a new file |

Publication copies the complete staged content to a temporary file in the published directory before the final move. It rejects traversal, ambiguous case-insensitive matches, non-regular files, and symbolic-link staged candidates.

Immediately after the verified live move, BookExport atomically writes a `published-archive-pending` manifest checkpoint before it archives or deletes staged content. It then creates a timestamped archive, removes the staged `.txt`, and finalizes the history sidecar with the final filename, checksum, publisher, collision mode, backup, archive, and outcome. The manifest sidecar never enters CMI. If checkpoint storage itself fails after the live move, the live publication has still succeeded, the staged draft is kept, archival is skipped, and the operator is warned not to retry. If archiving or finalization fails after a checkpoint, publication remains successful, the checkpoint blocks accidental republishing, a warning is reported, and the retained pending record remains available in history. A failed pre-publication operation never consumes the staged draft. This checkpoint is an audit safeguard, not the cross-process lock or full crash-recovery journal listed in the backlog.

BookExport deliberately does not execute `/cmi reload`; an administrator remains responsible for reviewing the published file and refreshing CMI.

## Placeholders

### Filename placeholders

These internal placeholders work in `filename-format`:

| Placeholder | Value |
| --- | --- |
| `%title%` | Requested title, or signed title when no custom title is supplied |
| `%book_title%` | Signed book title, or `untitled` |
| `%author%` | Signed book author, or `unknown` |
| `%player%` | Exporting player's current name |
| `%uuid%` | Exporting player's UUID |
| `%date%` | Export date as `yyyy-MM-dd` |
| `%time%` | Export time as `HH-mm-ss` |
| `%timestamp%` | Export timestamp as `yyyyMMdd-HHmmss` |
| `%pages%` | Source page count |

Replacement is single-pass: placeholder-looking text inside a title or author is kept as literal value data rather than expanded a second time.

### Pagination placeholders

These internal placeholders work in `pagination-markup`:

| Placeholder | Value |
| --- | --- |
| `%pageNumber%` | Current 1-based source page number |
| `%pages%` | Total source page count |

BookExport does not register a PlaceholderAPI expansion and does not resolve PAPI or CMI placeholders inside book content. Text such as `%cmi_user_display_name%` is exported unchanged so CMI can resolve it for the player who views the CustomText later.
## CMI CustomText output

With the packaged defaults, a three-page book becomes:

```text
<AutoPage>
Minecraft page 1
<NextPage>
Minecraft page 2
<NextPage>
Minecraft page 3
```

Important behavior:

- `<NextPage>` is never generated before source page 1.
- The controlled first line prevents book content from accidentally becoming CMI's first-line directive.
- Filenames are lowercase by default because CMI treats CustomText names case-insensitively.
- Draft writes and direct-mode exports use unique names; publication uses the selected collision policy.
- Run `/cmi reload` after publishing or directly exporting a CustomText file.
- CMI markup in the original book remains active by design. Restrict export and publication permissions accordingly.

The format follows the official [CMI CustomText documentation](https://www.zrips.net/cmi/custom-text/).

## Minecraft book limits and fidelity

Paper 26.2 exposes writable books with up to 100 pages and up to 1,024 Java UTF-16 code units per editable page. Signed titles are limited to 32 characters. Visible page capacity is not a fixed character count: it depends on glyph width, formatting, wrapping, and line breaks.

BookExport therefore:

- does not reflow, shorten, or merge valid source pages;
- preserves explicit newlines, blank lines, Unicode, and page order;
- reads written-book pages through modern Adventure components;
- serializes visible text, colors, and decorations into the selected text profile;
- cannot represent hover events, click events, insertion events, selectors, or other interactive component behavior in a plain `.txt` file;
- reports raw page and UTF-16 unit statistics through `/bookexport debug book`.

## Configuration

| Key | Default | Meaning |
| --- | --- | --- |
| `config-version` | `3` | Configuration schema marker; older version 2 files receive direct compatibility behavior |
| `workflow-mode` | `staged` | `staged` for review-first normal exports or `direct` for immediate unique publication |
| `staging-directory` | `staging` | Draft directory |
| `archive-directory` | `archive` | Timestamped successfully published draft history |
| `backup-directory` | `backups` | Timestamped pre-replacement history |
| `exported-books-directory` | `~/plugins/CMI/CustomText/` | Published destination; existing key retained for compatibility |
| `publish-collision-mode` | `fail` | Default publication mode: `fail`, `unique`, or `replace-with-backup`; `replace` is accepted as an alias for the replacement mode |
| `filename-format` | `%title%` | Filename template before sanitation |
| `lowercase-filenames` | `true` | Avoid CMI name collisions on case-sensitive filesystems |
| `maximum-filename-length` | `96` | Maximum final base length in code points, clamped to 16-160; filesystem byte safety may shorten it |
| `pagination` | `true` | Preserve page boundaries with a marker |
| `pagination-markup` | `<NextPage>` | Marker written between pages |
| `pagination-on-first-page` | `false` | Also write the marker before page 1; leave false for CMI |
| `cmi-document-header` | `<AutoPage>` | Controlled first line in `cmi` mode |
| `book-meta` | `false` | Add title, author, exporter, time, page, and UTF-16 unit metadata |
| `color-code-handling` | `cmi` | `vanilla`, `legacy`, `strip`, `cmi`, or `mini` |
| `list-page-size` | `10` | Filenames shown per list page, clamped to 1-50 |
| `debug-logging` | `false` | Add content-free stage/direct-export statistics; review and publication audit metadata is logged independently |

Build 017 keeps `config-version: 3` and adds no manifest or approval configuration keys. Managed sidecars are automatic for staged drafts, and explicit approval remains recommended rather than globally required so unchanged `unreviewed` and legacy drafts keep their compatible publication behavior.

### Path resolution and validation

- `staging` becomes `plugins/BookExport/staging/`.
- `~/plugins/CMI/CustomText/` starts at the Paper server root.
- An absolute path is used as configured.
- A relative path containing `..` may not escape `plugins/BookExport/`.
- Staging, published, archive, and backup directories must all be writable and resolve to distinct, non-overlapping locations.
- Workflow directories may not be symbolic links. Staged publication candidates must be direct, regular, non-symbolic-link `.txt` files.
- A managed manifest is a direct sibling of its staged `.txt`; BookExport does not follow a manifest symbolic link or accept a sidecar associated with another path.
- Manifest and creation-marker associations are resolved case-insensitively, and ambiguous case variants fail closed on case-sensitive filesystems.
- Startup fails safely when a configured workflow directory cannot be created, validated, or written.
- A rejected reload leaves the previous validated runtime settings active.

### Migrating a version 2 configuration

Version 2 is supported as a compatibility state, not silently upgraded:

- The existing file loads without being rewritten.
- Normal `/bookexport`, `/bookexport export`, and legacy-title routes retain direct publication behavior.
- Existing exports are not moved.
- `/bookexport stage [title]` is available and always stages.
- Published listing remains the default list behavior.

To opt in to config version 3:

1. Back up `plugins/BookExport/config.yml` and any existing BookExport/CMI text files.
2. Add `workflow-mode`, the three workflow directory keys, and `publish-collision-mode` using the packaged config as a reference.
3. Confirm the staging, published, archive, and backup paths are distinct and do not contain one another.
4. Set `config-version: 3` last.
5. Restart Paper, or run `/bookexport admin reload` if only valid BookExport settings changed.
6. Confirm `/bookexport admin status` and `/bookexport debug workflow` show staged mode and four writable directories.
7. Stage and publish a disposable test book before migrating production authoring.

A config version newer than this build supports is rejected instead of being guessed at.

## Color profiles

| Mode | Result |
| --- | --- |
| `vanilla` | Keep valid and literal section-sign sequences unchanged |
| `legacy` | Convert valid section-sign colors/decorations to ampersand form |
| `strip` | Remove valid colors, decorations, and resets; preserve malformed/literal section signs |
| `cmi` | Convert colors to `{#RRGGBB}` and decorations/resets to `&` codes |
| `mini` | Convert colors and decorations to MiniMessage tags; full component-aware style fidelity is a planned improvement |

Hex input is validated before conversion. Malformed sequences are treated as text rather than blindly consuming nearby characters.

## Filename and collision behavior

- Unicode letters and digits are retained and normalized with Unicode NFKC.
- Path separators, control characters, and unsafe filesystem punctuation are removed.
- Whitespace becomes a single underscore.
- Windows reserved device names are prefixed safely.
- The configured length limit, including collision suffixes, is applied by Unicode code point.
- The final `.txt` component also stays within a conservative 255-byte UTF-8 filesystem limit.
- CMI filenames are lowercase by default.
- Existing names are checked case-insensitively.
- Draft and direct writes are fully written to a temporary file before a no-replace move and receive `_1`, `_2`, and later suffixes when needed.
- Staged publication uses its explicit or configured collision mode; only `replace` may modify an existing target, and it first creates a timestamped backup.

## Security and privacy

- `servers/`, build output, caches, logs, IDE files, and local OS metadata are excluded by `.gitignore`.
- Debug output reports sizes and selected validated settings, never page text, server secrets, or the full configuration file.
- Manifests and server-log audit records contain metadata and SHA-256 values, never book pages or the rendered CustomText body.
- Manifest metadata includes player names, UUIDs, the signed book author, filenames, and timestamps. Treat sidecars as private staff records even though they contain no page content.
- Admin status, review/history output, and scoped lists can expose filesystem paths, private draft filenames, or author metadata and are OP-only by default.
- Staged-filename tab completion requires the relevant review, approval, or publication permission together with staged-list permission, so action-only users cannot enumerate private draft names.
- Book metadata can include player name, author, export time, and size statistics when configured. Filename templates can separately include the player's UUID.
- Staging creates a review boundary; it does not make author content safe. CMI actions, placeholders, or reserved directives can become active after publication.
- The staged-list Publish action suggests an explicit `fail` command rather than silently executing it or inheriting a replacement default.
- Review is read-only; Approve, Changes, and Publish are confirmation-oriented suggested commands. An approval is tied to one exact checksum.
- A corrupt managed sidecar fails closed instead of being downgraded to an untracked legacy draft.
- Invalid active sidecars cannot suppress unrelated valid history records; they remain blocked from every direct or mutating draft operation and are counted by status health.
- Replacement requires an independent permission, always creates a backup, and is excluded from both master nodes.
- Publication rejects path traversal, symlink candidates, ambiguous case-insensitive matches, and non-regular files.

## Building and testing

Run the complete verification suite:

```bash
./gradlew clean build --warning-mode all
```

The build:

- uses the installed Java 25 toolchain;
- targets Paper API 26.2 beta build 60;
- treats all Java compiler warnings as errors;
- runs JUnit 6.1.0 regression tests;
- creates plugin, source, and Javadoc JARs under `build/libs/`.

The main artifact uses this naming scheme:

```text
1MB-BookExport-v<version>-<build>-j<java>-<minecraft>.jar
```

For this release, build `017` is the zero-padded repository commit ordinal: sixteen commits existed before the standalone-documentation and `/info` release commit.

### Build 017 verification snapshot

The release candidate was verified on 2026-07-14 before publication:

- `./gradlew clean build --warning-mode all` completed successfully with 165 tests: 163 passed, zero failed, zero errored, and two case-variant tests were skipped because the test Mac uses case-insensitive APFS.
- The main plugin class is Java class-file major version 69, and the packaged descriptor declares BookExport 2.0.1 with Paper API 26.2.
- Paper 26.2 beta build 60 started cleanly on Oracle Java 25.0.2 with CMI 9.8.8.5, CMILib 1.5.9.9, LuckPerms 5.5.59, and PlaceholderAPI 2.12.3.
- Build 017 live checks covered clean enable/disable, `/bookexport info`, permission-filtered help, and runtime diagnostics; BookExport emitted no warning, error, exception, or deprecated-API message.
- The automated suite covers explicit approval, changes requested, changed-byte rejection, reapproval, legacy implicit approval, publication history, incomplete creation markers, malformed sidecars, and isolation of valid history from an unrelated corrupt draft. Earlier disposable Paper/CMI integration testing also confirmed that fixture text stayed out of BookExport logs and manifest sidecars, manifests stayed out of CMI's CustomText directory, and cleanup completed.

Final artifact SHA-256:

```text
0c409122ca6da39854acf10cefd377687cc46cd75689c16efa04cb499300f88f  1MB-BookExport-v2.0.1-017-j25-26.2.jar
```

This automated and console verification does not replace the repository's in-game beta review.

For runtime validation, use the repository's [beta tester checklist](https://github.com/mrfdev/1MB-CMI-BookExport/blob/master/checklist-bookexport.md). Planned hardening and feature ideas remain in the source repository's [feature backlog](https://github.com/mrfdev/1MB-CMI-BookExport/blob/master/feature-improvements-bookexport.md); neither project-management file is copied into the central player-documentation namespace.

## Troubleshooting

### Paper says the plugin was built for a newer Java version

Paper 26.2 requires Java 25 or newer. Confirm `java -version` for the process that starts Paper.

### BookExport does not load on an older server

This release declares `api-version: 26.2` intentionally. Older servers are unsupported.

### The writable book will not export without a title

Writable books have no signed title. Use `/bookexport stage <title>` or `/bookexport export <title>` and grant `bookexport.export.custom-title`.

### The command says staged, but the CMI text does not appear

Staging does not change the published directory. Review `/bookexport list staged`, inspect `/bookexport admin review <file>`, approve the reviewed bytes when appropriate, publish the draft, confirm `/bookexport list published`, then run `/cmi reload`.

### An unreviewed or legacy draft published without `/bookexport admin approve`

This is intentional compatibility behavior. Explicit approval is recommended but non-blocking for an unchanged `unreviewed` managed draft or an older untracked `.txt`. BookExport records the publisher as the implicit approving actor and stores the exact published checksum in the review decision.

### Publication says the managed draft changed

The current `.txt` bytes no longer match the checksum that defines the draft's review state. The draft was kept. Inspect it again with `/bookexport admin review <file>`, then run `/bookexport admin approve <file>` to approve the current bytes or restore the reviewed version.

### Publication says the manifest is corrupt

BookExport fails closed for a managed sidecar and does not pretend it is a legacy draft. Restore or repair `<draft>.bookexport-manifest.properties`, verify it with the review command, and approve the current draft before retrying. Do not delete the sidecar merely to bypass its recorded integrity state.

### A staged draft reports incomplete creation

BookExport found `<draft>.bookexport-creating`, which means the server or filesystem operation stopped after reserving a native draft name but before completing its manifest pair. Do not publish or delete the marker as a bypass. Inspect the staged file and logs; remove both incomplete files only when you have intentionally abandoned that stage attempt, then export the original book again to preserve author, timestamp, page, and checksum provenance.

### A staged row says changes requested

A reviewer used `/bookexport admin changes <file>`. Review the revised `.txt`, then explicitly approve its current bytes before publication.

### I need the result of an older publication

Run `/bookexport admin history [page]`, copy the stable manifest UUID, and use `/bookexport admin history show <manifest-id>`. The record reports final filename, checksum, actor, collision mode, archive, backup, and outcome without displaying content.

### This upgraded server still exports directly

An existing config version 2 deliberately uses direct compatibility mode. BookExport does not rewrite it. Follow the version 2 migration procedure to opt in to staged mode.

### Publication reports a collision

The default `fail` policy protects the current live text. Review both files, then publish with `unique` or ask an administrator with the separate replacement permission to use `replace`.

### Replace is denied even with `bookexport.admin`

This is intentional. Grant `bookexport.admin.replace` separately. Replacement also fails when no published target exists; use `fail` or `unique` to create a new text.

### Publication succeeded with an archive warning

The published file is already live. Review the warning and the staged/archive directories. BookExport retains the staged draft when it cannot safely complete archiving.

### A filename gained `_1`

A case-insensitive equivalent already exists. Draft and direct exports always choose a unique name; `unique` publication does the same.

### The CMI text still does not appear after publication

Confirm `/bookexport list published`, run `/bookexport debug cmi`, then run `/cmi reload` before `/cmi ctext <name> <player>`.

### CMI shows an unexpected extra page

The generated separator is correct. Check whether the original book itself contains a line equal to `<NextPage>` or other intentional CMI markup.

### Reload was rejected

Read the server log and correct the invalid path or value. Confirm all four workflow directories are distinct and non-overlapping. The previous validated runtime settings remain active.

## Source and license

- Player documentation: [docs.1moreblock.com/custom-server-plugins/bookexport/](https://docs.1moreblock.com/custom-server-plugins/bookexport/)
- Source: [mrfdev/1MB-CMI-BookExport](https://github.com/mrfdev/1MB-CMI-BookExport)
- Author: **mrfloris**
- License: use at your own risk; no warranty. Credit is appreciated.
