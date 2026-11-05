---
description: Ship kubedroid on F-Droid instead of Google Play — FOSS dependency gate (the non-FOSS ML Kit scanner is already gone with QR removal), in-repo fastlane metadata, reproducible builds so F-Droid publishes our own signing key, and the fdroiddata merge request after GitHub migration.
status: active
---

# Spec 11 — F-Droid distribution

## Context

Human decision 2026-10-28: kubedroid targets F-Droid, not the Google Play
Store. Play was assessed and rejected: it would require the 12-tester/14-day
closed-test regime for new personal developer accounts, a $25 account fee,
AAB packaging, and Play App Signing custody of the end-user key (which
breaks the pinned-cert verification story from specs 09/10 for Play users).
F-Droid needs none of that, and — critically — with **reproducible builds**
F-Droid publishes *our* spec 09-signed APK, so Gitea-sideload and F-Droid
users share one signing identity and can migrate between channels without
uninstall.

Supersedes the deferral note in spec 10 decision Q5.

## Current state (audited 2026-10-28)

- License: Apache-2.0 with NOTICE for Kubernetes art — inclusion-policy OK.
- Dependencies are FOSS (androidx/Compose/Hilt/kotlinx/snakeyaml/
  client-java stack). The former non-FOSS outlier
  `com.google.mlkit:barcode-scanning:17.3.0` was REMOVED together with the
  QR import feature (human decision 2026-10-28; spec 03 amendment, Dev plan
  stage 10) — the app now holds no camera permission at all.
- Release tags exist (spec 10); `publish.sh` already enforces tag/version
  gates. F-Droid requires a tag per official release — satisfied.
- No fastlane metadata in the repo yet; no reproducible-build setup;
  upstream repo is not yet public (spec 10 stage 6 migration pending).

## Requirements from F-Droid (verified 2026-10-28, f-droid.org docs)

1. Public upstream repo with real source + FOSS license file.
2. FOSS-only dependencies and build tools.
3. In-repo app metadata, fastlane structure, `en-US` locale:
   `fastlane/metadata/android/en-US/` — `title.txt`, `short_description.txt`
   (<80 chars, no trailing dot), `full_description.txt`, `images/icon.png`,
   `images/phoneScreenshots/*.png`, `changelogs/<versionCode>.txt` (≤500
   chars). F-Droid packagers require this in the *upstream* repo before
   inclusion.
4. `fdroiddata` submission: `metadata/com.neozeon.kubedroid.yml` sent as a
   merge request to gitlab.com/fdroid/fdroiddata — `RepoType: git`,
   `AutoUpdateMode: Version`, `UpdateCheckMode: Tags`, `commit:` full
   40-char hash, only the latest version block, no fields duplicated from
   fastlane, LF endings, `AuthorName` set.
5. Reproducible builds are optional but effectively mandatory in practice
   for new apps: MR reviewers ask for them, and without them F-Droid
   re-signs with *their* key, permanently — switching later forces a
   reinstall for every user. With them, `Binaries` +
   `AllowedAPKSigningKeys` let F-Droid ship our own signed APK.

## Design sketch

- **Camera surface: none.** QR import was removed outright (spec 03
  amendment stage 10), so no scanner dependency — FOSS or otherwise — is
  needed, and `CAMERA` is not in the manifest. This also moots the
  Play-era camera permission declaration.
- **FOSS dependency gate**: Gradle task (or unit test over
  `libs.versions.toml`) that fails the build on a denylist of known
  non-FOSS coordinate prefixes (`com.google.mlkit`, `com.google.android.gms`,
  `com.google.firebase`, …) — wired into `publish.sh` gates alongside the
  hygiene grep.
- **Reproducible builds**: pin the Gradle wrapper (checksum in fdroiddata),
  remove per-build nondeterminism (APK zip timestamps — AGP is deterministic
  modulo `buildDateMs`-style fields; verify empirically), verify locally
  with `reprotest`/`fdroid build` in the F-Droid docker builder image.
  Two identical independent builds (A-B, B-B) is the bar.
- **Metadata**: author `fastlane/metadata/android/en-US/…` in-repo.
  Title avoids implying official Kubernetes status (no bare "Kubernetes"
  brand in the title; descriptive mention in the full description is fine
  under LF trademark policy). Screenshots generated deterministically from
  the acceptance stub server (no real cluster data), captured on the
  emulator via the existing uiautomator flow.
- **publish.sh adapter**: new `--fdroid-check` mode (or `tools/fdroid/`
  script) that runs the FOSS gate + metadata validation + reprotest and
  emits the ready-to-paste fdroiddata YAML block with the full commit hash.
  Upload/publish itself stays with the human-submitted MR.

## Decisions (human, 2026-10-28)

