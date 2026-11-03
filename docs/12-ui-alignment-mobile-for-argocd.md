---
description: Align kubedroid's UI language with argoproj-labs/mobile-for-argocd — semantic status tokens, status pills, a fleet health bar, monospace metadata grids, filter/sort chips, and sheet-based detail surfaces.
status: active
---

# Spec 12 — UI alignment with mobile-for-argocd

## Context

`https://github.com/argoproj-labs/mobile-for-argocd` (reviewed 2026-10-28) is
an Expo/React Native mobile client for Argo CD. It is the best current
reference for a *status-first DevOps surface on a phone*: dense but
scannable lists, semantic status tokens, monospace metadata micro-labels,
search + filter-chip rails, and bottom sheets instead of dialogs.

This spec adopts its **information design**, not its skin. kubedroid stays
Material 3 with dynamic color (spec 08 decision) and Kubernetes-blue
branding; mobile-for-argocd's fixed navy/orange palette is explicitly out
of scope (see Q1).

Reference points in the upstream repo: `lib/status.ts` (status token
table), `lib/theme.ts`, `app/(app)/(tabs)/index.tsx` (search, chip rail,
`HealthBar`, star favorites, metadata grid cards), `components/sync-sheet.tsx`
+ `components/resource-detail-sheet.tsx` (sheet patterns, `HighlightedYaml`),
`app/(app)/logs.tsx` (container switcher, follow, wrap), `app/(app)/diff.tsx`.

## Current state (audited 2026-10-28)

- Cluster list: green `StatusDot` + name + `v: <gitVersion>` chip + context
  `AssistChip` (spec 08). No severity semantics beyond green/gray.
- Resource list (`ResourceListScreen`): search box, namespace scope
  dropdown, infinite scroll, 403 escape hatch. No status filter, no sort.
- Browse grid: section headers + official icons (spec 08). No health signal
  on cards.
- Detail (`ResourceDetailScreen`, `SummaryRows`): plain `label: value`
  rows; YAML shown without line numbers.
- Logs (`LogScreen`): tail 1000, in-memory filter, follow + autoscroll,
  container switcher, init-container awareness, SAF save. Missing: line
  wrap toggle (lines are fixed single-line rows today via `maxLines`).
- No relative timestamps anywhere; absolute ISO strings only.

## Goals

1. **Status token system** (`ui/status/StatusTokens.kt`): one
   `StatusToken(label, color, containerColor, outlineColor, icon, severity)`
   table covering every state the app renders:
   - Pod phases: Running, Succeeded, Pending, Failed, Unknown,
     CrashLoopBackOff (reason-aware).
   - Workload readiness: Ready, Progressing, NotReady (from
     `status.conditions` / ready-replica comparison).
   - Cluster probe: Reachable, Unreachable, Unvalidated (reuses spec 03
     `ConnectionStatus`).
   Semantics follow upstream conventions: green = good, amber = drift or
   pending, red = failure, blue = in-progress/informational, purple =
   suspended, gray = unknown. `containerColor = color.copy(alpha = 0.14)`
   and `outlineColor = color.copy(alpha = 0.40)` per upstream token recipe.
   A `severity: Int` gives problems-first sorting (upstream `HEALTH_ORDER`).
   Semantic hues are fixed constants (light/dark verified for contrast);
   all *other* theming continues to follow the M3 `ColorScheme` so dynamic
   color keeps working.
2. **`StatusPill` composable**: icon + label on translucent fill/border,
   used on cluster cards, resource list rows, detail headers, and log
   headers. Icons come from the existing vector set (spec 08) or
   `material-icons` core (`check-circle`, `error`, `hourglass_empty`,
   `sync`, `pause-circle` equivalents); unresolvable names use a TextButton
   label fallback per project convention.
3. **Fleet health bar**: one weighted-segment `HealthBar` composable
   (thin rounded `Row` of colored segments, zero-count segments omitted).
   Placement: under the TopAppBar on `ResourceListScreen` when listing
   pod-like kinds, and per-section counts on the browse grid (Q2).
