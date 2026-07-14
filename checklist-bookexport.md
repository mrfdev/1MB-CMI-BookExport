# BookExport 26.2 Beta Test Checklist

Use this checklist for an in-game review on the dedicated Paper 26.2 BookExport test server. Use disposable fixture books and filenames for collision/replacement tests. Do not include server addresses, passwords, security keys, player IPs, page contents, or full configuration files in a public bug report.

## Test record

- [ ] Tester name:
- [ ] Date and timezone:
- [ ] BookExport version:
- [ ] BookExport build number:
- [ ] Config version:
- [ ] Workflow mode:
- [ ] Paper version/build:
- [ ] Server Java version:
- [ ] Minecraft client version:
- [ ] CMI / CMILib versions:
- [ ] Permission group used:
- [ ] Result: PASS / PASS WITH NOTES / FAIL
- [ ] Screenshots or relevant log lines attached:

## Before connecting

- [ ] An administrator confirms the newest BookExport JAR is installed and older copies are removed.
- [ ] The installed artifact is `1MB-BookExport-v2.0.1-015-j25-26.2.jar`.
- [ ] `/version BookExport` reports `2.0.1` and `/bookexport info` reports build `015`.
- [ ] `/version` reports Paper 26.2 build 60.
- [ ] The server runtime reports Java 25 or newer (record the exact version above) and the plugin reports Java target 25.
- [ ] A fresh `config.yml` reports config version 3 and workflow `staged`.
- [ ] The staging, published, archive, and backup directories exist, are writable, are distinct, and do not contain one another.
- [ ] BookExport, CMI, CMILib, LuckPerms, and PlaceholderAPI are green in `/plugins`.
- [ ] Startup logs contain no BookExport `WARN`, `ERROR`, exception, class-loading failure, legacy-plugin warning, or deprecated-API warning.
- [ ] The tester knows which disposable published filename may be replaced and which permission set to use for each test.

## Information, help, and completion

- [ ] `/bookexport info` shows the correct version, build, Paper target, Java target, server version, and repository.
- [ ] The repository link is clickable.
- [ ] `/bookexport help` is readable and shows only commands available to the tester.
- [ ] Help documents `export`, `stage`, scoped `list`, `admin review`, `admin approve`, `admin changes`, `admin history`, `admin publish`, and `debug workflow` when the sender has their permissions.
- [ ] `/bexport help` works as an alias.
- [ ] First-level tab completion includes permitted commands and hides denied admin commands.
- [ ] Nested completion includes permitted list scopes, collision modes, debug sections, review/history routes, and staged filenames where applicable.
- [ ] A sender with publish permission but without `bookexport.admin.list.staged` cannot enumerate staged filenames through tab completion.
- [ ] A sender with both publish and staged-list permissions receives staged filename completion.
- [ ] Review filename completion requires both `bookexport.admin.review` and staged-list permission.
- [ ] Approve/changes filename completion requires both `bookexport.admin.approve` and staged-list permission.
- [ ] A sender with only an action permission cannot enumerate private staged filenames through completion.
- [ ] A sender without `bookexport.admin.replace` is not offered `replace` completion.
- [ ] Console can run `bookexport info`, `bookexport help`, lists, review/approval/changes, history, publication, and diagnostics.
- [ ] A console export or stage attempt returns a clear player-only message without an exception.

## Admin status and debug commands

