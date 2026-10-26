---
description: Read-only pod log viewer — tail a pod's current or previous container logs with follow streaming, container selector including init containers, client-side line filter, and safe copy/export, riding the spec 03/04 authenticated OkHttp path.
status: proposed
---

# Spec 05 — Pod logs

Implements the "view logs" affordance deferred from spec 04 (Non-goals: "Pod
logs / exec (spec 05+)"). Everything here is **read-only**: the API surface
stays GET-only, and no mutation or exec lands in this spec (exec is a later
spec with its own security analysis).

## Goals

* From a pod (detail screen), open a **log viewer** for that pod.
* Stream logs live with **Follow** (chunked streaming), or fetch a static tail
  with Follow off.
* **Container selector** for multi-container pods: all regular containers plus
  init containers; selecting a container restarts the stream against it.
* **Previous-run logs** (`--previous` equivalent) toggle for crashed/restarted
  containers.
* **Tail size**: last 100 / 1,000 / 5,000 lines, or all (default per Open Q2).
* **Timestamps** toggle (`timestamps=true`), off by default.
* **Client-side line filter** over the loaded buffer (instant, in-memory —
  same pattern as spec 04's search).
* **Wrap / no-wrap** toggle for long lines.
* **Copy all** visible (filtered) lines to clipboard; see Open Q6 for share.
* Friendly error states reusing the spec 04 error vocabulary: 403 on
  `pods/log` (a **separate RBAC grant** from `pods get` — the message must say
  so), 404 pod, 400 bad container name, Network, ServerError.
* Mid-stream disconnect (server closes the follow stream, network drop):
  buffer is kept, a banner appears, **Retry** restarts the tail (behavior per
  Open Q5).

## Non-goals (deferred, do not implement here)

* `kubectl exec` equivalent / attach / port-forward (later spec; separate
  security surface — WebSocket/SPDY, not plain GET).
* Multi-container aggregated single stream (kubectl needs `-l` selector; not
  MVP) — one container at a time, per selector.
* Log persistence, history across visits, offline cache — memory-only buffer,
  discarded on leaving the screen.
* Server-side filtering (`sinceSeconds`, `sinceTime`) — client-side filter is
  enough for MVP tails; timestamps toggle covers "when".
* Syntax highlighting / log parsing (JSON field expansion, stack-trace
  folding) — plain monospace text, same stance as spec 04 Q4.
* Events tab for the pod (candidate for a spec 06 amendment; `pods/{name}`
  sibling `events` field-selector list is already within gateway reach).
* Node/kubelet-level logs, control-plane component logs.

## Amendments to previous spec docs

* **spec 04 "ResourceDetailScreen"**: for `kind == "Pod"` details only, the
  detail top bar gains a **Logs** action which pushes `LogKey`. All other
  kinds are unchanged; the YAML/Summary tabs and Secret reveal are untouched.
  Implemented as dev-plan stage 1 (required placement per `AGENTS.md`).

## Data layer

### Endpoint

The Kubernetes pod-log subresource, the only endpoint this spec calls:

```
GET /api/v1/namespaces/{ns}/pods/{name}/log
    ?container={c}        (required for multi-container pods; init containers OK)
    &previous={true}      (previous terminated instance)
    &tailLines={n}        (omit for "all")
    &timestamps={true}
    &follow={true}        (chunked transfer, stream stays open server-side)
```

Path building extends `ResourceCatalog` with a pure
`logPath(entry, namespace, name)` helper (unit-tested like the other path
builders), reusing the `pods` entry.

### `LogGateway` (`data/kubernetes`)

A focused sibling of `ResourceGateway` — same `resolve()` seam to the
authenticated client from `ApiClientProvider`, but **streaming**:

* `fun stream(request: LogRequest): Flow<LogLineEvent>` — cold Flow; the
  blocking OkHttp call runs on the collector's dispatcher (`Dispatchers.IO`
  from the ViewModel); cancelling the collector cancels the call (verified by
  the server seeing the connection close in acceptance).
* Per-call client derivation: `resolved.http.newBuilder()` with
  `readTimeout = 0` and `callTimeout = 0` **only when follow=true** — a
  silent log stream must not trip OkHttp's default 10 s read timeout. Static
  tails keep the inherited timeouts. No new shared client, no cache growth
  (spec 03 LRU untouched).
* Lines are split on `\n`; the final unterminated chunk is flushed at stream
  end. Response body is text/plain per the API — **no JSON parsing**, so no
  Gson in this path.
* `Accept: text/plain`; `Authorization` inherited from the spec 03 interceptor
  (never in the URL, never logged — rule unchanged).
* Retry policy: **before first byte**, retry IOException/5xx ×2 with the
  injected `RetryBackoff` (same table as spec 04: 500 ms / 2 s). **After the
  first emitted line, no automatic retry** — reconnect is a user action
  (Open Q5) to avoid silent duplicate-tail confusion.
* Log line events: `Line(text)`, `StreamEnd(reason)` where reason ∈
  `Completed` (server closed cleanly), `Error(ResourceError)`.
* Cap on raw line length per event (4 KB, mid-line cut with a `…[truncated]`
  marker) so one pathological line cannot pin megabytes per row.

### `LogRepository` (`data/repository`)

* Maps gateway HTTP outcomes to the existing `ResourceError` sealed type
  (Unauthorized, Forbidden, NotFound, Network, ServerError, Malformed) and
  adds the pods/log-specific Forbidden copy input: the 403 message must
  distinguish "you can see the pod but not its logs" (`pods/log` is a
  separate RBAC resource/verb — the RBAC `pods/log` grant is often omitted
  while `pods get` is present).
* Validates the request locally first: container name must appear in the
  pod's `spec.containers`/`spec.initContainers` (400 from the server is still
  mapped, but the local check produces the friendlier "container not found"
  and lists known names).
