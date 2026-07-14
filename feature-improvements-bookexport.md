# BookExport Feature Improvements and Hardening Backlog

This document separates completed 2.0 modernization work from proposed follow-up work. It is intentionally a backlog, not a claim that every idea belongs in the plugin.

## Completed for the 2.0.1 beta cycle

- [x] Target Java 25 and Paper API 26.2 beta build 60.
- [x] Test runtime compatibility with installed Java 25.0.2 and 26.0.1.
- [x] Remove deprecated Bungee chat, `ChatColor`, and written-book string APIs.
- [x] Read written-book Adventure components and writable-book strings through the correct material-specific APIs.
- [x] Remove the blanket deprecation suppression and treat all compiler warnings as errors.
- [x] Correct CMI pagination so `<NextPage>` appears only between source pages.
- [x] Own CMI's first directive line with `<AutoPage>`.
- [x] Preserve PAPI/CMI-looking tokens instead of resolving them as the exporter.
- [x] Add validated color conversion with decoration and reset preservation.
- [x] Add Unicode-aware, bounded, traversal-safe filenames.
- [x] Check filename collisions case-insensitively and write complete content through temporary files.
- [x] Add config version 3 with a staged-by-default workflow and an explicit direct mode.
- [x] Preserve version 2 configs in direct compatibility mode without rewriting configs or moving files.
- [x] Add `/bookexport stage [title]`, which always stages regardless of workflow mode.
- [x] Add explicit reviewed-draft publication with `fail`, `unique`, and `replace-with-backup` collision modes.
- [x] Require independent `bookexport.admin.publish` and non-inherited `bookexport.admin.replace` permissions.
- [x] Create a timestamped backup before every replacement, checkpoint each live commit, and archive every normally completed publication while retaining a pending audit state on warning.
- [x] Reject traversal, symbolic links, non-regular candidates, overlapping workflow directories, and ambiguous case-insensitive publication matches.
- [x] Add scoped published/staged/archive/backup listing with clickable Previous/Next player controls.
- [x] Preserve the selected list scope during navigation, copy filenames on click, and make Publish a suggested explicit-`fail` command rather than an immediate action.
- [x] Add validated immutable settings and safe reload rollback.
- [x] Add granular `bookexport.*` permissions and legacy aliases.
- [x] Add `info`, `help`, `admin`, and `debug` command families.
- [x] Add content-free held-book diagnostics and export preview.
- [x] Generate version and target metadata from Gradle instead of hard-coding Java constants.
- [x] Add JUnit regression tests for filenames, colors, resets, malformed sequences, and CMI pagination.
- [x] Add storage tests for collision modes, exact-byte backups/archives, draft preservation, traversal, and symbolic links.
- [x] Add pure list/publish argument parser tests and Adventure action tests for navigation, clipboard names, and suggested publication.
- [x] Create atomic `<draft>.bookexport-manifest.properties` sidecars for managed staged drafts without changing config version 3.
- [x] Record stable manifest ID, origin, intended/actual filename, stager identity, book author, UTC creation time, page/unit/byte counts, and initial SHA-256 without storing page content.
- [x] Record review status, actor, UTC time, and checksum plus publisher, mode, final filename/checksum, archive, backup, and publication outcome.
- [x] Add content-free `review`, `approve`, `changes`, paginated `history`, and `history show <id>` administration commands.
- [x] Add independent review, approval, and history permissions to the non-replacing master while keeping replacement separate.
- [x] Show staged review state with confirmation-oriented Review, Approve, Changes, and Publish actions.
- [x] Keep explicit approval recommended and backward compatible: unchanged unreviewed and legacy drafts can publish with implicit publisher approval.
- [x] Detect changed or corrupt managed drafts and fail closed until the current bytes are approved or the manifest is repaired.
- [x] Preserve exact approved/published SHA-256 values so staff can prove the live bytes match the reviewed draft.
- [x] Keep manifest sidecars out of CMI and keep both manifests and audit logging free of page content.
- [x] Claim a content-free creation marker before exposing native staged text, reject incomplete pairs, and resolve manifest companions case-insensitively.
- [x] Checkpoint a committed live publication before archive/delete work so warnings cannot invite accidental republishing.
- [x] Keep valid publication history available when an unrelated active sidecar is malformed or case-ambiguous, while direct operations on that draft still fail closed.
- [x] Rewrite the README and create an in-game beta checklist.