4. **Metadata micro-label grid**: `MetaGrid` composable — uppercase,
   letter-spaced `labelSmall` keys + monospace values via
   `MaterialTheme.typography.fontFamilyMonospace`, single-line with
   ellipsis. First consumers: cluster card (`SERVER / CONTEXT / VERSION`
   replacing the ad-hoc chip row) and `ResourceDetailScreen` SUMMARY
   section (namespaced-name, IP, node, restarts, ages).
5. **Filter chip rail + sort sheet** on `ResourceListScreen`: horizontally
   scrollable chips — Status (token buckets from goal 1, with counts),
   Labels (suggestion chips harvested from loaded objects, `k=v` toggle
   style), and a Sort sheet (Name, Age, Status-severity, Restart count).
   Existing search box and namespace dropdown stay. Server-side filtering
   remains out of scope: chips filter the loaded pages client-side (Q3).
6. **Sheet-based detail surfaces**: tap-to-open YAML on detail screens
   moves into a `ModalBottomSheet` with a line-numbered monospace YAML
   viewer (`YamlViewer`, upstream `HighlightedYaml` pattern — line-number
   gutter, no syntax-color dependency). Delete confirmation becomes a
   sheet. Edits stay dialog/sheet per current spec 06 behavior (Q4).
7. **Log wrap toggle**: add `Wrap`/`No wrap` toggle to `LogScreen`
   (`ui.follow` sibling in `LogViewModel`), default off, persisted in
   session only.
8. **Relative timestamps**: `RelativeTime` utility (`now - ts` → `5h ago`,
   `5mo ago`, `3d ago`, ISO beyond 365d, exact seconds under 1m) applied
   to age columns, cluster card last-probed time, and log screen header.

## Non-goals

- The navy/orange fixed theme, Ionicons, and React Native componentry (Q1).
- Bottom tab bar: kubedroid is a drill-down hierarchy; TopAppBar + back
  stays.
- Websocket live watch (upstream `useQuery` watch): revisit in a future
  spec; client-java/OkHttp watch wiring is a data-layer spec of its own.
- Sync/prune operations (Argo-specific), SSO/webview login, favorites.
- Server-side filtering/pagination changes (spec 04 `continue` token flow
  unchanged).
- No new dependencies.

## Design sketch

```
ui/status/StatusTokens.kt     StatusToken + tables + severity + resolve()
ui/status/StatusPill.kt       pill composable (icon + label + token)
ui/common/HealthBar.kt        weighted segment bar
ui/common/MetaGrid.kt         uppercase micro-label / monospace value grid
ui/common/RelativeTime.kt     Instant -> "5h ago" (UTC-safe, testable clock)
ui/common/YamlViewer.kt       line-number gutter, monospace, horizontal scroll
```

`resolve(...)` functions map from raw strings (`pod.status.phase`,
`containerStatuses[].state.waiting.reason`, condition types) so rendering
never string-matches inline. Unknown values fall back to the Unknown token
— never a crash or blank.

## Configuration

No user-facing settings beyond the log wrap toggle. No theme flags;
dynamic color behavior unchanged (spec 08 Theme.kt).

## Additional dependencies

None.

## Open questions

1. **Theme**: adopt semantic hues only (proposed), or also offer an optional
   fixed "terminal dark" theme matching upstream? Recommendation: hues only.
   hues only
2. **Health bar placement**: browse-grid section badges need per-kind counts,
   which means one LIST call per kind at grid build — or defer counts until
   per-section lazy load. Alternative: bar only on pod-like list screens for
   v1. Recommendation: v1 = list screens only.
   list screens only
3. **Chip filtering scope**: client-side over loaded pages can mislead when
   pagination hasn't fetched everything (counts shown would be partial).
   Options: label chips apply server-side via `labelSelector` (correct,
   needs API param plumbing) vs client-side with a "filtered N of M loaded"
   hint. Recommendation: label chips server-side (`labelSelector`), status
   chips client-side (status is already fully loaded per page).
   Go with the recommendation