- [ ] `/bookexport admin` and `/bookexport admin status` show version/build, config version, workflow mode, collision mode, four validated directories, writable health, output profile, pagination, metadata, debug logging, and last failure.
- [ ] Compatibility mode is explicitly identified when a version 2 config is tested.
- [ ] `/bookexport debug runtime` shows Java runtime/target, Paper API/live server, artifact, directory health, and last failure.
- [ ] `/bookexport debug book` reports material, signed state, title/author presence, pages, and Java UTF-16 units without showing page contents.
- [ ] `/bookexport debug cmi` reports detected CMI, CMILib, and PlaceholderAPI versions plus CMI rendering settings.
- [ ] `/bookexport debug workflow` reports config/workflow mode, all four directories and counts, writable health, and collision mode without page content.
- [ ] `/bookexport debug preview <title>` reports destination scope, sanitized filename candidate, pages, UTF-16 units, and bytes but writes no file.
- [ ] Preview identifies `staged` in staged mode and `published` in direct mode.
- [ ] `/bookexport admin debug ...` matches the top-level debug behavior.
- [ ] Console admin/debug commands do not reveal credentials, page content, or the complete config.
- [ ] `/bookexport admin reload` accepts valid config changes.
- [ ] A fatal invalid path or malformed YAML reload is rejected, the file is not overwritten, and the prior validated runtime settings keep working.

## Permission matrix

Test with an unprivileged player, narrow individual nodes, the master node, and explicit denials.

- [ ] An unprivileged player can use `info` and `help` only.
- [ ] Export and explicit staging are denied without `bookexport.export`.
- [ ] A signed-title stage/export works with `bookexport.export` alone.
- [ ] A custom title is denied without `bookexport.export.custom-title`.
- [ ] `bookexport.export.custom-title` enables only title override behavior.
- [ ] Status is denied without `bookexport.admin.status`.
- [ ] Published listing is denied without `bookexport.admin.list`.
- [ ] Staged listing is denied without `bookexport.admin.list.staged`.
- [ ] Archive listing is denied without `bookexport.admin.list.archive`.
- [ ] Backup listing is denied without `bookexport.admin.list.backups`.
- [ ] Manifest review is denied without `bookexport.admin.review`.
- [ ] Approval and changes-requested decisions are denied without `bookexport.admin.approve`.
- [ ] Manifest history is denied without `bookexport.admin.history`.
- [ ] `bookexport.admin.review` alone cannot approve, request changes, publish, or browse history.
- [ ] `bookexport.admin.approve` alone cannot enumerate staged filenames through tab completion.
- [ ] `bookexport.admin.history` alone can inspect retained records but cannot change review or publication state.
- [ ] Publication is denied without `bookexport.admin.publish`.
- [ ] `fail` and `unique` publication work with `bookexport.admin.publish`.
- [ ] `replace` is denied without the independent `bookexport.admin.replace` node.
- [ ] Reload is denied without `bookexport.admin.reload`.
- [ ] Debug is denied without `bookexport.admin.debug`.
- [ ] `bookexport.admin` grants every documented non-replacing capability, including review, approval, history, and publication, but does not grant replacement.
- [ ] Granting `bookexport.admin.replace` alone does not grant listing or publication.
- [ ] `replace` works only when both publish and replace nodes are effective.
- [ ] An explicit LuckPerms denial of a child node remains denied when `bookexport.admin` is granted.
- [ ] `exportbook.command` grants the documented non-replacing master behavior but not replacement.
- [ ] Other legacy `exportbook.*` nodes behave as documented in the README.

## Basic held-item and command routing

- [ ] Empty main hand gives a clear held-book error.
- [ ] A non-book main-hand item gives the same controlled error.
- [ ] A valid book in the offhand only is rejected with a main-hand explanation.
- [ ] An empty book and quill reports that it has no pages.
- [ ] A book and quill with content requires a custom title.
- [ ] A written book with a signed title works through `/bookexport`.
- [ ] `/bookexport export` uses the signed title when no custom title is given.
- [ ] `/bookexport export <title>` overrides a signed title.
- [ ] `/bookexport stage` uses a signed title when no custom title is given.
- [ ] `/bookexport stage <title>` overrides a signed title.
- [ ] `/bookexport <title>` remains a working legacy shorthand and follows the configured workflow.
- [ ] Reserved words can be titles through explicit syntax, such as `/bookexport export info` and `/bookexport stage list`.
- [ ] Success messages distinguish a staged draft from a direct publication and include filename, pages, and UTF-8 bytes.
- [ ] Fixed-arity commands such as `info`, `help`, `status`, and `reload` reject unexpected extra arguments with controlled usage instead of silently ignoring them.

## Staged workflow