## Beta priorities

### P0: validate before production use

- [ ] Complete every item in `checklist-bookexport.md` with at least one OP and one restricted tester.
- [ ] Prepare repeatable fixture books: one-page, three-page, Unicode, formatted, placeholder, malformed-color, empty-page, and near-limit books.
- [ ] Decide how player-authored CMI markup should be governed. Current behavior intentionally preserves it and therefore requires trusted export permissions.
- [ ] Validate the production policy for existing CMI text: default `fail`, deliberate `unique`, or separately authorized backed-up `replace`.
- [x] Smoke-test CMI 9.8.8.5 and CMILib 1.5.9.9 startup stability on Paper 26.2 beta build 60; in-game behavior remains covered by the beta checklist.

### P1: publishing workflow follow-ups

- [ ] Optionally run a narrowly configured CMI refresh command after publish; keep it disabled by default.
- [ ] Add a small transaction journal and startup recovery report for a process or host crash between backup, publication, manifest update, and archive steps. The content-free manifest records lifecycle outcome but is not a crash-recovery journal.
- [ ] Evaluate cross-process file locking or an explicit single-writer policy for destinations that external tools may edit concurrently.
- [ ] Add a content-aware review report that flags CMI directives, interactive tags, and placeholder tokens without logging raw page content.
- [ ] Consider an opt-in strict policy that requires explicit approval for every managed and legacy draft; keep the current non-blocking compatibility policy until deliberately configured.
- [ ] Add `/bookexport admin discard <staged-file>` with a separate permission and confirmation-oriented UI.
- [ ] Add safe restore commands for selected archives or backups; require the same independent replacement permission and create another backup.
- [ ] Add configurable retention and explicit prune commands for archives/backups; never prune silently by default.
- [ ] Add configurable retention and explicit prune commands for completed manifest history; never prune review records silently by default.

### P1: resource and abuse controls

- [ ] Add per-player export cooldowns.
- [ ] Add maximum generated bytes per export.
- [ ] Add maximum file count or disk quota.
- [ ] Snapshot Bukkit item data on the main thread and move slow external-path file I/O to a safe asynchronous worker.

### P1: command and permission flexibility

- [ ] Add configurable/localizable messages.
- [ ] Add `/bookexport help <command>` detail pages.
- [ ] Add `/bookexport admin validate` to validate configuration and destination without applying changes.
- [ ] Add optional player, time, and outcome filters to the existing manifest-history command.
- [ ] Consider separate runtime, held-book, CMI, and preview debug permission nodes.
- [ ] Add a strict/deprecation mode that rejects unknown subcommands instead of treating every unknown word as a legacy title.
- [ ] Consider an explicit `bookexport.export.cmi-markup` permission. Without it, reserved CMI directives could be escaped or rejected.
- [ ] Consider `bookexport.export.placeholder-markup` separately if untrusted authors are ever supported.

## Fidelity improvements

- [ ] Add an optional JSON sidecar that preserves full Adventure components, including click and hover events.
- [ ] Add a Markdown renderer.
- [ ] Make the MiniMessage renderer component-aware rather than converting legacy codes after serialization.
- [ ] Add a strict plain-text renderer using Adventure's plain serializer.
- [ ] Investigate Paper's experimental written-book data components for raw versus filtered page variants.
- [ ] Add configurable treatment for empty pages and trailing blank lines.
- [ ] Add a configurable escape strategy for literal CMI reserved lines such as `<NextPage>`.
- [ ] Consider offhand selection only as an explicit command option; keep main hand as the unambiguous default.

