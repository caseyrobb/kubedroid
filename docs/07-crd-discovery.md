---
description: Dynamic discovery of CustomResourceDefinitions from the cluster and browsing of their custom resources — CRD list screen, CR lists with additionalPrinterColumns, dynamic catalog entries that flow through the spec 04 list/detail and spec 06 edit/delete paths unchanged.
status: active
---

# Spec 07 — CRD discovery and custom resources

Spec 04 froze the browsable type set to core + apps/v1 + batch/v1 via the
static `ResourceCatalog` (docs/01 Deferred: "CRD discovery / CR listing").
This spec removes that limitation for custom resources: the app discovers
`CustomResourceDefinition`s from the cluster, lists them, and browses their
instances with the same list → detail → YAML → edit/delete flow built for
built-in types. No new dependencies.

## Goals

* **CRD discovery**: `GET /apis/apiextensions.k8s.io/v1/customresourcedefinitions`
  (paged like every other list), parsed into dynamic catalog entries:
  `group + version + plural + kind + scope` from `spec.group`,
  `spec.versions[].name`, `spec.names.plural/kind/listKind`,
  `spec.scope == "Namespaced"`.
* **Browse grid entry point**: one "Custom Resources" card in a new
  `CatalogGroup.CUSTOM`; tapping it opens the CRD list screen.
* **CRD list screen**: one row per CRD — `metadata.name` (plural.group) as
  primary, `spec.group` + kind as secondary, served-version count and scope
  in the status line. Same paging/namespace-free behaviour as built-in
  cluster-scoped lists, plus manual refresh.
* **CR lists**: tapping a CRD opens the existing `ResourceListScreen` with a
  **dynamic entry** (same `CatalogEntry` shape, resolver-provided instead of
  static). Rows use the selected version's `additionalPrinterColumns`
  (priority 0) as the secondary line, evaluated with a dotted-path jsonPath
  subset; fallback to name/namespace/age when the CRD declares none.
* **CR detail**: existing detail screen; Summary tab renders a generic
  fallback fact set (see UI); YAML tab, Copy, **edit and delete work on CRs
  unchanged** (spec 06 identity guards are type-agnostic).
* **RBAC parity**: 403 on the CRD list or a CR list surfaces the same
  Forbidden copy + namespace-hint escape hatch as spec 04.
* **Resolver, not table**: screens and repository resolve entries through a
  `CatalogResolver` (static table first, then the discovered-entry cache)
  instead of calling `ResourceCatalog.find` directly.

## Non-goals (deferred, do not implement here)

* Create / apply / patch of any resource (spec 06's ruling stands:
  create-from-YAML is permanently out of scope).
* CRD schema rendering (`spec.versions[].schema.openAPIV3Schema`) — the CRD
  YAML tab shows it raw; no form rendering.
* Watch (lists stay pull/paging; also covers "CRDs appearing while open").
* Label/field selectors; shortNames in the search box.
* Array/range/escaped jsonPath expressions — the evaluator supports dotted
  paths only (`.status.phase`, `{.spec.replicas}`); unsupported expressions
  degrade the column to `-`, they never break the row.
* Subresources beyond what spec 05/06 already cover (`/log` only for pods).
* **Delete/replace of the CRD object itself** — CRDs are read-only for now
  (human decision 2026-10-26, Q6); only their CR instances are mutable.
* Aggregated/legacy API discovery (`GET /apis` catalogue browsing) — CRDs
  only.

## Amendments to previous spec docs

* `docs/04-resource-browsing.md` — the "frozen table" wording and the grid
  description gain the `CUSTOM` group card; `ResourceListScreen` /
  `ResourceDetailScreen` entry resolution moves from
  `ResourceCatalog.find` to `CatalogResolver`.
* `docs/01-project-architecture.md` — CRD discovery leaves **Deferred / not
  yet built**; package layout gains `data/kubernetes/CrdDiscovery.kt` +
  resolver wiring and `ui/crd/`.

## Data layer

### Endpoints

* CRD list: `/apis/apiextensions.k8s.io/v1/customresourcedefinitions`
  (all-namespaces is meaningless — CRDs are cluster-scoped).
* CR list: `/apis/{group}/{version}/{plural}` and namespaced variant, built
  from the **dynamic** entry via the existing `listPath`/`itemPath` logic
  (they already take a `CatalogEntry`, not a table row).

### `CrdDiscovery` (`data/kubernetes`)

Parses a CRD object (unstructured map) into:

