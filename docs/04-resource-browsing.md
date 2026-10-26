---
description: Read-only browsing of core Kubernetes resources from the active cluster — hardcoded type catalog, generic list/search with namespace scope, resource detail with Summary and YAML tabs, redacted Secrets, adaptive list-detail layout.
status: proposed
---

# Spec 04 — Resource browsing and detail

Implements user flow 2 from `00-project-overview.md` ("select a resource type →
filtered list → select an item → detail pane") and is stage 3 of the
implementation order suggested in `01-project-architecture.md` ("generic
resource list + detail with YAML viewer, start with Pods"). Everything in this
spec is **read-only**; mutation (edit/delete) and pod logs land in later specs.

## Goals

* Tap an imported connection → browse that cluster: grouped resource-type grid.
* List any MVP-catalog type, across all namespaces or one selected namespace.
* Instant client-side search over name and labels.
* Per-type status summaries on list rows (e.g. `3/3 ready`, `Running`, `10.30.0.5`).
* Detail screen with two tabs: **Summary** (identity, status, labels,
  annotations) and **YAML** (live `GET` rendered as YAML, read-only).
* Secrets redacted by default everywhere; explicit per-detail reveal.
* Pagination: infinite scroll — server-side `limit=500` pages fetched
  automatically via the `continue` token as the list nears its end
  (human decision 2026-10-25; no manual "Load more" button).
* Pull-to-refresh; stale responses discarded when scope changes mid-flight.
* Friendly error states: 403/404/401/timeout/5xx mapped to actionable text
  (architecture doc "Error Handling").
* Idempotent reads retried with exponential backoff (2 retries).
* Adaptive layout: compact = single pane; medium/expanded = list-detail
  two-pane using spec 02's `WindowSizeClassProvider`.

## Non-goals (deferred, do not implement here)

* Edit, delete, scale, restart (spec 05 — "Actions").
* Pod logs / exec (spec 05+).
* Watch / streaming / auto-refresh timers (later spec; manual refresh only).
* CRD discovery and CR instances (later spec; the gateway design deliberately
  pre-shapes this: catalog entries are just `apiVersion`/`resource`/namespaced).
* Dynamic API discovery (`/apis`) as the source of the catalog — see Open Q1.
* Metrics, dashboards, Helm, offline cache (stretch goals in 00).
* RBAC-based UI hiding — errors are surfaced, not predicted (00 stretch list).

## Amendments to previous spec docs

* **spec 03 "UI — cluster list"**: tapping a connection row currently only
  calls `setActive`. It becomes **set active AND navigate** —
  `viewModel.setActive(id)` followed by pushing `BrowseKey(connectionId)`;
  long-press delete, context selector and refresh are unchanged. Implemented
  as dev-plan stage 1 (required placement per `AGENTS.md`).

## Resource catalog (MVP, hardcoded in code)

The single source of truth for "what can be browsed" is
`ResourceCatalog` (data layer, pure Kotlin — unit-tested path table):

| Group | Type | apiVersion | resource | namespaced | List-row status |
| --- | --- | --- | --- | --- | --- |
| Workloads | Pods | v1 | pods | yes | ready/total containers, phase, node |
| Workloads | Deployments | apps/v1 | deployments | yes | ready/desired |
| Workloads | StatefulSets | apps/v1 | statefulsets | yes | ready/desired |
| Workloads | DaemonSets | apps/v1 | daemonsets | yes | ready/desired |
| Workloads | ReplicaSets | apps/v1 | replicasets | yes | ready/desired |
| Workloads | Jobs | batch/v1 | jobs | yes | completions, condition |
| Workloads | CronJobs | batch/v1 | cronjobs | yes | schedule, suspend, last schedule |
| Network | Services | v1 | services | yes | type, clusterIP, ports |
| Config | ConfigMaps | v1 | configmaps | yes | data key count |
| Config | Secrets | v1 | secrets | yes | type, data key count (values redacted) |
| Storage | PersistentVolumeClaims | v1 | persistentvolumeclaims | yes | phase, size, storageClass |
| Cluster | Nodes | v1 | nodes | no | Ready condition, kubelet version |
| Cluster | Namespaces | v1 | namespaces | no | phase |

Rules:

* A catalog entry is `(displayName, group, apiVersion, resource, namespaced,
  statusExtractor)`; `statusExtractor` is a pure function
  `Map<String, Any?> -> StatusLine` (primary + secondary text). Adding a type
  later is one row + one extractor + one test.
* List paths follow the Kubernetes API conventions:
  core → `/api/{v}/{resource}`; grouped → `/apis/{group}/{v}/{resource}`;
  namespaced single-namespace inserts `/namespaces/{ns}` before the resource;
  omitted for "all namespaces" (cluster-scoped types never include it).
* The catalog is frozen for this spec's Acceptance run; extensions belong to a
  future spec amendment (CR instances especially).

## Data layer

### Models (`data/model`)

* `ResourceRef(connectionId: UUID, apiVersion: String, resource: String,
  namespace: String?, name: String)` — `@Serializable`-friendly value type
  (nav keys embed its fields).
* `ResourceSummary(kind, apiVersion, name, namespace, labels: Map<String,String>,
  primary: String, secondary: String?, createdAt: String?, deletable: Boolean)`
  — `primary`/`secondary` come from the catalog's `statusExtractor`.
* `ResourcePage(items: List<ResourceSummary>, continueToken: String?)`.
* `ResourceDetail(ref: ResourceRef, obj: Map<String, Any?>)` — the unstructured
  object from `GET`; Summary tab reads fields out of it, YAML tab renders it.
* `ResourceError` sealed type: `Unauthorized`, `Forbidden(kind, namespace?)`,
  `NotFound`, `Network`, `ServerError(code)`, `Malformed` — mapped once, in
  `ResourceRepository`, from HTTP codes and gateway exceptions.

### `ResourceGateway` (`data/kubernetes`)

Generic Kubernetes REST access over the **already-authenticated OkHttp client**
from `ApiClientProvider.clientFor(connectionId, context)` — the exact path the
spec 03 probe and the acceptance harness proved works (no per-type
client-java API classes; one OkHttp call shape for every resource):

* `suspend fun list(refWithoutName, limit, continueToken): ResourcePage` and
  `suspend fun get(ref): Map<String, Any?>`.
* JSON parsed with Gson (client-java transitive, pinned there) into
  `Map<String, Any?>` — objects stay unstructured, exactly like the server
  sent them.
* Query params: `limit` (default 500), `continue` (pass-through), plus
  `fieldSelector`/`labelSelector` reserved for future specs (unused here).
* Retry policy (reads only, enforced at gateway level): 2 retries with
  500 ms / 2 s backoff on `IOException` and HTTP 5xx; never on 4xx. Cancellation
  propagates (no retry after cancel).
* Never logs bodies or headers (token redaction rule from spec 03 holds).

### `ResourceRepository` (`data/repository`)

* Owns catalog lookup, gateway calls, error mapping, **Secret redaction**, and
  the namespace list (`GET /api/v1/namespaces`, cached per connection while
  the browse screen is alive).
* Redaction runs in the repository (not the UI) so no path can leak values by
  forgetting a UI check: Secrets' `data`/`stringData` values are replaced with
  `RedactedValue(decodedByteLength)` at parse time — the base64 payload is
  decoded only to measure its length, and the decoded bytes are discarded.
  Reveal calls the gateway again and decodes each value **base64 → UTF-8 text**
  (kubectl-style) for that one Secret; non-UTF-8 payloads render
  `<binary, {n} bytes>` (human decision 2026-10-25). Bypasses redaction only
  for the single requested Secret.
* Follows the architecture doc's unidirectional data flow: exposes suspend
  functions + `Flow` results; no Android imports (testable off-device).

### Active connection

Browse is always scoped to an explicit `connectionId` carried in the nav keys
(pushed from the row click, which first sets it active). `ApiClientProvider`'s
LRU + eviction from spec 03 is reused verbatim; connection deletion already
evicts cached clients.

## UI

### Navigation (extends spec 02 shell, Nav3)

All new keys `@Serializable` in `navigation/NavKey.kt`:

* `BrowseKey(connectionId: UUID)` — resource type grid.
* `ResourceListKey(connectionId: UUID, apiVersion: String, resource: String)`
  — list + search + namespace scope (scope kept in the screen's ViewModel, not
  the key, so back navigation returns to the last scope within the entry).
* `ResourceDetailKey(connectionId: UUID, apiVersion: String, resource: String,
  namespace: String?, name: String)`.

Compact: each key is its own pane in the back stack. Medium/expanded: a
list-detail Scene strategy renders `ResourceListKey` + `ResourceDetailKey` side
by side (selecting an item pushes/replaces the detail entry in the same stack —
no duplicated ViewModel; the shared WindowSizeClass from spec 02 decides).

### `BrowseScreen` (ui/resources)

Top bar: connection display name + context name, back. Body: catalog grid
grouped by Workloads / Network / Config / Storage / Cluster with item counts
omitted (counts cost a list call each; skipped for MVP). Tapping a type pushes
`ResourceListKey`. A connection that goes Unreachable between screens shows the
existing spec 03 status chip treatment inline in the top bar.

### `ResourceListScreen` (ui/resources)

* Top bar: type display name; namespace dropdown (entries: **All
  namespaces** + names from the namespace list; hidden for cluster-scoped
  types); search field (client-side filter over name + label values, 300 ms
  debounce); refresh icon.
* `LazyColumn` of `ResourceCard`: name (title), namespace subtitle when scope
  is "all", primary status line (emphasized), secondary muted line, age from
  `createdAt`. `testTag("resource-row")` per row.
* States: loading shimmer rows; empty ("No {kind} found" + clear-search hint);
  Forbidden → "You do not have permission to list {kind} in
  {namespace|all namespaces}" with a "Pick a namespace" shortcut when scope is
  all; Network → retry button; infinite scroll: reaching the list bottom while
  a `continueToken` exists auto-fetches the next page (footer spinner; on
  completion the footer reads "{n} items · end") — human decision 2026-10-25.
* Mid-flight scope/search/type changes **cancel** the in-flight request;
  responses from a superseded scope are dropped (tested explicitly).

### `ResourceDetailScreen` (ui/detail)

* `TabRow`: Summary | YAML.
* Summary tab: header (kind, name, namespace), status rows from the catalog
  extractor (full form), `metadata` fields: created, resourceVersion, UID,
  labels (chips), annotations (chips, truncated with copy).
* YAML tab: `GET` the object, render with the app's existing SnakeYAML
  dependency (`dump` with block style, wide width); monospace, horizontally
  scrollable, read-only; copy button (copies the YAML).
* Secrets: values render `•••• ({n} bytes)` (n = decoded length); a per-detail
  **Reveal** button re-`GET`s and substitutes base64-decoded UTF-8 plaintext
  (`<binary, {n} bytes>` when not valid UTF-8) for that Secret only; leaving
  the screen discards it (nothing cached, nothing logged).
* YAML syntax highlighting: see Open Q4 (default: plain text).

## Security posture

* No new persistence: nothing from browsing touches DataStore/Keystore/files;
  YAML and object maps are memory-only (secrets excluded from `toString()` of
  models by construction — `RedactedValue` knows only the length).
* Reads only: this spec issues GET exclusively (enforced: the gateway exposes
  no verb other than GET in this spec's surface).
* Token never enters a URL or log line (unchanged from spec 03).

## Additional dependencies

**None.** Reuses pins already in the graph: OkHttp + Gson (via
`io.kubernetes:client-java:27.0.0`), `org.yaml:snakeyaml:2.5`, Material icons
(BOM). If Open Q4 (syntax highlighting) lands as "minimal highlighter in 04",
the chosen library is pinned here before its stage starts.

## Configuration

| Knob | Value | Notes |
| --- | --- | --- |
| Page size | 500 | server `limit`; infinite scroll prefetches at list end |
| Read retries | 2 | backoff 500 ms, 2 s; IOException + 5xx only |
| Search debounce | 300 ms | client-side filter |
| Namespace list cache | per browse session | refetched on refresh |
| Catalog | hardcoded `ResourceCatalog` | path table above, unit-tested |

## Testing

Every layer is pure-Kotlin-testable; Robolectric + Compose for UI:

* `ResourceCatalogTest`: one case per catalog row asserting the exact list/get
  URL for namespaced (scoped + all) and cluster-scoped forms; status extractors
  fed real fixture JSON fragments (Pods not-ready, Deployment partial, Node
  NotReady, CronJob suspended...).
* `ResourceGatewayTest` (MockWebServer 5.4.0, already pinned): URL + query
  construction, `continue` token round-trip, retry on 500 then 200, **no**
  retry on 403, no retry after cancel, auth header present (inherited from the
  ApiClient interceptor — assert server saw `Authorization`).
* `ResourceRepositoryTest`: HTTP code → `ResourceError` mapping table; Secret
  redaction (values never appear in `ResourceDetail` when unrevealed; masked
  length = decoded byte count; reveal returns base64-decoded UTF-8 exactly
  once and renders `<binary, n bytes>` for non-UTF-8 payloads); namespace list
  caching + refresh.
* ViewModel tests (`ResourceViewModelTest`, canned gateway via
  `testing/ResourceTestHarness`): initial load, refresh,
  search filtering (name + labels), namespace switch cancels and replaces,
  end-of-list scroll appends the next page and keeps search filter, error
  states surface per type (403 message includes namespace scope),
  stale-response drop. Search is an in-memory filter over the loaded window
  and applies immediately — the 300 ms debounce in the Configuration table
  only targets network queries, of which there are none (human decision
  2026-10-25 refinement).
* `YamlRendererTest`, `SummaryRowsTest`: YAML normalisation (Integer not
  Double, redacted placeholders, no long-scalar wrapping) and Summary row
  derivation.
* Compose tests (`ResourceListScreenTest`, `ResourceDetailScreenTest`,
  `ResourceSceneTest`, `BrowseScreenTest`): grid → list → detail happy path
  with tags; tab switch renders YAML text; Secret row masked then revealed;
  Forbidden message + namespace hint render; compact vs medium/expanded
  Scene strategy (stacked vs side-by-side).
* Acceptance (`tools/acceptance`, extends the existing driver): the TLS stub
  gained canned GET responses (`/api/v1/namespaces`, `/api/v1/pods` two-page
  with `continue`, `/apis/apps/v1/deployments`, one Secret, one canned 403
  route). New steps on top of the spec 03 fixture import:
  `spec04-browse-grid`, `spec04-list-rows`, `spec04-infinite-scroll`
  (asserts the `continue=PAGE2` hit in the stub log), `spec04-detail-summary`
  (status.podIP visible), `spec04-detail-yaml`, `spec04-forbidden-escape`
  (403 message + hint), `spec04-network-error` + `spec04-network-recovered`
  (stub killed → retry shows Network message; stub restarted → retry recovers,
  asserted on fresh stub-log hits). Search filtering and Secret reveal are
  covered by the Robolectric tests above rather than adb driving. The stub
  still requires the acceptance bearer token on every route (including the
  new canned ones).

## Acceptance criteria

* From a healthy imported cluster, row tap lands on the type grid; Pods lists
  the stub's canned pods with correct ready/phase columns.
* Search narrows rows instantly; clearing restores them.
* Namespace dropdown lists namespaces and re-queries when changed; selecting
  one is visible in the top bar.
* Detail Summary shows labels; YAML tab shows the object rendered as YAML and
  copy works.
* A Secret's `data` values are redacted by default and appear only after
  Reveal.
* A 403 route renders the permission message with namespace scope; Network
  failure (stub killed) renders retry, which recovers when the stub returns.
* Scrolling to the bottom auto-appends the next page when the stub returns
  `metadata.continue`, and the footer shows the end marker when exhausted.
* All new unit/Robolectric/Compose tests green; `lintDebug` clean; acceptance
  run green on API 37 and API 26.

## Open questions

All answered by Casey 2026-10-25; decisions folded into the body above.

1. **Catalog source** → hardcode the MVP catalog.
2. **Default scope** → All namespaces by default with a pick-a-namespace escape.
3. **Pagination** → infinite scroll (replaces the recommended Load-more button).
4. **YAML highlighting** → plain monospace; a highlighter may come later.
5. **Entry gesture** → row tap = activate + browse; no dedicated Browse button.
6. **Secret reveal** → per-detail reveal button, which also performs the
   base64 decode (decisions 3 and 6 folded into Goals, UI, repository and
   Testing sections).

## Dev plan

1. **spec 03 amendment** (must be stage 1): row click activates + navigates;
   `BrowseKey` + placeholder `BrowseScreen`; nav round-trip Compose test.
2. **Catalog + gateway**: `ResourceCatalog`, `ResourceGateway` (OkHttp GET
   only), models, `ResourceCatalogTest`, `ResourceGatewayTest` on MockWebServer.
3. **Repository + ViewModel layer**: error mapping, redaction, namespace
   caching, list/search/infinite-scroll ViewModel logic + tests.
4. **Screens**: `BrowseScreen` grid, `ResourceListScreen`,
   `ResourceDetailScreen` (Summary/YAML tabs, Secret reveal) + Compose tests.
5. **Adaptive + acceptance**: medium/expanded list-detail Scene strategy,
   acceptance stub routes + new steps, run API 37 and API 26, docs sync,
   flip this spec to `active`.
