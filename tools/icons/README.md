# Kubernetes icon pipeline (spec 08)

Official resource icons from the Kubernetes community repo, converted to
Android VectorDrawable. Q2A human decision (2026-10-27): one-time conversion,
converted XML under `src/main/res/drawable/` is the tracked artifact; this
file records the exact pipeline for re-runs.

## Source

- Repo: https://github.com/kubernetes/community/tree/main/icons
- Set: `svg/resources/unlabeled` (30 files, `pod.svg` .. `vol.svg`)
- Fetched 2026-10-27 from `main` into `svg/` (raw.githubusercontent.com).
- `LICENSE` (Apache-2.0) vendored from the repo root; the set is dual-licensed
  Apache-2.0 **or** CC-BY-4.0. The Kubernetes logo remains a Linux Foundation
  trademark (LF trademark-usage guidelines apply).

## Normalization (svg-normalized/)

Upstream art is Inkscape output. Two shapes defeat the converter and are
fixed by `normalize.py`-style preprocessing (applied 2026-10-27, kept as
committed `svg-normalized/` files):

1. Fills expressed only inside `style="..."` strings are promoted to SVG
   presentation attributes (`fill=`, `stroke=`, ...).
2. `<g transform="matrix(a,0,0,d,e,f)">` is rewritten to converter-friendly
   `scaleX/scaleY/translateX/translateY` attributes (all upstream matrices in
   this set are diagonal; the pass hard-fails on non-diagonal ones).

## Conversion (pinned)

- Tool: npm `svg2vectordrawable` **2.9.1** (CLI `s2v`) — Ashung/svg2vectordrawable.
- Runtime: Node **22.14.0** (user-local tarball on the build host; not a
  project dependency).
- Command (run on devbox from a dir containing `svg2/`):

      npm install svg2vectordrawable@2.9.1
      ./node_modules/.bin/s2v -f svg2 -o out

- Output files were renamed to `ic_k8s_<name>.xml` (hyphen `c-role` becomes
  `c_role`; Android resource names forbid hyphens).

## Known converter gaps

- The two files needing matrix decomposition above (`hpa.svg`, `psp.svg`)
  convert to empty vectors without the normalization pass — always convert
  from `svg-normalized/`, never from `svg/`.
- `ic_k8s_node.xml` is NOT from this pipeline: the upstream set has no node
  icon, so it is the Material Icons "dns" glyph (Apache-2.0) hand-committed
  in Kubernetes blue.