```kotlin
data class CrdDefinition(
    val name: String,            // metadata.name, plural.group
    val group: String, val kind: String, val listKind: String,
    val plural: String, val singular: String?, val shortNames: List<String>,
    val namespaced: Boolean,
    val servedVersions: List<String>,      // spec.versions[] served=true, order kept
    val storageVersion: String?,           // spec.versions[] storage=true
    val printerColumns: Map<String, List<PrinterColumn>>, // per version
)
data class PrinterColumn(
    val name: String, val type: String, val jsonPath: String,
    val priority: Int, val description: String?,
)
```

and `CatalogEntry.from(crd, version)` builders — `displayName = "<Kind>s"`
(listKind pluralised from `kind`), `statusExtractor` = printer-column
renderer with the age fallback. Malformed CRDs (missing `spec.group`,
`spec.names.plural`, no served version) are **skipped**, never fatal —
one bad CRD must not blank the whole list.

`PrinterColumnEvaluator`: renders `{.a.b.c}` / `.a.b.c` dotted paths against
the unstructured map; scalar → text, bool/number → text, absent → `-`.
Everything else (arrays, objects, filters) → `-`.

### `CatalogResolver` (`data/kubernetes`)

* `suspend fun resolve(connectionId, apiVersion, resource): CatalogEntry?`
  — static `ResourceCatalog.find` first, then the per-connection discovered
  cache.
* `suspend fun discovered(connectionId, refresh: Boolean): ResourceResult<List<CrdDefinition>>`
  — cached after first fetch; `refresh = true` re-fetches (manual refresh
  button, post-mutation invalidation reuse from spec 06).
* Cache is in-memory only (process lifetime); no persistence, no TTL
  (Open question Q1).

### `CrdRepository` (`data/repository`)

Owns the gateway call + `ResourceError` mapping (403 on CRDs is Forbidden
with `customresourcedefinitions` named — reuses the verb-copy helper),
feeds `CatalogResolver`. The CR list/detail/mutations keep going through
`ResourceRepository` unchanged — it just needs resolver-injected entries.

## UI

### Navigation (extends spec 02 shell, Nav3)

* `BrowseScreen`: new `CatalogGroup.CUSTOM` section with a single
  "Custom Resources" card → `CrdListKey(connectionId)`.
* `CrdListScreen` (`ui/crd`): TopAppBar + Refresh (core icons only —
  material-icons constraint), paged rows via the existing paging machinery,
  row tap → `ResourceListKey(connectionId, apiVersion, resource)` with the
  CRD's group/version/plural (same key type — the resolver makes it live).
* CR rows → existing `ResourceDetailKey` flow; delete/edit affordances come
  from spec 06 automatically (caution line: none for unknown kinds).

### `CrdListScreen` (`ui/crd`)

States mirror spec 04 (loading / error / empty / list). Row:
`metadata.name` primary; `spec.group · Kind` secondary; status line
`"<n> served version(s)"` + scope (`cluster` / `namespaced`). Version
picker: when the tapped CRD has > 1 served version, the CR list screen shows
a version chip row (TextButtons — icon-set constraint) above the rows; the
default selection is the storage version when served, else the first served
(Open question Q2).

### CR detail Summary fallback

For dynamic entries the Summary tab renders: Name, Namespace (when
namespaced), Created (age), API version, Kind; plus `status.conditions`
types with True status as a secondary line when present. YAML tab remains
the source of truth; Secrets-style redaction is irrelevant for CRs but
reveal-before-edit stays (it keys on `kind == "Secret"`, so CRs skip it).

## Additional dependencies

None — OkHttp gateway (via `ApiClientProvider`), Gson unstructured maps,
`ResourceGateway.execute` paging and the spec 06 mutation path are reused
as-is.

## Configuration

No new build config. `gradle/libs.versions.toml` unchanged. Acceptance stub
gains CRD routes (stage 4) behind the existing bearer gate.

## Testing

Unit (JVM, devbox gate):

* `CrdDiscoveryTest` — CRD JSON → `CrdDefinition` (namespaced + cluster,
  multi-version, printer columns, shortNames), malformed-CRD skip rules,
  served/storage selection, per-version column maps.
* `PrinterColumnEvaluatorTest` — dotted paths incl. numeric/bool values,
  missing keys → `-`, unsupported expressions (arrays, filters) → `-`,
  braces stripping, empty jsonPath → `-`.
* `CatalogResolverTest` — static-hit precedence, discovered hit, cache
  populate/refresh semantics, malformed entry never shadows a static one.