Spec approved 2026-10-28 with the recommended defaults adopted:

* **Q1 — Scanner swap scope**: mooted — QR import was removed entirely
  (spec 03 amendment stage 10), so no scanner dependency exists at all.
* **Q2 — Reproducible builds from the first submission**: YES. Deferred
  repro builds would permanently lock us to the F-Droid signing key and
  force a future reinstall for all users.
* **Q3 — Submission upstream**: migrate first — submit the fdroiddata
  MR against `github.com/caseyrobb/kubedroid` after the spec 10 stage 6
  migration; no `Repo` field churn.
* **Q4 — Category/wording**: dev tools category; short description
  "Kubernetes cluster browser for self-hosted clusters".
* **Q5 — VersionCode**: keep manual bumps with a publish.sh monotonicity
  gate (fail the build if versionCode <= last tag).
* **Q6 — Update checks**: no in-app updater; the F-Droid client owns
  update notifications (spec 10 Q3 deferral stands).

## Dev plan

Status (2026-11-02): stages 1 and 2 complete (commits `bd368cb`, `39529c5`);
stage 3 GREEN on the build host with docker (user decision 2026-11-02):
container A/B in the F-Droid buildserver image hashes identical
(`81ec7b4acd522f69548399ecf6405133f77f566e4937c9eab55641c5e8c92ddc` for the
`v0.2.0` clone), the perturbation negative control fails as required, and
`reprotest` inside the same image reports "Reproduction successful" with
variations `+domain_host,+user_group,+time,+build_path,+home,+umask,
+environment` (host name, users, clock, build path, HOME, env and file
ordering proven irrelevant to the output). Host-side twin gate stays green
(four builds `80e6644c…` pre-spec-12; `052d461a…` for v0.2.0). Cert digest
`8aa62040…` identical across published and rebuilt APKs. See stage 3 for
the F-Droid-asset consequence (publish container-built APKs). Stage 4
metadata + runbook landed (`tools/fdroid/fdroiddata/`). **Stage 4
submitted 2026-11-05**: fdroiddata MR
[fdroid/fdroiddata!55918](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/55918)
opens `metadata/com.neozeon.kubedroid.yml` from a fork of
`caseyrobb/fdroiddata`; device in-place-update check pending. The
submission follows the fdroiddata CONTRIBUTING guide and Build Metadata
Reference: single latest `Builds:` entry (versionCode 4) with full-hash
`commit`, `RepoType`, `AuthorName`, template `gradle: [yes]`,
`AutoUpdateMode: Version` + `UpdateCheckMode: Tags`,
`Binaries:` + `AllowedAPKSigningKeys:` for reproducible builds, and
rewritemeta-normalized form (maintainer notes live in the runbook README,
not the yml — fdroid lint rejects unknown keys).

1. **Stage 1 — FOSS compliance**: denylist FOSS gate task wired into
   `publish.sh` (rejects `com.google.mlkit`, `com.google.android.gms`,
   `com.google.firebase`, … coordinates); regression test asserting the
   manifest requests no `CAMERA` and the catalog contains no denied
   prefixes. (The mlkit/CameraX/zxing removal itself already landed with
   spec 03 stage 10.)
2. **Stage 2 — in-repo metadata**: fastlane `en-US` tree incl. deterministic
   emulator screenshots + changelogs for v0.1.0; metadata validation test;
   publish.sh gate additions (short_description limits, changelog exists for
   current versionCode, versionCode monotonic vs last tag).
