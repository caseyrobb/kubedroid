---
description: Read-only pod log viewer — tail a pod's last 1,000 lines with follow streaming, container selector including init containers, client-side line filter, and SAF save of the complete log, riding the spec 03/04 authenticated OkHttp path.
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
  with Follow off (default, human decision 2026-10-25).
* **Container selector** for multi-container pods: all regular containers plus
  init containers; selecting a container restarts the stream against it.
* **Previous-run logs** (`--previous` equivalent) toggle for crashed/restarted
  containers.
* **Tail**: the viewer loads the **last 1,000 lines** (fixed; the log API has
  no pagination token, so larger history is not paged in-screen — it is
  obtained via **Save**, which fetches the complete log; human decision
  2026-10-25).
* **Timestamps** are requested from the server on every fetch; the on-screen
  toggle is **display-only** (strip/prefix), never a network change
  (human decision 2026-10-25).
* **Client-side line filter** over the loaded buffer (instant, in-memory,
  matches the text portion only — never the timestamp prefix; same pattern as
  spec 04's search).
* **Wrap / no-wrap** toggle for long lines.
* **Save to local file**: a save icon fetches the **complete log** (no
  `tailLines`) via the Storage Access Framework document picker and streams
  it to the user-chosen file; saved files always carry RFC3339 timestamps
  (human decision 2026-10-25; copy-to-clipboard was explicitly dropped).
* Friendly error states reusing the spec 04 error vocabulary: 403 on
  `pods/log` (a **separate RBAC grant** from `pods get` — the message must say
  so), 404 pod, 400 bad container name, Network, ServerError.
* Mid-stream disconnect (server closes the follow stream, network drop):
  buffer is kept, a banner appears, **Retry** restarts the tail
  (manual-retry decision, 2026-10-25).

## Non-goals (deferred, do not implement here)

* `kubectl exec` equivalent / attach / port-forward (later spec; separate
  security surface — WebSocket/SPDY, not plain GET).
* Multi-container aggregated single stream (kubectl needs `-l` selector; not
  MVP) — one container at a time, per selector.
* In-screen pagination of history beyond the 1,000-line tail (no API
  pagination token exists; Save covers "the whole log", per human decision
  2026-10-25).
* Log persistence, history across visits, offline cache — memory-only buffer,
  discarded on leaving the screen (the Save output is the user's file, not
  app state).
* Server-side filtering (`sinceSeconds`, `sinceTime`) — client-side filter is
  enough for MVP tails.
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
    &tailLines=1000       (viewer fetches only; Save omits it entirely)
    &timestamps=true      (ALWAYS sent; the UI toggle is display-only)
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
* `suspend fun save(request: SaveRequest, sink: OutputStream): ResourceError?`
  — the Save path: one-shot GET with **no `tailLines`, no `follow`**, response
  body copied to `sink` in 8 KB chunks (constant memory; the complete log
  never enters the heap as a whole). Cancellation closes both sides and
  truncates the document back to zero bytes (best effort). Returns null on
  success, the mapped error otherwise. Saved bytes are the **raw server body**
  — the 4 KB display truncation below does not apply here, and the server
  already prefixes every line with its RFC3339 timestamp.
* Per-call client derivation: `resolved.http.newBuilder()` with
  `readTimeout = 0` and `callTimeout = 0` **only when follow=true** — a
  silent log stream must not trip OkHttp's default 10 s read timeout. Static
  tails and Save keep the inherited timeouts. No new shared client, no cache
  growth (spec 03 LRU untouched).
* Lines are split on `\n`; the final unterminated chunk is flushed at stream
  end. The leading `RFC3339 + space` prefix is parsed off into
  `LogLine(timestamp: String?, text: String)` — a line whose prefix doesn't
  parse (or a runtime that sent none) keeps `timestamp = null` and renders
  raw. Response body is text/plain per the API — **no JSON parsing**, so no
  Gson in this path.
* `Accept: text/plain`; `Authorization` inherited from the spec 03 interceptor
  (never in the URL, never logged — rule unchanged).
* Retry policy: **before first byte**, retry IOException/5xx ×2 with the
  injected `RetryBackoff` (same table as spec 04: 500 ms / 2 s). **After the
  first emitted line, no automatic retry** — reconnect is a user action
  (manual-retry decision) to avoid silent duplicate-tail confusion.
* Log line events: `Line(LogLine)`, `StreamEnd(reason)` where reason ∈
  `Completed` (server closed cleanly), `Error(ResourceError)`.
* Cap on raw line length per **displayed** event (4 KB, mid-line cut with a
  `…[truncated]` marker) so one pathological line cannot pin megabytes per
  row. Save is unaffected (raw passthrough).

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
  has exactly one container); icon toggles: Follow, Timestamps, Previous,
  Wrap; **Save** icon. No tail-size selector (fixed 1,000; human decision
  2026-10-25).
