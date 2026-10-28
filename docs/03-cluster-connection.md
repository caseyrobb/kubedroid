---
description: Cluster connection management — import kubeconfigs (file, paste — QR import removed by the 2026-10-28 amendment), manage multiple connections and contexts, store credentials in Keystore-encrypted storage, and build cached io.kubernetes client-java ApiClients per context.
status: active
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

* Import kubeconfig via SAF file picker or clipboard/paste.
  (QR import was removed by the 2026-10-28 amendment — Dev plan stage 10;
  the app must not request camera permission.)
* Manage N saved connections; rename, set active, delete (with wipe).
* Per-connection context selection; global active (connection, context).
* Encrypted at-rest storage of kubeconfig payloads (Android Keystore).
* `ApiClientProvider` building/caching one configured `ApiClient` per
  (connection, context), including TLS material.
* Connection status probe (GET `/version`) with clear error mapping.
* ~~Companion JVM tool `tools/qr-export`~~ — REMOVED by the 2026-10-28
  amendment together with QR import (Dev plan stage 10).

Out of scope (later specs / explicit non-goals):

* Any Kubernetes resource browsing (spec 04).
* Credential refresh flows: OAuth refresh tokens and exec plugins are NOT
  supported (see "Auth methods") — a clear error is surfaced instead.
* Sharing/exporting kubeconfigs OUT of the app in any form (the former
  QR export path is gone with the 2026-10-28 amendment).
* Biometric gate on opening the app (open question, below).

## Data model

`data/model/`:

* `ClusterConnection(id: UUID, displayName: String, addedAtEpochSec: Long,
  payloadSha256: String, contextName: String, contexts: List<String>)` —
  metadata only, no secrets; persisted in
  DataStore Preferences. `payloadSha256` is the hex SHA-256 of the raw
  kubeconfig bytes, used only for duplicate-import detection (human
  decision 2026-10-24: imports are independent except exact-identical
  payloads are rejected as duplicates). `contextName` is the row's
  currently selected context and `contexts` the full context-name list
  (non-secret) so the dropdown renders without decrypting the blob
  (stage 2 refinement).
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
| Client cert/key | `client-certificate(-data)` + `client-key(-data)` | PEM PKCS#8 accepted directly; PKCS#1 RSA/EC keys (k0s emits these) are auto-wrapped into PKCS#8 before use (amended 2026-10-26) |
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

1. Source UI produces a raw UTF-8 kubeconfig string (file bytes or pasted
   text).
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

The former chunked QR transfer format (`kubedroid:1:<i>/<n>:<b64>`,
`QrEnvelopeCodec`/`QrChunkCodec`, companion CLI `tools/qr-export`) was
removed wholesale by the 2026-10-28 amendment (Dev plan stage 10); see
that stage and the git history for the format details.

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
  cluster"; empty state with two import buttons (File / Paste).
* `AddClusterSheet`: three entry points →
  * File: `ActivityResultContracts.OpenDocument` launched with `*/*`
    (amended 2026-10-26: kubeconfig files often carry no recognizable MIME
    — `DocumentsUI` greys them out under a yaml/text filter list, making
    them impossible to select; the parser is the real validation gate);
  * Paste: multiline TextField + Validate button (inline typed errors);
  * ~~QR~~ removed by the 2026-10-28 amendment (Dev plan stage 10).
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
| `androidx.datastore:datastore-preferences` | 1.3.0 | cluster metadata registry |
| `androidx.compose.material:material-icons-core` | (BOM) | `Icons.Filled.Refresh/Warning` |
| `com.squareup.okhttp3:mockwebserver` | 5.4.0 | test only; must match the OkHttp 5.4.0 that client-java 27.0.0 resolves |

The former `tools/qr-export` companion project (plain JVM, shared codec
sources, zxing) was removed with the QR feature by the 2026-10-28
amendment.

client-java Android caveats (resolved during dev-plan stage 1, 2026-10-24):
`./gradlew build` is green with no core library desugaring (`ClientJavaSpikeTest`
constructs/configures `ApiClient` under unit tests). Two transitive groups are
excluded in `build.gradle.kts` because kubedroid never reaches them: `io.netty`
(Exec/PortForwarder only) and `software.amazon.awssdk` (EKS get-token — an
exec-style auth this spec declares a non-goal). `packaging.resources.excludes`
drops duplicate `META-INF` license/INDEX files from the remaining Google auth
transitives. ProGuard/R8: release builds add keep rules for Gson-serialized
models; debug builds unaffected.

Historical ML Kit/CameraX note (stage 5, REMOVED 2026-10-28): the QR
scanner once required `com.google.mlkit:barcode-scanning` and four
`androidx.camera:*` artifacts (with an `opt-in` lint option for CameraX
`@ExperimentalGetImage`); all were dropped by the amendment.
`AndroidManifest.xml` declares `INTERNET` and `ACCESS_LOCAL_NETWORK`
only — `CAMERA` and `<uses-feature camera>` were REMOVED by the
2026-10-28 amendment; the app never requests camera permission.

**Local network access (stage 9, field finding).** On Android 16+
(API 36), apps that *target* SDK 36+ are blocked at the kernel packet
level from opening TCP connections to private/LAN addresses unless
`android.permission.ACCESS_LOCAL_NETWORK` is **declared in the manifest
and granted at runtime** — SYNs are silently dropped, so OkHttp surfaces
a bare 15 s connect timeout while system apps and apps targeting older
SDKs (e.g. the browser) connect fine, and `adb shell` (uid 2000) is
exempt. Emulators do not enforce it, so acceptance never caught it; a
kubedroid cluster almost always lives on a LAN address, which makes this
permission existential, not optional. Behavior:

