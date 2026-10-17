#!/usr/bin/env python3
"""Builds a dark variant of OpenFreeMap's Liberty style, keeping every layer.

OpenFreeMap's own "dark" style drops most detail (no POIs, buildings or landcover), so instead
we take Liberty and remap each colour: lightness is inverted onto a dark range, hue is kept and
saturation toned down. Roads and text get their own ranges so they stay readable.

    python3 tools/make_dark_style.py > app/src/main/assets/liberty-dark.json
"""
import colorsys
import json
import re
import sys
import urllib.request

SOURCE = "https://tiles.openfreemap.org/styles/liberty"

NAMED = {"white": (255, 255, 255, 1.0), "black": (0, 0, 0, 1.0), "transparent": (0, 0, 0, 0.0)}


def parse(c):
    c = c.strip().lower()
    if c in NAMED:
        return NAMED[c]
    if c.startswith("#"):
        h = c[1:]
        if len(h) in (3, 4):
            h = "".join(ch * 2 for ch in h)
        r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
        a = int(h[6:8], 16) / 255 if len(h) == 8 else 1.0
        return r, g, b, a
    m = re.match(r"(rgba?|hsla?)\((.*)\)", c)
    if not m:
        return None
    parts = [p.strip() for p in m.group(2).split(",")]
    a = float(parts[3]) if len(parts) > 3 else 1.0
    if m.group(1).startswith("rgb"):
        r, g, b = (float(p) for p in parts[:3])
        return r, g, b, a
    h = float(parts[0]) / 360
    s = float(parts[1].rstrip("%")) / 100
    l = float(parts[2].rstrip("%")) / 100
    r, g, b = colorsys.hls_to_rgb(h, l, s)
    return r * 255, g * 255, b * 255, a


def fmt(r, g, b, a):
    r, g, b = (max(0, min(255, round(v))) for v in (r, g, b))
    return f"rgba({r},{g},{b},{round(a, 3)})"


def remap(color, role):
    p = parse(color)
    if p is None:
        return color
    r, g, b, a = p
    h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
    if role == "text":          # dark text becomes light, light text stays light
        l = 0.62 + (1 - l) * 0.3 if l < 0.5 else 0.85
        s *= 0.6
    elif role == "halo":        # light halos become dark
        l = 0.08
        s *= 0.3
    elif role == "road":        # road fills: lighter than the ground so the network reads
        l = 0.16 + l * 0.2
        s *= 0.55
    elif role == "casing":
        l = 0.1
        s *= 0.4
    elif role == "building":    # a touch above the ground so blocks stay visible
        l = 0.14
        s *= 0.2
    else:                       # ground, water, landcover: invert onto a dark range
        l = 0.07 + (1 - l) * 0.22
        s *= 0.4
    r, g, b = colorsys.hls_to_rgb(h, l, s)
    return fmt(r * 255, g * 255, b * 255, a)


COLOR_RE = re.compile(r"^(#[0-9a-fA-F]{3,8}|rgba?\(.*\)|hsla?\(.*\)|white|black)$")


def walk(value, role):
    """Remaps colours anywhere inside a paint value, including stops and expressions."""
    if isinstance(value, str):
        return remap(value, role) if COLOR_RE.match(value.strip()) else value
    if isinstance(value, list):
        return [walk(v, role) for v in value]
    if isinstance(value, dict):
        return {k: walk(v, role) for k, v in value.items()}
    return value


def role_for(layer, prop):
    lid = layer["id"]
    if prop == "text-color":
        return "text"
    if prop == "text-halo-color":
        return "halo"
    if lid.startswith("building"):
        return "building"
    if layer["type"] == "line" and re.match(r"(road|bridge|tunnel)_", lid):
        return "casing" if lid.endswith("_casing") else "road"
    return "ground"


def main():
    # The tile server rejects urllib's default user agent.
    req = urllib.request.Request(SOURCE, headers={"User-Agent": "unfog-style-builder"})
    style = json.load(urllib.request.urlopen(req))
    style["name"] = "Liberty Dark (Unfog)"
    for layer in style["layers"]:
        paint = layer.get("paint", {})
        for prop in list(paint):
            if prop.endswith("color"):
                paint[prop] = walk(paint[prop], role_for(layer, prop))
        if layer["type"] == "raster":
            # Natural Earth shaded relief at low zoom: dim it to match.
            paint["raster-brightness-max"] = 0.25
        # Liberty's highway name labels rely on a default (black) text colour.
        if layer["type"] == "symbol" and "text-field" in layer.get("layout", {}):
            paint.setdefault("text-color", remap("#333333", "text"))
            paint.setdefault("text-halo-color", remap("#ffffff", "halo"))
            paint.setdefault("text-halo-width", 1)
        layer["paint"] = paint
    json.dump(style, sys.stdout, separators=(",", ":"))


if __name__ == "__main__":
    main()
