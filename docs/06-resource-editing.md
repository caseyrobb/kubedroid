---
description: Proposed spec 06 — mutate resources from the app: edit a resource's YAML with replace semantics and optimistic concurrency, plus delete with confirmation, for all catalog types, wired into the spec 04 detail/list surfaces.
status: proposed
---

# Spec 06 — Resource editing and deletion

Specs 02–04 shipped read-only browsing: the detail screen renders YAML but
cannot change anything. This spec adds the first **write** path: edit a
resource's YAML and delete resources, for every type the spec 04
`ResourceCatalog` exposes. It is the first item of the deferred list recorded
in `docs/01` (implementation order step 5).

## Goals

1. **Edit**: from `ResourceDetailScreen`, enter an editable YAML mode
   (SnakeYAML round-trip via the existing `YamlRenderer` dependency);
   validate locally, then send one **replace** request (PUT semantics).
2. **Optimistic concurrency**: every edit carries the `resourceVersion`
   read at load time as a precondition; a 409 Conflict is surfaced with a
   reload-and-retry path, never silently overwritten.
3. **Delete**: delete action on the detail top bar and in the list row
   overflow, with a confirmation dialog naming kind, name, and namespace.
4. **RBAC-aware errors**: 403 copy names the exact verb/resource
   (`pods delete`, `deployments update`) following the spec 04/05
   `ResourceError.userMessage()` pattern.
5. **Post-mutation consistency**: after a successful edit or delete the
   list and detail panes refresh (list invalidated; detail shows the new
   object or pops back on delete).
6. All of this works on both adaptive layouts (inline detail pane and
   pushed detail screen) and on API 26 and 37.

## Non-goals (deferred, do not implement here)

* **Create** a brand-new resource from YAML — permanently out of scope
  (human decision 2026-10-26: "will probably never be a feature").
* Structured forms (label editors, replica steppers, image setters) — the
  only edit surface here is raw YAML.
* Multi-document YAML, diff view, or three-way merge on conflict.
* `PATCH` of arbitrary field subsets / server-side apply.
* Scale/rollback/restart shortcuts (Deployment-specific verbs).
* Port-forward, exec, and any subresource write (`pods/log` stays GET-only
  per spec 05).

## Amendments to previous spec docs

* `docs/04-resource-browsing.md`: the detail screen gains `Edit` and
  `Delete` top-bar actions, and list rows gain a `Delete` overflow entry;
  the "read-only" wording of spec 04 is superseded for these actions.
  Superseded fragment: "detail YAML is read-only (copy only)".

## Dev plan

Required per repo rules for this amended spec; stages are sequential, each
ends with the devbox gate (`testDebugUnitTest lintDebug assembleDebug`).

1. **Stage 1 — amendments + mutation gateway/repository**. Apply the
   docs/04 amendment (supersede the read-only wording; docs/01 Deferred
   update). `ResourceGateway.replace()` / `.delete()`, repository methods,
   error mapping (`Conflict`, `ForbiddenMutation`), `StripForEdit` helper
   (client-side field stripping), unit + MockWebServer tests.
2. **Stage 2 — edit UI**: YAML editor mode on `ResourceDetailScreen`
   (Edit → edit/save/cancel, local validation, save with conflict
   handling), Compose tests.
3. **Stage 3 — delete flows**: confirmation dialog shared by detail and
   list, invalidation wiring, Compose tests.
4. **Stage 4 — acceptance**: stub PUT/DELETE routes with etag/409
   behaviour, new `spec06-*` driver steps, full chain on API 37 + 26.
5. **Stage 5 — activation**: flip `status: active` once acceptance is
   green and Casey has signed off on the Open questions.

## Data layer

### Endpoints

* Replace: `PUT {catalogPath}/{name}` (same path builder as spec 04
  `ResourceCatalog.logPath` style — add `itemPath`), body = edited JSON
  (converted from YAML via SnakeYAML → existing kotlinx JSON),
  `Content-Type: application/json`.
* Delete: `DELETE {catalogPath}/{name}`, no body, default propagation
  policy (server default).

Both ride the same authenticated, CA-pinned OkHttp path derived from
`ApiClientProvider` (spec 03). No retries: mutations are **never** retried
automatically, even on network failure (an ambiguous response must surface
for the user to decide, not silently re-apply).

### `ResourceGateway` additions (`data/kubernetes`)

```kotlin
suspend fun replace(ref: ResourceRef, bodyJson: String): ResourceResult<Unit>
suspend fun delete(ref: ResourceRef): ResourceResult<Unit>
```

* `replace` sends `metadata.resourceVersion` in the body (precondition);
  the gateway does not add or alter it beyond what the editor kept.
* Status mapping reuses spec 04 mapping plus new branches:
  * 409 → `ResourceError.Conflict(kind, name)` — "someone else changed it".
  * 422 → `ResourceError.Invalid(reason)` — server rejection with the
    apiserver `message` surfaced verbatim when present.
  * 403 → `ResourceError.ForbiddenVerb(ref, verb)` — copy names
    `update`/`delete` on the exact resource.
  * 404 → `NotFound` (already existed; also covers delete-after-load).

### `EditSanitizer` (`data/kubernetes`)