* **Save flow**: tap Save → `ACTION_CREATE_DOCUMENT` (`text/plain`, suggested
  name `{namespace}_{pod}_{container}_{yyyyMMdd-HHmmss}.log`) → on result,
  open the Uri for writing and call `LogGateway.save` on IO. A "Saving…"
  state disables the icon; success → snackbar "Saved {n} lines"; cancel →
  nothing happens (no file). Errors map to the standard error copy. Save is
  independent of the on-screen buffer: it re-fetches the complete log from
  the server.
* **Filter field** under the top bar (in-memory over the buffer, immediate,
  text portion only — no network involvement, per the spec 04 precedent).
* **Log body**: `LazyColumn` of monospace lines, each with a muted line
  number; timestamp prefix rendered only when the Timestamps toggle is on;
  auto-scrolls to bottom while Follow is on and the user has not scrolled up
  (a "Jump to latest" pill appears when they have); no-wrap mode gives
  horizontal scrolling per line.
* **Buffer**: ring buffer capped at **10,000 lines** (`LogBuffer` in the
  ViewModel) — reached only by follow-mode appends (the static tail is
  1,000); when the cap evicts, a muted header reads
  "showing last 10,000 of {n} lines".
* **States**: loading (spinner until first byte); streaming (blinking cursor
  line while Follow on); ended (`StreamEnd(Completed)` → muted "stream
  closed" + Retry); error (`StreamEnd(Error)` → inline error card with Retry;
  403 variant names the `pods/log` RBAC grant); empty ("No logs" — a real
  200 with empty body, not an error).
* Control changes that **cancel and restart** the stream: container,
  previous, follow. The Timestamps and Wrap toggles are **display-only** —
  they never touch the network (tested explicitly). Any restart uses a
  generation guard that drops events from superseded streams (spec 04
  pattern, tested explicitly).

### Security posture

* Memory-only logs; the app never writes log content to its own storage —
  the only file is the one the user picks via the SAF picker, written once,
  owned by the user's choice (no cacheDir exports; the earlier FileProvider
  shape was dropped with copy-to-clipboard, human decision 2026-10-25).
* Logs can contain secrets — this is why Save is a deliberate user action
  (picker UI every time), never automatic, and never logged to Logcat.
* GET only (enforced at gateway surface); no token in URLs or log lines; the
  viewer never logs line content to Logcat.

## Additional dependencies

**None.** OkHttp streaming and Material icons are already pinned in the
graph. The SAF picker uses framework `ACTION_CREATE_DOCUMENT` — no
FileProvider needed (the app writes to a user-granted Uri, not shares one).

## Configuration

| Knob | Value | Notes |
| --- | --- | --- |
| Viewer tail | 1,000 lines, fixed | no in-screen paging; Save fetches the complete log |
| Buffer cap | 10,000 lines | ring buffer; only follow appends can reach it; eviction header shows total seen |
| Line length cap | 4 KB (display) | mid-line cut + `…[truncated]`; Save is raw passthrough |
| Timestamps | server always on | toggle is display-only, never restarts the stream |
| Pre-first-byte retries | 2 | `RetryBackoff` 500 ms / 2 s; IOException + 5xx |
| Post-first-byte retries | 0 | manual Retry only |
| Follow timeouts | read=0, call=0 | follow calls only; static/Save keep inherited |
| Save chunk | 8 KB | streamed copy; constant memory |
| Auto-scroll | follow-on, not scrolled up | "Jump to latest" pill otherwise |

## Testing

Every layer pure-Kotlin-testable; Robolectric + Compose for UI:

* `ResourceCatalogTest` extension: `logPath` exact-URL cases (namespaced,
  container/previous/timestamps/follow query encoding, `tailLines=1000`
  present in stream URLs and **absent** in save URLs, URL-encoded
  pod/container names with special chars).
* `LogGatewayTest` (MockWebServer, already pinned): query construction;
  `timestamps=true` always present; follow call has disabled timeouts (assert
  via MockWebServer slow-response + wall-clock-free chunk emission); line
  splitting across chunk boundaries and trailing-partial flush; RFC3339
  prefix parsing incl. unparseable-prefix passthrough; display line-length
  cap; Save streams the full body to a `ByteArrayOutputStream` byte-for-byte
  with no `tailLines` param, and cancellation truncates; retry before first
  byte (500→200), **no** retry after first line then drop; no retry on 403;
  cancel mid-stream closes the call.
