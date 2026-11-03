---
description: Public release preparation — release-APK hosting with checksummed artifacts and a scripted publish flow, plus repo/documentation hygiene (root README, CONTRIBUTING, SECURITY policy, de-internalization of docs) needed to open the repository.
status: active
---

# Spec 10 — Release hosting & public-repo readiness

Spec 09 gave kubedroid a reproducible signed release build. This spec covers
the two remaining steps to a public release: (a) hosting the signed APK
where users can download it with integrity guarantees, and (b) cleaning the
repository and documentation so it reads as a public project rather than a
homelab diary.

Survey already done (2026-10-28, HEAD `58608be`):

* Working tree clean; 50 commits; `LICENSE` + `NOTICE` present; no root
  `README.md`, no `CONTRIBUTING.md`, no `SECURITY.md`.
* Full-history secret scan: clean (only test fixtures and the parser's
  PEM-format string constants match credential patterns).
* Internal identifiers: only "devbox" prose in acceptance-tool docstrings
  and maintainer-environment names (devbox/GB10) in specs 01/06/07/09. The
  `chaos`/`Chaos` hits are generic test fixture names. Package id
  `com.neozeon.kubedroid` is intentional branding.

## Goals

1. Every published release is a tagged commit producing:
   `kubedroid-release.apk` (spec 09 signing), a `SHA256SUMS` file, and
   release notes; all three attached to a release entry users can find.
2. A repeatable maintainer script (`tools/release/publish.sh`) performs:
   gradle release build → `apksigner verify` gate → checksum → tag push →
   artifact upload, with a dry-run mode. No secrets in the script (token via
   env/askpass pattern already used for git push).
3. Public-facing repo files: root `README.md` (what it is, screenshots
   placeholder, install instructions incl. checksum verification),
   `CONTRIBUTING.md` (spec-driven workflow summary + test bar),
   `SECURITY.md` (reporting contact + the credential-handling posture from
   spec 03: keystore-at-rest, no logging, no analytics).
4. Documentation de-internalization: maintainer-environment specifics move
   to a new `docs/MAINTAINERS.md`; numbered specs refer to "the build host"
   generically. The spec history stays truthful — nothing is deleted, just
   relocated.

## Non-goals

* The GitHub migration itself (repo transfer, import mechanics) — this
  spec only prepares a repo that can move cleanly. Play Store, F-Droid
  metadata (planned, human decision Q5), and in-app update checker
  (planned, Q3) are out of scope here, as are screenshots (later pass).

## Design sketch

* **Hosting**: canonical release entry on the code host. The repo moved
  to `github.com/caseyrobb/kubedroid` as sole source of truth on
  2026-11-02 (human decision, superseding the "~v1.0" timing in Q4); the
  Gitea repo remains as an internal mirror. `publish.sh` is host-agnostic:
  a small `upload()` function per backend — Gitea adapter and GitHub
  Releases adapter both implemented; `KUBEDROID_RELEASE_BACKEND` selects.
* **Integrity**: release body links `SHA256SUMS`; the README documents
  `sha256sum -c` + `apksigner verify --print-certs` with the pinned cert
  SHA-256 digest (`8aa62040e780...` from spec 09) so users can confirm the
  APK truly comes from this project's key.
* **Versioning**: git tag `v<versionName>` == `versionName`/`versionCode`
  bump in `build.gradle.kts` must match the tag; `publish.sh` refuses on
  mismatch.

## Configuration

* `KUBEDROID_RELEASE_UPLOAD_TOKEN` env var (or the existing GIT_ASKPASS
  pattern) for the release API; never stored in the repo or logs.
* Cert digest constant lives in `tools/release/publish.sh` + README,
  updated only on deliberate key rotation (spec 09).

## Testing

* `publish.sh --dry-run` against a scratch tag: builds, verifies signature,
  writes checksums, prints planned uploads without network writes — run as
  part of this spec's verification.
* Upload path exercised once for real with a `v0.1.0-rc1` prerelease, then
  deleted (or promoted) — leaving the hosting entry proven.
