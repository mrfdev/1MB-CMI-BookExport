# BookExport

BookExport is a Paper 26.2 administration plugin that turns a written book or book and quill into a UTF-8 `.txt` file. Its default output profile publishes a book as CMI CustomText while preserving the Minecraft page order, colors, decorations, blank lines, Unicode text, and placeholder-looking tokens.

The 2.0 beta is a complete modernization of the original 1.21 plugin: Java 25 bytecode, Paper 26.2 APIs, Adventure messages, correct CMI pagination, granular permissions, validated configuration, safe filenames, complete-file staging, diagnostics, and regression tests.

## Compatibility

| Component | Supported target |
| --- | --- |
| Server | Paper 26.2 only |
| Compile API | `io.papermc.paper:paper-api:26.2.build.60-beta` |
| Java bytecode | Java 25 |
| Tested runtime | Oracle Java 26.0.1 |
| Build tool | Gradle 9.4.1 wrapper |
| BookExport | `2.0.0-beta.1` |

Older Minecraft, Paper, Spigot, and Java releases are intentionally unsupported.

## Feature introduction

Use Minecraft's book editor as an in-game content authoring tool. Hold the finished book, run `/bookexport`, and BookExport creates a text file that an administrator can review or publish. With the default CMI profile, the result starts with `<AutoPage>` and places `<NextPage>` only between Minecraft pages, so CMI page 1 matches Minecraft page 1.

Typical uses include:

- CMI CustomText rules, guides, news, help, and event pages
- Drafting formatted server copy in-game
- Archiving written books as readable UTF-8 text
- Converting legacy Minecraft colors into CMI, MiniMessage, ampersand, or plain text

## Features

- Supports signed written books and unsigned book-and-quill items in the main hand.
- Uses the signed title automatically or an explicit custom title.
- Provides an unambiguous `/bookexport export [title]` route for titles that match subcommands.
- Preserves page order and page boundaries without reflowing or truncating valid book content.
- Produces correct CMI pagination: controlled `<AutoPage>` first line and `<NextPage>` only between pages.
- Preserves CMI and PlaceholderAPI-looking tokens for CMI to resolve for the eventual viewer.
- Converts validated Minecraft colors and decorations to `vanilla`, `legacy`, `strip`, `cmi`, or `mini` output.
- Uses normalized Unicode-aware filenames with configurable templates, lowercasing, length limits, and collision suffixes.
- Stages complete UTF-8 files before a no-replace move and never overwrites an existing export.
- Lists exports with chat-safe pagination.
- Validates output paths and configuration during startup and reload.
- Offers player-safe `info` and `help`, plus trusted `admin` and `debug` diagnostics.
- Retains the old `exportbook.*` permission nodes as compatibility aliases.
- Treats compiler warnings as errors and includes unit tests for the high-risk conversion logic.

## Requirements and optional integrations

Paper is the only production/plugin API dependency. JUnit is used only by the test suite. BookExport does not call the APIs of CMI, CMILib, PlaceholderAPI, Vault, or LuckPerms.

The current test-server versions, audited on 2026-07-14, are:

| Plugin | Tested version | Relationship to BookExport |
| --- | --- | --- |
| CMI | 9.8.8.5 | Optional consumer of exported CustomText files |
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

2. Copy `build/libs/BookExport-2.0.0-beta.1.jar` to the Paper 26.2 server's `plugins/` directory.
3. Remove any older BookExport JAR so Paper does not discover two copies.
4. Restart Paper cleanly. Do not use Bukkit `/reload` or a hot-reload plugin.
5. Confirm `/version BookExport` reports `2.0.0-beta.1`.
6. Review `plugins/BookExport/config.yml`, then run `/bookexport admin status`.

## Admin-player quick start

1. Give a trusted staff player the master node:

   ```text
   /lp user <player> permission set bookexport.admin true
   ```

2. Put a written book or book and quill in the player's main hand.
3. Preview the export without writing a file:

   ```text
   /bookexport debug preview Server Rules
   ```

4. Export it:

   ```text
   /bookexport export Server Rules
   ```

5. Confirm the generated filename:

   ```text
   /bookexport list
   ```

6. For the default CMI destination, reload CMI and open the text:

   ```text
   /cmi reload
   /cmi ctext server_rules <player>
   ```

