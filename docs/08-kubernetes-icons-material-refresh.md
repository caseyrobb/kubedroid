---
description: Official Kubernetes resource icons and a Material 3 Expressive refresh for browse, list, detail, and cluster screens. (Spec 12 layers status tokens/pills on top of this theme; M3 dynamic color and light/dark remain canonical per spec 12 Q1.)
status: active
---

# Spec 08 — Kubernetes icons and Material refresh

kubedroid currently renders every resource type with identical text-only rows;
the browse grid gives a CronJob and a Secret the same visual weight. This spec
adds the official Kubernetes community resource icons to every resource-
identified surface, and refreshes the app onto the latest Material Design
(Material 3 Expressive) component and token usage.

Source of record: `https://github.com/kubernetes/community/tree/main/icons`
(unlabeled resource set; dual-licensed Apache-2.0 or CC-BY-4.0, upstream
`LICENSE` vendored; Kubernetes logo remains an LF trademark subject to the
LF trademark-usage guidelines — attribution required).

## Goals

- Every `CatalogEntry` (static catalog + CRD-derived) shows its official icon
  in the browse grid, list rows, and the detail header.
- Unknown/custom kinds (CRDs, future catalog additions) fall back to the
  official `crd.svg` icon — never a blank or broken image.
- Visual language is current Material 3: expressive components where they
  already exist in the pinned BOM, dynamic color where the platform supports
  it, fixed Kubernetes-blue palette where it does not (GrapheneOS has no
  Material You, and this is a GrapheneOS-first app).
- No change to any user-visible *text*: acceptance steps, `testTag`s, and
  content strings are pinned by the emulator driver (see docs 04-07). Icons
  ride alongside existing labels.

## Non-goals

- Control-plane / infrastructure icon sets (apiserver, kubelet, kubectl) —
  no surface in this app identifies them.
- Labeled icon variants (icon + wordmark images) — text labels are already
  rendered by Compose and stay localizable.
- Animated or themed (monochrome-recolored) icon variants.
- Navigation redesign — the existing NavHost structure stays; this is theming
  and iconography, not IA.
- New screens beyond the attribution surface in Q7.

## What is covered by the official set

`CatalogEntry` gains `iconVector: Int` (a `DrawableRes`). Mapping for the
static catalog (`ResourceCatalog.kt`), using `src/main/res/drawable/ic_k8s_*.xml`:

| kind | asset | | kind | asset |
|---|---|-|---|---|
| Pod | `ic_k8s_pod` | | Service | `ic_k8s_svc` |
| Deployment | `ic_k8s_deploy` | | Ingress | `ic_k8s_ing` |
| ReplicaSet | `ic_k8s_rs` | | ConfigMap | `ic_k8s_cm` |
| DaemonSet | `ic_k8s_ds` | | Secret | `ic_k8s_secret` |
| StatefulSet | `ic_k8s_sts` | | ServiceAccount | `ic_k8s_sa` |
| Job | `ic_k8s_job` | | Namespace | `ic_k8s_ns` |
| CronJob | `ic_k8s_cronjob` | | PersistentVolume | `ic_k8s_pv` |
| HorizontalPodAutoscaler | `ic_k8s_hpa` | | PersistentVolumeClaim | `ic_k8s_pvc` |
| Endpoints | `ic_k8s_ep` | | StorageClass | `ic_k8s_sc` |
| LimitRange | `ic_k8s_limits` | | ResourceQuota | `ic_k8s_quota` |
| NetworkPolicy | `ic_k8s_netpol` | | Role | `ic_k8s_role` |
| ClusterRole | `ic_k8s_c-role` | | RoleBinding | `ic_k8s_rb` |
| ClusterRoleBinding | `ic_k8s_crb` | | Group | `ic_k8s_group` |
| User | `ic_k8s_user` | | CRD / CR / unknown | `ic_k8s_crd` |

Assets kubedroid's catalog doesn't currently list (`psp`, `vol`) ship anyway
so the table is complete for the upstream set; unknown kinds resolve to
`ic_k8s_crd` via a single `catalogIcon(apiVersion, kind)` helper next to
`CatalogResolver`.

## Icon pipeline (proposed, see Q1/Q2)

The upstream art is multicolor SVG (~5-13 KB each, fills `#326CE5`/white).
Plan of record:

