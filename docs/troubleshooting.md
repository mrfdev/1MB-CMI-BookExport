# BookExport Troubleshooting

Start with `/bookexport info`, `/bookexport admin status`, and
`/bookexport debug runtime`. Staff can add `/bookexport debug workflow` for storage
and manifest health, `/bookexport debug cmi` for the surrounding CMI stack, and
`/bookexport debug book` while holding the problem book. These diagnostics report
metadata and counts, not page content.

## Startup and installation

### Paper reports a newer Java class version

BookExport is compiled for Java 25. Check `java -version` for the exact process or
service that launches Paper, not only the shell used to administer the server. Run
Paper 26.2 with Java 25 or newer.

### BookExport does not load on an older server

The descriptor declares `api-version: 26.2`, and the code compiles against Paper
26.2 beta build 60. Older Paper, Minecraft, Spigot, and Java releases are not
supported. Install the plugin only on the targeted Paper 26.2 stack.

### Paper discovers BookExport twice

Stop Paper and leave exactly one BookExport main JAR in `plugins/`. Do not install
the source or Javadoc JAR, and do not keep build 017 beside an older plugin JAR.
Restart cleanly; do not use `/reload` or a hot-reload plugin.

### BookExport disables itself because configuration is invalid

Read the first BookExport configuration error in the server log. Common causes are:

- invalid YAML;
- `config-version` below 1 or above 3;
- a version 3 `workflow-mode` other than `staged` or `direct`;
- `publish-collision-mode` other than `fail`, `unique`, `replace-with-backup`, or
  its accepted `replace` alias;
- a relative path escaping `plugins/BookExport/`;
- a server-root-relative path escaping the server root;
- a symbolic-link workflow root or relative path component;
- an unwritable or non-directory path; or
- workflow directories that are equal, nested, or otherwise overlapping.

Correct the file and start Paper again. BookExport fails startup rather than using
partially validated paths.

### A configuration reload is rejected

The previous validated runtime settings remain active. Read the server log and the
recorded last failure from `/bookexport debug runtime` or workflow diagnostics,
correct `config.yml`, and retry `/bookexport admin reload`. Reload does not move
existing files and does not reload CMI.

### This upgraded installation still publishes directly