Use config version 3 with `workflow-mode: staged`.

- [ ] `/bookexport` creates a unique `.txt` draft in `staging-directory`.
- [ ] `/bookexport export [title]` creates a staged draft.
- [ ] Legacy `/bookexport <title>` creates a staged draft.
- [ ] `/bookexport stage [title]` creates a staged draft.
- [ ] No normal staged command creates a file in the published directory.
- [ ] Staging alone does not add or change a live CMI CustomText entry.
- [ ] A staged success message says the file was staged rather than published.
- [ ] Repeating the same title creates `_1`, then `_2`, without replacing another draft.
- [ ] A complete UTF-8 draft exists after success and no `.bookexport-*` temporary file remains.
- [ ] A complete properties sidecar named `<draft>.bookexport-manifest.properties` exists after the staged `.txt` succeeds.
- [ ] No `<draft>.bookexport-creating` marker remains after a successful native stage.
- [ ] A simulated interrupted stage containing `.txt` plus `.bookexport-creating` fails closed and is not adopted as legacy.
- [ ] A sidecar failure does not report a fully managed staged success or leave a misleading partial manifest.
- [ ] An I/O failure produces a controlled error, records a useful last failure, and leaves no partial published file.

## Draft manifests and review workflow

Use disposable managed drafts and preserve exact copies of the `.txt` and sidecar before each mutation.

### Manifest creation and privacy

- [ ] Every newly managed staged draft receives one direct sibling `<draft>.bookexport-manifest.properties`.
- [ ] Each manifest has a valid stable UUID that differs from other drafts and remains unchanged through review and publication.
- [ ] Each manifest uses schema version 1 and advances its revision by exactly one for each recorded review or publication transition.
- [ ] The manifest records its origin, intended filename, and actual staged filename.
- [ ] A native manifest records creator name and UUID separately from the signed book author.
- [ ] A native manifest records an unambiguous UTC creation timestamp; a legacy-adopted manifest records its adopter and adoption timestamp without inventing the original creator or time.
- [ ] It records exact source page count, Java UTF-16 units, and rendered UTF-8 bytes.
- [ ] Its initial SHA-256 exactly matches the staged `.txt` bytes.
- [ ] It uses exactly `unreviewed`, `approved`, or `changes-requested` review state and leaves decision actor/time/checksum fields empty before a decision occurs.
- [ ] It uses exactly `staged`, `published-archive-pending`, or `published` publication state and leaves publication actor/time/mode/final/archive/backup fields empty before publication occurs.
- [ ] A replacement record includes the backup byte count and SHA-256; a non-replacement record has no backup fingerprint.
- [ ] The properties file contains no complete page, rendered body, CMI document content, server address, token, or full configuration dump.
- [ ] BookExport log messages contain only content-free metadata and never page text or the rendered body.
- [ ] No sidecar appears in `/bookexport list published|staged|archive|backups`; those scopes continue to list regular `.txt` files only.
- [ ] No `.bookexport-manifest.properties` file is copied into CMI's CustomText directory.
- [ ] Case-only manifest/marker variants cannot bypass association checks, and ambiguous variants fail closed.

### Review, approval, and changes requested

- [ ] `/bookexport admin review <file>` shows stable ID, origin, intended/actual name, stager, book author, UTC creation, pages/units/bytes, checksum, review state, and publication state without showing content.
- [ ] Reviewing an unchanged unreviewed draft reports matching current integrity and does not change files or review state.
- [ ] `/bookexport admin approve <file>` records the approving actor, UTC time, approved SHA-256, and approved state.
- [ ] The approved SHA-256 exactly matches the current staged bytes.
- [ ] Re-running approval safely refreshes the approval for the current bytes without changing the stable manifest ID.
- [ ] `/bookexport admin changes <file>` records the acting reviewer and changes-requested state and revokes any prior approval.
- [ ] A changes-requested managed draft cannot publish until explicitly approved again.
- [ ] Review is read-only; Approve and Changes player controls only suggest their commands and do not mutate state until submitted.
- [ ] Filenames containing spaces are parsed correctly by review, approve, and changes commands.
- [ ] Missing, nested, absolute, traversal, temporary-prefix, non-`.txt`, directory, and symbolic-link candidates are rejected cleanly.

