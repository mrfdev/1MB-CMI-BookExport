# BookExport Configuration

BookExport reads `plugins/BookExport/config.yml`. A fresh installation copies the
packaged version 3 configuration on first start. Existing configuration files are
loaded as written: BookExport supplies defaults for missing settings at runtime,
but it does not rewrite or silently migrate the file.

Configuration affects future operations. Reloading a path, filename, renderer, or
workflow setting does not move, rename, rerender, or delete files that already
exist.

## Settings

| Key | Packaged default | Accepted values and exact behavior |
| --- | --- | --- |
| `config-version` | `3` | Positive schema number. Version 3 is current. A missing key is treated as version 1, and any positive version below 3 enters direct compatibility mode. A value above 3 or below 1 is rejected. |
| `workflow-mode` | `staged` | `staged` or `direct` for version 3. In `staged`, normal exports create drafts. In `direct`, normal exports write immediately to the published directory. `/bookexport stage` always stages. Older compatibility configurations always run in `direct`, regardless of this key. |
| `staging-directory` | `staging` | Directory for staged `.txt` drafts and their manifest sidecars. |
| `archive-directory` | `archive` | Directory for timestamped copies of successfully published drafts and finalized manifest history. |
| `backup-directory` | `backups` | Directory for timestamped copies made before a `replace` publication. |
| `exported-books-directory` | `~/plugins/CMI/CustomText/` | Published destination. The older key name is retained for compatibility. |
| `publish-collision-mode` | `fail` | `fail`, `unique`, or `replace-with-backup`; `replace` is also accepted as an alias for `replace-with-backup`. This is used only when `/bookexport admin publish` omits a mode. Prefer the canonical `replace-with-backup` value in configuration. |
| `filename-format` | `%title%` | Template used before filename normalization and safety filtering. A null, blank, or missing value falls back to `%title%`. |
| `lowercase-filenames` | `true` | Lowercase generated filenames. This is recommended because CMI CustomText names are treated case-insensitively. |
| `maximum-filename-length` | `96` | Maximum filename base length in Unicode code points, before `.txt`. Values are clamped to `16`–`160` with a warning. A conservative 251-byte UTF-8 base limit can shorten the result further. |
| `pagination` | `true` | When enabled, insert `pagination-markup` at page boundaries. When disabled, separate later Minecraft pages with one blank line instead. |
| `pagination-markup` | `<NextPage>` | Marker inserted between pages. `%pageNumber%` and `%pages%` are replaced for each boundary. A null, blank, or missing value falls back to `<NextPage>`. |
| `pagination-on-first-page` | `false` | Also insert `pagination-markup` before page 1. Keep this `false` for normal CMI output; enable it only when the configured markup is a visible page heading rather than CMI's separator. |
| `cmi-document-header` | `<AutoPage>` | Controlled first line emitted only in `cmi` color mode. A blank value in `cmi` mode is replaced with `<AutoPage>` and logged as a warning. |
| `book-meta` | `false` | Add title, author, exporting player, ISO local export time, page count, and UTF-16 unit count before the book pages. |
| `color-code-handling` | `cmi` | `vanilla`, `legacy`, `strip`, `cmi`, or `mini`. An unknown value falls back to `cmi` with a warning. |
| `list-page-size` | `10` | Number of filenames shown per list or history page. Values are clamped to `1`–`50` with a warning. |
| `debug-logging` | `false` | Add content-free stage/direct-export statistics such as filename, actor, counts, and manifest ID. Review and publication audit metadata is logged independently. BookExport never logs book pages. |

### Workflow modes

| Operation | `staged` mode | `direct` mode |
| --- | --- | --- |
| `/bookexport` or `/bookexport export [title]` | Writes a unique managed draft to `staging-directory` | Writes a unique `.txt` directly to `exported-books-directory` |
| `/bookexport stage [title]` | Writes a unique managed draft | Writes a unique managed draft |
| Review, approval, archive, and replacement backup | Available for staged drafts | Available only for drafts created with the explicit `stage` route |

A direct export does not create a draft manifest, archive, or replacement backup.
It is retained for existing installations, but `staged` is the safer authoring
default.

### Publication collision modes

| Mode | Existing case-insensitive target | No existing target |
| --- | --- | --- |
| `fail` | Stops and keeps the draft | Publishes with the staged filename |
| `unique` | Publishes with `_1`, `_2`, or the next available suffix | Publishes with the staged filename |
| `replace-with-backup` | Creates and verifies a timestamped backup, then atomically replaces the target | Stops and keeps the draft; use `fail` or `unique` to create a new file |

`replace` is accepted as an alias for `replace-with-backup`, including in
configuration, but `replace-with-backup` is the canonical value. Replacement also
requires `bookexport.admin.replace`. That permission is
not included in either BookExport master permission.

## Filename placeholders

`filename-format` supports these single-pass replacements:

| Placeholder | Value |
| --- | --- |
| `%title%` | Requested custom title, otherwise the signed book title |
| `%book_title%` | Signed book title, otherwise `untitled` |
| `%author%` | Signed book author, otherwise `unknown` |
| `%player%` | Exporting player's current name |
| `%uuid%` | Exporting player's UUID |
| `%date%` | Export date in `yyyy-MM-dd` form |
| `%time%` | Export time in `HH-mm-ss` form |
| `%timestamp%` | Export timestamp in `yyyyMMdd-HHmmss` form |
| `%pages%` | Number of source pages |

