# BookExport Integrations

BookExport has one required platform dependency: Paper. It deliberately avoids
linking against the APIs of CMI, CMILib, PlaceholderAPI, LuckPerms, and Vault.
`plugin.yml` declares no `depend` or `softdepend` entries, so those optional plugins
do not control whether BookExport loads.

## Compatibility and tested stack

| Component | Relationship | Verified target or tested version |
| --- | --- | --- |
| Paper | Required server and production API | Paper 26.2; compile API `26.2.build.60-beta` |
| Java | Required runtime and bytecode target | Java 25 or newer runtime; Java 25 class files |
| CMI | Optional consumer of published CustomText files | CMI 9.8.8.5 tested |
| CMILib | CMI's dependency, not BookExport's dependency | CMILib 1.5.9.9 tested alongside CMI |
| PlaceholderAPI | Optional resolver used by CMI or other display plugins; no BookExport expansion | PlaceholderAPI 2.12.3 tested alongside CMI |
| LuckPerms | Optional Bukkit permission provider | LuckPerms 5.5.59 tested |
| Vault | No BookExport relationship | The tested server's CMI-flavored Vault artifact reported manifest version `1.7.3-CMI`; BookExport did not use it |

The optional-plugin versions above describe the stack exercised on 2026-07-14.
They are not declared minimum dependencies and are not bundled into the BookExport
JAR.

## Paper and Adventure text

Paper 26.2 supplies the server API and Adventure component types used to read modern
written-book pages and send chat components. BookExport targets Java 25 class files
and `api-version: 26.2`; Spigot and earlier Paper/Minecraft versions are not
supported.

The Paper-aligned Adventure API listed in the Gradle test configuration supports
unit tests. BookExport does not bundle a separate Adventure runtime JAR.

## CMI CustomText

CMI is a file-format consumer, not an API dependency. With packaged defaults,
BookExport publishes UTF-8 files into:

```text
plugins/CMI/CustomText/
```

The `cmi` renderer writes `<AutoPage>` as a controlled first line, converts colors
to CMI's `{#RRGGBB}` form, converts supported decorations and resets to ampersand
codes, and inserts `<NextPage>` only between Minecraft pages. BookExport never
executes a CMI command and never invokes a CMI API.

After publishing or directly exporting a new file, an administrator must reload CMI
and then test the entry:

```text
/cmi reload
/cmi ctext <name> <player>
```

Without CMI, BookExport can still stage, review, archive, back up, and write text
files. The default published directory may be created even when CMI is absent, but
there is no CMI viewer for those files. `/bookexport debug cmi` reports whether CMI
and CMILib are currently installed; detection is diagnostic only.

CMI directives, action tags, and placeholder-looking text in an authored book are
preserved intentionally and can become active when CMI displays the published file.
Staging and checksums provide a review boundary; they do not sanitize author content.
Grant export and publication permissions only to trusted users.

CMI format reference: [CMI CustomText](https://www.zrips.net/cmi/custom-text/).

## CMILib

BookExport does not compile against or call CMILib. CMILib is present only when the
installed CMI version requires it. `/bookexport debug cmi` shows its detected plugin
version to help diagnose the surrounding CMI stack; BookExport behavior does not
change based on that value.

## PlaceholderAPI

BookExport does not register a PlaceholderAPI expansion and does not resolve PAPI
or CMI placeholders. A token such as `%cmi_user_display_name%` is written unchanged.
CMI may resolve that token later for the player viewing the CustomText when the
server's CMI/PlaceholderAPI setup supports it.

This deferred behavior is useful for per-viewer text, but it is also a reason to
review authored content before publication. A token being preserved is not proof
that an installed expansion recognizes it. `/bookexport debug cmi` reports
PlaceholderAPI presence for diagnostics only.

## LuckPerms and Bukkit permissions

BookExport checks Bukkit permission nodes. LuckPerms is an optional way to manage
those nodes; BookExport does not call its API and does not require a particular
permission plugin. Bukkit operator/default behavior applies when no external
permission provider changes it.

Use [Permissions](permissions.md) as the authoritative node list. In particular,
`bookexport.admin` excludes `bookexport.admin.replace`, so permission inheritance
does not accidentally authorize replacement of a live file.

## Vault

BookExport has no economy, chat, or permissions integration through Vault. It does
not require Vault and does not use the Vault API. A server may install Vault for CMI
or other plugins, but that is unrelated to BookExport.

## Output profiles are not runtime integrations

The `legacy`, `vanilla`, `strip`, and `mini` color modes transform serialized text;
they do not hook another plugin. In particular, `mini` writes supported
MiniMessage-like tags but BookExport does not install or invoke a MiniMessage
renderer. The eventual consumer must understand the selected output syntax.