Only grant export access to trusted authors. Book content is intentionally preserved, so CMI directives, interactive tags, and PlaceholderAPI tokens written into a book may become active when CMI displays the exported file.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/bexport ...` | Alias for any `/bookexport ...` route | Same as the routed command |
| `/bookexport` | Export a signed written book using its title | `bookexport.export` |
| `/bookexport export [title]` | Explicitly export the held book; title is required for book and quill | `bookexport.export` and, for a custom title, `bookexport.export.custom-title` |
| `/bookexport <title>` | Legacy shorthand for a custom-title export | Same as explicit export |
| `/bookexport info` | Show version, target, live server, and source | `bookexport.info` |
| `/bookexport help` or `/bookexport ?` | Show only the commands the sender may use | `bookexport.help` |
| `/bookexport admin [status]` | Show validated settings and output health | `bookexport.admin.status` |
| `/bookexport admin list [page]` | List exported `.txt` files | `bookexport.admin.list` |
| `/bookexport admin reload` | Reload and validate `config.yml` | `bookexport.admin.reload` |
| `/bookexport admin debug [runtime\|book\|cmi\|preview]` | Show read-only diagnostics | `bookexport.admin.debug` |
| `/bookexport list [page]` | Compatibility shortcut for admin list | `bookexport.admin.list` |
| `/bookexport reload` | Compatibility shortcut for admin reload | `bookexport.admin.reload` |
| `/bookexport debug [runtime]` | Show Java, Paper, build, directory, and failure diagnostics | `bookexport.admin.debug` |
| `/bookexport debug book` | Inspect held-book type and size without showing its content | `bookexport.admin.debug` |
| `/bookexport debug cmi` | Show detected CMI stack and renderer settings | `bookexport.admin.debug` |
| `/bookexport debug preview [title]` | Calculate filename, pages, characters, and bytes without writing | `bookexport.admin.debug`; custom title also needs `bookexport.export.custom-title` |

All information, administration, and debug commands work from the console except held-book inspection, preview, and export.

## Command examples

```text
# Export a signed book using its signed title
/bookexport

# Export any held book under a chosen title
/bookexport export July News

# Export a title that is also a reserved subcommand
/bookexport export info

# View runtime and configuration health
/bookexport info
/bookexport admin status
/bookexport debug
/bookexport debug cmi

# Preview without writing
/bookexport debug preview July News

# Browse many exports
/bookexport list 2

