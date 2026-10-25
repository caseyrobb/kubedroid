---
description: Cluster connection management — import kubeconfigs (file, paste, QR), manage multiple connections and contexts, store credentials in Keystore-encrypted storage, and build cached io.kubernetes client-java ApiClients per context.
status: proposed
---

# Cluster Connection Management

Implements stage 2 of the implementation order in
`01-project-architecture.md`. After this spec is `active`, the Home
placeholder from spec 02 is replaced by a working cluster list, and feature
specs (resource browsing, logs) receive a ready-to-use `ApiClient` from
`ApiClientProvider`.

Client library: `io.kubernetes:client-java` — human decision recorded in
`02-project-skeleton.md` Open questions (2026-10-23). No Fabric8.

## Scope

In scope:

* Import kubeconfig via SAF file picker, clipboard/paste, or QR code.
* Manage N saved connections; rename, set active, delete (with wipe).
* Per-connection context selection; global active (connection, context).
* Encrypted at-rest storage of kubeconfig payloads (Android Keystore).
* `ApiClientProvider` building/caching one configured `ApiClient` per
  (connection, context), including TLS material.
* Connection status probe (GET `/version`) with clear error mapping.
* Companion JVM tool `tools/qr-export`: `kubedroid-qr <kubeconfig>` prints
  chunked QR codes for import (human decision 2026-10-24: ships in this
  repo). It must strip `exec:`/`auth-provider` blocks before export.

Out of scope (later specs / explicit non-goals):

* Any Kubernetes resource browsing (spec 04).
* Credential refresh flows: OAuth refresh tokens and exec plugins are NOT
  supported (see "Auth methods") — a clear error is surfaced instead.
* Sharing/exporting kubeconfigs OUT of the app (QR export ships with the
  companion tooling spec, not here; this spec only IMPLEMENTS import).
* Biometric gate on opening the app (open question, below).

## Data model

`data/model/`:

* `ClusterConnection(id: UUID, displayName: String, addedAtEpochSec: Long,
  payloadSha256: String)` — metadata only, no secrets; persisted in
  DataStore Preferences. `payloadSha256` is the hex SHA-256 of the raw
  kubeconfig bytes, used only for duplicate-import detection (human
  decision 2026-10-24: imports are independent except exact-identical
  payloads are rejected as duplicates).
* `ConnectionStatus` — `Unvalidated | Reachable(serverVersion) | Unreachable(reason)`.
* `ActiveSelection(connectionId: UUID?, contextName: String?)` — persisted;
  null means no cluster selected (Home empty state).

`displayName` defaults to the kubeconfig `current-context` or first context
name; user can rename. IDs are generated at import; never derived from
content (deleting and re-importing yields a fresh id).

## Auth methods

Supported at import (validated by the parser):

| Method | Kubeconfig fields | Behavior |
| --- | --- | --- |
| Bearer token | `users[].user.token` | passed through |
| Client cert/key | `client-certificate(-data)` + `client-key(-data)` | PEM PKCS#8 required; PKCS#1 keys rejected with guidance |
| Basic auth | `username` + `password` | passed through (kept despite server-side deprecation to support future OpenShift clusters — human decision 2026-10-24) |
| Static OIDC id-token | `auth-provider.name: oidc`, `config.id-token` | used as bearer until expiry; NO refresh |

