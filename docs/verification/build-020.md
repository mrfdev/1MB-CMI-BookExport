# Build 020 verification

Verified on 2026-09-15 on macOS 27.0 arm64, case-insensitive APFS.
BookExport remains version 2.0.2; the independent monotonic release number is
build 020. The Paper target and dependencies are unchanged.

## Toolchains and build

| Role | Exact Oracle runtime | JDK home |
| --- | --- | --- |
| Gradle, compiler, default tests, Paper smoke | `25.0.4.1+1-LTS-5` | `/Library/Java/JavaVirtualMachines/jdk-25.0.4.1.jdk/Contents/Home` |
| Compatibility tests and Paper smoke, matching the live Java major version | `26.0.2.1+1-7` | `/Library/Java/JavaVirtualMachines/jdk-26.0.2.1.jdk/Contents/Home` |

The Gradle 9.4.1 wrapper was run with:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.0.4.1.jdk/Contents/Home
export JAVA26_HOME=/Library/Java/JavaVirtualMachines/jdk-26.0.2.1.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew clean build --warning-mode all
./gradlew testJava26 --warning-mode all
```

Both commands passed. Gradle recorded the actual compiler and test JVM versions.
`testJava26` uses the same Java 25 compiled classes and complete test suite with
the Java 26 launcher. Neither compilation nor Javadocs reported warnings/errors.
All 76 plugin classes have class-file major version 69 and minor version 0:
Java 25 bytecode without preview features.

| Suite | Tests | Passed | Failed/errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| `test`, Java 25.0.4.1 | 253 | 251 | 0 | 2 |
| `testJava26`, Java 26.0.2.1 | 253 | 251 | 0 | 2 |

Both skips are existing `DraftManifestStoreTest` cases that require distinct
case-variant files, which this APFS volume cannot create:
`ambiguousUnrelatedActiveSidecarsDoNotHideValidArchiveHistory` and
`multipleCaseVariantSidecarsFailAsAmbiguous`.

The release gate verified generated resources, the plugin descriptor, manifest,
all three artifact names, Java target, Paper target/API/build/channel/checksum,
and maintained documentation. Six additional temporary documentation fixtures
confirmed that the gate accepts current four-part JDK versions, rejects stale
three-part and incorrect four-part patches for both Java versions, and accepts
explicitly marked historical records. The fixtures were removed afterward.

The official [Paper documentation index](https://docs.papermc.io/llms.txt),
[Paper Java requirements](https://docs.papermc.io/paper/getting-started/), and
[Gradle 9.4.1 compatibility table](https://docs.gradle.org/9.4.1/userguide/compatibility.html)
were checked. Paper requires Java 25 or newer; the existing wrapper supports both
Java 25 and Java 26. No Paper API or server build upgrade was needed.

## Artifacts

All three archives passed ZIP integrity checks. Only the main JAR is installed
as a plugin. Source and Javadoc JARs remain developer references.

| Artifact under `build/libs/` | Bytes | SHA-256 |
| --- | ---: | --- |
| `1MB-BookExport-v2.0.2-020-j25-26.2.jar` | 239890 | `b7f8ac362d80332dfe1369361d9a82f9c9ef3af967a9f94596304071d8cfc38f` |
| `1MB-BookExport-v2.0.2-020-j25-26.2-sources.jar` | 101918 | `134542e884a8aaf6a177af4b1268676699e7f9de0685b4315969faea2cd7bff6` |
| `1MB-BookExport-v2.0.2-020-j25-26.2-javadoc.jar` | 4141417 | `9b63439b27e64c172b61142b6bb2de253b4d245de0747731e2102c227c18cb4e` |

The copies loaded by both Paper processes matched the main JAR checksum above.

## Paper smoke and restart checks

Both isolated servers ran Paper 26.2 STABLE build 84,
`26.2-84-main@26e81c4`, implementing `26.2.build.84-stable`.
Their `Paper-26.2.jar` matched the pinned SHA-256:
`defe82c1c89067186895de34cf32983e9f5a2ea387cfe7597c020faebb98ca16`.

The installed optional stack was CMI 9.8.9.9, CMILib 1.5.9.9, LuckPerms 5.5.81,
PlaceholderAPI 2.12.3, and Vault 1.7.3-CMI. All enabled successfully alongside
BookExport on each JDK.

The servers used separate disposable directories under the ignored
`servers/verification-build-020/`, fresh worlds, and a loopback listener.
The existing server's EULA acceptance and pinned Paper JAR were reused.
For each runtime, `JAVA_HOME` was set to the corresponding directory in the
table above, `PATH` began with `$JAVA_HOME/bin`, and the harness launched:

```bash
java -Xms512M -Xmx2G -Dfile.encoding=UTF-8 \
  -Dterminal.ansi=false -Dterminal.jline=false \
  -jar Paper-26.2.jar --nogui
```

Each runtime passed:

- Startup to Paper's ready message, BookExport enable, and exact version/build/API checks.
- `version`, `version BookExport`, `plugins`, and BookExport `info`, `version`,
  `status`, `help`, `admin status`, `debug cmi`, and `debug workflow` commands.
- Legacy UTF-8 draft review and explicit approval, then successful `fail` mode
  publication when no live file existed.
- Rejection of a colliding `fail` publication, preserving both live and staged bytes.
- `unique` publication to a distinct filename, preserving the original live file.
- `replace` publication with an exact-byte backup of the overwritten file.
  The disposable server granted `bookexport.admin.replace` through a local
  `permissions.yml` fixture. A preliminary run also confirmed that replacement
  was denied without that independent permission, including from the console.
- Three exact-byte archives and one exact-byte replacement backup per runtime;
  approved/published fixture bytes retained Unicode and placeholder-looking text.
- No manifest sidecars in CMI CustomText and no fixture content in logs or metadata.
- Manifest history, clear recovery inspection, and validated configuration reload.
- Clean `stop`, BookExport disable, completed world saves, and process exit code 0.
- A second startup, status/history/recovery inspection, and clean shutdown.
  Workflow file checksums and modification timestamps were identical before and
  after the restart, confirming that inspection performed no workflow writes.

No BookExport warning, error, exception, or deprecated-API message occurred.
Third-party warnings were Paper-bundled JOML's `sun.misc.Unsafe::objectFieldOffset`
use and OSHI's unrecognized macOS 27.0 codename on both runtimes, plus
LuckPerms-bundled Commodore final-field mutation on Java 26. These did not prevent
startup, command execution, publication, or clean shutdown.

The console smoke suite used disposable text fixtures. Player-held book capture,
client interactions, and permission-group beta testing remain documented in the
[in-game checklist](../../checklist-bookexport.md).

Local evidence includes `full-rebuild.log`, `java26-tests.log`, the per-runtime
smoke/restart logs and JSON summaries, `metadata-regressions.json`, `SHA256SUMS`,
and the disposable `smoke.py` harness under `servers/verification-build-020/`.
JUnit reports are under `build/reports/tests/test/` and
`build/reports/tests/testJava26/`. Earlier release results remain in the
[historical verification record](build-019.md).