3. **Stage 3 — reproducible builds**: wrapper pinning, nondeterminism hunt,
   local reprotest (A-B/B-B) in the F-Droid builder image; document exact
   reproduction command.
   *Human decision (2026-11-02): docker is the container runtime on the
   build host.* Implemented as `tools/fdroid/repro-docker.sh` (+
   `repro-docker-impl.sh`): two clean signed `:assembleRelease` legs inside
   `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie` (the image
   class F-Droid rebuilders use), building a fresh `git clone` of the
   `Repo:` URL checked out at the release tag under a `kubedroid/` project
   dir — clone dir name and `.git` presence are load-bearing (Kotlin module
   metadata flows into dex layout; AGP `version-control-info.textproto`
   reads `.git`). `KUBEDROID_REPRO_REF` selects the tag;
   `KUBEDROID_REPRO_NEGATIVE=1` is the perturbation negative control.
   Exact command:
   `KUBEDROID_BUILD_HOST=<user@buildhost> KUBEDROID_KEYSTORE_PROPERTIES=<props path on host> KUBEDROID_KEYSTORE_FILE=<keystore path on host> KUBEDROID_REPRO_REF=vX.Y.Z bash tools/fdroid/repro-docker.sh`
   (local-host twin: `tools/fdroid/repro-check.sh`).
   FINDING (2026-11-02): the *build environment class* is load-bearing for
   what F-Droid's rebuilders will hash. Host-toolchain builds and
   buildserver-image builds of the same clone differ (uniform small dex
   offset shift from toolchain/SDK layout), so a published asset built on
   the host would be flagged non-reproducible by F-Droid. Decision:
   publish the container-built APK as the release asset — the same image
   class rebuilders use — via the single-leg flow
   `tools/fdroid/fdroiddata` README / publish-leg (clone tag -> buildserver
   image -> `publish-container.apk`); the v0.2.0 asset was replaced
   accordingly. reprotest (inside the image, null virtual server,
   variations `+domain_host,+user_group,+time,+build_path,+home,+umask,
   +environment`) reports Reproduction successful; run via
   `fdroid-work/run-reprotest2.sh`-style flow documented here:
   `reprotest --variations=... --no-diffoscope --source-pattern '*' -s <src>
   'sh build.sh' 'out.apk'` where build.sh is self-contained (keystore
   props staged inside the copied source root) and networked SDK bootstrap
   happens BEFORE reprotest (faketime must not wrap sdkmanager downloads).
   FINDING (2026-10-28): A-B builds on the build host matched on every zip
   entry (names, CRCs, timestamps) but differed in the APK Signing Block —
   AGP's `dependenciesInfo` SDKP pair (0x504b4453), a version-stamped,
   Play-encrypted blob, is per-build nondeterministic. Fix:
   `dependenciesInfo { includeInApk = false; includeInBundle = false }`
   (we never use Play Console, so the metadata has no value here). The
   release key is RSA 3072 / SHA256withRSA — PKCS#1 v1.5 signatures are
   deterministic, so a fully byte-reproducible signed APK is achievable.
   After the opt-out, A-B double builds must hash-identical (gate scripted
   in `tools/fdroid/repro-check.sh`).
4. **Stage 4 — submission** (after spec 10 stage 6 migration, per Q3):
   generate `metadata/com.neozeon.kubedroid.yml`, open the fdroiddata MR
   with the standard checklist, follow up with packagers; device-verify the
   F-Droid install path updates the Gitea-sideloaded app in place (same
   signing key).

## Testing

- Stage 1: FOSS gate task has a fixture test proving a denylisted
  coordinate fails the build; manifest regression test asserts no
  `CAMERA` permission; import flows (file/paste) covered by the existing
  spec 03 suites — no scanner tests exist by design.
- Stage 2: unit tests over fastlane files (short_description <80 + no
  trailing dot, changelog present for current versionCode, screenshots
  exist and are non-empty PNGs); full 46-step acceptance suite stays green.
- Stage 3: `tools/fdroid/repro-docker.sh` (A/B hash-identity gate +
  `KUBEDROID_REPRO_NEGATIVE=1` perturbation control that must FAIL the
  gate) and `tools/fdroid/reprotest-docker.sh` (reprotest inside
  `registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie` with
  variations `+domain_host,+user_group,+time,+build_path,+home,+umask,
  +environment`; must report "Reproduction successful"). Both were run
  green for v0.2.0 on 2026-11-02 (A=B=`81ec7b4a…`; reprotest out.apk
  `dbfdb985…` — differs from A/B only by reprotest's sandbox dir name,
  which varies the Gradle root project name; the F-Droid-relevant layout
  is the clone-dir `kubedroid` A/B hash, which is also what the published
  container-built release asset hashes to).
- Stage 4: acceptance criteria below; final proof is the merged fdroiddata
  MR plus an F-Droid-client install/update on the Pixel 8a.

## Configuration

- No new secrets. Reproduction of the build requires only the public repo,
  pinned wrapper, and the F-Droid docker builder image; our keystore stays
  in its spec 09/10 location (F-Droid never needs it — with reproducible
  builds they *verify* against our published signed APK, they do not sign).
- Optional env for the fdroid tooling: `KUBEDROID_FDROID_DATADIR` (local
  fdroiddata clone path).

## Acceptance criteria

- `fdroid lint` and `fdroid build` pass locally against the fdroiddata
  metadata at a tagged release.
- reprotest A-B/B-B clean; F-Droid publishes our signed APK (cert digest
  `8aa62040…93acd2` visible on the F-Droid APK page).
- FOSS gate has zero denylist hits; mlkit/CameraX/zxing coordinates gone; manifest has no `CAMERA` permission.
- fastlane metadata complete in upstream repo (en-US) and referenced, not
  duplicated, in fdroiddata.
- Pixel 8a: F-Droid client installs the app over the Gitea-sideloaded
  build without uninstall (shared signing key).
