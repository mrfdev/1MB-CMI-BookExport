# Permissions

BookExport registers every permission in `plugin.yml` and relies on normal Bukkit
permission inheritance. Permission providers such as LuckPerms can grant narrow
roles or explicitly deny a child permission.

`bookexport.info` and `bookexport.help` default to everyone. Authoring and normal
administration default to operators. Destructive replacement defaults to false and
is deliberately excluded from the master permission.

## Current Permissions

| Permission | Default | Purpose |
| --- | --- | --- |
| `bookexport.admin` | Operator | Grant every documented non-replacing capability, including export, custom titles, information/help, all list scopes, review decisions, publication history, non-replacing publication, reload, and diagnostics. |
| `bookexport.export` | Operator | Process a written book or book and quill from the player's main hand. |
| `bookexport.export.custom-title` | Operator | Supply a book-and-quill title or override a signed book title. |
| `bookexport.info` | Everyone | Use `/bookexport info`. |
| `bookexport.help` | Everyone | Use `/bookexport help` and `/bookexport ?`. |
| `bookexport.admin.status` | Operator | View validated settings, workflow paths and health, audit counts, and the last failure. |
| `bookexport.admin.list` | Operator | List published text filenames. |
| `bookexport.admin.list.staged` | Operator | List staged filenames, review states, and permitted action controls. |
| `bookexport.admin.list.archive` | Operator | List archived published-draft filenames. |
| `bookexport.admin.list.backups` | Operator | List replacement backup filenames. |
| `bookexport.admin.review` | Operator | View content-free draft metadata, review state, and current checksum integrity. |
| `bookexport.admin.approve` | Operator | Approve a draft's current bytes or record a changes-requested decision. |
| `bookexport.admin.publish` | Operator | Publish verified drafts using `fail` or `unique`, and enter the publication workflow. |
| `bookexport.admin.replace` | False | Use replacement publication after BookExport creates a backup. This does not grant publication by itself. |
| `bookexport.admin.history` | Operator | List and inspect retained content-free publication records. |
| `bookexport.admin.reload` | Operator | Reload and validate the BookExport configuration. |
| `bookexport.admin.debug` | Operator | View runtime, workflow, CMI, held-book, and no-write preview diagnostics. |

## Master Permission Boundaries

`bookexport.admin` includes these children:

- `bookexport.export`
- `bookexport.export.custom-title`
- `bookexport.info`
- `bookexport.help`
- `bookexport.admin.status`
- `bookexport.admin.list`
- `bookexport.admin.list.staged`
- `bookexport.admin.list.archive`
- `bookexport.admin.list.backups`
- `bookexport.admin.review`
- `bookexport.admin.approve`
- `bookexport.admin.publish`
- `bookexport.admin.history`
- `bookexport.admin.reload`
- `bookexport.admin.debug`

It does **not** include `bookexport.admin.replace`. A sender needs both
`bookexport.admin.publish` and `bookexport.admin.replace` to replace a live file.
Granting replacement alone does not grant listing, review, or publication.

BookExport does not manually treat the master as an override. An explicit denial
of a child node remains authoritative when the permission provider supports normal
Bukkit child inheritance.

## Suggested Role Separation

These are capability examples rather than predefined ranks:

| Role | Suggested permissions |
| --- | --- |
| Ordinary player | Defaults only: `bookexport.info` and `bookexport.help` |
| Trusted signed-book author | `bookexport.export` |
| Trusted author with custom titles | `bookexport.export` and `bookexport.export.custom-title` |
| Read-only draft reviewer | `bookexport.admin.list.staged` and `bookexport.admin.review` |
| Approval reviewer | Read-only reviewer nodes plus `bookexport.admin.approve` |
| Publisher | Staged-list/review nodes as required by policy, plus `bookexport.admin.publish` |
| Replacement publisher | Publisher permissions plus the separately reviewed `bookexport.admin.replace` |
| Non-replacing administrator | `bookexport.admin` |

Authoring and publishing are intentionally separate. Book content may contain CMI
directives, actions, or placeholder tokens that become active after publication,
so author access should be limited to trusted users.

## Filename Privacy and Tab Completion

Staged filenames may reveal draft titles. Tab completion therefore requires the
action permission **and** `bookexport.admin.list.staged` before exposing staged
filenames:

- Review completion needs `bookexport.admin.review` plus staged-list access.
- Approve and Changes completion need `bookexport.admin.approve` plus staged-list
  access.
- Publish completion needs `bookexport.admin.publish` plus staged-list access.

The commands themselves still enforce their action permissions. List access is an
additional privacy boundary for discovery, not a substitute for an action node.

## Legacy Permission Aliases

Legacy `exportbook.*` nodes remain registered for existing permission data. They
default to false.

| Legacy permission | Grants |
| --- | --- |
| `exportbook.command` | `bookexport.admin`, meaning every non-replacing master capability but not `bookexport.admin.replace` |
| `exportbook.export` | `bookexport.export` and `bookexport.export.custom-title` |
| `exportbook.list` | `bookexport.admin.list` for published listing only |
| `exportbook.help` | `bookexport.help` |
| `exportbook.reload` | `bookexport.admin.reload` |

New permission setups should use the `bookexport.*` namespace. The legacy nodes
exist for compatibility and do not add broader replacement access.