Rejected at import with a per-reason user-facing error (error type
`KubeconfigUnsupported`): `exec:`, `auth-provider` other than oidc,
`user-id`/`group` impersonation fields (warn, strip), malformed YAML.
Detection is done by our own parser layer (see below), not by client-java
exceptions, so messages stay actionable ("This kubeconfig uses an exec
credential plugin (e.g. kubelogin), which kubedroid cannot run on Android —
re-issue it with a static token or client certificate").

## Import pipeline

1. Source UI produces a raw UTF-8 kubeconfig string (file bytes, pasted
   text, or reassembled QR payload).
2. `KubeconfigParser.parse(raw)` validates structure, extracts
   clusters/users/contexts, classifies each context's auth method, and
   returns `ParsedKubeconfig` or a typed error. The raw bytes are never
   logged; `toString()` of all parse results is redaction-safe.
3. User confirms display name (prefilled) and picks initial context.
4. `ClusterRepository.add()` first checks `payloadSha256` against existing
   connections; on match it returns `AddResult.Duplicate(existingId)` and
   persists nothing (UI shows "Already imported"). Otherwise it persists
   metadata + encrypted payload, then marks the connection `Unvalidated`
   and sets it active.
5. Home triggers `probe()` (below).

QR format (chunked, because kubeconfigs exceed QR capacity):
`kubedroid:1:<index>/<total>:<base64(json-chunk-bytes)>`, one QR per chunk,
scanned in sequence; the scanner UI shows progress `i/n` and requires the
chunks in order; reassembly validates the JSON and total count before
handing the string to the pipeline. Producer side (export) is a companion
CLI responsibility, not this app.

## Secure storage

`data/local/`:

* `CipherVault` interface: `encrypt(plaintext, aad)`, `decrypt(cipher, aad)`.
  AAD is `connectionId.toString()` so blobs cannot be swapped between rows.
* `KeystoreCipherVault`: AndroidKeyStore AES-256-GCM key
  (`kubedroid-master`, non-exportable, `setRandomizedEncryptionRequired`),
  12-byte IV prefix per blob. Deliberately avoids the deprecated
  `androidx.security:security-crypto` EncryptedSharedPreferences (frozen
  upstream).
* Ciphertext blobs: `filesDir/connections/<id>.enc` (atomic write via
  temp+rename). Plaintext kubeconfig NEVER touches disk.
* Metadata (no secrets): DataStore Preferences `cluster_registry`
  (JSON list of `ClusterConnection`).
* `ClusterRepository.delete(id)` removes the DataStore entry first, then the
  blob file, then evicts cached ApiClients.
* All view models and repositories log connection ids and display names
  only.

## ApiClientProvider

`data/kubernetes/`:

* `@Singleton class ApiClientProvider @Inject constructor(repo)`;
  `suspend fun clientFor(connectionId, context): ApiClient`.
* Builds via `io.kubernetes.client.openapi.ApiClient` from the decrypted
  kubeconfig YAML for the selected context, honoring: server URL, CA data,
  `insecure-skip-tls-verify` (accepted, surfaced as a persistent warning
  badge in the UI), token, client cert/key, basic auth.
* OkHttp timeouts: connect 15 s, read 30 s (see Configuration).
* In-memory LRU cache keyed `(connectionId, contextName)`, max 4 entries;
  eviction drops the TLS contexts. Cache is never persisted.
* Decrypted kubeconfig bytes are zeroized after `ApiClient` construction.

## Connection probe

`ClusterRepository.probe(connectionId)` → `GET <server>/version` via the
provider's client. Result stored in memory as `ConnectionStatus`; Home list
shows a status dot per row (gray/yellow/red/green). Failure text is the
mapped user-friendly message (network, TLS, 401, 403, DNS). No auto-retry
loop; explicit pull-to-refresh on the list re-probes the active connection.

## UI

`ui/cluster/` (replaces the spec 02 Home placeholder body; `HomeKey` stays):

* `ClusterListScreen`: Scaffold + TopAppBar "kubedroid"; connection rows
  (display name, context dropdown, status dot, warning badge), FAB "Add
  cluster"; empty state with three import buttons (File / Paste / QR).
* `AddClusterSheet`: three entry points →
  * File: `ActivityResultContracts.OpenDocument` (mime
    `application/x-yaml`, `text/yaml`, `text/plain`);
  * Paste: multiline TextField + Validate button (inline typed errors);
  * QR: `QrScannerScreen` (CameraX preview + ML Kit analyzer) with chunk
    progress UI.
* Delete: long-press row → confirm dialog → wipe (no undo).
* All screens behind `ClusterListViewModel` (StateFlow + one-shot events),
  `hiltViewModel()` per entry decorator from spec 02 — this is the first
  ViewModel, so spec 02's open question about `hiltViewModel()` under Nav3
  is exercised here; assisted-injection fallback applies if needed.

## Additional dependencies

| Artifact | Version | Notes |
| --- | --- | --- |
| `io.kubernetes:client-java` | 27.0.0 | core client; brings OkHttp/Gson/SnakeYAML transitives |
| `org.yaml:snakeyaml` | 2.5 | used directly by KubeconfigParser (own validation layer) |
| `com.google.mlkit:barcode-scanning` | 17.3.0 | bundled model (works offline, no Play dependency) |
| `androidx.camera:camera-core` | 1.6.2 | QR scanner |
| `androidx.camera:camera-camera2` | 1.6.2 | |
| `androidx.camera:camera-lifecycle` | 1.6.2 | |
| `androidx.camera:camera-view` | 1.6.2 | |
| `androidx.camera:camera-compose` | 1.6.2 | preview in Compose |
| `androidx.datastore:datastore-preferences` | 1.3.0 | cluster metadata registry |

client-java Android caveats (resolved during dev-plan stage 1, 2026-10-24):
`./gradlew build` is green with no core library desugaring (`ClientJavaSpikeTest`
constructs/configures `ApiClient` under unit tests). Two transitive groups are
excluded in `build.gradle.kts` because kubedroid never reaches them: `io.netty`
(Exec/PortForwarder only) and `software.amazon.awssdk` (EKS get-token — an
exec-style auth this spec declares a non-goal). `packaging.resources.excludes`
drops duplicate `META-INF` license/INDEX files from the remaining Google auth
transitives. ProGuard/R8: release builds add keep rules for Gson-serialized
models; debug builds unaffected.

## Configuration

No user-facing settings in this spec. Build/runtime constants (pinned here
so later specs don't drift):

| Constant | Value | Location |
| --- | --- | --- |
| OkHttp connect timeout | 15 s | ApiClientProvider |
| OkHttp read timeout | 30 s | ApiClientProvider |
| ApiClient LRU size | 4 | ApiClientProvider |
| Keystore key alias | `kubedroid-master` | KeystoreCipherVault |
| GCM IV length | 12 bytes | KeystoreCipherVault |
| Blob dir | `filesDir/connections` | ClusterRepository |
| QR chunk prefix | `kubedroid:1:` | QrChunkCodec |

## Testing

Unit tests (JVM + Robolectric; fakes everywhere, no network):

* `KubeconfigParserTest`: fixture kubeconfigs per auth method (token, cert,
  basic, static oidc), exec rejection message, bad YAML, PKCS#1 key
  rejection, impersonation strip warning, current-context selection.
* `QrChunkCodecTest`: chunk/reassemble round trip, out-of-order chunk error,
  corrupt base64, total-count mismatch, single-chunk config.
* `FakeCipherVaultTest` + `CipherVaultContractTest`: AAD binding (decrypt
  with wrong id fails), IV uniqueness across encrypts, empty/large payload.
* `ClusterRepositoryTest`: add/rename/delete order (DataStore entry removed
  before blob), re-import same content gets new id, identical payload
  rejected as `AddResult.Duplicate` (persists nothing), active selection
  persistence, no plaintext on disk (filesDir scan after add).
* `ApiClientProviderTest`: cache hit/evict by LRU, different context ⇒
  different client, insecure-TLS flag honored, timeouts set (via reflection
  on OkHttp client).
* `ClusterListViewModelTest` (coroutines-test): empty state, add-paste
  happy path, validation error display, delete confirm/cancel, status
  rendering per ConnectionStatus.
* Robolectric Compose: empty-state buttons visible; paste flow end-to-end
  with fake repo; delete confirmation dialog.

WireMock (or OkHttp `MockWebServer`, preferred — already on the classpath
via OkHttp): `probe()` against fake `/version` for green/red/unreachable
paths and 401 mapping.

`KeystoreCipherVault` itself is verified by a manual/instrumented checklist
(Keystore is unavailable under Robolectric): create key, round trip, key
non-exportability assertion. This exception is acceptable because the
class is ~60 lines of platform glue and its logic contract is tested
against the fake.

## Acceptance criteria

* Importing each supported kubeconfig type (token, cert, basic, static
  oidc) from Paste produces a green status dot against a MockWebServer.
* Importing an exec-plugin kubeconfig shows the specific unsupported-auth
  error naming `exec:`; no crash; nothing persisted (blob dir empty).
* File import via SAF and QR import (scan pre-generated chunks) both reach
  the same validated end state as Paste.
* Killing and relaunching the app restores connections, active selection,
  and produces a working `ApiClient` (probe green again).
* Deleting a connection removes both DataStore entry and blob file, and
  evicts cached clients (next `clientFor` on that id fails with a typed
  error, no stale reuse).
* Context switch changes the server URL actually contacted (observable in
  MockWebServer request log).
* `logcat` inspection during import/probe shows no token/cert material
  (verified with a probe-based log sink assertion in a ViewModel test).
* `./gradlew build` green with all the above tests.

## Open questions

* **QR payload source**: the companion `kubedroid qr <kubeconfig>` CLI
  (which also must strip exec/auth-provider blocks before export) — build
  it as part of this spec's repo, as a separate small JVM tool, or rely on
  an external script for now? Build it as part of the spec's repo.
* **Basic auth**: keep supporting at import, or reject like exec (servers
  deprecated it)? Keep support at import as OpenShift clusters will be supported in the future.
* **Multiple contexts pointing at same cluster**: any de-dup expectation,
  or just treat every import as independent? Independent except for the case where and imported kubeconfig yaml is identical to an existing imported cluster.
* **Biometric gate**: defer entirely, or add a spec 03.x toggle now that
  the Keystore groundwork exists? Defer entirely.

## Dev plan

1. **Parser + model core (no UI, no client-java yet)**: `KubeconfigParser`
   + typed errors + fixtures; `QrChunkCodec`; unit tests. Also: spike that
   adds `io.kubernetes:client-java:27.0.0` and confirms `./gradlew build`
   stays green on Android (desugaring decision recorded here).
2. **Storage**: `CipherVault` interface + Keystore impl + fake, blob
   persistence, DataStore registry, `ClusterRepository` + tests.
3. **ApiClientProvider + probe**: client build/cache, MockWebServer probe
   tests, status plumbing into the repository.
4. **UI**: cluster list, add flows (File/Paste), rename/delete/activate,
   context switcher, ViewModel + Compose tests. Home placeholder replaced.
5. **QR**: camera permission flow, ML Kit analyzer, chunk UI, tests;
   document the QR format for the companion tool.
6. **Hardening + flip**: log-redness audit, acceptance checklist run on
   API 26 + API 37 emulators, docs sync, flip this spec to `active`.
7. **Companion QR export CLI** (`tools/qr-export`, plain JVM — can proceed
   in parallel with stages 4–6): reads a kubeconfig, strips
   `exec:`/`auth-provider` blocks, emits chunks via the same
   `kubedroid:1:<i>/<n>:<b64>` codec and renders QR codes (terminal or
   PNG); unit tests for strip + chunk round trip against
   `QrChunkCodecTest` fixtures.