### Integrity and compatibility behavior

- [ ] Changing one byte of an unreviewed managed draft is detected before publication.
- [ ] Changing one byte after explicit approval invalidates the approved checksum and blocks publication.
- [ ] A changed managed draft remains staged and published files remain unchanged after the blocked attempt.
- [ ] Reviewing the changed file clearly shows the mismatch without printing content.
- [ ] Explicitly approving the changed current bytes permits the later publication and retains the original provenance fields.
- [ ] A malformed, truncated, unsupported, duplicate-ID, or otherwise corrupt managed sidecar blocks publication instead of being treated as a legacy draft.
- [ ] A staged `.txt` with no sidecar is consistently identified as legacy/untracked; BookExport does not invent unavailable source metadata.
- [ ] An unchanged unreviewed managed draft remains publishable for compatibility and records the publisher as implicit approving actor.
- [ ] A legacy staged `.txt` with no sidecar remains publishable and receives a content-free record with legacy origin and implicit publisher approval.
- [ ] Unknown legacy source fields remain explicitly unknown rather than being guessed.
- [ ] Config remains version 3; manifest/review behavior adds no new config keys and does not rewrite `config.yml`.

### History

- [ ] `/bookexport admin history` and an explicit positive page list records newest first.
- [ ] History pagination has correct first, middle, last, out-of-range, zero, negative, and non-number behavior.
- [ ] History rows show enough stable ID, status, filename, actor, and time information to select the intended record without content.
- [ ] `/bookexport admin history show <id>` accepts a full stable UUID and shows the retained content-free lifecycle, including whether archival is pending or finalized.
- [ ] Unknown and malformed IDs return controlled errors without exposing filesystem details.
- [ ] One unrelated malformed or case-ambiguous active sidecar is omitted from history discovery without hiding other valid pending/finalized records; direct operations on that bad draft still fail closed and status counts an error.
- [ ] Two revisions using the same intended filename remain separately addressable by stable UUID.
- [ ] History records survive a clean server restart and retain review/publication actors, timestamps, checksums, and outcomes.

## Direct workflow and explicit staging

Temporarily use config version 3 with `workflow-mode: direct` and disposable names.

- [ ] `/bookexport`, `/bookexport export [title]`, and legacy `/bookexport <title>` write directly to the published directory.
- [ ] A direct success message explicitly says the file was published directly.
- [ ] Repeating a direct title adds `_1`, then `_2`; it never replaces an existing published file.
- [ ] `/bookexport stage [title]` still writes only to the staging directory.
- [ ] Returning to `workflow-mode: staged` through a valid reload changes subsequent behavior without moving existing files.

## Scoped file lists and player controls

Create enough disposable `.txt` fixtures in each scope to produce at least three pages.

