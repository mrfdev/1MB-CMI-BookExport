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

The descriptor declares `api-version: 26.2`, and the code compiles against
`io.papermc.paper:paper-api:26.2.build.84-stable`. Older Paper, Minecraft, Spigot,
and Java releases are not supported. Install the plugin only on Paper 26.2
`STABLE` build 84 or a reviewed later same-version stable build.

### Paper discovers BookExport twice

Stop Paper and leave exactly one BookExport main JAR in `plugins/`. Do not install
the source or Javadoc JAR, and do not keep build 019 beside an older plugin JAR.
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

### Publication says another BookExport writer is using the destination

Direct and reviewed publication use an immediate, non-blocking lock on
`.bookexport-publication.lock` in the published directory. Wait for the active
publication in the same installation to finish and retry once. If a second Paper
process or BookExport installation uses the destination, stop the duplicate cleanly
instead of retrying: sharing workflow roots is unsupported because each installation
has a separate recovery journal. A clean process exit releases the operating-system
lock; do not delete or replace the sentinel to bypass an active writer.

The protocol coordinates BookExport processes and external tools that deliberately
honor the sentinel. It cannot prevent an arbitrary editor from ignoring an advisory
lock, so keep non-cooperating writers out of the workflow directories during
publication.

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

### Startup reports unresolved publication transactions

BookExport found one or more durable records in its fixed internal
`plugins/BookExport/transactions/` directory. A record normally exists only while
one publication is crossing its backup, live commit, manifest checkpoint, archive,
staged-removal, and finalization boundaries. A cleanly completed publication
removes its journal after the final state is durable.

Startup recovery is deliberately read-only. BookExport does not roll back, retry,
republish, archive, delete, clean up, reload CMI, or otherwise change a journaled
result. `/bookexport admin status` shows aggregate information. An administrator
with `bookexport.admin.recovery` can use:

```text
/bookexport admin recovery list
/bookexport admin recovery show <complete-transaction-uuid>
```

`show` reconciles basename-only journal metadata with the current staged, live,
archive, backup, and manifest checksums. It does not display book content or mutate
files. Preserve the transaction ID and every named artifact before investigating.
Do not retry publication merely because the server restarted.

### What the durable transaction states mean

The recorded state is the last metadata boundary known to be durable. The scanner
also checks filesystem evidence because a process can stop after a file commit but
before the next journal update.

| State | Last recorded boundary |
| --- | --- |
| `prepared` | Exact content-free plan is durable; no later mutation is asserted. |
| `backup-created` | The exact old live bytes have a durable replacement backup. Replacement only. |
| `live-committed` | The planned live filename contains the approved bytes. |
| `manifest-checkpointed` | The active manifest records the committed live outcome. |
| `archive-created` | The planned archive contains the approved bytes. |
| `staged-removed` | The approved staged source has been removed after live/archive verification. |
| `finalized` | Final manifest history is durable; only journal cleanup remains. |

Do not infer that the next action is safe from the state name alone. Always use the
assessment and checksum observations from `recovery show`.

### Interpreting recovery assessments

| Assessment | Meaning and safe response |
| --- | --- |
| `ABANDONED_BEFORE_LIVE_COMMIT` | The plan exists but the approved bytes are not observed at the planned live target. Preserve the staged draft and journal, verify the existing live name, and reconcile manually before retrying. |
| `BACKUP_CREATED_NO_LIVE_COMMIT` | A replacement backup matches the old live bytes, but the approved bytes were not committed live. Preserve all three files and review the interrupted replacement manually. |
| `LIVE_COMMIT_NEEDS_CHECKPOINT` | The approved bytes are live but the matching manifest checkpoint is absent. This is critical: do not republish, replace, delete, or reload based on an assumed failure. |
| `CHECKPOINT_NEEDS_ARCHIVE` | Live bytes and the pending manifest agree, but a matching archive is not complete. Keep the staged source and all metadata. |
| `ARCHIVE_NEEDS_SOURCE_CLEANUP` | Live and archive copies agree while the staged source remains. Do not manually delete it until the entire record has been backed up and reviewed. |
| `ARCHIVE_NEEDS_MANIFEST_FINALIZE` | Live and archive copies agree and the staged source is gone, but final history promotion is incomplete. Preserve the pending and archive metadata. |
| `COMPLETED_JOURNAL_REMAINS` | Artifacts and final history are consistent, but the journal was not removed. It is informational residue, not permission for automatic deletion. |
| `CONFLICT_REQUIRES_MANUAL_REVIEW` | State, configuration roots, filenames, manifests, or checksums contradict one another. Stop publication activity for the affected names and investigate from backups. |
| `UNREADABLE_REQUIRES_MANUAL_REVIEW` | A journal is malformed, unsupported, ambiguous, too large, symbolic-linked, or otherwise unreadable. Because its scope cannot be trusted, publication may be blocked globally until an administrator safely reconciles it. |