4. **Delete confirmation as sheet** — sheets can be dismissed by swipe,
   which is fine, but the destructive button must not sit where a swipe
   lands. Keep AlertDialog or sheet-with-no-dismiss-on-swipe?
   Recommendation: keep AlertDialog for delete (safer), sheet only for the
   YAML viewer + resource quick-peek.
   Go with the recommendation
5. **Driver-visible strings**: acceptance targets text/content-desc exactly.
   New strings may be added; existing driver targets ("No cluster connected",
   "Add cluster", "Workloads", "All namespaces", pod names, etc.) must not
   be renamed or removed. Any unavoidable rename means amending the driver
   in the same commit — which spec 08's "untouched acceptance.py" rule
   previously forbade. Confirm the rule relaxes to "driver amended only
   with spec amendment + re-green". Recommendation: yes, relax for this spec.

## Decisions (human, 2026-10-29)

- Q1: semantic hues only — no fixed "terminal dark" skin; M3 dynamic color
and light/dark remain canonical.
- Q2: health bar v1 on pod-like list screens only; browse-grid section
counts deferred (avoid per-kind LIST fan-out).
- Q3: as recommended — label chips server-side via `labelSelector`; status
chips client-side over loaded pages.
- Q4: as recommended — delete stays AlertDialog; sheets used for the YAML
viewer and resource quick-peek only.
- Q5: as recommended — the spec 08 "untouched acceptance.py" rule relaxes
for this spec: the driver may be amended in the same commit as an
unavoidable target rename, with a spec amendment note and a full
acceptance re-green.

## Dev plan

1. **Stage 1 — token core**: `StatusTokens.kt` (tables, severity,
   resolvers) + `RelativeTime.kt`, pure JVM, unit-tested before any UI.
2. **Stage 2 — cluster card**: `MetaGrid` + `StatusPill` on cluster list
   rows — DONE 2026-11-02. Deviation from draft text: server/context stay
   as the existing tonal nav buttons (they are tap targets, not metadata);
   the VERSION/ADDED grid carries probe version + `RelativeTime` "added".
   `StatusPill(ProbeStatus.resolve(status))` replaces the ad-hoc `v:`/
   "Unknown" text (Unreachable/Invalid keep their exact copy as pill
   labels). Compose tests `StatusPillTest`/`MetaGridTest`; suite 339
   green; device-verified pill + `v1.36.4` + `9s ago`. Driver amendment
   (Q5 relax, same commit): new `probe-chip-rendered` step waits for the
   parsed version to render on the card after `probe-hit-stub-with-token`
   — makes the spec 03 stage 11 chip render an acceptance assertion and
   removes the async-probe race that starved the screenshot monitor.
   Re-green = full two-leg chain run (below).