- [ ] `/bookexport list` and `/bookexport admin list` list the `published` scope by default.
- [ ] `/bookexport list 2` retains backward-compatible published page-number syntax.
- [ ] `/bookexport list published [page]` lists published files only.
- [ ] `/bookexport list staged [page]` lists staged drafts only.
- [ ] `/bookexport list archive [page]` lists archived drafts only.
- [ ] `/bookexport list backups [page]` lists replacement backups only.
- [ ] Nested `/bookexport admin list ...` produces the same scoped results.
- [ ] Lists show only regular, non-symbolic-link `.txt` files and sort names case-insensitively.
- [ ] The header identifies the selected scope and current/total pages.
- [ ] Page 1 has a clickable Next control but no active Previous control.
- [ ] A middle page has working clickable Previous and Next controls.
- [ ] The last page has a clickable Previous control but no active Next control.
- [ ] Navigation runs the correct adjacent command, preserves the selected scope, and rechecks its permission.
- [ ] Clicking a filename copies it to the clipboard without running a command.
- [ ] Managed staged rows show the correct unreviewed, approved, changes-requested, changed, already-published, mismatch, missing, or corrupt manifest state.
- [ ] A staged `.txt` without a sidecar is clearly identified as legacy/untracked rather than approved.
- [ ] A Review action appears only for senders with `bookexport.admin.review` and performs no mutation.
- [ ] Approve and Changes actions appear only for senders with `bookexport.admin.approve`.
- [ ] Clicking Approve or Changes only suggests the complete command; no state changes until the tester submits it.
- [ ] A staged row has a Publish action only for senders allowed to publish.
- [ ] Clicking Publish only places a suggested publish command in chat; the staged and published files do not change until the tester submits it.
- [ ] The suggested Publish command explicitly ends in `fail`, even if the configured default is temporarily `unique` or `replace-with-backup`.
- [ ] Published, archive, and backup rows do not offer a staged Publish action.
- [ ] Console listing remains readable and works with explicit scope/page arguments; console publishers can type the documented publish command manually.
- [ ] Invalid scope, zero/negative page, non-number page, and past-last page produce controlled messages without exceptions.

## Publication and collision safety

Use a disposable `beta_publish.txt` draft and compare exact file bytes before/after each operation.

### New target and default mode

- [ ] `/bookexport admin publish beta_publish.txt` uses configured `publish-collision-mode: fail` when no mode is supplied.
- [ ] A new target publishes with the staged filename.
- [ ] The success message distinguishes a normal publication and names the published file.
- [ ] The staged draft is copied to a timestamped archive after success and removed from staging.
- [ ] The published bytes exactly match the reviewed staged bytes.
- [ ] The manifest records publisher, UTC publication time, `fail` mode, final filename, final SHA-256, archive filename, no backup, and successful outcome.
- [ ] The manifest reaches `published-archive-pending` immediately after the live commit and before the staged file is archived or removed.
- [ ] The final SHA-256 exactly matches the live published bytes and the bytes approved explicitly or implicitly.
- [ ] The sidecar is not copied to the published CMI directory.
- [ ] Publishing does not run `/cmi reload`; CMI changes only after an administrator reloads it.

### `fail`

- [ ] Create different staged and published files with the same case-insensitive name.
- [ ] Publishing with `fail` stops with a clear collision message.
- [ ] The published file is byte-for-byte unchanged.
- [ ] The staged draft remains byte-for-byte unchanged.
- [ ] No backup or archive is created for the failed attempt.
- [ ] The manifest does not falsely claim a successful publication after the collision failure.

### `unique`

- [ ] Publishing the colliding draft with `unique` creates `_1` or the next available suffix.
- [ ] The original published file is unchanged.
- [ ] The new published file matches the staged bytes.
- [ ] The success message distinguishes unique publication and names the final suffixed file.
- [ ] The successful draft is archived and removed from staging.
- [ ] The manifest preserves the intended/staged name while recording the distinct final suffixed filename and its checksum.

### `replace`

- [ ] Replacement is denied with only `bookexport.admin`, and both files remain unchanged.
- [ ] After separately granting `bookexport.admin.replace`, `replace` creates a timestamped backup containing the exact old published bytes.
- [ ] The original published path now contains the exact staged bytes.
- [ ] The success message distinguishes replacement and names the backup.
- [ ] The successful draft is archived and removed from staging.
- [ ] The manifest records `replace-with-backup`, final checksum, backup filename, archive filename, publisher, UTC time, and successful outcome.
- [ ] A case-only target match is handled as the same published name.
- [ ] `replace` fails safely when no published target exists; the draft remains and no misleading backup is created.
- [ ] No `.bookexport-publish-*` or `.bookexport-history-*` temporary files remain after success.

### Invalid and failure cases