An existing positive configuration version below 3 intentionally enters direct
compatibility mode and is not rewritten. Follow the
[version 2 migration procedure](configuration.md#migrating-a-version-2-configuration)
to opt into staged-by-default exports.

## Book and export problems

### BookExport says to hold a supported book

Put a written book or book and quill in the player's main hand. Books in the off
hand, another inventory slot, or another material are not read. The player-only
preview and book diagnostics also require that main-hand item.

### The held book has no pages

Add at least one page before exporting. BookExport rejects an empty page list
instead of creating a meaningless file.

### A book and quill will not export without a title

Writable books do not have a signed title. Use `/bookexport stage <title>` or
`/bookexport export <title>`. The sender needs both `bookexport.export` and
`bookexport.export.custom-title` for a custom title.

### A signed book exports with the wrong name

Check `filename-format`, `lowercase-filenames`, and the custom title supplied to the
command. Preview the result with `/bookexport debug preview [title]`. Filename
replacement is single-pass and then normalized for filesystem safety; unsafe
characters can be removed. If the template produces no safe characters, export is
rejected.

### The filename gained `_1` or a later suffix

A case-insensitive equivalent already exists. Draft and direct writes always choose
a unique name. Publication in `unique` mode does the same. Use the list and review
commands to identify the actual filename; do not assume case differences create
separate CMI names.

### Text formatting looks different from the book

Confirm `color-code-handling` with `/bookexport debug cmi` and review the selected
profile in [Configuration](configuration.md#color-and-page-output). BookExport
serializes visible text, colors, decorations, newlines, blank lines, Unicode, and
page order. A plain `.txt` file cannot preserve hover, click, insertion, selector,
or other interactive component behavior. The `mini` conversion is not a full
component-aware MiniMessage renderer.

BookExport does not reflow or truncate valid source pages. Minecraft's visible page
capacity depends on glyph widths, formatting, wrapping, and line breaks, so there is
no reliable fixed visible character count per page.

## Staging and review

### The command reports a staged file, but no CMI text appears

Staging does not change the published directory. List the draft, inspect its `.txt`
content outside the game, run `/bookexport admin review <file>`, approve the exact
bytes when appropriate, and publish with `/bookexport admin publish <file> fail`.
Then run `/cmi reload`.

### An unchanged unreviewed or legacy draft publishes without explicit approval

This is intentional compatibility behavior. Explicit approval is recommended, but
an unchanged managed `unreviewed` draft or older untracked staged `.txt` can be
adopted and implicitly approved by the publisher. The manifest records that actor,
timestamp, exact byte count, and SHA-256. If local policy requires two-person or
explicit review, enforce that operating procedure before granting publication;
there is no configuration key that makes explicit approval mandatory.

### Publication says the managed draft changed

The current bytes no longer match the checksum defining its review decision. The
draft is retained. Review the `.txt` again, run
`/bookexport admin review <file>`, and then approve the current bytes with
`/bookexport admin approve <file>` or restore the previously reviewed content.

### A staged row says changes requested

A reviewer used `/bookexport admin changes <file>`. Edit and inspect the draft, then
approve its current bytes explicitly. Changes-requested drafts cannot be published.

### The manifest is corrupt or associated with the wrong filename

BookExport fails closed and does not downgrade a managed draft to legacy/untracked
state. Restore the matching `.txt` and
`<draft>.txt.bookexport-manifest.properties` from a trusted backup, then review and
approve the exact current bytes. Do not delete the sidecar merely to bypass its
integrity state.

Manifest files are strict UTF-8 metadata with a 64 KiB maximum. Unknown, duplicate,
malformed, future-schema, and invalid typed fields are rejected. A manifest symbolic
link, mismatched staged filename, or multiple case-insensitive associations is also
rejected.

### A staged draft reports incomplete creation

`<draft>.txt.bookexport-creating` means an operation stopped after reserving a name
but before safely completing the native draft/manifest pair. Do not publish it or
delete only the marker as a bypass. Inspect the staged file and server log. If the
attempt is deliberately abandoned, remove both incomplete files under controlled
maintenance and export the original book again so its author, timestamp, page count,
and checksum provenance are recreated.

### History cannot find an older publication

`/bookexport admin history` is backed by pending sidecars in staging and finalized
sidecars beside archive files. Confirm the archive `.txt` and its matching manifest
sidecar were retained together. BookExport has no automatic retention, but manual or
external cleanup can remove audit history. Legacy direct exports have no synthesized
manifest history.

## Publication and recovery

### Publication reports a collision

The default `fail` policy protects the current live file. Compare the staged and
published text. Use `unique` for a separate filename, or ask a trusted administrator
with `bookexport.admin.replace` to use `replace` after confirming the existing
target should be backed up and replaced.

### Replace is denied even with `bookexport.admin`

This is intentional. Grant `bookexport.admin.replace` separately. Replacement also
requires an existing case-insensitive target; if none exists, publish with `fail` or
`unique` instead.

### Atomic replacement is not supported by the filesystem

BookExport refuses a non-atomic replacement. The published target and staged draft
remain unchanged, although the already verified timestamped backup may remain in the
backup directory. Use a filesystem that supports an atomic replace in the published
directory or avoid `replace`.

### Publication succeeds with an archive warning

The live file is already published. Do not immediately retry. Read the exact warning,
inspect `/bookexport admin history`, and compare the live, staged, and archive files.
When archival cannot complete safely, BookExport keeps the staged draft and its
`published-archive-pending` checkpoint to block accidental republishing.

### Publication says its manifest checkpoint could not be stored

The live move succeeded, but BookExport could not persist the immediate audit
checkpoint. It keeps the staged draft and skips archival. Do not publish the draft
again. Inspect the live file and server log, preserve the staged data, and reconcile
the outcome manually before further action.

### Publication and archival succeed but history remains archive-pending

Do not republish. The live and archive files already exist, but final manifest
promotion failed. Preserve all files, inspect the matching stable manifest UUID in
history, and use the server log to diagnose storage or association errors.

### The live target was written but could not be verified

Treat the live destination as an uncertain committed result. BookExport keeps the
staged draft. Compare the published file with the reviewed SHA-256 and inspect the
log before deciding whether any manual recovery is needed. Do not blindly retry.

### I need to restore an older result

BookExport provides history and backup files, not an automatic rollback command.
Stop authoring/publication activity, take a fresh backup, identify the correct
timestamped archive or replacement backup, verify its content and checksum, and
restore it under administrator control. Reload CMI afterward. Keep matching archive
manifests intact if their audit history must remain available.

## CMI and placeholder output

### The CMI text does not appear after publication

Confirm `/bookexport list published`, verify the configured published directory with
`/bookexport debug cmi`, and run `/cmi reload` before
`/cmi ctext <name> <player>`. BookExport deliberately does not reload CMI. CMI is an
optional file consumer and must be installed and configured separately.

### CMI shows an unexpected blank or extra page

Keep `pagination-on-first-page: false` with the default `<NextPage>` separator.
Inspect the original book for a literal line equal to `<NextPage>` or another CMI
directive; authored CMI markup is preserved. Confirm the first line and separator
with `/bookexport debug cmi` and inspect the published file before reloading CMI.

### Placeholder-looking text is unchanged in the file

Expected behavior: BookExport neither registers a PlaceholderAPI expansion nor
resolves CMI/PAPI tokens. It preserves tokens so CMI can resolve them for the
eventual viewer when the necessary expansion is installed. An unknown or missing
expansion can leave a token visible.

## Safety limitations

- BookExport supports Paper 26.2 with Java 25 or newer; its class files target Java 25.
- Staging and checksum approval do not sanitize CMI actions, directives, or
  placeholder tokens. Restrict author and publisher permissions.
- Direct workflow mode bypasses staged review for normal exports.
- Explicit approval is recommended but is not mandatory for an unchanged unreviewed
  or legacy draft.
- The publication checkpoint is an audit safeguard, not a complete transaction
  journal or automatic crash-recovery system.
- In-process storage operations are synchronized, but BookExport does not provide a
  cross-process filesystem lock. Do not point multiple Paper processes or external
  writers at the same workflow directories.
- BookExport has no automatic archive/backup retention, prune command, or rollback
  command.
- BookExport never reloads CMI automatically.
- Plain text cannot preserve every interactive Adventure component feature, and the
  `mini` output profile is not a full component renderer.

## Collecting safe diagnostics

When reporting a problem, collect:

- BookExport version/build from `/bookexport info`;
- live Java and Paper values from `/bookexport debug runtime`;
- configuration version, workflow, directory health, manifest counts, and last
  failure from status/workflow diagnostics;
- detected CMI, CMILib, and PlaceholderAPI versions from `/bookexport debug cmi`;
- held material, signed state, page count, and UTF-16 unit count from
  `/bookexport debug book`; and
- the relevant BookExport log lines and stable manifest UUID.

Do not post the book body, published server text, full configuration, filesystem
paths, or manifest sidecars publicly. Manifests contain no pages, but can include
player names, UUIDs, author names, filenames, timestamps, and checksums.