* `ClusterListScreen` requests the permission via
  `ActivityResultContracts.RequestPermission` as soon as the cluster
  list becomes non-empty and the permission is missing (fires right
  after import and on subsequent launches until granted). The request
  is skipped entirely below API 36 where the permission does not exist,
  so pre-36 devices and the API 26 emulator matrix are unaffected.
* If a probe fails while the permission is missing, the unreachable
  message appends a hint naming the permission so the failure is
  self-explanatory instead of a mystery timeout.

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

## Testing

Unit tests (JVM + Robolectric; fakes everywhere, no network):

* `KubeconfigParserTest`: fixture kubeconfigs per auth method (token, cert,
  basic, static oidc), exec rejection message, bad YAML, PKCS#1 key
  acceptance (auto-wrap), impersonation strip warning, current-context
  selection.
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
  rendering per ConnectionStatus, duplicate rejection, context switch,
  rename, file import via `file://` URI through `contentResolver`
  (SAF picker itself hands the same `Uri` on a real device; the picker
  UI is the only part not scriptable here).
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

End-to-end emulator acceptance is scripted: `tools/acceptance/acceptance.py
<api>` boots the headless emulator (`kv26`/`kv37`) and drives the app over
adb against a TLS API stub (`tools/acceptance/accept-server.py` running on
the host at `https://10.0.2.2:6443` with a throwaway CA generated at
`~/kubedroid-accept`). Steps asserted per run: paste-import of a
CA-pinned token kubeconfig ⇒ stub log shows `GET /version Auth=Bearer …`
(green-dot path incl. custom-CA TLS handshake and Keystore encrypt);
force-stop + relaunch ⇒ row restored (DataStore + Keystore decrypt);
Refresh ⇒ second stub hit (ApiClient rebuild); long-press delete ⇒ row
gone and `files/connections` empty; exec kubeconfig ⇒ inline
`uses an exec credential plugin` error with zero stub traffic and no
blob. The former QR export→scan loop tests in `tools/qr-export` were removed
with the QR feature (2026-10-28 amendment); no camera/emulator coverage
remains because no camera path remains.

## Acceptance criteria

* Importing each supported kubeconfig type (token, cert, basic, static
  oidc) from Paste produces a green status dot against a MockWebServer.
* Importing an exec-plugin kubeconfig shows the specific unsupported-auth
  error naming `exec:`; no crash; nothing persisted (blob dir empty).
* File import via SAF reaches the same validated end state as Paste.
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
* Local network access (stage 9): unit tests for the `LocalNetworkAccess`
  helper (below API 36 the permission is treated as always granted so the
  request path and hints are dormant; at/above 36 the granted state
  follows `checkSelfPermission`). Real-device acceptance: with the
  permission denied, LAN-cluster probes show the named-permission hint;
  once granted (system dialog or `adb shell pm grant`), pods list
  (verified on a Pixel 8a, Android 17, against the k0s LAN API server —
  before the fix every connect timed out while the browser from the same
  phone reached the same endpoint).

## Open questions

* ~~QR payload source~~ — moot: QR import removed 2026-10-28.
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
8. **Device-feedback fixes (2026-10-26, real-phone report)**: (a) file
   picker launches with `*/*` — the yaml/text MIME list greyed out real
   kubeconfig files in DocumentsUI (no recognizable MIME), making import
   impossible; the parser remains the validation gate. (b) PKCS#1 RSA/EC
   client keys are accepted and auto-wrapped into PKCS#8 at TLS setup
   (`TlsSupport` minimal DER wrapper; k0s emits PKCS#1, so every k0s
   kubeconfig was blocked by the old rejection). Tests: openssl PKCS#1
   RSA/EC fixtures asserted to produce the same keys as `openssl pkcs8`;
   parser accept/reject matrix updated.
9. **Local network permission (2026-10-27, real-phone report)**: declare
   `ACCESS_LOCAL_NETWORK` in the manifest and request it from
   `ClusterListScreen` once clusters exist (Android 16+ kernel-level drop
   of LAN SYNs for apps targeting SDK 36+ when ungranted); probe
   unreachable text names the permission when it is missing. Unit tests
   for the helper + device verification per the Testing section.
10. **QR feature removal (amendment, 2026-10-28, human decision).** The
    app must not request camera permission, so the entire QR import path
    goes: delete `ui/cluster/QrScannerDialog.kt`, `data/qr/QrChunkCodec.kt`
    + `QrEnvelopeCodec.kt` and their tests, the `tools/qr-export` companion
    project, the QR entry points in `ClusterListScreen` (empty state and
    add sheet become File/Paste only), `CAMERA` permission +
    `<uses-feature camera>` from the manifest, and the `mlkit`, CameraX
    and zxing dependencies from the catalog. Supersedes dev-plan stages 5
    and 7. Rationale: least-privilege posture and F-Droid FOSS policy
    (ML Kit barcode-scanning is a closed-source binary; see spec 11).
    Tests: QR test classes deleted with the feature; `ClusterListScreenTest`
    updated (no QR buttons asserted; new assertion that no "QR" node
    exists); full unit suite + lint + assemble green; acceptance driver
    untouched (it never used QR).