- [ ] Omitting the staged filename gives clear usage.
- [ ] Supplying the name without `.txt` finds the matching text draft if supported by the command contract.
- [ ] A manually staged filename containing spaces can be published by typing or submitting the full suggested command.
- [ ] `../file`, nested paths, absolute paths, temporary-prefix names, blank names, and non-`.txt` candidates are rejected.
- [ ] A missing staged file is rejected without changing published files.
- [ ] Multiple case-insensitive staged matches are rejected as ambiguous.
- [ ] A staged directory, symlink, or other non-regular-file fixture is rejected.
- [ ] A non-regular or symbolic-link replacement target is rejected.
- [ ] A simulated publication write/move failure retains both the staged draft and existing published file.
- [ ] A simulated archive failure reports that publication succeeded, retains the staged draft when possible, and remains visible as `Last failure`.
- [ ] After an archive failure, the manifest distinguishes successful publication from the archive warning and records the final live checksum.
- [ ] A manifest-update failure never produces a misleading command response that claims an unrecorded state; inspect the retained files and logs for the controlled recovery condition.

## Filename behavior

- [ ] Spaces become underscores.
- [ ] Default CMI filenames become lowercase.
- [ ] Accented and non-Latin Unicode letters and digits remain readable.
- [ ] Unsupported symbols, emoji, and uncombined marks are removed safely; a title that sanitizes to nothing fails cleanly.
- [ ] `/`, `\\`, `:`, `*`, `?`, quotes, and control characters cannot create another path.
- [ ] `../` cannot escape the configured destination.
- [ ] A very long title is safely shortened without splitting a Unicode code point.
- [ ] A Windows reserved name such as `CON` is made safe.
- [ ] Names differing only by case receive a collision suffix for draft/direct/unique writes.
- [ ] Collision suffixes still fit both the configured code-point limit and conservative 255-byte UTF-8 component limit.
- [ ] Filename placeholders `%title%`, `%book_title%`, `%author%`, `%player%`, `%uuid%`, `%date%`, `%time%`, `%timestamp%`, and `%pages%` resolve correctly.
- [ ] Placeholder-looking title or author text is not expanded a second time.

## Page and text fidelity

Prepare books with known page labels so page order can be checked precisely.

- [ ] A one-page written book exports exactly one content page.
- [ ] A three-page book preserves page order 1, 2, 3.
- [ ] There is no empty generated page before source page 1.
- [ ] Intentional blank lines survive.
- [ ] An intentionally empty middle page has documented, understandable output.
- [ ] Long wrapped text is exported without plugin truncation or reflow.
- [ ] A near-limit book (up to 100 pages and 1,024 writable UTF-16 code units per page) exports without an exception.
- [ ] Accents, emoji, and non-Latin scripts survive as UTF-8.
- [ ] A written book's colors and decorations match the configured profile.
- [ ] Resets stop color/decoration bleed into later text.
- [ ] Interactive click/hover behavior is absent from plain text, as documented.
- [ ] Placeholder-looking text such as `%cmi_user_display_name%` remains unchanged in the file.

## Color profiles

Repeat a small fixture containing legacy colors, hex colors, bold, underline, italic, reset, and a literal malformed `§` sequence.

- [ ] `vanilla` preserves section-sign codes.
- [ ] `legacy` produces ampersand codes.
- [ ] `strip` removes valid formatting but retains literal/malformed section signs.
- [ ] `cmi` produces `{#RRGGBB}` colors and keeps decoration/reset behavior.
- [ ] `mini` produces MiniMessage color and decoration tags.
- [ ] An invalid `color-code-handling` value produces a warning and safe `cmi` fallback.

## CMI CustomText integration

- [ ] The published file's first line is `<AutoPage>`.
- [ ] Source page 1 follows `<AutoPage>` directly; there is no leading `<NextPage>`.
- [ ] `<NextPage>` appears exactly between source pages.
- [ ] The generated CMI page count matches the source book page count.
- [ ] Before `/cmi reload`, confirm BookExport did not silently refresh CMI.
- [ ] Run `/cmi reload` after publication.
- [ ] Open the text with `/cmi ctext <filename> <player>`.
- [ ] CMI page 1 equals Minecraft page 1.
- [ ] CMI navigation buttons work across all pages.
- [ ] CMI hex colors and decorations display correctly.
- [ ] CMI/PAPI placeholders resolve for the viewing player rather than the exporting player.
- [ ] Intentional CMI tags behave as expected.
- [ ] A book line equal to `<NextPage>` is reported as a trust/markup edge case, not mistaken for a BookExport pagination defect.

