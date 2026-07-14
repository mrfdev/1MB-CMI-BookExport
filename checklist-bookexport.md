# BookExport 26.2 Beta Test Checklist

Use this checklist for an in-game review on the dedicated Paper 26.2 BookExport test server. Do not include server addresses, passwords, security keys, player IPs, or full configuration files in a public bug report.

## Test record

- [ ] Tester name:
- [ ] Date and timezone:
- [ ] BookExport version:
- [ ] BookExport build number:
- [ ] Paper version/build:
- [ ] Server Java version:
- [ ] Minecraft client version:
- [ ] Permission group used:
- [ ] Result: PASS / PASS WITH NOTES / FAIL
- [ ] Screenshots or relevant log lines attached:

## Before connecting

- [ ] An administrator confirms the newest BookExport JAR is installed and older copies are removed.
- [ ] `/version BookExport` reports `2.0.1` and `/bookexport info` reports build `012`.
- [ ] `/version` reports Paper 26.2 build 60.
- [ ] The server runtime reports Java 26.0.1 and the plugin reports Java target 25.
- [ ] BookExport, CMI, CMILib, LuckPerms, and PlaceholderAPI are green in `/plugins`.
- [ ] Startup logs contain no BookExport `WARN`, `ERROR`, exception, class-loading failure, legacy-plugin warning, or deprecated-API warning.
- [ ] The tester has been told which permission set to use for each permission test.

## Information and help

- [ ] `/bookexport info` shows the correct version, Paper target, Java target, server version, and repository.
- [ ] The repository link is clickable.
- [ ] `/bookexport help` is readable and only shows commands available to the tester.
- [ ] `/bexport help` works as an alias.
- [ ] Tab completion includes permitted commands and hides denied admin commands.
- [ ] Console can run `bookexport info` and `bookexport help`.
- [ ] A console export attempt returns a clear player-only message without an exception.

## Admin and debug commands

- [ ] `/bookexport admin` shows version, destination, writable state, profile, pagination, metadata, debug logging, and last failure.
- [ ] `/bookexport admin status` produces the same status view.
- [ ] `/bookexport debug` shows Java runtime, Java target, Paper API, live server, directory health, and last failure.
- [ ] `/bookexport debug cmi` detects CMI, CMILib, and PlaceholderAPI with the installed versions.
- [ ] `/bookexport debug cmi` reminds the tester to run `/cmi reload`.
- [ ] `/bookexport debug book` reports only held-book type/title presence/page and character counts—not page contents.
- [ ] `/bookexport debug preview <title>` reports a filename and size but writes no file.
- [ ] `/bookexport admin list` and `/bookexport admin debug` match their top-level shortcut behavior.
- [ ] Console admin/debug commands do not reveal credentials or page content.
- [ ] `/bookexport admin reload` accepts valid config changes.
- [ ] A fatal invalid path or malformed YAML reload is rejected, the file is not overwritten, and the prior validated runtime settings keep working.

## Permissions

Test with an unprivileged player, narrow nodes, and the master node.

- [ ] An unprivileged player can use `info` and `help` only.
- [ ] Export is denied without `bookexport.export`.
- [ ] A signed-title export works with `bookexport.export` alone.
- [ ] A custom title is denied without `bookexport.export.custom-title`.
- [ ] `bookexport.export.custom-title` enables only title override behavior.
- [ ] Status is denied without `bookexport.admin.status`.
- [ ] List is denied without `bookexport.admin.list`.
- [ ] Reload is denied without `bookexport.admin.reload`.
- [ ] Debug is denied without `bookexport.admin.debug`.
- [ ] `bookexport.admin` grants every documented capability.
- [ ] An explicit LuckPerms denial of a child node remains denied even when another node is granted.
- [ ] Legacy `exportbook.*` nodes behave as documented in the README.

## Basic held-item validation

