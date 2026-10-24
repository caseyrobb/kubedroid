# AGENTS.md

kubedroid: a Kubernetes client for Android
This is a **spec-driven project**: `docs/README.md` and `docs/00-project-overview.md` define the workflow.
`docs/01-project-architecture.md` defines the architecture.  Read them before writing code.

## Spec workflow

- New feature: spec first (`docs/NN-short-description.md`), then code. Specs must stay in sync with the code.
- Change to existing code: update or supersede the relevant spec *before* adjusting code.
- Spec frontmatter: a required `description` (one-sentence summary of the doc) plus `status: proposed | active | superseded` (`superseded` can optionally name a replacement doc via `replacement: <doc name>` or `replacement` with a list of doc names). If frontmatter is missing, assume `proposed` state.
- Every spec must have a `Testing` section that clearly describes test coverage for the specification.
- If a `proposed` spec is longer than 250 lines of text, it MUST have a "Dev plan" section with a staged implementation plan.
- If a spec contains an "Amendments to previous spec docs" section, it MUST have a "Dev plan" section with those amendments as stage 1.
- If a spec is being amended directly, it MUST either create a Dev plan section if none existed, or append new stages to the existing plan.
- Every spec should have `Configuration` and `Acceptance criteria` sections. `proposed` specs may carry `Open questions`, but all must be answered before the status flips to `active`.
- A feature is not complete without reasonably comprehensive unit tests.

## Commands

- No linter, formatter, typechecker, or CI is configured. Do not add or invent one without being told to.

## Layout and wiring

- Flat `src/` layout.
