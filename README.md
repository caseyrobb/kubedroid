# kubedroid

A native Android client for Kubernetes clusters. Browse workloads, read
pods logs (with follow streaming), edit and delete resources safely, and
discover custom resources — all from your phone, with credentials kept
on-device in the Android Keystore.

> Status: early (v0.1.x). Homelab-proven, hardening toward v1.0. The
> project is developed spec-first: every feature ships with a written
> specification in [`docs/`](docs/).

## Features

- **Cluster connections** — import kubeconfig via file picker, paste, or
  client-certs, tokens, and basic auth; per-cluster
  TLS trust pinned to the kubeconfig CA. (spec 03)
- **Resource browsing** — official Kubernetes icons for 30+ kinds,
  namespace filtering, label/name filters, summaries, YAML detail view.
  (specs 04, 08)
- **Pod logs** — tail, follow (streaming), container/init-container
  selector, timestamps toggle, client-side filter, save via SAF. (spec 05)
- **Safe editing & deletion** — YAML editor with optimistic-concurrency
  (resourceVersion) guards, conflict/invalid errors verbatim from the API
  server, explicit delete confirmations. (spec 06)
- **Custom resources** — CRD discovery with storage-version resolution
  and dynamic catalog entries. (spec 07)
- **Material You** — expressive Material 3 theme with a Kubernetes-blue
  fallback on ROMs without dynamic color. (spec 08)

## Requirements

- Android 8.0 (API 26) or newer.
- A reachable Kubernetes API server (in-cluster VPN/LAN access is your
  responsibility; on Android 16+ kubedroid requests the local-network
  permission for LAN endpoints).

## Install

1. Download `kubedroid-release.apk` and `SHA256SUMS` from the
   [releases page](/releases).
2. Verify integrity:

   ```sh
   sha256sum -c SHA256SUMS
   ```

3. (Optional) Verify the signing certificate with the Android SDK:

   ```sh
   apksigner verify --print-certs kubedroid-release.apk
   ```

   The certificate SHA-256 digest must be:

   ```
   8aa62040e78074602176340b3bb8c46c7ceab5b8c3a9d485a5ae45dd0593acd2
   ```

4. Install the APK (`adb install` or sideload).

## Build from source

```sh
./gradlew assembleDebug          # debug APK (debug signing key)
./gradlew test lintDebug         # unit tests + lint
```

Release builds sign themselves automatically when a `keystore.properties`
is present in the project root (see `docs/09-release-signing.md`); without
one they stay unsigned. Release artifacts are produced by the maintainer
flow on the maintainer build host (`docs/09-release-signing.md` +
`docs/10-release-hosting-and-public-repo.md`).

## Privacy & security

- Kubeconfig material is encrypted at rest with an Android Keystore
  AES-256-GCM key; tokens/certs are never written to logs or crash
  reports. No analytics, no phones-home except to the API servers you
  configure. (spec 03)
- Read-only by default; edits and deletes always require explicit
  confirmation, and the app never performs `exec` or port-forwarding.
- See [SECURITY.md](SECURITY.md) for reporting vulnerabilities.

## Documentation

Design lives in [`docs/`](docs/) as numbered specs (00–10); the
spec-driven workflow is described in [`docs/README.md`](docs/README.md).
Contributions should follow [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT (see [LICENSE](LICENSE)). Bundled Kubernetes icon artwork is
dual-licensed Apache-2.0/CC-BY-4.0 with Linux Foundation trademark terms
— see [NOTICE](NOTICE).
