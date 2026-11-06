# fdroiddata submission (spec 11 stage 4)

> **OUT OF SCOPE (2026-11-05, Casey):** the F-Droid release track is
> frozen. MR !55918 stays as submitted (CI green, mergeable) — do not
> push to it and do not open anything else on gitlab.com/fdroid/fdroiddata.
> This runbook and the canonical yml are kept for reference only, not
> maintained per-release. Releases continue via GitHub only.

`com.neozeon.kubedroid.yml` is the canonical copy of the file submitted to
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) under
`metadata/com.neozeon.kubedroid.yml`. It is kept in `fdroid rewritemeta`
normalised form (the fdroiddata CI `fdroid rewritemeta test` job fails on
non-normalised files). `commit:` values must be FULL commit hashes — tags
and branches are not allowed (Build Metadata Reference). Only the latest
version needs a `Builds:` entry before the MR merges; fdroiddata keeps
history after merge.

Submission procedure (one-time, then per-release MR):

1. Fork https://gitlab.com/fdroid/fdroiddata on GitLab (a GitLab account
   is required; repo maintainer performs this). Note: on the fork API,
   omit the `namespace` param to fork into the authenticated user.
2. On a branch named after the app id, add
   `metadata/com.neozeon.kubedroid.yml` from this file. Never commit to
   the fork's `master`.
3. Add `kubedroid.yml`-referenced assets if requested: the repo already
   ships the fastlane tree (`fastlane/metadata/android/en-US/`,
   `fastlane/images/`) which fdroidserver consumes automatically for
   descriptions and screenshots when `Uses New Sources Format` is unset
   and the app `RepoType` is git.
4. Run the local checks the CI will run, from a fdroidserver checkout:
   `fdroid lint --verify com.neozeon.kubedroid` and
   `fdroid build -v -l com.neozeon.kubedroid` (builder container).
5. Open the MR titled `New app: kubedroid`, fill the new-app checklist
   template, and note that builds are expected to be reproducible
   (`Binaries:` + `AllowedAPKSigningKeys:` pin the published cert); the
   A/B container gate is `tools/fdroid/repro-docker.sh`.
6. After merge, follow up with packagers on
   [Matrix #fdroid-metadata](https://chat.f-droid.in) if the MR stalls.

Per-release updates: `AutoUpdateMode: Version` + `UpdateCheckMode: Tags`
let fdroid's checkupdates add new `Builds:` entries automatically when a
new `v<versionName>` tag appears (the tag is used directly as `commit:`).
Manual metadata MRs are then only needed when the recipe changes. Before
tagging a release, update
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` in this
repo first (500-char gate is unit-tested) and bump `versionCode`
(publish.sh gates it against the previous tag).

Device verification of the update path (spec 11 acceptance): install the
F-Droid-store build over the GitHub-release sideload on a device — must
update in place with no signature mismatch, proving cert identity
(verified digest: SHA-256 8aa62040e78074602176340b3bb8c46c7ceab5b8c3a9d485a5ae45dd0593acd2).

Maintainer notes (moved out of the yml; fdroid lint rejects unknown keys):

- Release APKs are byte-reproducible. The release signing key is RSA-3072
  with SHA256withRSA (PKCS#1 v1.5 deterministic signatures) and AGP
  dependenciesInfo is disabled in build.gradle.kts because its
  Play-encrypted version-stamped SDKP block is the only nondeterministic
  part of the APK Signing Block. Two clean container builds of the tagged
  commit hash-identically (see tools/fdroid/repro-docker.sh A/B gate), and
  the published APK certificate digest matches rebuilds.
- Reproduction parity notes (verified against the
  registry.gitlab.com/fdroid/fdroidserver buildserver-trixie image). Build
  from a git clone of the Repo URL (AGP embeds
  META-INF/version-control-info.textproto from the git dir) and keep the
  project directory named kubedroid. The Gradle root project name flows
  into Kotlin module metadata and hence the dex layout.
- The release build signs only when `keystore.properties` (or
  `KUBEDROID_KEYSTORE_PROPERTIES`) exists; without it `:assembleRelease`
  yields the unsigned APK that F-Droid's builder signs, and `Binaries:`
  verification against the published signed APK uses that.
- The app requests no camera permission and ships no non-FOSS
  dependencies (F-Droid new-app requirement, enforced in-repo by the FOSS
  denylist gate in tools/fdroid and publish.sh).
- Update flow. versionCode is bumped manually per release and gated in
  publish.sh against the previous tag. There is no in-app updater.