## Configuration paths and reload validation

- [ ] `staging`, `archive`, and `backups` resolve inside `plugins/BookExport/`.
- [ ] `~/plugins/CMI/CustomText/` resolves from the Paper server root.
- [ ] An allowed absolute test path works.
- [ ] A relative `../` escape is rejected.
- [ ] A path that is an existing regular file is rejected.
- [ ] A symbolic-link workflow directory is rejected.
- [ ] Reusing one directory for two scopes is rejected.
- [ ] Nesting one workflow directory inside another is rejected.
- [ ] An unwritable workflow directory fails in a controlled way and records a useful log message.
- [ ] A rejected reload preserves all previously validated runtime settings.
- [ ] `pagination: false` separates source pages with a blank line.
- [ ] A visible heading such as `=== Page %pageNumber% of %pages% ===` works with `pagination-on-first-page: true`.
- [ ] Metadata toggles all documented metadata lines, including the UTF-16 unit count.
- [ ] Debug logging adds statistics without logging page contents.
- [ ] The packaged and existing supported config remain `config-version: 3`; no manifest, approval-policy, or history key is required or silently added.
- [ ] Invalid `workflow-mode` and `publish-collision-mode` values are rejected.
- [ ] A future `config-version` is rejected instead of guessed at.

## Version 2 compatibility and manual migration

Use a backup or disposable server copy. Preserve hashes/timestamps for the old config and existing published files.

- [ ] A version 2 config loads in explicit direct compatibility mode.
- [ ] BookExport does not rewrite the version 2 file.
- [ ] BookExport does not move or rename existing exports.
- [ ] Normal `/bookexport`, explicit `export`, and legacy-title routes publish directly with unique suffixes.
- [ ] `/bookexport stage [title]` remains available and writes to staging.
- [ ] Explicit staging under a version 2 config creates the same managed sidecar without rewriting or upgrading `config.yml`.
- [ ] `/bookexport list [page]` still lists published files by default.
- [ ] Status/debug clearly explain compatibility mode and the version 3 migration path.
- [ ] Back up config and texts, add the version 3 workflow keys, verify four non-overlapping paths, and change `config-version` last.
- [ ] Reload/restart accepts the complete version 3 config and changes normal exports to staged mode.
- [ ] No existing published or staged file is moved during migration.
- [ ] A disposable stage, publish, archive, and CMI reload succeeds after migration.

## Restart and log review

- [ ] Stop the server cleanly; do not force-kill it.
- [ ] Review `logs/latest.log` for BookExport warnings, errors, exceptions, deprecated, legacy, unsupported, `NoClassDefFoundError`, `ClassNotFoundException`, or `LinkageError` messages.
- [ ] Separate BookExport findings from unrelated warnings emitted by other plugins.
- [ ] Restart once and confirm valid config and all four workflow scopes remain intact.
- [ ] Confirm no administrator setting is unexpectedly rewritten.
- [ ] Confirm staged sidecars remain readable and retain the same stable IDs, actors, timestamps, states, and checksums after restart.
- [ ] Confirm `/bookexport admin history [page]` returns the same retained record order after restart.
- [ ] Confirm published, archived, and backup bytes remain intact after restart.
- [ ] Search the full BookExport log output and manifest sidecars for distinctive fixture page text; none is present outside the intended `.txt`/archive content files.
- [ ] Confirm BookExport disables cleanly without an asynchronous-task warning.

## Finding template

Copy this section once for each defect:

```text
Severity: blocker / high / medium / low
Command or action:
Config version and workflow mode:
Permission nodes:
Held item:
Staged/published/archive/backup filenames:
Manifest ID and state:
Initial/reviewed/published SHA-256 values:
Expected result:
Actual result:
Relevant log lines:
Reproduction steps:
Screenshot/video:
```
