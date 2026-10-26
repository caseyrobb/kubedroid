---
description: Defines kubedroid's layered unidirectional-data-flow architecture as built by specs 02-06 — package layout, key components, adaptive strategy, security posture, testing stack, and the remaining deferred areas.
status: active
---

# **Project Architecture**

This doc describes the architecture **as built** by specs 02–06 (all active).
It supersedes the pre-build proposal; where the proposal anticipated shapes
that were built differently (Fabric8, EncryptedSharedPreferences, watch),
the built shape below is authoritative and the deltas are
noted. Deferred areas are listed under **Deferred / not yet built**.

## **Architectural Style**

Layered architecture with **Unidirectional Data Flow** (state flows down,
events flow up).

* **UI Layer**: Jetpack Compose screens + Hilt ViewModels holding immutable
  `UiState` data classes exposed as `StateFlow`.
* **Data Layer**: Repositories (`ClusterRepository`, `ResourceRepository`,
  `LogRepository`) own data access and error mapping; gateways
  (`ResourceGateway`, `LogGateway`) own the HTTP shape;
  `ApiClientProvider` owns per-cluster client construction/caching.
* **No Domain layer** — none has been needed so far (the "optional thin
  Domain layer" clause remains untriggered).

All UI state is StateFlow of immutable objects. Navigation is event-like
user gestures producing typed `NavKey` pushes; there are no one-shot event
channels yet (snackbars are driven from state fields, e.g. `saveMessage`).

## **Package Structure (as built)**

```
com.neozeon.kubedroid
├── MainActivity.kt / KubeApplication.kt
├── di/AppModule.kt                    // Hilt: ApplicationScope, singletons, RetryBackoff
├── navigation/                        // Navigation 3: NavKey sealed types, KubedroidApp NavDisplay
├── ui/
│   ├── theme/                         // Color/Type/Theme (Material 3)
│   ├── adaptive/WindowSizeClassProvider.kt
│   ├── cluster/                       // ClusterListScreen/ViewModel, QrScannerDialog
│   ├── resources/                     // Browse, ResourceList, ResourceDetail screens+VMs,
│   │                                  //   ResourceScene (adaptive two-pane), SummaryRows
│   └── logs/                          // LogScreen/LogViewModel + LogNavPayloads (detail→viewer hand-off)
├── data/
│   ├── model/                         // ClusterConnection; ResourceRef/ResourceRow/ResourceError (sealed,
│   │                                  //   userMessage()), ResourceResult
│   ├── kubernetes/                    // KubeconfigParser/Models, ApiClientProvider, TlsSupport,
│   │                                  //   ResourceCatalog, ResourceGateway, LogGateway
│   ├── repository/                    // ResourceRepository, LogRepository
│   ├── local/                         // ClusterRepository, BlobStore, CipherVault (+Keystore impl), PayloadHash
│   ├── registry/                      // ClusterRegistry interface + DataStoreClusterRegistry
│   └── qr/                            // QrEnvelopeCodec / QrChunkCodec (chunked QR kubeconfig transfer)
└── util/YamlRenderer.kt               // deterministic YAML for the detail view + edit drafts
```

Outside the app module: `tools/qr-export/` (standalone plain-JVM Gradle
project sharing app sources for the QR CLI) and `tools/acceptance/`
(emulator E2E driver + TLS stub, see spec 03/04/05 Testing).

## **Key Components**

### **Data Layer**

* **ApiClientProvider** (spec 03) — builds and caches (LRU) one
  `io.kubernetes:client-java` `ApiClient` per (connection, context): pinned
  CA from the stored kubeconfig via `TlsSupport`, bearer/cert auth, and the
  `Authorization` interceptor. The proposal's "Fabric8 or client-java" fork
  resolved to **client-java 27.0.0** (builds without core-library
  desugaring; `io.netty` and `software.amazon.awssdk` transitive groups
  excluded). Token refresh: not needed for the supported auth methods.
* **ResourceGateway / LogGateway** (specs 04/05) — plain OkHttp `GET`s
  derived from the resolved ApiClient (shared `httpClient` + `basePath`),
  so every request rides the authenticated, CA-pinned path. Reads are
  unstructured `Map<String, Any?>` (no Gson codegen). `ResourceCatalog`
  pins the browsable type set and builds paths
  (`/apis?/api` + group/version/resource, namespacing, percent-encoded
  segments, `logPath`, item `itemPath`). LogGateway streams line Flow + raw
  save stream. Mutations (`replace`/`delete`) go through single-shot
  `executeOnce` — never auto-retried (spec 06); `EditSanitizer` strips
  display-only fields, re-attaches the loaded `resourceVersion`, and guards
  the identity fields on the PUT body.
* **ResourceRepository / LogRepository** — resolve the connection context,
  map HTTP/IO failures to the sealed `ResourceError` (with `userMessage()`
  copy; 403 distinguishes `pods` vs `pods/log` RBAC), enforce paging
  (`continue` tokens) and the fixed 1,000-line tail / 10,000-line follow
  buffer contract. No watch, no selectors yet (deferred).
* **ClusterRepository + ClusterRegistry + BlobStore + CipherVault** —
  kubeconfig text is content-addressed in `BlobStore` (app files dir),
  secrets (token/client-key) sealed with Keystore AES-GCM
  (`KeystoreCipherVault`); registry metadata in DataStore. The proposal's
  EncryptedSharedPreferences was replaced by this split. Delete removes
  blob + sealed payload + registry row atomically.
* **KubeconfigParser** — strict validation gate on import (exec-plugin
  configs rejected with specific copy — exec is an explicit Android
  non-goal, spec 03).

### **UI Layer**

* **ViewModels** (Hilt, one per screen area): `ClusterListViewModel`,
  `BrowseViewModel`, `ResourceListViewModel` (generation-guarded paging),
  `ResourceDetailViewModel` (summary/YAML + Secret reveal + spec 06
  edit/save/conflict/delete state),
  `LogViewModel` (stream lifecycle, ring buffer, save state machine).
* **Navigation 3**: `NavDisplay` + user-owned `NavBackStack` of sealed
  `NavKey`s (`ClusterListKey`, `BrowseKey`, `ResourceListKey`,
  `ResourceDetailKey`, `LogKey`) with `SavedState`-serializable args —
  process death restores the stack.
* **Adaptive strategy**: `WindowSizeClassProvider` observes
  `currentWindowAdaptiveInfo()`; compact = single-pane push navigation;
  medium/expanded = `ResourceScene` list-detail two-pane with shared
  list/detail ViewModels hoisted outside the provider lambdas.

### **Data Flow Example (Resource List → Detail → Logs)**

1. Grid (`BrowseScreen`) tap on Pods → `ResourceListKey`.
2. `ResourceListViewModel` → `ResourceRepository.list` → paged
   `ResourceResult` → `ResourceListUiState` (rows from `ResourceCatalog`
   column config).
3. Row tap → inline detail (medium+) or `ResourceDetailKey` push;
   `ResourceDetailViewModel` loads the unstructured object; Summary facts +
   `YamlRenderer` output (copy action; spec 06 adds the YAML **edit mode**:
   draft validation, PUT replace with the loaded `resourceVersion`,
   conflict dialog, and delete with shared confirmation + list
   invalidation).
4. Pod detail top-bar `Logs` → `LogNavPayloads.put(containerNames)` →
   `LogKey` → `LogScreen`/`LogViewModel` stream via `LogRepository` →
   `LogGateway`.

## **Authentication & Security**

* Kubeconfigs in a content-addressed blob store; token/client-key sealed
  with Android Keystore (AES-GCM). Never logged, never in URLs, never in
  Logcat (log content included).
* TLS is CA-pinned from the imported kubeconfig (`TlsSupport`); no system
  trust fallback for imported clusters.
* Supported auth: bearer token and client certificate. Basic auth is
  accepted if present; **exec plugins are rejected** (Android non-goal).
* Removing a cluster clears all its local state; there is no other session
  state to clear.

## **Error Handling**

* Sealed `ResourceError` (`Forbidden`, `ForbiddenLogs`, `NotFound`,
  `Unauthorized`, `Server`, `Network`, `Malformed`, `ContainerNotFound`)
  with user-facing `userMessage()`; RBAC copy names the exact
  resource/verb, including the separate `pods/log` grant.
* Pre-first-byte retries only: idempotent reads retried ×2 (500 ms / 2 s)
  via injected `RetryBackoff`; **no silent reconnect after a log stream has
  emitted** — manual Retry instead (spec 05 decision).
* Parse/import failures map to specific `ParseResult.Invalid` copy (e.g.
  exec-plugin message), never a generic error.

## **Configuration**

Build: AGP 9 (built-in Kotlin — do not apply the Kotlin plugin), compileSdk
37 (`platforms;android-37.0`), minSdk 26, JDK 21; deps pinned in
`libs.versions.toml` (client-java 27.0.0, MockWebServer 5.4.0,
Robolectric 4.17 with `--add-opens`/`--add-exports`, Compose BOM,
Navigation 3, Hilt, DataStore, CameraX for QR import; zxing 3.5.4 is
`tools/qr-export` only — never in the APK). Material icons are the
**core set only** — extended icons do not resolve; use text buttons.
Canonical build/test machine is devbox (`~/devbox-build.sh`); GB10 holds a
git/file mirror only.

## **Testing**

* JVM/Robolectric unit tests per layer: parser, provider/gateways
  (MockWebServer, throttled bodies for streaming), repositories (fake
  gateways/registries), ViewModels (fake repositories, manually-drained
  channel streams), Compose UI tests (Robolectric; testTags per spec).
* Emulator E2E: `tools/acceptance/acceptance.py` + `accept-server.py`
  driven via `run-chain.sh` on API 37 and API 26; every spec adds named
  steps (currently 35/35).
* Most logic is pure/immutable so it runs on the JVM without instrumented
  tests; the app has no `androidTest` sources.

## **Acceptance criteria**

* This document matches the shipped tree (`src/main/java`), the pinned
  dependency set, and the specs' locked decisions; anything it marks
  deferred is absent from the code.

## **Deferred / not yet built** (proposal items, not dropped by decision)

* **CRD discovery / CR listing** (`ui/crd`, `CrdRepository`) — in progress:
  spec 07 (`docs/07-crd-discovery.md`, proposed 2026-10-26). Until it
  activates, spec 04's static `ResourceCatalog` remains the only source.
* **Watch** — lists are pull/paging only.
* **Label/field selectors**, richer list columns.
* Shared `ui/components/` extraction — screens currently inline their
  primitives.

## **Dev plan**

1. **Sync pass (this change, 2026-10-26)**: reconcile with the built tree
   and specs 02–05 decisions — package structure, client-java choice,
   Keystore/blob storage split, Navigation 3 keys, error model, core-only
   icons, testing stack; add Configuration/Acceptance/Deferred sections.
   *Complete.*
2. **Activation**: flip `status: proposed → active` after review — approved
   by Casey 2026-10-26. *Complete.*

## **Suggested Implementation Order (original)**

Kept for history; items 1–4 shipped as specs 02–05, items 5–7 remain open
(see Deferred).

1. Project skeleton + Hilt + Navigation 3 adaptive shell + theme. (spec 02)
2. Cluster connection management. (spec 03)
3. Generic resource list + detail with YAML viewer. (spec 04)
4. Pod logs. (spec 05)
5. Edit / delete actions. (spec 06)
6. CRD discovery and CR instance listing. (spec 07, proposed)
7. Polish adaptive layouts and error states. (partly done in 04/05)