Placeholder-looking text inside a replacement value is kept literally; it is not
expanded a second time. Generated names are normalized with Unicode NFKC. Letters,
digits, hyphens, and dots are retained; whitespace becomes `_`; path separators,
control characters, and unsafe punctuation are removed. Unsafe edge punctuation
and Windows device names are handled before `.txt` is added. Existing names are
checked case-insensitively.

## Color and page output

| Mode | Output |
| --- | --- |
| `vanilla` | Keep valid section-sign formatting codes |
| `legacy` | Convert valid section-sign colors and decorations to ampersand codes |
| `strip` | Remove valid colors, decorations, and resets while preserving malformed literal sequences |
| `cmi` | Convert colors to `{#RRGGBB}`, decorations and resets to ampersand codes, and emit `cmi-document-header` first |
| `mini` | Convert supported colors and decorations to MiniMessage-like tags; BookExport does not render those tags itself |

With the packaged CMI settings, `<AutoPage>` is the first line and `<NextPage>` is
written only between Minecraft pages. BookExport does not reflow or truncate page
text. Setting `pagination-on-first-page: true` with `<NextPage>` creates a separator
before the first source page and is normally incorrect for CMI.

## Directory resolution and validation

- A relative value such as `staging` resolves below `plugins/BookExport/`.
- A value beginning with `~/` resolves from the Paper server root.
- An absolute path is used as configured. Only trusted administrators should edit
  directory settings.
- Relative and server-root-relative paths may not escape their containment root or
  traverse symbolic-link components. The final workflow directory itself may not
  be a symbolic link.
- Missing directories are created. Each is tested with a temporary writable probe
  and canonicalized before use.
- Staging, published, archive, and backup directories must be distinct. They may
  not be equal, nested inside one another, or contain one another.
- Staged publication accepts only a direct, regular, non-symbolic-link `.txt` file.
  Case-ambiguous filenames and manifest associations fail closed.

An invalid path disables BookExport during startup. An invalid reload is rejected
without replacing the previous validated runtime settings.

## Reload behavior

Use:

```text
/bookexport admin reload
```

The command rereads YAML into a new configuration object, applies packaged defaults,
validates every value and all four workflow directories, and swaps runtime settings
only after the complete candidate succeeds. Successful reload clears the recorded
last failure. Invalid YAML, unsupported schema values, invalid workflow or collision
values, unsafe or overlapping paths, and unwritable directories reject the candidate;
the last working settings stay active and the failure is recorded for diagnostics.
An unknown `color-code-handling` value instead warns and falls back to `cmi`.

This command reloads only BookExport's `config.yml`. It does not reload Paper,
`plugin.yml`, permissions, CMI, or existing files. Use a clean Paper restart for a
JAR update. After publishing new CMI CustomText content, run `/cmi reload`
separately.

## Migrating a version 2 configuration

Version 2 is a deliberate compatibility state:

- BookExport does not rewrite the file.
- Normal exports continue to publish directly.
- Existing exports are not moved.
- `/bookexport stage [title]` is still available.
- Published files remain the default list scope.

To adopt the version 3 staged workflow:

1. Stop authoring activity and back up `plugins/BookExport/config.yml`, the
   BookExport data directory, and the configured published directory.
2. Add `workflow-mode`, `staging-directory`, `archive-directory`,
   `backup-directory`, and `publish-collision-mode`, using the defaults above as a
   safe starting point.
3. Confirm all four resolved workflow directories are writable, distinct, and
   non-overlapping.
4. Set `config-version: 3` last.
5. Restart Paper, or use `/bookexport admin reload` when only valid configuration
   values changed.
6. Confirm `/bookexport admin status` and `/bookexport debug workflow` report
   configuration version 3, compatibility mode `false`, workflow `staged`, and four
   writable directories.
7. Stage, review, approve, publish, and inspect a disposable test book before using
   the workflow for production text.

BookExport intentionally does not move old direct exports into staging or synthesize
manifest history for them.

## Persistent files and retention

The default layout is:

```text
plugins/
  BookExport/
    config.yml
    staging/
      <draft>.txt
      <draft>.txt.bookexport-manifest.properties
      <draft>.txt.bookexport-creating       # transient; present after an interrupted stage
    archive/
      <timestamp>_published_<draft>.txt
      <timestamp>_published_<draft>.txt.bookexport-manifest.properties
    backups/
      <timestamp>_backup_<published>.txt
  CMI/
    CustomText/
      <published>.txt
```

Managed manifest sidecars contain IDs, actors, timestamps, filenames, page/unit/byte
counts, checksums, review decisions, and publication outcomes. They do not contain
book pages. They can contain player names, UUIDs, signed-book author names, and
private filenames, so treat them as staff data.

BookExport has no database and no retention-period, maximum-history, or automatic
cleanup setting. Drafts remain until published or removed deliberately. Successful
publications retain an archive and finalized history sidecar. Replacement backups
remain until an administrator removes them. Pending publication checkpoints remain
in staging when archival or finalization cannot safely complete.

Prune only under an administrator-controlled retention policy after taking a backup
and confirming no publication is active. Keep an archived `.txt` and its matching
manifest sidecar together; deleting one makes the content or audit history incomplete.
Do not delete an active or archive-pending manifest merely to bypass an integrity
failure. There is no in-game prune or rollback command.