## CMI-specific improvements

- [ ] Support controlled first-line directives such as `<AutoAlias>`, `<Hidden>`, and `<ReqPermission>` through validated configuration.
- [ ] Support safe page labels after generated page breaks.
- [ ] Detect duplicate CMI names before export and explain that CMI keys names case-insensitively.
- [ ] Provide a CMI preview that flags reserved directives and interactive tags without displaying sensitive page content in console logs.
- [ ] Add an integration test fixture based on the official CMI CustomText grammar.
- [ ] Document and test whether CMI reload can be narrowed to CustomText rather than a full plugin reload in future CMI versions.

## Permission review

The current model deliberately keeps publishing power trusted by default.

| Capability | Current node | Review note |
| --- | --- | --- |
| Export signed book | `bookexport.export` | Appropriate as a trusted-author node |
| Choose filename/title | `bookexport.export.custom-title` | Correctly separated from base export |
| Public version info | `bookexport.info` | Safe for everyone |
| Permission-filtered help | `bookexport.help` | Safe for everyone |
| Filesystem/config status | `bookexport.admin.status` | Keep trusted; exposes destination path |
| List filenames | `bookexport.admin.list` | Keep trusted if filenames contain private titles |
| List staged drafts | `bookexport.admin.list.staged` | Keep separate; draft titles and publish suggestions are review data |
| List archives | `bookexport.admin.list.archive` | Keep trusted; exposes historical titles |
| List replacement backups | `bookexport.admin.list.backups` | Keep trusted; exposes historical live names |
| Review manifest integrity | `bookexport.admin.review` | Keep trusted; exposes stager, author, filename, timestamps, and checksums but never page content |
| Approve/request changes | `bookexport.admin.approve` | Included by `bookexport.admin`; records a review decision bound to exact current bytes |
| View manifest history | `bookexport.admin.history` | Keep trusted; exposes content-free review and publication audit metadata |
| Reload config | `bookexport.admin.reload` | Keep trusted |
| Runtime/book diagnostics | `bookexport.admin.debug` | Keep trusted; content is intentionally omitted |
| Publish staged CMI text | `bookexport.admin.publish` | Included by `bookexport.admin`; permits `fail` and `unique`, not replacement by itself |
| Replace existing file | `bookexport.admin.replace` | Independent, default false, and deliberately excluded from current and legacy master nodes |
| Author active CMI markup | Included in export trust | Consider a separate node if non-staff authors are allowed |

Avoid manual checks such as `childPermission || masterPermission`. Bukkit/LuckPerms child inheritance must remain authoritative so an explicit child denial works.

## Test automation suggestions

- [ ] Unit-test every filename placeholder.
- [ ] Decouple configuration parsing enough to unit-test path containment/overlap, invalid values, reload rollback, and version 2 direct compatibility without a live Bukkit server.
- [ ] Add proxy-sender tests for permission-filtered help, scoped tab completion, review/approval/history separation, staged-filename privacy, replacement separation, and explicit child denials.
- [ ] Unit-test the five output profiles with nested colors and resets.
- [ ] Unit-test blank, one-page, 100-page, and writable pages containing 1,024 UTF-16 code units.
- [ ] Add a Paper test harness when a reliable 26.2-compatible harness is available.
- [ ] Add a CI workflow that runs `./gradlew clean build --warning-mode all` on Java 25.
- [ ] Verify class major version 69 and expanded `plugin.yml` as build assertions.
- [ ] Start an isolated Paper process in CI only if CMI licensing and test artifacts can be handled without redistribution.

## Deliberately out of scope for now

- A direct CMI/CMILib API dependency: no API call is currently necessary.
- A PlaceholderAPI expansion: BookExport does not expose changing runtime values.
- Support for older Paper, Spigot, or Minecraft versions.
- Hot reload support through Bukkit `/reload` or plugin managers.
- Silent or unbacked replacement of existing exports.