# Apply an edited config safely
/bookexport admin reload
```

## Permissions

| Permission | Default | Purpose |
| --- | --- | --- |
| `bookexport.admin` | OP | Master node granting all current BookExport capabilities |
| `bookexport.export` | OP | Export the held book |
| `bookexport.export.custom-title` | OP | Override the signed title or name a writable book export |
| `bookexport.info` | Everyone | View public plugin and compatibility information |
| `bookexport.help` | Everyone | View permission-filtered help |
| `bookexport.admin.status` | OP | View paths, settings, and output health |
| `bookexport.admin.list` | OP | List exported filenames |
| `bookexport.admin.reload` | OP | Reload configuration |
| `bookexport.admin.debug` | OP | View runtime, CMI, book-size, and preview diagnostics |

Legacy aliases remain available for existing LuckPerms data:

| Legacy node | Default | Grants |
| --- | --- | --- |
| `exportbook.command` | False | `bookexport.admin` |
| `exportbook.export` | False | `bookexport.export` and `bookexport.export.custom-title` |
| `exportbook.list` | False | `bookexport.admin.list` |
| `exportbook.help` | False | `bookexport.help` |
| `exportbook.reload` | False | `bookexport.admin.reload` |

BookExport relies on permission inheritance. It does not manually bypass a denied child node when a master node is present.

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

- `<NextPage>` is never written before source page 1.
- The controlled first line prevents book content from accidentally becoming CMI's first-line directive.
- Filenames are lowercase by default because CMI treats CustomText names case-insensitively.
- Existing files are never overwritten; `_1`, `_2`, and so on are appended.
- Run `/cmi reload` after adding or changing a CustomText file.
- CMI markup in the original book remains active by design. Restrict export permissions accordingly.

The format follows the official [CMI CustomText documentation](https://www.zrips.net/cmi/custom-text/).

## Minecraft book limits and fidelity

Paper 26.2 exposes writable books with up to 100 pages and up to 1,024 Java UTF-16 code units per editable page. Signed titles are limited to 32 characters. Visible page capacity is not a fixed character count: it depends on glyph width, formatting, wrapping, and line breaks.

BookExport therefore:

- does not reflow, shorten, or merge valid source pages;
- preserves explicit newlines, blank lines, Unicode, and page order;
- reads written-book pages through modern Adventure components;
- serializes visible text, colors, and decorations into the selected text profile;
- cannot represent hover events, click events, insertion events, selectors, or other interactive component behavior in a plain `.txt` file;
- reports raw page and character statistics through `/bookexport debug book`.

## Configuration

| Key | Default | Meaning |
| --- | --- | --- |
| `config-version` | `2` | Configuration schema marker |
| `exported-books-directory` | `~/plugins/CMI/CustomText/` | Relative, server-root-relative, or absolute destination |
| `filename-format` | `%title%` | Filename template before sanitation |
| `lowercase-filenames` | `true` | Avoid CMI name collisions on case-sensitive filesystems |
| `maximum-filename-length` | `96` | Maximum final base length in code points, clamped to 16-160; filesystem byte safety may shorten it |
| `pagination` | `true` | Preserve page boundaries with a marker |
| `pagination-markup` | `<NextPage>` | Marker written between pages |
| `pagination-on-first-page` | `false` | Also write the marker before page 1; leave false for CMI |
| `cmi-document-header` | `<AutoPage>` | Controlled first line in `cmi` mode |
| `book-meta` | `false` | Add title, author, exporter, time, page, and character metadata |
| `color-code-handling` | `cmi` | `vanilla`, `legacy`, `strip`, `cmi`, or `mini` |
| `list-page-size` | `10` | Filenames shown per list page, clamped to 1-50 |
| `debug-logging` | `false` | Log content-free export statistics |

Path resolution:

- `books` becomes `plugins/BookExport/books/`.
- `~/plugins/CMI/CustomText/` starts at the Paper server root.
- An absolute path is used as configured.
- A relative path containing `..` may not escape `plugins/BookExport/`.
- Startup fails safely when the destination cannot be created or written.
- A rejected reload leaves the previous validated runtime settings active.

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
- Content is fully written to a sibling temporary file before a no-replace move publishes the final name.
- Existing targets are never replaced, including if another export claims the name concurrently.

## Security and privacy

- `servers/`, build output, caches, logs, IDE files, and local OS metadata are excluded by `.gitignore`.
- Debug output reports sizes and state, never page text, server secrets, or configuration-file contents.
- Admin status exposes the configured filesystem path and is therefore OP-only by default.
- Book metadata can include player name, UUID-derived filename data, author, and export time when configured.
- Publishing directly to CMI CustomText is a trust boundary. A trusted book author can intentionally include CMI actions, placeholders, or page directives.

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
- creates the plugin, source, and Javadoc JARs under `build/libs/`.

For runtime validation, use [checklist-bookexport.md](checklist-bookexport.md). Planned hardening and feature ideas are tracked in [feature-improvements-bookexport.md](feature-improvements-bookexport.md).

## Troubleshooting

### Paper says the plugin was built for a newer Java version

Paper 26.2 requires Java 25 or newer. Confirm `java -version` for the process that starts Paper.

### BookExport does not load on an older server

This release declares `api-version: 26.2` intentionally. Older servers are unsupported.

### The writable book will not export without a title

Writable books have no signed title. Use `/bookexport export <title>` and grant `bookexport.export.custom-title`.

### The CMI text does not appear

Confirm the destination, run `/bookexport debug cmi`, then run `/cmi reload` before `/cmi ctext <name> <player>`.

### CMI shows an unexpected extra page

The generated separator is correct. Check whether the original book itself contains a line equal to `<NextPage>` or other intentional CMI markup.

### A filename gained `_1`

BookExport never overwrites. An export with that name already exists, including a case-insensitive equivalent.

### Reload was rejected

Read the server log and correct the invalid path or value. The previous validated runtime settings remain active.

## Source and license

- Source: [mrfdev/1MB-CMI-BookExport](https://github.com/mrfdev/1MB-CMI-BookExport)
- Author: **mrfloris**
- License: use at your own risk; no warranty. Credit is appreciated.
