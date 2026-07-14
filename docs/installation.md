# Installing and Updating BookExport

BookExport 2.0.1 build 017 targets Paper 26.2 and Java 25 bytecode. Older Paper,
Minecraft, and Spigot releases, plus Java runtimes below 25, are intentionally
unsupported.

## Requirements

- A Paper 26.2 server. The plugin compiles against Paper API
  `26.2.build.60-beta` and declares `api-version: 26.2`.
- A Java 25 or newer runtime for Paper and a Java 25 JDK/toolchain when building
  from source.
- Filesystem write access for the BookExport data directory and all four configured
  workflow directories.

Paper is the only required runtime plugin/API. CMI is optional to BookExport itself,
but it is needed when the published `.txt` files are intended to be displayed as
CMI CustomText entries. See [Integrations](integrations.md) for the exact
relationship.

## Build from source

From a clean checkout of
[`mrfdev/1MB-CMI-BookExport`](https://github.com/mrfdev/1MB-CMI-BookExport):

```bash
./gradlew clean build --warning-mode all
```

The Gradle 9.4.1 wrapper selects a Java 25 toolchain, compiles against Paper 26.2
beta build 60, treats Java compiler warnings as errors, runs the JUnit suite, and
builds the plugin, source, and Javadoc JARs under `build/libs/`. The wrapper and
dependencies may require network access on their first use.

Deploy only this main artifact:

```text
build/libs/1MB-BookExport-v2.0.1-017-j25-26.2.jar
```

The `-sources.jar` and `-javadoc.jar` artifacts are references for developers and
must not be installed as Paper plugins. BookExport uses Gradle, not Maven.

## Clean installation

1. Stop Paper cleanly.
2. Copy `1MB-BookExport-v2.0.1-017-j25-26.2.jar` into the server's `plugins/`
   directory.
3. Confirm there is no second or older BookExport JAR in `plugins/`. Paper must
   discover exactly one copy.
4. Start Paper with Java 25 or newer. Do not use Bukkit `/reload` or a hot-reload
   plugin.
5. On first start, BookExport creates `plugins/BookExport/config.yml` and validates
   or creates its workflow directories. The packaged configuration uses staged
   review and publishes to `plugins/CMI/CustomText/`.
6. Review the readiness checks below before granting author or publisher access.

If BookExport cannot parse the configuration, create and validate a workflow
directory, or write its probe file, it logs the reason and disables itself rather
than running with partially validated storage.

## Updating an existing installation

1. Stop Paper and prevent external tools from writing to the BookExport workflow
   directories.
2. Back up the existing BookExport JAR, `plugins/BookExport/config.yml`, staging,
   archive, backup, and configured published directories as one recovery set.
3. Remove the old BookExport JAR from `plugins/` and install the build 017 main JAR.
   Do not install both versions together.
4. Keep the existing `config.yml`; do not overwrite it blindly with the packaged
   file. Compare settings and migrate deliberately when needed.
5. Start Paper cleanly and confirm the installed version and build.
6. Run the runtime and workflow readiness checks. Existing data is not moved or
   rewritten during startup.

An existing version 2 configuration remains in direct compatibility mode. It does
not become staged merely because the JAR was updated. Follow the
[version 2 migration procedure](configuration.md#migrating-a-version-2-configuration)
when the review-first workflow is ready to be adopted.

For rollback, stop Paper and restore the previous JAR, configuration, and persistent
directories from the same backup set. Do not combine an older JAR with partially
restored manifest/history files.

## Permissions before use

`/bookexport info` and permission-filtered help are available to everyone by
default. Export, review, publication, list, status, reload, and debug capabilities
default to operators. Grant those capabilities only to trusted authors or staff.

`bookexport.admin` grants the non-replacing administration workflow. It deliberately
does not grant `bookexport.admin.replace`; replacement must be granted separately
to the smallest appropriate group. Book content can contain CMI directives and
placeholder-looking tokens that become active after CMI loads the published text.

See [Permissions](permissions.md) for every node and default.

## Readiness checks

Run these after a clean install or update:

1. Confirm the startup log contains an enabled line for BookExport `2.0.1`, build
   `017`, Java target `25`, Paper target `26.2`, and the intended workflow and paths.
2. Confirm `/version BookExport` reports version `2.0.1`.
3. Run `/bookexport info` and confirm it reports build `017`, Paper `26.2`, Java
   target `25`, and the clickable canonical documentation URL:
   `https://docs.1moreblock.com/custom-server-plugins/bookexport/`.
4. Run `/bookexport admin status`. Confirm configuration version `3` on a fresh
   installation, workflow `staged`, collision default `fail`, and writable,
   non-overlapping staging, published, archive, and backup directories.
5. Run `/bookexport debug runtime`, `/bookexport debug workflow`, and
   `/bookexport debug cmi`. Confirm the expected live Java/Paper build, no recorded
   failure, expected integration presence, renderer profile, header, and page marker.
6. With a disposable written book or book and quill in the main hand, run
   `/bookexport debug preview <title>`. Confirm the destination, sanitized filename,
   page count, UTF-16 units, and UTF-8 bytes without writing a file.
7. When an in-game test is possible, complete one disposable staged workflow:
   stage, inspect the `.txt`, review the content-free manifest, approve its current
   checksum, publish with `fail`, inspect history, run `/cmi reload`, and open the
   resulting CustomText entry. Remove only that clearly identified fixture after
   testing.

The preview and held-book diagnostics require a player. Information, status, lists,
review, history, publication, reload, and non-book diagnostics can also be run from
the console when the sender has the required permission.

## After publication

BookExport writes the file but deliberately does not reload CMI. For a CMI-backed
installation, review the published file and then run:

```text
/cmi reload
/cmi ctext <name> <player>
```

Use the published filename without `.txt` as the CustomText name. Exact CMI command
availability is controlled by the installed CMI configuration and permissions.