* `LogRepositoryTest`: HTTP→`ResourceError` table incl. the pods/log 403
  message; local container validation against the supplied container list;
  200-with-empty-body → empty, not error.
* `LogViewModelTest` (harness extended in `testing/`): first-byte → content;
  follow appends; container/previous/follow switches cancel + generation-drop
  stale events; **Timestamps and Wrap toggles issue zero gateway calls**;
  filter matches text portion only (a line whose timestamp contains the query
  string but whose text does not is excluded); buffer cap evicts oldest and
  header counts; save success/cancel/error state transitions.
* `LogScreenTest` (Compose): tags `log-body`, `log-line`, `log-filter`,
  `container-select`, `follow-toggle`, `save-button`, `retry-button`,
  `jump-pill`, `ended-banner`, `stream-error`; Robolectric asserts the Save
  click fires `ACTION_CREATE_DOCUMENT` with `text/plain` and the filename
  template; 403 card text names `pods/log`; empty renders "No logs".
* Acceptance (`tools/acceptance`, extends the driver): the TLS stub gains
  `/api/v1/namespaces/{ns}/pods/{name}/log` routes — static tail, a chunked
  follow route that appends a new line every second (asserted appearing),
  a container-switch route (different canned text per `container=`), a
  mid-stream drop route (server closes after 2 chunks), and a 403 `pods/log`
  route. New steps on top of the spec 03/04 fixture: `spec05-open-logs`,
  `spec05-log-static`, `spec05-log-follow-append` (asserts a second canned
  line visible), `spec05-log-container-switch`, `spec05-log-disconnect`
  (ended banner + Retry recovers), `spec05-log-403`, `spec05-log-save-picker`
  (tap Save; assert the system document picker gains focus —
  `com.android.documentsui` window — then cancel back to the viewer; the
  written-file content is covered by the Robolectric/gateway tests above).
  The stub still requires the acceptance bearer token on every route.

## Acceptance criteria

* From a Pod detail, **Logs** opens the viewer and shows the canned tail
  (≤ 1,000 lines, timestamps hidden by default, toggle reveals them without
  a network refetch).
* Follow shows new lines appearing without user action; scrolling up stops
  auto-follow and the jump pill appears.
* Container switch changes the stream to the other container's lines.
* A mid-stream drop keeps loaded lines, shows the ended banner, and Retry
  reloads successfully.
* A `pods/log` 403 renders the permission message naming the grant.
* Filter narrows lines instantly.
* Save opens the system document picker with the suggested filename and
  cancels cleanly.
* All new unit/Robolectric/Compose tests green; `lintDebug` clean;
  acceptance run green on API 37 and API 26.

## Open questions

All answered by Casey 2026-10-25; decisions folded into the body above.

1. **Entry gesture** → Logs action in the pod detail top bar (spec 04
   amendment, dev-plan stage 1).
2. **Tail** → 1,000 lines fixed. An infinite-scroll idea was considered and
   withdrawn once it was established the log API has no pagination token;
   anything beyond the tail is obtained via Save (human decision 2026-10-25).
3. **Follow default** → off.
4. **Buffer cap** → 10,000-line ring, reachable only by follow appends
   (history paging was withdrawn with Q2's infinite-scroll idea).
5. **Mid-stream disconnect** → keep buffer + banner + manual Retry.
6. **Export** → no copy-to-clipboard; Save icon → SAF document picker,
   complete log streamed to the user-chosen file (replaces the earlier
   "copy visible + optional share file" shape).
7. **Timestamps** → server always timestamps; toggle is display-only and
   off by default; saved files always include timestamps.

## Dev plan

1. **spec 04 amendment** (must be stage 1): `Logs` action on pod detail top
   bar + `LogKey` nav + placeholder `LogScreen`; nav round-trip Compose test.
2. **Gateway + paths**: `logPath` on `ResourceCatalog`, `LogGateway` Flow
   streaming + Save streaming path, timestamps/timeout/retry policy,
   `LogGatewayTest` + catalog test extension on MockWebServer.
3. **Repository + ViewModel**: error mapping (pods/log 403 copy), container
   validation, `LogBuffer` ring with cap, generation-guarded control
   restarts, display-only toggles, save state machine,
   `LogRepositoryTest`, `LogViewModelTest`.
4. **Screen**: `LogScreen` (top bar controls, filter, body, states,
   jump-to-latest, SAF save flow) + `LogScreenTest` Compose coverage.
5. **Acceptance + activation**: stub log routes (static/follow/chunk-drop/
   403), new driver steps incl. save-picker, run API 37 and API 26, docs
   sync, flip this spec to `active`.