Artifact observations are `MISSING`, `MATCHES_EXPECTED`, `MATCHES_ORIGINAL`,
`MISMATCH`, `AMBIGUOUS`, `UNREADABLE`, or `NOT_APPLICABLE`. A replacement live
target may legitimately report `MATCHES_ORIGINAL` before the live commit. A
`MISMATCH`, `AMBIGUOUS`, or `UNREADABLE` observation always requires manual review.

### Manual reconciliation procedure

1. Stop publication and external edits for the affected staged and published names.
2. Preferably stop the Paper process, then take one backup of staging, published,
   archive, backups, manifests, and `plugins/BookExport/transactions/` together.
3. Record `/bookexport admin recovery show <id>` and the matching
   `/bookexport admin history show <manifest-id>` output. Keep the full output
   private because it includes actors, filenames, timestamps, IDs, and checksums.
4. Independently calculate SHA-256 and byte counts for the named regular files.
   Compare them with the approved and, for replacement, original fingerprints.
5. Resolve configuration-root drift, case-only duplicates, symbolic links,
   unexpected external edits, or damaged metadata before considering any file
   operation.
6. Decide the repair under administrator change control. BookExport intentionally
   has no journal replay, rollback, cleanup, or force-complete command.
7. Restart and inspect recovery again before allowing another publication for the
   same draft or live filename.

Never delete a journal simply to remove a warning. Never copy staged bytes over a
live result, restore a backup, remove a staged file, or promote a manifest until its
checksums and lifecycle evidence have been reviewed. If evidence is incomplete or
contradictory, preserve it and restore from a known-good full backup instead.

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
inspect `/bookexport admin recovery list`, inspect manifest history, and compare the
live, staged, and archive checksums.
When archival cannot complete safely, BookExport keeps the staged draft and its
`published-archive-pending` checkpoint to block accidental republishing.

### Publication says its manifest checkpoint could not be stored

The live move succeeded, but BookExport could not persist the immediate audit
checkpoint. It keeps the staged draft and skips archival. Do not publish the draft
again. Inspect the matching recovery transaction, preserve the live and staged data,
and reconcile their checksums manually before further action.

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
- Staged publication uses a durable, content-free transaction journal and read-only
  checksum reconciliation. It is not an automatic recovery, rollback, retry, or
  cleanup system. Direct workflow exports do not create publication journals.
- Direct and reviewed publication use a cooperative cross-process lock in the
  published directory. It prevents overlapping cooperative writes but does not make
  shared workflows supported and cannot stop a tool that ignores advisory locks;
  keep one BookExport installation as the workflow owner and keep other writers out
  during publication.
- BookExport has no automatic archive/backup retention, prune command, or rollback
  command.
- BookExport never reloads CMI automatically.
- Plain text cannot preserve every interactive Adventure component feature, and the
  `mini` output profile is not a full component renderer.

## Collecting safe diagnostics

When reporting a problem, collect:

- BookExport version/build from `/bookexport info`;
- live Java and Paper values from `/bookexport debug runtime`;
- configuration version, workflow, directory health, manifest counts, last failure,
  and aggregate recovery counts from status/workflow diagnostics;
- the complete transaction UUID, state, assessment, and artifact observations from
  `recovery show` when a publication is interrupted;
- detected CMI, CMILib, and PlaceholderAPI versions from `/bookexport debug cmi`;
- held material, signed state, page count, and UTF-16 unit count from
  `/bookexport debug book`; and
- the relevant BookExport log lines and stable manifest UUID.

Do not post the book body, published server text, full configuration, filesystem
paths, or manifest sidecars publicly. Manifests contain no pages, but can include
player names, UUIDs, author names, filenames, timestamps, and checksums. Transaction
journals are also content-free, but expose publisher identity, workflow filenames,
stable IDs, timestamps, and fingerprints; keep them private too.