* `CrdRepositoryTest` — canned-gateway: 200 paging + continue token, 403
  Forbidden naming `customresourcedefinitions`, network error mapping,
  single-shot (no retry) on discovery — reads keep the spec 03 retry
  policy; verify against existing gateway behaviour, not a new one.

Compose/Robolectric:

* Browse grid shows the Custom Resources card; CRD list renders fixture rows
  + refresh re-issues GET; empty state; 403 escape hatch.
* CR list from a discovered CRD: printer-column secondary line renders;
  fallback line when no columns; version chip switch re-fetches
  (`seenRequests` assertion, request-aware harness from spec 06).
* CR detail: Summary fallback facts present; YAML tab renders; edit +
  delete affordances reachable for a CR (dialog tags from spec 06).

Emulator acceptance (stage 4): stub gains CRD list + two CR kinds
(one namespaced with printer columns incl. a cluster-scoped CRD), new steps
`spec07-browse-card`, `spec07-crd-list`, `spec07-cr-columns` (printer value
visible), `spec07-cr-detail-edit` (save a CR edit via PUT), `spec07-crd-403`.
Full chain green on API 37 **and** API 26, existing steps untouched.

Hardware build (stage 5): after activation, produce the installable APK
(`assembleDebug`, debug-key signed) on the devbox, rsync it back to the
GB10, and hand over the absolute path + `adb install -r` instructions.

## Acceptance criteria

* Every goal demonstrable on the acceptance chain on API 37 **and** API 26.
* One malformed CRD in the stub list never blanks the CRD list (unit +
  acceptance both assert).
* All Open questions answered before `status: active`.
* docs/04 + docs/01 amendments applied (stage 1); docs/01 Deferred updated.
* No new third-party dependency.

## Open questions

All answered 2026-10-26 (human decision 2026-10-26), resolved as follows:

* **Q1 — Discovery freshness:** in-memory cache populated on first use;
  manual Refresh re-fetches; no TTL. *(as recommended)*
* **Q2 — Default CR version for multi-version CRDs:** storage version when
  served, else first served; chip switcher visible only when > 1 served
  version. *(as recommended)*
* **Q3 — Printer columns:** render priority-0 columns only (kubectl default
  parity). *(as recommended)*
* **Q4 — CR Summary tab:** generic fallback fact set (Name, Namespace,
  Created, API version, Kind + True condition types); YAML stays source of
  truth. *(as recommended)*
* **Q5 — CRD search/filter box:** yes — client-side name/shortName
  substring filter. *(as recommended)*
* **Q6 — Delete of a CRD itself:** **CRDs stay read-only for now**
  (overrides the recommendation) — no delete affordance on the CRD list;
  CR *instances* still mutate through the spec 06 path.

## Dev plan

Stages are sequential; each ends green on the devbox gate
(`testDebugUnitTest lintDebug assembleDebug`).

1. **Stage 1 — amendments + discovery data layer**: apply the docs/04 and
   docs/01 amendments; `CrdDiscovery`, `PrinterColumnEvaluator`,
   `CatalogResolver`, `CrdRepository` + unit tests.
   *Complete 2026-10-26 (`622ea5e`); 268 unit tests (+21).*
2. **Stage 2 — CRD list UI**: `CrdListScreen`, navigation keys, Browse
   grid CUSTOM card, Compose tests.
   *Complete 2026-10-26 (`8c26ba1`); 275 unit tests (+7).*
3. **Stage 3 — dynamic CR browsing**: screens/repository move to
   `CatalogResolver`; CR list printer columns + version chips; CR detail
   Summary fallback; Compose tests.
   *Complete 2026-10-26 (`18ba184`); 281 unit tests (+6).*
4. **Stage 4 — acceptance**: stub CRD routes + fixtures, five `spec07-*`
   driver steps, full chain API 37 + 26.
   *Complete 2026-10-26 (`19d0cf1`, fixes `7b16fc7`); ACCEPTANCE_PASS
   46/46 on API 37 and API 26, zero failures. Activation findings: the
   stub needs a GET-single-CR route or the CR Summary renders empty;
   driver grid-scroll and SAF-picker dismissal need substring/resumed
   matching (see acceptance tooling comments).*
5. **Stage 5 — activation + hardware build**: flip `status: active`,
   docs/01 sync, commit/push, wiki milestone, then the installable APK
   hand-off.
   *Complete 2026-10-26: activated with docs/01 synced to specs 02–07;
   hardware APK built on the devbox and delivered to the GB10 for
   real-device testing.*
