---
description: Ship kubedroid on F-Droid instead of Google Play — replace the non-FOSS ML Kit barcode scanner, add in-repo fastlane metadata, gate on a FOSS dependency audit, achieve reproducible builds so F-Droid publishes our own signing key, and submit the fdroiddata merge request.
status: proposed
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

## Open questions

- **Q1 — Scanner swap scope**: replace ML Kit outright (single code path,
  recommended) vs keeping ML Kit behind a non-F-Droid flavor?
- **Q2 — Reproducible builds from the first submission**: recommended yes —
  deferred repro builds lock us into the F-Droid key and force a future
  reinstall for all users.
- **Q3 — Submission upstream**: wait for the spec 10 stage 6 GitHub
  migration and submit against `github.com/caseyrobb/kubedroid`, or submit
  now against the (public?) Gitea repo and rewrite the fdroiddata `Repo`
  field at migration? A `Repo` change is allowed but looks unstable to
  reviewers; recommendation: migrate first.
- **Q4 — Category/wording**: dev tools category; suggested summary
  "Kubernetes cluster browser for self-hosted clusters" — confirm wording.
- **Q5 — VersionCode story**: keep manual versionCode bumps with a
  publish.sh monotonicity gate, or derive versionCode from tag
  (major*10000+minor*100+patch)? F-Droid only demands monotonic increase.
- **Q6 — Update checks**: F-Droid client handles update notifications;
  confirm the spec 10 Q3 deferral of an in-app updater still stands (no
  in-app check).

## Dev plan

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
- Stage 3: reprotest exit 0 for A-B and B-B; a deliberately perturbed build
  fails reprotest (negative control).
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