* No caching, no persistence. Container list comes from the already-loaded
  detail object (passed by the caller), not a second GET.

## UI

### Navigation (extends spec 02 shell, Nav3)

* `LogKey(connectionId: UUID, namespace: String, podName: String,
  container: String?)` — `@Serializable`, UUID via the existing
  `UuidAsStringSerializer`. Container is nullable (auto-select single
  container).

### `LogScreen` (`ui/logs`)

* **Top bar**: pod name + namespace; container dropdown (hidden when the pod
  has exactly one container); toggles as icon buttons: Follow, Timestamps,
  Previous, Wrap; overflow: tail size (100 / 1k / 5k / All), Copy all.
* **Filter field** under the top bar (in-memory over the buffer, immediate —
  no network involvement, per the spec 04 precedent).
* **Log body**: `LazyColumn` of monospace lines, each with a muted line
  number; auto-scrolls to bottom while Follow is on and the user has not
  scrolled up (a "Jump to latest" pill appears when they have); no-wrap mode
  gives horizontal scrolling per line.
* **Buffer**: ring buffer capped at **10,000 lines** (`LogBuffer` in the
  ViewModel); when the cap evicts, a muted header reads
  "showing last 10,000 of {n} lines".
* **States**: loading (spinner until first byte); streaming (blinking cursor
  line while Follow on); ended (`StreamEnd(Completed)` → muted "stream
  closed" + Retry); error (`StreamEnd(Error)` → inline error card with Retry;
  403 variant names the `pods/log` RBAC grant); empty ("No logs" — a real
  200 with empty body, not an error).
* Any control change (container / previous / tail / timestamps / follow)
  **cancels** the in-flight stream and starts a new one; a generation guard
  drops events from superseded streams (spec 04 pattern, tested explicitly).

### Security posture

* Memory-only logs; nothing touches DataStore/Keystore/disk (except the
  share-to-cache file, only if Open Q6 selects it).
* Logs can contain secrets — this is why copy/share is deliberately explicit
  user action, never automatic, and cache-dir share files are written under
  `cacheDir/exports/` (evicted by the OS) if Q6 opts in.
* GET only (enforced at gateway surface); no token in URLs or log lines; the
  viewer never logs line content to Logcat.

## Additional dependencies

**None.** OkHttp streaming, Gson (unused here), Material icons and
`androidx.core` FileProvider (only if Open Q6 selects share) are all already
pinned in the graph.

## Configuration

| Knob | Value | Notes |
| --- | --- | --- |
| Default tail | 1,000 lines | selector: 100 / 1k / 5k / All (Open Q2) |
| Buffer cap | 10,000 lines | ring buffer, eviction header shows total seen |
| Line length cap | 4 KB | mid-line cut + `…[truncated]` marker |
| Pre-first-byte retries | 2 | `RetryBackoff` 500 ms / 2 s; IOException + 5xx |
| Post-first-byte retries | 0 | manual Retry only (Open Q5) |
| Follow timeouts | read=0, call=0 | follow calls only; static keeps inherited |
| Auto-scroll | follow-on, not scrolled up | "Jump to latest" pill otherwise |

## Testing

Every layer pure-Kotlin-testable; Robolectric + Compose for UI:

* `ResourceCatalogTest` extension: `logPath` exact-URL cases (namespaced,
  container/previous/tailLines/timestamps/follow query encoding, URL-encoded
  pod/container names with special chars).
