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
   rows (SERVER/CONTEXT/VERSION grid, probe token, last-probed relative
   time). Re-green acceptance + regenerate store shot 2.png.
3. **Stage 3 — resource list**: status column as `StatusPill`, `HealthBar`
   under the app bar for pod-like kinds, sort sheet, status filter chips
   (client-side), label chips per Q3 decision.
4. **Stage 4 — detail + YAML sheet**: `YamlViewer` sheet, detail SUMMARY
   via `MetaGrid`, condition pills.
5. **Stage 5 — logs parity**: wrap toggle (+ session persistence), relative
   timestamps in header.
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