Before showing YAML for editing *and* before PUT, strip server-managed
fields the user cannot own, so the common edit path doesn't 422:

* `metadata`: remove `creationTimestamp`, `uid`, `managedFields`, `resourceVersion`
  only for display; **PUT re-attaches the loaded `resourceVersion`**.
* `status`: dropped from the editable document entirely; dropped again
  before PUT even if the user pasted one back.
* Identity guard: `kind`, `apiVersion`, `metadata.name`,
  `metadata.namespace` must match the loaded object at save time, or the
  save is blocked locally with specific copy (prevents accidental
  cross-object overwrite).

## UI

### Edit flow (`ResourceDetailScreen`)

* Top bar gains `Edit` (core-set `Icons.Filled.Edit`) and `Delete`
  (`Icons.Filled.Delete`) — core material icons only (docs/01 constraint).
* Edit mode replaces the YAML text area with an editable field
  (`testTag("yaml-editor")`), keeps top-bar context, and swaps actions to
  `Save` / `Cancel` text buttons (Cancel discards; dirty-state back
  press asks "discard changes?").
* Local validation before any network call: SnakeYAML parse must succeed
  and produce a mapping with `apiVersion`/`kind`/`metadata.name`; errors
  show inline under the editor with line context — never a generic error.
* Save button disables while a save is in flight (double-tap = one PUT).
* Conflict (409) → dialog: "This {kind} changed since you opened it" with
  **Reload** (refreshes detail, edit discarded) and **Discard** actions.
  No merge UI.

### Delete flow

* Detail: `Delete` → confirmation dialog naming
  `{kind} {name} in {namespace}` + a caution line for types where
  deletion cascades (Pods, Deployments, StatefulSets, Services, Nodes —
  list lives in code with the catalog entries).
* List: row overflow gains `Delete` (same dialog); row disappears on
  success via list invalidation.
* 404 on delete (already gone) → treat as success with a toast-style
  message line, not an error card.

### States

Loading / error states reuse spec 04 cards; mutation-specific states:
`saving` (button spinner), `save-error` card with `userMessage()` +
`Retry` (re-PUTs the same body — allowed because the precondition makes a
duplicate 409, not a double-apply), `conflict` dialog as above.

## Additional dependencies

None. SnakeYAML 2.5 (spec 02), kotlinx-serialization JSON (spec 02), and
core material icons already pinned. If a YAML syntax-highlight editor is
later desired it must be raised as its own dependency decision.

## Configuration

No new build configuration. Both verbs honour the same per-context
`ApiClientProvider` resolution as reads; no new feature flags. The stub
server (`tools/acceptance/accept-server.py`) gains in-memory mutable state
for its fixture objects.

## Testing

Unit (JVM/Robolectric, devbox gate):

* `ResourceGatewayMutationTest` — MockWebServer: PUT success, PUT 409 →
  `Conflict`, PUT 422 message surfaced, DELETE 200/404 mapping, 403 verb
  copy, no-retry assertion (network-failure path issues exactly one
  request per mutation).
* `EditSanitizerTest` — field stripping incl. pasted-back `status`,
  resourceVersion re-attach, identity-guard rejection for each of the
  four identity fields, YAML round-trip stability (render → sanitize →
  parse → equal map).

Compose/Robolectric (testTags above):

* Edit happy path (edit → save → reload shows new value), local parse
  error blocks network (zero-request assertion), cancel, dirty-back
  prompt, save-in-flight single PUT, conflict dialog Reload.
* Delete confirm/cancel from detail and list, 404-as-success, RBAC error
  card naming the verb.

Emulator acceptance (stage 4): stub gains PUT/DELETE with stored
`resourceVersion` bumping so a stale edit genuinely 409s; new steps
`spec06-edit-save`, `spec06-edit-conflict`, `spec06-edit-invalid-yaml`,
`spec06-delete-detail`, `spec06-delete-list-row`, `spec06-delete-forbidden`.

## Acceptance criteria

* Every goal demonstrable on the acceptance chain on API 37 **and** API 26.
* No mutation is ever auto-retried; asserted by request-count in tests.
* All Open questions answered (done 2026-10-26, recorded above) before
  `status: active`.
* docs/04 amendment applied; docs/01 deferred list updated (edit/delete
  removed from Deferred).

## Open questions

All answered by Casey 2026-10-26 (original recommendations held except
Q1, which is harder than recommended — permanently out of scope):

* **Q1 — Create from YAML:** out of scope for this spec and will probably
  never be a feature. *Moved to Non-goals.*
* **Q2 — Replace semantics:** PUT whole-object replace.
* **Q3 — Editable type set:** all catalog types; RBAC is the only gate;
  no client-side allowlist.
* **Q4 — Delete confirmation:** simple dialog naming kind/name/namespace;
  no typed-name confirmation; cascade caution lines kept.
* **Q5 — Cluster-scoped resources** (Nodes, Namespaces, PVs): allowed,
  same dialog — homelab admin tool, RBAC is the gate.
* **Q6 — Save-error Retry:** re-PUT the same body, allowed as-is (the
  resourceVersion precondition turns a duplicate into a 409, not a
  double-apply).
