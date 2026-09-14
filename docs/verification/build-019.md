# Historical release verification

These are the original 2026-07-28 results. They do not describe the current release.

### Build 019 verification snapshot <!-- release-metadata-history -->

The release candidate was verified on 2026-07-28 before publication:

- `./gradlew clean build --warning-mode all` completed on JDK 25.0.4 with 253 tests: 251 passed, zero failures/errors, and two expected case-variant skips on case-insensitive APFS. It uses `--release 25`, treats compiler warnings as errors, and verifies generated resources, the JAR manifest, Java class version, all three release JARs, artifact names, and release metadata across every maintained documentation page. <!-- release-metadata-history -->
- The main plugin class is Java class-file major version 69, and the packaged descriptor declares BookExport 2.0.2 with `api-version: 26.2`. <!-- release-metadata-history -->
- Paper 26.2 STABLE build 84 is installed as `Paper-26.2.jar`; PaperScript verifies its saved and installed SHA-256 as `defe82c1c89067186895de34cf32983e9f5a2ea387cfe7597c020faebb98ca16`. <!-- release-metadata-history -->
- Build 019 is smoke-tested through clean startup, metadata/status/recovery commands, and shutdown on Oracle Java 25.0.4 and 26.0.2 with CMI 9.8.8.5, CMILib 1.5.9.9, LuckPerms 5.5.59, and PlaceholderAPI 2.12.3. <!-- release-metadata-history -->
- Neither smoke test emitted a BookExport warning, error, exception, or deprecated-API message. JVM startup did report Paper-bundled JOML's terminally deprecated `sun.misc.Unsafe::objectFieldOffset` use on both runtimes; Java 26 additionally reported LuckPerms-bundled Commodore final-field mutation. These third-party warnings did not originate in BookExport.
- `/bookexport version` is an exact information alias and `/bookexport status` is an exact admin-status alias; neither can fall through to the legacy custom-title export route.
- The automated suite adds 44 reachable collision-mode/crash-boundary scenarios, three complete publication modes, strict transaction codec/store durability, checksum reconciliation, restart-idempotent zero-write scans, scoped/global blocking, malformed-journal handling, direct-mode isolation, and sentinel privacy. Earlier manifest, renderer, filename, command, and Paper/CMI integration coverage remains in place.

Final artifact SHA-256:

`b2c55799ba63e7c7885eb568ff38e0a4d375f697857cb16fdd1e5ec3a26825f5  1MB-BookExport-v2.0.2-019-j25-26.2.jar` <!-- release-metadata-history -->

This automated and console verification does not replace the repository's in-game beta review.


## Original automated checklist baseline

- [x] Build 019 source coverage includes the durable publication journal, exact precomputed publication plans, recovery reconciliation, scoped publication blocking, fault injection at every publication boundary, and content-privacy sentinels. <!-- release-metadata-history -->
- [x] Build 019 uses Oracle JDK 25.0.4 with Java 25 class files and the exact stable compile API `26.2.build.84-stable`. <!-- release-metadata-history -->
- [x] Build 019 completed `./gradlew clean build --warning-mode all` with 253 tests: 251 passed, zero failures/errors, and two expected case-variant skips on case-insensitive APFS. <!-- release-metadata-history -->
- [x] The release drift gate validates generated resources, plugin descriptor, JAR manifest, all three release JARs, Java class-file major version 69, and semantic/build/JDK/Paper/artifact metadata across every maintained documentation page. <!-- release-metadata-history -->
- [x] Paper 26.2 `STABLE` build 84 is installed as `Paper-26.2.jar`; PaperScript's saved and installed SHA-256 values match. <!-- release-metadata-history -->
- [x] Build 019 is smoke-tested on Oracle Java 25.0.4 and 26.0.2 with CMI 9.8.8.5 and CMILib 1.5.9.9. <!-- release-metadata-history -->
- [x] Build 019 live console checks cover clean enable/disable, `/bookexport info`, `/bookexport version`, `/bookexport status`, permission-filtered help, admin/workflow status, clear recovery diagnostics, and shutdown without a BookExport warning, error, exception, or deprecated-API message. <!-- release-metadata-history -->
- [x] The only smoke-test JVM warnings were attributed to Paper-bundled JOML (`sun.misc.Unsafe::objectFieldOffset`, both runtimes) and LuckPerms-bundled Commodore (final-field mutation, Java 26 only), not BookExport.
- [x] Packaged JAR SHA-256: `b2c55799ba63e7c7885eb568ff38e0a4d375f697857cb16fdd1e5ec3a26825f5`.
- [x] Automated tests cover review decisions, checksum invalidation and reapproval, legacy publication, history, incomplete creation, malformed sidecars, and corrupt-draft history isolation. Earlier disposable integration testing confirmed that fixture content stayed out of logs and manifests, manifest sidecars stayed out of CMI, and fixtures were removed.


## Original optional plugin stack

The test-server versions, reverified on 2026-07-28, are:

| Plugin | Tested version | Relationship to BookExport |
| --- | --- | --- |
| CMI | 9.8.8.5 | Optional consumer of published CustomText files |
| CMILib | 1.5.9.9 | CMI's dependency, not BookExport's dependency |
| PlaceholderAPI | 2.12.3 | Optional; CMI can resolve preserved tokens at display time |
| LuckPerms | 5.5.59 | Optional Bukkit permission provider |
| Vault CMI build | Manifest version 1.7.3-CMI | Unrelated to BookExport |
