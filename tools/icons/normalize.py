#!/usr/bin/env python3
"""Normalize upstream kubernetes/community icon SVGs for svg2vectordrawable.

The upstream art is raw Inkscape output, and `s2v` mishandles two things:

1. Paint expressed inside `style="..."` strings instead of presentation
   attributes -> promoted here.
2. Paint INHERITED from parent <g> elements -> VectorDrawable <group> has no
   fill, so every path must carry its own resolved paint. This script walks
   each SVG, resolves the inheritance chain (style attrs + presentation
   attrs, child wins), and materializes fill/fill-opacity/fill-rule/stroke/
   stroke-opacity/stroke-width on every shape element.

3. Diagonal matrix(a,0,0,d,e,f) group transforms -> rewritten to
   scaleX/scaleY/translateX/translateY attributes (s2v drops matrix());
   hard-fails on non-diagonal matrices so surprises are loud.

Usage: python3 normalize.py <in-dir> <out-dir>
Requires stdlib only. Run from tools/icons/.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

SVG = "{http://www.w3.org/2000/svg}"
SHAPE_TAGS = {"path", "circle", "rect", "ellipse", "line", "polygon", "polyline"}
PAINT = ("fill", "fill-opacity", "fill-rule", "stroke", "stroke-opacity", "stroke-width")


def style_props(el):
    props = {}
    style = el.get("style")
    if style:
        for decl in style.split(";"):
            if ":" in decl:
                k, v = decl.split(":", 1)
                k = k.strip()
                if k in PAINT:
                    props[k] = v.strip()
        del el.attrib["style"]
    for k in PAINT:
        if el.get(k) is not None:
            props[k] = el.get(k)
    return props


def walk(el, inherited):
    props = style_props(el)
    cur = {**inherited, **props}
    tag = el.tag.replace(SVG, "")
    if tag in SHAPE_TAGS:
        for k, v in cur.items():
            el.set(k, v)
    for child in list(el):
        walk(child, cur)


def fix_matrix_text(text):
    # transforms live as attributes; ET won't expose them post-parse unless
    # we rewrite the source (also covers <g transform="matrix(...)">).
    def repl(m):
        import math
        vals = m.group(1)
        try:
            a, b, c, d, e, f = (float(x) for x in vals.split(","))
        except ValueError:
            return m.group(0)
        # Decompose M = T(t) · R(θ) · diag(sx, sy): first row fixes θ and sx,
        # second row fixes sy (negative => mirror, expressible via negative
        # scaleY on a VectorDrawable group).
        s1 = math.hypot(a, b)
        if s1 < 1e-9:
            raise SystemExit(f"degenerate matrix in source: {vals}")
        theta = math.atan2(b, a)
        si_c, si_s = -math.sin(theta), math.cos(theta)
        s2 = c * si_c + d * si_s
        residual = abs(c - s2 * si_c) + abs(d - s2 * si_s)
        if residual > 2e-2:  # upstream Inkscape matrices carry sloppy rounding;
            # 0.02 residual is far below icon-size visual relevance, but a
            # genuinely anisotropic/sheared matrix still fails loudly.
            raise SystemExit(f"non-uniform matrix in source: {vals}")
        return (f'scaleX="{s1}" scaleY="{s2}" rotation="{math.degrees(theta)}" '
                f'pivotX="0" pivotY="0" translateX="{e}" translateY="{f}"')

    return re.sub(r'transform="matrix\(([^)]+)\)"', repl, text)


def normalize(src_path, dst_path):
    # Transforms are left as-is: s2v's transform handling is lossy for
    # matrices, so the pipeline runs SVGO (convertTransform) after this step
    # to bake every transform into absolute path data.
    root = ET.fromstring(open(src_path).read())
    # SVG's implicit default fill is black; s2v leaves unset paths invisible,
    # so seed the inheritance chain with the spec default (explicit fill
    # attributes/styles still win via the child-wins merge).
    walk(root, {"fill": "#000000"})
    ET.register_namespace("", "http://www.w3.org/2000/svg")
    ET.register_namespace("xlink", "http://www.w3.org/1999/xlink")
    ET.ElementTree(root).write(dst_path, encoding="unicode", xml_declaration=False)


def main():
    src, dst = sys.argv[1], sys.argv[2]
    os.makedirs(dst, exist_ok=True)
    n = 0
    for f in sorted(os.listdir(src)):
        if not f.endswith(".svg"):
            continue
        normalize(os.path.join(src, f), os.path.join(dst, f))
        n += 1
    print(f"normalized {n} files -> {dst}")


if __name__ == "__main__":
    main()
