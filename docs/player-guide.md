# BookExport Player Guide

## Introduction

BookExport lets trusted 1MoreBlock authors use Minecraft's book editor to prepare
guides, rules, news, event information, and other server text. A book can be staged
for staff review before an administrator publishes it for CMI CustomText.

Most players do not need author or administration access. Everyone can use
`/bookexport info` and `/bookexport help` by default to learn what the installed
plugin provides and see the commands available to them.

## How Players Use It

1. Write the content in a written book or book and quill.
2. Hold the book in your **main hand**.
3. Use `/bookexport stage [title]` to create a draft. A book and quill needs an
   explicit title; a signed written book can use its signed title.
4. Tell the reviewing staff member which draft was created.
5. Wait for staff to review and publish it. Staging by itself does not make the
   text live.
6. After publication and a CMI refresh, the text can be opened through the CMI
   command chosen by staff.

BookExport reads the held book; it does not consume it or remove it from your
inventory.

## Available Features

- Convert written books and books and quills into UTF-8 text.
- Keep Minecraft page order, explicit line breaks, blank lines, Unicode text,
  colors, and decorations supported by the configured output profile.
- Stage a draft for review without immediately changing the live CMI text.
- Preview the proposed filename and size when staff grant diagnostic access.
- Protect existing published names with safe collision choices controlled by
  administrators.
- Preserve placeholder-looking text for CMI to resolve for the eventual viewer.
- Keep review records free of the book's actual page content.

## Quick Start

- Run `/bookexport info` for the introduction, installed version/build, useful
  starting commands, and a clickable link to this guide.
- Run `/bookexport help` to see only the commands your permissions allow.
- If you are a trusted author, hold the book in your main hand and run
  `/bookexport stage My Title`.
- A signed book can use `/bookexport stage` without a custom title.
- Staff can use `/bookexport list staged` to find drafts awaiting review.

Using the explicit `stage` command is the clearest authoring route because it
always creates a draft, even on a server retaining an older direct-export
configuration.

## Commands

| Command | What it does |
| --- | --- |
| `/bookexport info` | Shows the introduction, version/build, compatibility, starting commands, and canonical documentation link. |
| `/bookexport help` | Shows the commands available to you. |
| `/bookexport stage [title]` | Always stages the held book for review. A book and quill requires a title. |
| `/bookexport export [title]` | Uses the server's configured workflow; this normally stages on a current installation. |
| `/bookexport` | Uses a signed book's title and follows the configured workflow. |
| `/bookexport list staged [page]` | Lists staged drafts when you have staff listing access. |
| `/bookexport debug preview [title]` | Shows a no-write preview when you have diagnostic access. |

`/bexport` is an alias for `/bookexport`. Review, approval, publication, history,
configuration, and other diagnostic commands are staff tools. See the
[complete command reference](https://github.com/mrfdev/1MB-CMI-BookExport/blob/master/docs/commands.md) for their exact syntax.

## Permissions or Rank Requirements

`/bookexport info` and `/bookexport help` are available to everyone by default.
Book authoring, custom titles, draft listing, review, and publication default to
server operators and may be granted only to selected staff or trusted-author
ranks.

The important author permissions are:

- `bookexport.export` to process a held book.
- `bookexport.export.custom-title` to name a book and quill or override a signed
  title.

Having author access does not automatically grant review or publication access.
See the [permission reference](https://github.com/mrfdev/1MB-CMI-BookExport/blob/master/docs/permissions.md) for the complete staff permission
model.

## Rewards, Costs, Limits, and Cooldowns

BookExport itself provides no item, currency, experience, rank, or kit rewards.
It does not charge money, experience, or items, and it does not consume the held
book.

BookExport currently adds no per-player cooldown, generated-byte quota, file-count
quota, or disk quota. Access is controlled through permissions, so trusted authors
should avoid repeatedly creating unnecessary drafts.

Minecraft and Paper still enforce book limits:

- Up to 100 editable pages.
- Up to 1,024 Java UTF-16 code units per editable page.
- Up to 32 characters for a signed book title.

Visible text capacity is not a fixed character count. Glyph width, formatting,
wrapping, and line breaks determine what fits on a visible page.

## Placeholders

BookExport does not register a PlaceholderAPI expansion for scoreboards or chat.
Placeholder-looking text inside a book, such as `%cmi_user_display_name%`, is kept
unchanged so CMI can resolve it for the player who later views the published text.

Administrators can also use internal filename and page-marker placeholders in the
plugin configuration. Those values are documented in the
[placeholder reference](https://github.com/mrfdev/1MB-CMI-BookExport/blob/master/docs/placeholders.md).

## Important Notes

- Use the main hand. A supported book in the offhand is not selected.
- A book and quill has no signed title, so it must be given a permitted custom
  title in the command.
- Staging creates a reviewable draft; it does not publish or refresh CMI.
- BookExport preserves authored CMI directives, interactive tags, and placeholder
  tokens. These can become active when CMI displays the published result, which is
  why author access is trusted.
- Generated text preserves visible text formatting, but a plain text file cannot
  retain book click events, hover events, insertion events, selectors, or other
  interactive component behavior.
- Staff may request changes instead of publishing a draft. Revise the source book
  or follow the reviewer's instructions before staging a replacement.

## Related Features

CMI CustomText is the optional display system used by the default BookExport
profile. CMI is responsible for loading the published file, handling its page
navigation, and resolving supported CMI or PlaceholderAPI tokens for the viewer.
BookExport does not run a CMI reload automatically.

The generated format follows the official
[CMI CustomText documentation](https://www.zrips.net/cmi/custom-text/).

## Technical Documentation

Administrators and developers can use the
[BookExport technical overview](https://github.com/mrfdev/1MB-CMI-BookExport/blob/master/README.md), plus the detailed
[commands](https://github.com/mrfdev/1MB-CMI-BookExport/blob/master/docs/commands.md), [permissions](https://github.com/mrfdev/1MB-CMI-BookExport/blob/master/docs/permissions.md), and
[placeholders](https://github.com/mrfdev/1MB-CMI-BookExport/blob/master/docs/placeholders.md) references.

The canonical public page is
[docs.1moreblock.com/custom-server-plugins/bookexport/](https://docs.1moreblock.com/custom-server-plugins/bookexport/).
