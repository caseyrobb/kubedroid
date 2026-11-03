# fdroiddata submission (spec 11 stage 4)

`com.neozeon.kubedroid.yml` is the canonical copy of the file submitted to
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) under
`metadata/com.neozeon.kubedroid.yml`. Keep this copy in sync with every
release: the `Builds:` entry added for each versionCode must carry the
release tag as `commit:` and the same `versionCode`/`versionName` that
`publish.sh` gated.

Submission procedure (one-time, then per-release MR):

1. Fork https://gitlab.com/fdroid/fdroiddata on GitLab (a GitLab account
   is required; repo maintainer performs this).
2. On a branch, add `metadata/com.neozeon.kubedroid.yml` from this file.
3. Add `kubedroid.yml`-referenced assets if requested: the repo already
   ships the fastlane tree (`fastlane/metadata/android/en-US/`,
   `fastlane/images/`) which fdroidserver consumes automatically for
   descriptions and screenshots when `Uses New Sources Format` is unset
   and the app `Repo Type` is git.
4. Run the local checks the CI will run, from a fdroidserver checkout:
   `fdroid lint --verify com.neozeon.kubedroid` and
   `fdroid build -v -l com.neozeon.kubedroid` (builder container).
5. Open the MR, fill the new-app checklist, and note that builds are
   expected to be reproducible (see Maintainer Notes in the yml); the
   A/B container gate is `tools/fdroid/repro-docker.sh`.
6. After merge, follow up with packagers on
   [Matrix #fdroid-metadata](https://chat.f-droid.in) if the MR stalls.

Per-release updates: add a new `Builds:` entry (keep history), bump
`commit:` to the new tag, update `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
in this repo first (500-char gate is unit-tested), then MR the diff.

Device verification of the update path (spec 11 acceptance): install the
F-Droid-store build over the GitHub-release sideload on a device — must
update in place with no signature mismatch, proving cert identity
(verified digest: SHA-256 8aa62040e78074602176340b3bb8c46c7ceab5b8c3a9d485a5ae45dd0593acd2).
