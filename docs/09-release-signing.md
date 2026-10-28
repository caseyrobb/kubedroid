---
description: Release build signing — conditional Gradle signingConfig fed by a local, git-ignored properties file, keystore generated once on the build host with a backup, and an apksigner verification gate.
status: active
---

# Spec 09 — Release signing

Debug builds carry `.debug` suffixes and the SDK debug key; there is no
release-signing story yet (`assembleRelease` emits an unsigned APK). This
spec adds reproducible release signing without putting any secret in Git,
logs, or chat output. Requested as a loose end at the spec 08 hardware
verification (2026-10-28); signed release builds are the precondition for
distributing kubedroid outside adb.

## Goals

* `assembleRelease` produces an APK signed with a dedicated kubedroid key
  (not the debug key), verifiable with `apksigner verify`.
* Builds stay green on machines **without** the keystore: signing is
  conditional, and the release APK simply stays unsigned in that case
  (same behavior as today, so CI/acceptance is unaffected).
* Zero secrets in the repository: keystore and credentials live outside
  the tree; `.gitignore` already excludes `*.keystore`/`*.jks`, this spec
  adds `keystore.properties`.

## Non-goals

* Play Store publishing, versioning policy, F-Droid metadata, artifact
  hosting, R8/minification (explicitly disabled for now), and key
  rotation ceremony (documented once, executed whenever needed).

## Design

`build.gradle.kts` resolves a properties file — path from the
`KUBEDROID_KEYSTORE_PROPERTIES` environment variable, defaulting to
`keystore.properties` in the project root (git-ignored). If the file
exists, a `signingConfigs.release` block is created from its
`storeFile`/`storePassword`/`keyAlias`/`keyPassword` keys and attached to
the release build type; otherwise the release type is left untouched.
No password ever appears in Gradle output (properties are read, not
echoed) and never in Git, remotes, or logs.

Key parameters (generated once with `keytool`): RSA 3072, SHA256withRSA,
validity 10000 days, alias `kubedroid`, DN `CN=kubedroid, O=neozeon`.
The password is machine-generated (`openssl rand`), never displayed.

## Configuration

* Build host (see docs/MAINTAINERS.md for the concrete machine): the
  release keystore (0600) and
  `keystore.properties` in `~/kubedroid/` (0600), mode `storePassword ==
  keyPassword`.
* Backup copies of both files kept off the build host (maintainer
  workstation, 0600; exact paths in docs/MAINTAINERS.md). Losing the keystore means release
  APK signatures change — upgrades over existing installs would fail — so
  the backup is part of "done".

## Testing

* Unit/lint/assembleDebug gates unchanged (signing is release-only).
* `assembleRelease` + `apksigner verify --print-certs` on the build host
  is the acceptance gate: exit 0 and the expected certificate DN.
* Negative case exercised implicitly: with the properties file absent the
  release task must still build (unsigned) — verified once at activation
  by temporarily unsetting the path.
* Acceptance driver (`tools/acceptance`) runs debug builds and is not
  touched.

## Acceptance criteria

1. `assembleRelease` produces `kubedroid-release.apk` (not `-unsigned`)
   with the kubedroid certificate; `apksigner verify` passes.
2. `git log`/`git status` show no keystore or password material;
   `.gitignore` covers the properties file.
3. A debug build + full unit suite remain green after the Gradle change.