3. **Stage 3 — resource list**: DONE 2026-11-02. `resolveStatus(obj)`
   kind-dispatcher runs at extraction and is carried on
   `ResourceSummary.statusToken` (single source of truth; data→ui.status
   import is a deliberate single-module decision). Trailing `StatusPill`
   on pod-like rows (row `primary` string is suppressed when it equals
   the token label so states never print twice); a `HealthBar` plus an
   `All/Healthy/Pending/Failing` `FilterChip` row for `STATUS_KINDS`
   kinds only (Q2; "Failing" chosen to stay text-unique from the row
   pills for driver/test targeting); a sort `ModalBottomSheet`
   (Name / Newest / Severity)
   behind a top-bar action; label chips (Q3) are an `AssistChip` rail
   that toggles AND-joined server-side `labelSelector` (new `?labelSelector=`
   query param on the list URL). Pure logic in `ResourceFiltering.kt`
   (sort/filter/bucket-count/topLabels) — unit-tested; VM tests cover
   chip/sort state and selector propagation; gateway test pins the URL;
   Robolectric test covers pills/health-bar/chips render + interaction.
   Sort defaults to NAME (matches the API's name ordering). Acceptance
   re-green required at this stage. Driver amendment (Q5 relax, same
   commit): after the infinite-scroll swipe the new chip rail can leave
   `web-1` out of the viewport — the driver now scrolls back toward the
   list top (full-height swipe inside the list area, never over the
   chips) before tapping it; the flow now also settles on the cluster
   list for 4 s at the end so the screenshot sidecar gets a
   deterministic clean window (probe state cached, no toast).
4. **Stage 4 — detail + YAML sheet**: DONE 2026-11-02. Detail SUMMARY
   gains an identity header above the (untouched, driver-targeted)
   spec 04 key/value rows: `StatusPill(resolveStatus(obj))` for
   `STATUS_KINDS`, a horizontally-scrollable `conditionChips(obj)` pill
   rail (`status.conditions[]` -> type-labelled good/red/gray tokens),
   and a `MetaGrid` (KIND / API / CREATED via `RelativeTime`). A
   "YAML sheet" `ModalBottomSheet` quick-peek (Q4: sheets for YAML
   only) renders the document with a line-number gutter and Copy;
   opening it from the summary tab, auto-dismissed on tab switch. The
   YAML tab remains the primary surface the driver uses. The ViewModel
   gained `ensureYaml()` so the sheet can render the document without
   switching to the YAML tab. Pills are queried in tests via their
   merged contentDescription (Compose hides child text nodes under
   merged semantics). Pure `conditionChips` unit tests + Robolectric
   tests for header, chips, and sheet gutter. Acceptance re-green
   required (no driver change needed: all new labels avoid the
   driver's exact-match targets).
5. **Stage 5 — logs parity**: DONE 2026-11-02. `LogScreen` already had the
   Wrap toggle (spec 05); amended here: wrap now **defaults off** and both
   Wrap and TS toggles persist for the app **session only** via
   `LogDisplaySession` (process-statics like `LogNavPayloads`, never
   persisted to disk; `reset()` for tests). Header shows the newest
   line's relative age as a muted subtitle ("last line 2m ago") under the
   pod·namespace title via `RelativeTime.parse` (loose RFC3339, fractional
   seconds tolerated); the subtitle is omitted when the newest line has no
   timestamp. Q5 driver amendment: the spec05 timestamps step now toggles
   TS back off after the follow step, so later log screens (container
   switch, drop, 403) keep their exact unprefixed row-text asserts —
   session persistence made the earlier TS toggle leak into those screens.
   Acceptance re-green required.
6. **Stage 6 — polish**: browse grid readiness pills if Q2 allows, docs
   sync (specs 04/05/08 cross-links), full acceptance + screenshot
   regeneration, store metadata wording review.

## Testing

- Stage 1: exhaustive JVM tests — every token table key resolves, severity
  ordering matches spec, resolvers handle missing/unknown/nested fields;
  `RelativeTime` with a fixed-instant clock (seconds/minutes/hours/days/
  months/years boundaries, future timestamps, UTC).
- Stages 2–5: Compose semantics tests per screen (pills expose content-desc
  "Running", "Ready"; `MetaGrid` renders uppercase labels; `HealthBar`
  segment count equals non-zero bucket count; `YamlViewer` line-number
  gutter matches line count; wrap toggle flips `maxLines`).
- Existing suites: full unit suite green; lint + assembleDebug green per
  stage; acceptance chain re-greened at stages 2–6 (driver untouched unless
  Q5 relax is invoked); `tools/fdroid/capture-screenshots.sh` re-run with
  vision verification at stage 6.

## Acceptance criteria

- Every pod row, workload card, and cluster row shows a `StatusPill` whose
  token is produced by `resolve(...)`, never an inline string match.
- Unknown/raw values render the Unknown token (no crash, no blank).
- Health bar renders for pod-like lists, segments proportional to bucket
  counts, omitted when total is zero.
- Resource list supports search + namespace + status + label filtering and
  sort by name/age/status/restarts; chip counts visible.
- YAML opens in a bottom sheet with line numbers; delete flow unchanged
  unless Q4 decides otherwise.
- Log screen offers wrap toggle; default off; long lines ellipsize when
  off and soft-wrap when on.
- Relative times render "N ago" styles with ISO fallback >365d.
- No new dependencies; FOSS gate + publish gates still pass; dynamic color
  and light/dark both render all new components (screenshot-verified).