* Doc additions reviewed for accuracy against actual commands (no invented
  flags); a grep gate asserts no homelab hostnames (`*.redcomet.ca`,
  `*.neozeon.ca` outside branding, `172.16.*`, `devbox`, `GB10`) remain in
  `docs/0*`, `src/`, `tools/` after de-internalization (MAINTAINERS.md
  exempt).

## Acceptance criteria

1. A release exists at the chosen public location with signed APK,
   SHA256SUMS, and notes; download + checksum + apksigner verified from a
   clean machine outside the build host.
2. `git tag -l 'v*'` shows the published tag; rebuilding it reproduces a
   signature-matching APK (cert digest equal, not byte-identity).
2a. The guarded CI workflow is present and provably inert on Gitea (no
   Actions tasks created on push).
3. Root README/CONTRIBUTING/SECURITY present; hostname grep gate passes.
4. No token ever appears in argv, git objects, or logs (review publish.sh +
   shell history of the session).

## Decisions (human, 2026-10-28)

* **Q1/Q2 — Hosting & code home**: Gitea releases are canonical for now;
  the project will eventually move to the **public repository
  `github.com/caseyrobb/kubedroid`, which becomes the one and only source
  of truth once a release is deemed acceptable**. `publish.sh` therefore
  keeps its `upload()` seam with a Gitea adapter implemented now and a
  GitHub Releases adapter stubbed for the migration.
* **Q3 — Update channel**: no in-app update check for now; expected later
  (future spec candidate, not scheduled here).
* **Q4 — First public version**: `v0.1.0` from current HEAD. The public
  GitHub launch will be around `v1.0` (or `v0.99`) — i.e. after the
  homelab-proven prerelease line.
  *Amendment (human, 2026-11-02): migration executed early — GitHub
  becomes sole source now so spec 11 (F-Droid) can proceed; the first
  GitHub-published release is `v0.2.0` (versionCode 2).*
* **Q5 — F-Droid**: yes, later — now scoped as spec 11
  (`docs/11-fdroid-distribution.md`, 2026-10-28). Note: the earlier claim
  "no proprietary deps" was wrong — `com.google.mlkit:barcode-scanning` is
  a closed-source binary; it was removed with the QR import feature
  (spec 03 amendment stage 10, 2026-10-28), restoring FOSS-only status.
  No work in this spec.
* **Q6 — CI**: yes, hosted CI on GitHub where possible. A minimal
  test+assemble workflow is added at `.github/workflows/` NOW but guarded
  (`if: github.repository == 'caseyrobb/kubedroid'`) so it is inert on the
  Gitea canonical; it activates unchanged when the repo migrates. AGENTS.md
  no-CI is considered explicitly overridden by this decision.

## Dev plan

1. **Decisions folded + spec active** (this commit).
2. **Public repo files**: root `README.md`, `CONTRIBUTING.md`,
   `SECURITY.md`, `docs/MAINTAINERS.md`; de-internalization pass across
   `docs/0*`, `src/`, `tools/` (build-host names genericized, homelab
   hostnames removed); hostname grep gate added.
3. **publish.sh**: build/verify/checksum/tag/upload flow with `--dry-run`,
   Gitea upload adapter, GitHub adapter stub, cert-digest + version/tag
   consistency gates.
4. **Guarded GitHub CI workflow** (`.github/workflows/ci.yml`).
5. **Publish v0.1.0**: tag, run publish.sh for real against Gitea,
   end-to-end verification from a clean machine (download, `sha256sum -c`,
   `apksigner verify`), acceptance criteria review, docs sync, wiki
   milestone.
6. **GitHub migration** — DONE 2026-11-02 (human decision; early, per
   spec 11 unblock). `docs/MAINTAINERS.md` was removed from the repo and
   from full history (`git filter-branch --index-filter`, all 68 commits
   + tags rewritten); maintainer content moved off-repo. All in-tree
   references to it now point at the public specs. `upload_github()` is
   implemented (GitHub Releases API, token via 0600 curl config, asset
   read-back verification) and `KUBEDROID_RELEASE_BACKEND=github` is the
   publish path. The guarded CI activates via its repository `if`.
   `KUBEDROID_REPO_SLUG=caseyrobb/kubedroid` selects the GitHub target.
