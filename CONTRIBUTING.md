# Contributing to kubedroid

Thanks for your interest! kubedroid is developed **spec-first**: behavior
changes are written down before they are coded, and every feature ships
with unit tests.

## The spec workflow

1. Propose your change in an issue first (especially UX or behavior
   changes) so the design can be discussed cheaply.
2. Substantial features get a numbered spec in `docs/` (see
   `docs/README.md` for the conventions: frontmatter, Testing /
   Configuration / Acceptance criteria sections, Dev plan).
3. Code lands with reasonably comprehensive unit tests
   (`./gradlew test`) and passes lint (`./gradlew lintDebug`).

## Ground rules

- Kotlin, flat `src/` layout, Jetpack Compose UI, Hilt DI.
- The Kubernetes client library is `io.kubernetes:client-java` — do not
  add Fabric8 or raw-HTTP alternatives; new network paths ride the
  authenticated OkHttp stack from spec 03.
- Never log credentials, tokens, or private keys (existing redaction
  policy applies to new code too).
- User-visible strings are pinned by the acceptance driver: when you
  change UI text, the acceptance steps in `tools/acceptance/` must be
  updated in the same change and re-run on the supported emulator matrix
  (maintainers run this; mention the affected steps in your PR).
- No exec, port-forward, or shell features — this is a read-focused
  client, deliberately.

## Pull requests

- One concern per PR; link the spec section or issue it implements.
- Run `./gradlew test lintDebug assembleDebug` and note the result.
- Sign off commits (`git commit -s`) with your real name/email.

## What we will not merge

- Features that bypass the on-device Keystore credential storage.
- Dependencies with incompatible licenses (MIT/Apache-2.0/CC-BY family
  only; check with us first).