* `LogGatewayTest` (MockWebServer, already pinned): query construction;
  follow call has disabled timeouts (assert via MockWebServer slow-response +
  wall-clock-free chunk emission); line splitting across chunk boundaries and
  trailing-partial flush; line-length cap; retry before first byte
  (500→200), **no** retry after first line then drop; no retry on 403;
  cancel mid-stream closes the call.
* `LogRepositoryTest`: HTTP→`ResourceError` table incl. the pods/log 403
  message; local container validation against the supplied container list;
  200-with-empty-body → empty, not error.
* `LogViewModelTest` (harness extended in `testing/`): first-byte → content;
  follow appends; container switch cancels + generation-drops stale events;
  buffer cap evicts oldest and header counts; filter over buffer (immediate,
  no gateway hit); toggles restart stream with right request; jump-to-latest
  behavior on manual scroll-up.
* `LogScreenTest` (Compose): tags `log-body`, `log-line`, `log-filter`,
  `container-select`, `follow-toggle`, `retry-button`, `jump-pill`,
  `ended-banner`, `stream-error`; 403 card text names `pods/log`; empty
  renders "No logs".
* Acceptance (`tools/acceptance`, extends the driver): the TLS stub gains
  `/api/v1/namespaces/{ns}/pods/{name}/log` routes — static tail, a chunked
  follow route that appends a new line every second (asserted appearing),
  a container-switch route (different canned text per `container=`), a
  mid-stream drop route (server closes after 2 chunks), and a 403 `pods/log`
  route. New steps on top of the spec 03/04 fixture: `spec05-open-logs`,
  `spec05-log-static`, `spec05-log-follow-append` (asserts a second canned
  line visible), `spec05-log-container-switch`, `spec05-log-disconnect`
  (ended banner + Retry recovers), `spec05-log-403`. The stub still requires
  the acceptance bearer token on every route.

## Acceptance criteria

* From a Pod detail, **Logs** opens the viewer and shows the canned tail.
* Follow shows new lines appearing without user action; scrolling up stops
  auto-follow and the jump pill appears.
* Container switch changes the stream to the other container's lines.
* A mid-stream drop keeps loaded lines, shows the ended banner, and Retry
  reloads successfully.
* A `pods/log` 403 renders the permission message naming the grant.
* Filter narrows lines instantly; Copy all copies the filtered buffer.
* All new unit/Robolectric/Compose tests green; `lintDebug` clean;
  acceptance run green on API 37 and API 26.

## Open questions

Recommendation first for each; answer inline as with spec 04.

1. **Entry gesture** → "Logs" action in the pod **detail top bar**
   (recommended, it is an amendment to spec 04's detail screen); alternative:
   long-press context menu on pod list rows (more discoverable from the list,
   but splits log entry across two surfaces).
2. **Default tail** → **1,000 lines** (recommended — kubectl defaults to
   *all*, which risks multi-hundred-MB buffers on chatty pods; All remains
   available in the selector).
3. **Follow default** → **off** (recommended — deterministic screen load;
   users opt into the live view; on = auto-follow the moment you open).
4. **Buffer cap** → **10,000 lines** ring (recommended; alternatives
   5k/50k — 10k ≈ a few MB worst case, comfortably fits low-end devices).
5. **Mid-stream disconnect behavior** → **keep buffer + banner + manual
   Retry** (recommended; kubectl just exits; an *automatic* reconnect would
   silently re-tail and duplicate lines unless `tailLines` is re-fetched,
   which muddies the buffer semantics).
6. **Export scope** → **copy-to-clipboard only** (recommended for MVP —
   logs frequently carry secrets, and any share sheet leaves app sandbox;
   if a share file is wanted, FileProvider + `cacheDir/exports/` is the
   agreed shape and gets pinned in Additional dependencies).
7. **Timestamps default** → **off** (recommended — cleaner reading for
   tails; toggle available per-view, server-side filter via timestamps only
   affects display).

## Dev plan

1. **spec 04 amendment** (must be stage 1): `Logs` action on pod detail top
   bar + `LogKey` nav + placeholder `LogScreen`; nav round-trip Compose test.
2. **Gateway + paths**: `logPath` on `ResourceCatalog`, `LogGateway` with
   Flow streaming, timeouts/retry policy, `LogGatewayTest` + catalog test
   extension on MockWebServer.
3. **Repository + ViewModel**: error mapping (pods/log 403 copy), container
   validation, `LogBuffer` ring with cap, generation-guarded control
   restarts, `LogRepositoryTest`, `LogViewModelTest`.
4. **Screen**: `LogScreen` (top bar controls, filter, body, states,
   jump-to-latest) + `LogScreenTest` Compose coverage.
5. **Acceptance + activation**: stub log routes (static/follow/chunk-drop/
   403), new driver steps, run API 37 and API 26, docs sync, flip this spec
   to `active`.
