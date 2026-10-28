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

## Pipeline (must run in this order)

1. `python3 normalize.py svg svg-normalized` — upstream art is Inkscape
   output and defeats the converter in two ways, fixed here:
   - Paint expressed inside `style="..."` strings is promoted to SVG
     presentation attributes, and paint INHERITED from parent `<g>` elements
     is resolved and materialized on every shape (VectorDrawable `<group>`
     cannot carry fill/stroke; dropped inheritance rendered glyphs fully
     transparent on device — shipped once, fixed 2026-10-28).
   - SVG's implicit default fill (black) is seeded at the inheritance root;
     `s2v` leaves unset paths invisible, unlike real SVG renderers.
2. SVGO — `svgo -f svg-normalized -o flat` — bakes every `transform`
   (Inkscape `matrix()`, rotations, mirrors) into absolute path data.
   REQUIRED: `s2v` silently drops matrix()/complex transforms, which left
   glyph geometry outside the viewport (second pre-fix device bug). Transforms
   must be baked by SVGO, not rewritten into attributes — `s2v` only parses
   `transform=` strings.
3. `s2v -f flat -o out` (npm `svg2vectordrawable` **2.9.1**, CLI from
   Ashung/svg2vectordrawable; runtime Node **22.14.0**, user-local tarball on
   the build host, not a project dependency):

       npm install svg2vectordrawable@2.9.1 svgo
       ./node_modules/.bin/svgo -f svg-normalized -o flat
       ./node_modules/.bin/s2v -f flat -o out

4. Output files are copied as `ic_k8s_<name>.xml` (hyphen `c-role` becomes
   `c_role`; Android resource names forbid hyphens).

## Regression guard

`ResourceCatalogIconInflateTest` asserts every committed vector inflates AND
every path carries fill/stroke with geometry inside the viewport — the exact
two regressions this pipeline shipped before being hardened.

## Known gaps

- `ic_k8s_node.xml` is NOT from this pipeline: the upstream set has no node
  icon, so it is the Material Icons "dns" glyph (Apache-2.0) hand-committed
  in Kubernetes blue.
- Paths with `fill:none` and no stroke survive as invisible paths (legitimate
  hidden layers in the upstream art; the test allows up to 2 per icon).
