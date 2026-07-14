# Placeholders

BookExport has two internal placeholder groups: filename-template placeholders and
pagination-markup placeholders. It does not register a PlaceholderAPI expansion.

## Filename Placeholders

Use these values in the `filename-format` configuration setting:

| Placeholder | Exact value |
| --- | --- |
| `%title%` | The requested custom title, or the signed book title when no custom title was supplied. |
| `%book_title%` | The signed book title, or `untitled` for a book without one. |
| `%author%` | The signed book author, or `unknown` when unavailable. |
| `%player%` | The exporting player's current Minecraft name. |
| `%uuid%` | The exporting player's UUID in canonical hyphenated form. |
| `%date%` | Export date in server-local time, formatted `yyyy-MM-dd`. |
| `%time%` | Export time in server-local time, formatted `HH-mm-ss`. |
| `%timestamp%` | Export date and time in server-local time, formatted `yyyyMMdd-HHmmss`. |
| `%pages%` | Number of source book pages. |

Example:

```yaml
filename-format: "%date%_%player%_%title%"
```

A player named `Alex` exporting `Server News` on 14 July 2026 would begin with
the template result `2026-07-14_Alex_Server News`. Filename sanitation then
normalizes the value, removes unsafe filesystem characters, applies configured
lowercasing and length limits, and converts whitespace to underscores. With the
packaged lowercase setting, the filename becomes similar to
`2026-07-14_alex_server_news.txt`.

Replacement is single-pass. If a title or author literally contains text such as
`%player%`, that value remains literal data and is not expanded a second time.

## Pagination Placeholders

Use these values in `pagination-markup`:

| Placeholder | Exact value |
| --- | --- |
| `%pageNumber%` | Current source page number, starting at `1`. |
| `%pages%` | Total number of source pages. |

Example visible page heading:

```yaml
pagination: true
pagination-markup: "=== Page %pageNumber% of %pages% ==="
```

The packaged CMI profile instead uses `<NextPage>` as a page boundary and leaves
`pagination-on-first-page` disabled, so the marker appears only between source
pages. Pagination placeholders are not processed when no pagination marker is
generated.

## PlaceholderAPI and CMI Content

BookExport does **not** provide values such as `%bookexport_player%` for other
plugins, and installing PlaceholderAPI does not add BookExport placeholders.

Placeholder-looking text already written in a book is preserved rather than
resolved during export. For example:

```text
Welcome, %cmi_user_display_name%!
```

The exported text retains that token. When the file is later published as CMI
CustomText, CMI and its configured integrations may resolve it for the player who
views the text.

This also means authored placeholder tokens and CMI directives can become active
after publication. Only trusted users should receive book authoring and publishing
permissions, and staff should review the generated content before making it live.

## Book Metadata Is Separate

The `book-meta` configuration option can add a fixed title, author, exporter,
timestamp, page-count, and UTF-16-unit header to generated content. Those fields
are output metadata, not reusable placeholders, and their labels are not
configurable in the current release.