- [ ] Empty main hand gives a clear held-book error.
- [ ] A non-book main-hand item gives the same controlled error.
- [ ] A valid book in the offhand only is rejected with a main-hand explanation.
- [ ] An empty book and quill reports that it has no pages.
- [ ] A book and quill with content requires a custom title.
- [ ] A written book with a signed title exports through `/bookexport`.
- [ ] `/bookexport export` uses the signed title when no custom title is given.
- [ ] `/bookexport export <title>` overrides a signed title.
- [ ] `/bookexport <title>` remains a working legacy shorthand.
- [ ] Reserved words can be exported through explicit syntax, such as `/bookexport export info`.

## Filename behavior

- [ ] Spaces become underscores.
- [ ] Default CMI filenames become lowercase.
- [ ] Accented, non-Latin, and Unicode titles remain readable.
- [ ] `/`, `\\`, `:`, `*`, `?`, quotes, and control characters cannot create another path.
- [ ] `../` cannot escape the configured destination.
- [ ] A very long title is safely shortened without splitting a Unicode character.
- [ ] A Windows reserved name such as `CON` is made safe.
- [ ] Re-exporting the same title creates `_1`, then `_2`, without overwriting.
- [ ] Names differing only by case still receive a collision suffix.
- [ ] `/bookexport list [page]` shows only `.txt` filenames and paginates cleanly.

## Page and text fidelity

Prepare books with known page labels so page order can be checked precisely.

- [ ] A one-page written book exports exactly one content page.
- [ ] A three-page book preserves page order 1, 2, 3.
- [ ] There is no empty page before source page 1.
- [ ] Intentional blank lines survive.
- [ ] An intentionally empty middle page has documented, understandable output.
- [ ] Long wrapped text is exported without plugin truncation.
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

- [ ] The generated file's first line is `<AutoPage>`.
- [ ] Source page 1 follows `<AutoPage>` directly; there is no leading `<NextPage>`.
- [ ] `<NextPage>` appears exactly between source pages.
- [ ] The generated CMI page count matches the source book page count.
- [ ] Run `/cmi reload` after export.
- [ ] Open the text with `/cmi ctext <filename> <player>`.
- [ ] CMI page 1 equals Minecraft page 1.
- [ ] CMI navigation buttons work across all pages.
- [ ] CMI hex colors and decorations display correctly.
- [ ] CMI/PAPI placeholders resolve for the viewing player rather than the exporting player.
- [ ] Intentional CMI tags behave as expected.
- [ ] A book line equal to `<NextPage>` is reported as a trust/markup edge case, not mistaken for a BookExport pagination defect.

## Configuration paths

- [ ] `books` resolves to `plugins/BookExport/books/`.
- [ ] `~/plugins/CMI/CustomText/` resolves from the Paper server root.
- [ ] An allowed absolute test path works.
- [ ] A relative `../` escape is rejected.
- [ ] A path that is an existing regular file is rejected.
- [ ] An unwritable destination fails in a controlled way and records a useful log message.
- [ ] `pagination: false` separates source pages with a blank line.
- [ ] A visible heading such as `=== Page %pageNumber% of %pages% ===` works with `pagination-on-first-page: true`.
- [ ] Metadata toggles all documented metadata lines.
- [ ] Debug logging adds statistics without logging page contents.

## Restart and log review

- [ ] Stop the server cleanly; do not force-kill it.
- [ ] Review `logs/latest.log` for BookExport warnings, errors, exceptions, deprecated, legacy, unsupported, `NoClassDefFoundError`, `ClassNotFoundException`, or `LinkageError` messages.
- [ ] Separate BookExport findings from unrelated warnings emitted by other plugins.
- [ ] Restart once and confirm valid config and existing exports remain intact.
- [ ] Confirm no valid administrator setting is unexpectedly rewritten.
- [ ] Confirm BookExport disables cleanly without an asynchronous-task warning.

## Finding template

Copy this section once for each defect:

```text
Severity: blocker / high / medium / low
Command or action:
Permission nodes:
Held item:
Expected result:
Actual result:
Relevant log lines:
Reproduction steps:
Screenshot/video:
```