1. Vendor the 30 source SVGs + the upstream `LICENSE` (CC-BY-4.0 notice)
   under `tools/icons/svg/` for provenance (never shipped in the APK).
2. Convert each to a Compose-safe `VectorDrawable` XML committed under
   `src/main/res/drawable/`. Conversion is a one-time, reviewed step; the
   converted XML is the tracked artifact (no build-time SVG tool, no runtime
   SVG renderer).
3. `CatalogEntry.iconVector` carries the `@DrawableRes`; `BrowseScreen`,
   `ResourceListScreen` rows, and the detail header render
   `Icon(painterResource(entry.iconVector), contentDescription = kind)`.

## Material refresh (proposed, see Q4/Q5)

Working within the pinned BOM (no new artifacts unless a stage needs one):

- Theme: dynamic color when `DynamicColors.isDynamicColorAvailable()`;
  fallback = fixed palette seeded on Kubernetes blue `#326CE5`. Typography
  and shape tokens follow M3 Expressive defaults (large titles,
  `shapeManager.extraLarge` on cards).
- Components: `PullToRefreshBox` (already in the BOM's material3) on
  resource lists replacing the text Refresh button as primary affordance —
  the button remains as a fallback node so acceptance steps stay green;
  `SegmentedButton` for the namespace filter chip row; list rows gain the
  icon leading slot; empty states get the matching icon at 64 dp.
- Status colors (pod phase, health probe) stay semantic tokens
  (`errorContainer` etc.), unchanged text.

## Design decisions

- human decision (2026-10-27, stage 4 implementation): the SegmentedButton
  lands on the CRD served-version set, not the namespace filter — namespace
  lists are unbounded (dozens of entries) where M3 SegmentedButton is
  designed for 2-5 fixed choices; the namespace scope dropdown is the
  correct expressive-era component there. Version chips keep their
  `version-<v>` testTags and plain-text labels so the acceptance driver
  (which substring-matches version text) stays unchanged.

- human decision (2026-10-27): all open questions answered Option A — Q1
  committed converted vectors; Q2 one-time documented conversion; Q3 official
  multicolor everywhere; Q4 expressive scope = theme tokens +
  PullToRefreshBox + SegmentedButton + icon slots on existing screens; Q5
  dynamic color with KubernetesBlue fallback; Q6 icons on browse tiles,
  list rows, detail header, and cluster cards (CRD/CR use crd.svg); Q7
  NOTICE file + About dialog line.
- Implementation note: Android resource names disallow hyphens, so
  `ic_k8s_c-role` is committed as `ic_k8s_c_role`.
- Text is pinned: no user-visible strings change in this spec (driver +
  acceptance regression must stay green unchanged).
- Multicolor art preserved (not tinted to onSurface) — the icons' meaning in
  the k8s ecosystem is their color; tinting happens only in the 64 dp
  decorative empty-state rendering, per Q3.

## Configuration

- No runtime config. Build inputs: vendored SVGs under `tools/icons/svg/`
  and generated vectors under `src/main/res/drawable/`.
- Theme seed color `#326CE5` lives in `ui/theme/Color.kt` as
  `KubernetesBlue`.

## Additional dependencies

- None at runtime with the committed-vector pipeline (Q1A). If the runtime
  SVG alternative (Q1B) were chosen instead, `com.caverock:androidsvg-aar`
  at the then-current stable would be pinned here — not adopted by default.
- Conversion (offline, one-time, per Q2A): npm **svg2vectordrawable 2.9.1**
  (CLI `s2v`, Ashung/svg2vectordrawable) under Node 22.x, run ad hoc on the
  build host — recorded for provenance, not wired into the build. A
  normalization pass (style-attr promotion + diagonal-matrix decomposition)
  is required first or `hpa`/`psp` convert to empty vectors; the exact
  pipeline is documented in `tools/icons/README.md`. (Earlier draft named a
  nonexistent PyPI package `svg2vectordrawable==2.9.2` — corrected 2026-10-27
  when the PyPI name was found not to exist.)

## Open questions

- Q1 — Icon artifact form. A: convert SVG→VectorDrawable once and commit the
  XMLs (recommended: no runtime dependency, tintable, aapt-valid, ProGuard
  safe). B: ship raw SVGs in assets + runtime render via androidsvg (fresher
  source, one more runtime dep and per-frame parse cost). Option A
- Q2 — Conversion reproducibility. A: one-time committed conversion,
  `tools/icons/README.md` documents the exact command + tool version
  (recommended). B: Gradle task invoking the pinned converter on every build
  (drift-free but slows builds and adds a JVM-python toolchain wrinkle). Option A
- Q3 — Color treatment. A: preserve official multicolor everywhere
  (recommended — ecosystem recognition). B: tint to monochrome `onSurface`
  for visual calm. C: multicolor in browse/detail, monochrome in dense list
  rows. Option A
- Q4 — Expressive scope. A: theme tokens + PullToRefreshBox +
  SegmentedButton + icon slots on existing screens (recommended, test-safe).
  B: additionally migrate buttons/fabs to expressive morphing variants and
  update all top bars to the large-title collapsing pattern (bigger diff,
  higher driver risk). Option A
- Q5 — Dynamic color. A: honor Material You when available, fixed
  Kubernetes-blue seed otherwise (recommended). B: always fixed palette for
  brand consistency across devices. Option A
- Q6 — Where icons appear. A: browse tiles + list-row leading slot + detail
  header + cluster-card leading icon for *all* entries including CRDs (CRD/CR
  use `crd.svg`) (recommended). B: browse tiles only. Option A
- Q7 — Attribution surface. A: `NOTICE` file in repo + small attribution
  line in the cluster-list overflow "About" dialog (recommended; CC-BY-4.0
  is satisfied, no new nav surface). B: full About screen listing artwork
  license (more work, nicer). Option A

## Dev plan

(Required once Qs resolve to `active`; stages below are the working plan.)

**Implemented 2026-10-27** — stages 1-2 in `88f8593`, stages 3-5 in
`be88259`; 306 unit tests green; full acceptance regression (spec 04-07
chains, both emulator APIs) `ACCEPTANCE_PASS` with an untouched driver.
Outstanding: on-device visual confirmation (Pixel 8a Material You, Pixel 7
GrapheneOS fixed-blue identity) with `kubedroid-debug-be88259-spec08-ui.apk`.

1. Stage 1 — Assets: vendor SVGs + license, convert, commit vectors, add
   `iconVector` to `CatalogEntry`, `catalogIcon()` helper, unit test mapping
   every static entry + CRD fallback.
2. Stage 2 — Icon surfaces: browse tiles, list rows, detail header, empty
   states; contentDescription = kind. Compose tests: icon node present per
   kind, fallback renders for CRD.
3. Stage 3 — Theme: dynamic color + KubernetesBlue seed, shape/type tokens.
4. Stage 4 — Expressive components: PullToRefreshBox (keep Refresh button
   node), SegmentedButton namespace filter.
5. Stage 5 — Attribution (per Q7) + full acceptance regression on both
   emulator APIs (must pass with zero driver changes).

## Testing

- Unit: `catalogIcon()` covers every `ResourceCatalog` entry maps to a
  distinct asset name matching a committed drawable; unknown apiVersion/kind
  → `ic_k8s_crd`; drawable existence asserted via a generated manifest test
  (R fields resolved in Robolectric).
- Compose: browse grid shows icon for a static kind; CRD list row shows the
  CRD icon; existing screen tests still pass unchanged (text assertions are
  the regression guard).
- Robolectric: theme factory returns dynamic palette when the shadow reports
  availability and fixed KubernetesBlue palette otherwise (Q5A).
- Emulator acceptance: full regression (spec 04-07 chains, both APIs) must
  pass with **no changes to acceptance.py** — proof text/affordances are
  unpinned-safe. Screenshots saved per stage for human review (Casey).

## Acceptance criteria

- Every catalog kind displays its official icon in browse, list, and detail.
- A CR and its CRD display `crd.svg`; nothing ever shows a broken/missing
  icon.
- The app visibly follows Material You on the Pixel 8a (stock OS) and keeps
  a coherent Kubernetes-blue identity on GrapheneOS Pixel 7.
- Pull-to-refresh works on resource lists; the legacy Refresh button still
  exists and functions.
- Repo contains the upstream `LICENSE` (Apache-2.0), an attribution NOTICE
  covering the dual Apache-2.0/CC-BY-4.0 grant and LF trademark clause, and
  vendored sources; acceptance regression green with an untouched driver.
