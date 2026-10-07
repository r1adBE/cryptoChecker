#!/usr/bin/env python3
"""Generate the iOS asset catalogs from the Android VectorDrawables of cryptoChecker.

  App/Resources/Assets.xcassets   AppIcon (+7 alternates), Logo* image sets, AccentColor, WidgetBackground
                                  Every icon set carries the iOS 18 appearance variants: any = dark icon
                                  (white glyph, the brand look, also used on iOS 17), dark = the same dark
                                  icon, tinted = dark glyph on black. The light renders are not used here.
                                  AppIcon<Color>Light is a twin of AppIcon<Color> (kept so that icons
                                  chosen by older versions still resolve).
  Widgets/Assets.xcassets         Logo* image sets, AccentColor, WidgetBackground
  tools/svg/*.svg                 the VectorDrawables converted to SVG (for reference / other tools)

Rendering: VectorDrawable XML is parsed into a small scene model, written out as SVG and
rasterised with libcairo through ctypes (cairosvg/rsvg are not available in the build
environment, libcairo is). Python 3 + Pillow required.

Usage: python3 tools/gen_assets.py [--android-res PATH]
"""
import argparse
import ctypes
import ctypes.util
import json
import math
import os
import re
import shutil
import tempfile
import xml.etree.ElementTree as ET

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
# Android resources next to this project: ../cryptoChecker (local folders) or ../android
# (GitHub repository and ZIP bundle); --android-res overrides.
DEFAULT_RES = next((p for p in (os.path.normpath(os.path.join(ROOT, "..", d, "app", "src", "main", "res"))
                    for d in ("cryptoChecker", "android")) if os.path.isdir(p)),
                   os.path.normpath(os.path.join(ROOT, "..", "android", "app", "src", "main", "res")))
A = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/aapt}"

COLORS = ["orange", "red", "blue", "green"]
VARIANTS = ["dark", "light"]
ACCENT = "#DD6F48"
WIDGET_BG = {"light": "#EEEEEE", "dark": "#1F1F1F"}  # WidgetColors.kt baseColor


def icon_set_name(color, variant):
    """AppIcon (orange), AppIconOrangeLight, AppIconRed, AppIconRedLight, ... (see module doc)"""
    if color == "orange" and variant == "dark":
        return "AppIcon"
    return "AppIcon" + color.capitalize() + ("Light" if variant == "light" else "")


def tinted_icon(flat):
    """Tinted appearance: white glyph of the dark icon on black (iOS tints by luminance).
    The glyph is white/unsaturated, the coloured background is saturated, so the
    whiteness is 1 - saturation / median saturation (background ~0, cut at 20 %)."""
    sat = flat.convert("HSV").split()[1]
    hist, half, med = sat.histogram(), sat.width * sat.height / 2, 1
    for v in range(256):
        half -= hist[v]
        if half <= 0:
            med = max(v, 1)
            break

    def level(v):
        w = max(0.0, min(1.0, 1.0 - v / med))
        return round(max(0.0, min(1.0, (w - 0.2) / 0.8)) * 255)

    return sat.point([level(v) for v in range(256)]).convert("RGB")


ALTERNATE_ICON_NAMES = [icon_set_name(c, v) for c in COLORS for v in VARIANTS
                        if not (c == "orange" and v == "dark")]


# ---------------------------------------------------------------- resources

class Resources:
    def __init__(self, res):
        self.res = res
        self.colors = {}
        for d in ["values"]:
            for fn in os.listdir(os.path.join(res, d)):
                if fn.endswith(".xml"):
                    for el in ET.parse(os.path.join(res, d, fn)).getroot():
                        if el.tag == "color":
                            self.colors[el.get("name")] = el.text.strip()

    def color(self, ref):
        """Returns (r, g, b, a) floats or None."""
        if ref is None:
            return None
        ref = ref.strip()
        seen = 0
        while ref.startswith("@color/") or ref.startswith("@android:color/"):
            name = ref.split("/", 1)[1]
            if ref.startswith("@android:color/"):
                ref = {"transparent": "#00000000", "black": "#FF000000", "white": "#FFFFFFFF"}[name]
            else:
                ref = self.colors[name]
            seen += 1
            if seen > 10:
                raise ValueError("color reference loop")
        return parse_hex(ref)


def parse_hex(h):
    h = h.lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
        h = "FF" + h
    elif len(h) == 4:
        h = "".join(c * 2 for c in h)
    elif len(h) == 6:
        h = "FF" + h
    a, r, g, b = (int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4, 6))
    return (r, g, b, a)


def fnum(el, name, default):
    v = el.get(A + name)
    if v is None:
        return default
    return float(re.sub(r"(dp|dip|px|sp)$", "", v))


# ---------------------------------------------------------------- scene model

class Gradient:
    def __init__(self, el, res):
        self.type = el.get(A + "type", "linear")
        self.start = (fnum(el, "startX", 0), fnum(el, "startY", 0))
        self.end = (fnum(el, "endX", 0), fnum(el, "endY", 0))
        self.center = (fnum(el, "centerX", 0), fnum(el, "centerY", 0))
        self.radius = fnum(el, "gradientRadius", 0)
        self.tile = el.get(A + "tileMode", "clamp")
        stops = []
        items = [i for i in el if i.tag == "item"]
        if items:
            for it in items:
                stops.append((fnum(it, "offset", 0), res.color(it.get(A + "color"))))
        else:
            stops.append((0.0, res.color(el.get(A + "startColor", "#00000000"))))
            if el.get(A + "centerColor"):
                stops.append((0.5, res.color(el.get(A + "centerColor"))))
            stops.append((1.0, res.color(el.get(A + "endColor", "#00000000"))))
        self.stops = stops


def paint_attr(el, name, res):
    """Paint for fillColor/strokeColor: color tuple, Gradient or None."""
    v = el.get(A + name)
    if v is not None:
        return res.color(v)
    for attr in el.findall(AAPT + "attr"):
        if attr.get("name") == "android:" + name:
            g = attr.find("gradient")
            if g is not None:
                return Gradient(g, res)
    return None


class Path:
    def __init__(self, el, res):
        self.d = el.get(A + "pathData", "")
        self.fill = paint_attr(el, "fillColor", res)
        self.stroke = paint_attr(el, "strokeColor", res)
        self.stroke_width = fnum(el, "strokeWidth", 0)
        self.fill_alpha = fnum(el, "fillAlpha", 1)
        self.stroke_alpha = fnum(el, "strokeAlpha", 1)
        self.cap = el.get(A + "strokeLineCap", "butt")
        self.join = el.get(A + "strokeLineJoin", "miter")
        self.miter = fnum(el, "strokeMiterLimit", 4)
        self.fill_type = el.get(A + "fillType", "nonZero")
        if el.get(A + "trimPathStart") or el.get(A + "trimPathEnd"):
            print("warning: trimPath* is not supported, ignored")


class ClipPath:
    def __init__(self, el):
        self.d = el.get(A + "pathData", "")


class Group:
    def __init__(self, el, res):
        self.tx = fnum(el, "translateX", 0)
        self.ty = fnum(el, "translateY", 0)
        self.sx = fnum(el, "scaleX", 1)
        self.sy = fnum(el, "scaleY", 1)
        self.px = fnum(el, "pivotX", 0)
        self.py = fnum(el, "pivotY", 0)
        self.rot = fnum(el, "rotation", 0)
        self.children = parse_children(el, res)


def parse_children(el, res):
    out = []
    for c in el:
        if c.tag == "group":
            out.append(Group(c, res))
        elif c.tag == "path":
            out.append(Path(c, res))
        elif c.tag == "clip-path":
            out.append(ClipPath(c))
    return out


class Vector:
    def __init__(self, path, res):
        root = ET.parse(path).getroot()
        if root.tag != "vector":
            raise ValueError(f"{path}: not a vector drawable")
        self.name = os.path.splitext(os.path.basename(path))[0]
        self.vw = fnum(root, "viewportWidth", 24)
        self.vh = fnum(root, "viewportHeight", 24)
        self.width = fnum(root, "width", self.vw)
        self.height = fnum(root, "height", self.vh)
        self.alpha = fnum(root, "alpha", 1)
        self.children = parse_children(root, res)


# ---------------------------------------------------------------- SVG export

def svg_color(c):
    r, g, b, a = c
    return f"#{round(r * 255):02X}{round(g * 255):02X}{round(b * 255):02X}", a


def to_svg(vec, viewbox=None, background=None, size=None):
    defs = []
    counter = [0]
    vb = viewbox or (0, 0, vec.vw, vec.vh)
    w, h = size or (vec.width, vec.height)

    def paint(p, alpha, kind):
        if p is None:
            return f'{kind}="none"'
        if isinstance(p, Gradient):
            counter[0] += 1
            gid = f"g{counter[0]}"
            stops = "".join(
                f'<stop offset="{o}" stop-color="{svg_color(c)[0]}" stop-opacity="{svg_color(c)[1]:.4g}"/>'
                for o, c in p.stops)
            spread = {"clamp": "pad", "repeat": "repeat", "mirror": "reflect"}.get(p.tile, "pad")
            if p.type == "radial":
                defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{p.center[0]}" '
                            f'cy="{p.center[1]}" r="{p.radius}" spreadMethod="{spread}">{stops}</radialGradient>')
            else:  # linear (sweep is approximated as linear)
                defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{p.start[0]}" '
                            f'y1="{p.start[1]}" x2="{p.end[0]}" y2="{p.end[1]}" spreadMethod="{spread}">{stops}'
                            f'</linearGradient>')
            return f'{kind}="url(#{gid})" {kind}-opacity="{alpha:.4g}"'
        col, a = svg_color(p)
        return f'{kind}="{col}" {kind}-opacity="{a * alpha:.4g}"'

    def emit(children, indent):
        lines = []
        pending_clip = None
        for c in children:
            pad = "  " * indent
            if isinstance(c, ClipPath):
                counter[0] += 1
                pending_clip = f"c{counter[0]}"
                defs.append(f'<clipPath id="{pending_clip}"><path d="{c.d}"/></clipPath>')
            elif isinstance(c, Group):
                t = (f"translate({c.tx + c.px:g} {c.ty + c.py:g}) rotate({c.rot:g}) "
                     f"scale({c.sx:g} {c.sy:g}) translate({-c.px:g} {-c.py:g})")
                lines.append(f'{pad}<g transform="{t}">')
                lines.extend(emit(c.children, indent + 1))
                lines.append(f"{pad}</g>")
            else:
                attrs = [f'd="{c.d}"', paint(c.fill, c.fill_alpha, "fill")]
                if c.fill_type == "evenOdd":
                    attrs.append('fill-rule="evenodd"')
                if c.stroke is not None and c.stroke_width > 0:
                    attrs += [paint(c.stroke, c.stroke_alpha, "stroke"), f'stroke-width="{c.stroke_width:g}"',
                              f'stroke-linecap="{c.cap}"', f'stroke-linejoin="{c.join}"',
                              f'stroke-miterlimit="{c.miter:g}"']
                if pending_clip:
                    attrs.append(f'clip-path="url(#{pending_clip})"')
                lines.append(f"{pad}<path {' '.join(attrs)}/>")
        return lines

    body = emit(vec.children, 1)
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{w:g}" height="{h:g}" '
           f'viewBox="{vb[0]:g} {vb[1]:g} {vb[2]:g} {vb[3]:g}">']
    if defs:
        out.append("  <defs>" + "".join(defs) + "</defs>")
    if background:
        col, a = svg_color(background)
        out.append(f'  <rect x="{vb[0]:g}" y="{vb[1]:g}" width="{vb[2]:g}" height="{vb[3]:g}" fill="{col}" '
                   f'fill-opacity="{a:.4g}"/>')
    if vec.alpha < 1:
        out.append(f'  <g opacity="{vec.alpha:g}">')
    out.extend(body)
    if vec.alpha < 1:
        out.append("  </g>")
    out.append("</svg>")
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------- path data

TOKEN_RE = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


def parse_path(d):
    """Yields absolute segments: ('M',x,y) ('L',x,y) ('C',x1,y1,x2,y2,x,y) ('Z',)."""
    i = 0
    n = len(d)
    cmd = None
    cx = cy = sx = sy = 0.0
    last_c2 = None  # last cubic control point (for S)
    last_q = None   # last quad control point (for T)
    out = []

    def skip():
        nonlocal i
        while i < n and d[i] in " ,\t\r\n":
            i += 1

    def number():
        nonlocal i
        skip()
        m = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?").match(d, i)
        if not m:
            raise ValueError(f"bad path data near {d[i:i + 20]!r}")
        i = m.end()
        return float(m.group(0))

    def flag():
        nonlocal i
        skip()
        c = d[i]
        if c not in "01":
            raise ValueError(f"bad arc flag near {d[i:i + 20]!r}")
        i += 1
        return c == "1"

    while True:
        skip()
        if i >= n:
            break
        if d[i].isalpha():
            cmd = d[i]
            i += 1
            if cmd in "Zz":
                out.append(("Z",))
                cx, cy = sx, sy
                last_c2 = last_q = None
                continue
        elif cmd is None:
            raise ValueError("path data must start with a command")
        rel = cmd.islower()
        C = cmd.upper()
        ox, oy = (cx, cy) if rel else (0.0, 0.0)
        if C == "M":
            x, y = number() + ox, number() + oy
            out.append(("M", x, y))
            cx, cy, sx, sy = x, y, x, y
            cmd = "l" if rel else "L"
            last_c2 = last_q = None
        elif C == "L":
            x, y = number() + ox, number() + oy
            out.append(("L", x, y))
            cx, cy = x, y
            last_c2 = last_q = None
        elif C == "H":
            x = number() + ox
            out.append(("L", x, cy))
            cx = x
            last_c2 = last_q = None
        elif C == "V":
            y = number() + (cy if rel else 0.0)
            out.append(("L", cx, y))
            cy = y
            last_c2 = last_q = None
        elif C == "C":
            x1, y1 = number() + ox, number() + oy
            x2, y2 = number() + ox, number() + oy
            x, y = number() + ox, number() + oy
            out.append(("C", x1, y1, x2, y2, x, y))
            last_c2, last_q = (x2, y2), None
            cx, cy = x, y
        elif C == "S":
            x2, y2 = number() + ox, number() + oy
            x, y = number() + ox, number() + oy
            x1, y1 = (2 * cx - last_c2[0], 2 * cy - last_c2[1]) if last_c2 else (cx, cy)
            out.append(("C", x1, y1, x2, y2, x, y))
            last_c2, last_q = (x2, y2), None
            cx, cy = x, y
        elif C == "Q":
            qx, qy = number() + ox, number() + oy
            x, y = number() + ox, number() + oy
            out.append(("C", cx + 2 / 3 * (qx - cx), cy + 2 / 3 * (qy - cy),
                        x + 2 / 3 * (qx - x), y + 2 / 3 * (qy - y), x, y))
            last_q, last_c2 = (qx, qy), None
            cx, cy = x, y
        elif C == "T":
            x, y = number() + ox, number() + oy
            qx, qy = (2 * cx - last_q[0], 2 * cy - last_q[1]) if last_q else (cx, cy)
            out.append(("C", cx + 2 / 3 * (qx - cx), cy + 2 / 3 * (qy - cy),
                        x + 2 / 3 * (qx - x), y + 2 / 3 * (qy - y), x, y))
            last_q, last_c2 = (qx, qy), None
            cx, cy = x, y
        elif C == "A":
            rx, ry, phi = number(), number(), number()
            large, sweep = flag(), flag()
            x, y = number() + ox, number() + oy
            out.extend(arc_to_cubics(cx, cy, rx, ry, phi, large, sweep, x, y))
            cx, cy = x, y
            last_c2 = last_q = None
        else:
            raise ValueError(f"unsupported command {cmd}")
    return out


def arc_to_cubics(x1, y1, rx, ry, phi_deg, large, sweep, x2, y2):
    if (x1, y1) == (x2, y2):
        return []
    if rx == 0 or ry == 0:
        return [("L", x2, y2)]
    rx, ry = abs(rx), abs(ry)
    phi = math.radians(phi_deg)
    cp, sp = math.cos(phi), math.sin(phi)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    x1p = cp * dx + sp * dy
    y1p = -sp * dx + cp * dy
    lam = (x1p ** 2) / (rx ** 2) + (y1p ** 2) / (ry ** 2)
    if lam > 1:
        s = math.sqrt(lam)
        rx, ry = rx * s, ry * s
    num = rx ** 2 * ry ** 2 - rx ** 2 * y1p ** 2 - ry ** 2 * x1p ** 2
    den = rx ** 2 * y1p ** 2 + ry ** 2 * x1p ** 2
    coef = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        coef = -coef
    cxp = coef * rx * y1p / ry
    cyp = -coef * ry * x1p / rx
    cx = cp * cxp - sp * cyp + (x1 + x2) / 2
    cy = sp * cxp + cp * cyp + (y1 + y2) / 2

    def ang(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a

    t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    segs = max(1, math.ceil(abs(dt) / (math.pi / 2) - 1e-9))
    delta = dt / segs
    k = 4 / 3 * math.tan(delta / 4)
    out = []

    def pt(t):
        return (cx + rx * math.cos(t) * cp - ry * math.sin(t) * sp,
                cy + rx * math.cos(t) * sp + ry * math.sin(t) * cp)

    def deriv(t):
        return (-rx * math.sin(t) * cp - ry * math.cos(t) * sp,
                -rx * math.sin(t) * sp + ry * math.cos(t) * cp)

    t = t1
    for s in range(segs):
        ta, tb = t, t + delta
        pa, pb = pt(ta), pt(tb)
        da, db = deriv(ta), deriv(tb)
        c1 = (pa[0] + k * da[0], pa[1] + k * da[1])
        c2 = (pb[0] - k * db[0], pb[1] - k * db[1])
        end = (x2, y2) if s == segs - 1 else pb
        out.append(("C", c1[0], c1[1], c2[0], c2[1], end[0], end[1]))
        t = tb
    return out


# ---------------------------------------------------------------- cairo rasteriser

class Cairo:
    FORMAT_ARGB32 = 0
    CAP = {"butt": 0, "round": 1, "square": 2}
    JOIN = {"miter": 0, "round": 1, "bevel": 2}
    FILL_WINDING, FILL_EVEN_ODD = 0, 1
    EXTEND = {"clamp": 2, "repeat": 1, "mirror": 3}  # PAD, REPEAT, REFLECT

    def __init__(self):
        name = ctypes.util.find_library("cairo") or "libcairo.so.2"
        lib = ctypes.CDLL(name)
        P, D, I = ctypes.c_void_p, ctypes.c_double, ctypes.c_int
        sig = {
            "cairo_image_surface_create": (P, [I, I, I]),
            "cairo_create": (P, [P]),
            "cairo_destroy": (None, [P]),
            "cairo_surface_destroy": (None, [P]),
            "cairo_surface_write_to_png": (I, [P, ctypes.c_char_p]),
            "cairo_save": (None, [P]), "cairo_restore": (None, [P]),
            "cairo_translate": (None, [P, D, D]), "cairo_scale": (None, [P, D, D]),
            "cairo_rotate": (None, [P, D]),
            "cairo_move_to": (None, [P, D, D]), "cairo_line_to": (None, [P, D, D]),
            "cairo_curve_to": (None, [P, D, D, D, D, D, D]),
            "cairo_close_path": (None, [P]), "cairo_new_path": (None, [P]),
            "cairo_set_source_rgba": (None, [P, D, D, D, D]),
            "cairo_set_source": (None, [P, P]),
            "cairo_fill_preserve": (None, [P]), "cairo_stroke": (None, [P]),
            "cairo_clip": (None, [P]), "cairo_paint": (None, [P]),
            "cairo_paint_with_alpha": (None, [P, D]),
            "cairo_push_group": (None, [P]), "cairo_pop_group_to_source": (None, [P]),
            "cairo_set_fill_rule": (None, [P, I]),
            "cairo_set_line_width": (None, [P, D]), "cairo_set_line_cap": (None, [P, I]),
            "cairo_set_line_join": (None, [P, I]), "cairo_set_miter_limit": (None, [P, D]),
            "cairo_rectangle": (None, [P, D, D, D, D]), "cairo_fill": (None, [P]),
            "cairo_pattern_create_linear": (P, [D, D, D, D]),
            "cairo_pattern_create_radial": (P, [D, D, D, D, D, D]),
            "cairo_pattern_add_color_stop_rgba": (None, [P, D, D, D, D, D]),
            "cairo_pattern_set_extend": (None, [P, I]),
            "cairo_pattern_destroy": (None, [P]),
            "cairo_set_antialias": (None, [P, I]),
        }
        for fn, (res, args) in sig.items():
            f = getattr(lib, fn)
            f.restype = res
            f.argtypes = args
            setattr(self, fn[len("cairo_"):], f)


_cairo = None


def render_png(vec, out_png, px_w, px_h, viewbox=None, background=None):
    """Render vec (viewport coords; optional viewbox crop) into a PNG of px_w x px_h."""
    global _cairo
    if _cairo is None:
        _cairo = Cairo()
    c = _cairo
    vb = viewbox or (0, 0, vec.vw, vec.vh)
    surf = c.image_surface_create(Cairo.FORMAT_ARGB32, px_w, px_h)
    cr = c.create(surf)
    if background:
        c.set_source_rgba(cr, *background)
        c.rectangle(cr, 0, 0, px_w, px_h)
        c.fill(cr)
    c.scale(cr, px_w / vb[2], px_h / vb[3])
    c.translate(cr, -vb[0], -vb[1])
    if vec.alpha < 1:
        c.push_group(cr)

    def build(d):
        c.new_path(cr)
        for seg in parse_path(d):
            if seg[0] == "M":
                c.move_to(cr, seg[1], seg[2])
            elif seg[0] == "L":
                c.line_to(cr, seg[1], seg[2])
            elif seg[0] == "C":
                c.curve_to(cr, *seg[1:])
            else:
                c.close_path(cr)

    def set_paint(p, alpha):
        if isinstance(p, Gradient):
            if p.type == "radial":
                pat = c.pattern_create_radial(p.center[0], p.center[1], 0, p.center[0], p.center[1], p.radius)
            else:
                pat = c.pattern_create_linear(p.start[0], p.start[1], p.end[0], p.end[1])
            for off, col in p.stops:
                c.pattern_add_color_stop_rgba(pat, off, col[0], col[1], col[2], col[3] * alpha)
            c.pattern_set_extend(pat, Cairo.EXTEND.get(p.tile, 2))
            c.set_source(cr, pat)
            c.pattern_destroy(pat)
        else:
            c.set_source_rgba(cr, p[0], p[1], p[2], p[3] * alpha)

    def draw(children):
        c.save(cr)  # clip-paths apply until the end of the enclosing group
        for ch in children:
            if isinstance(ch, ClipPath):
                build(ch.d)
                c.clip(cr)
            elif isinstance(ch, Group):
                c.save(cr)
                c.translate(cr, ch.tx + ch.px, ch.ty + ch.py)
                c.rotate(cr, math.radians(ch.rot))
                c.scale(cr, ch.sx, ch.sy)
                c.translate(cr, -ch.px, -ch.py)
                draw(ch.children)
                c.restore(cr)
            else:
                build(ch.d)
                if ch.fill is not None:
                    c.set_fill_rule(cr, Cairo.FILL_EVEN_ODD if ch.fill_type == "evenOdd" else Cairo.FILL_WINDING)
                    set_paint(ch.fill, ch.fill_alpha)
                    c.fill_preserve(cr)
                if ch.stroke is not None and ch.stroke_width > 0:
                    set_paint(ch.stroke, ch.stroke_alpha)
                    c.set_line_width(cr, ch.stroke_width)
                    c.set_line_cap(cr, Cairo.CAP.get(ch.cap, 0))
                    c.set_line_join(cr, Cairo.JOIN.get(ch.join, 0))
                    c.set_miter_limit(cr, ch.miter)
                    c.stroke(cr)
                c.new_path(cr)
        c.restore(cr)

    draw(vec.children)
    if vec.alpha < 1:
        c.pop_group_to_source(cr)
        c.paint_with_alpha(cr, vec.alpha)
    rc = c.surface_write_to_png(surf, out_png.encode())
    c.destroy(cr)
    c.surface_destroy(surf)
    if rc != 0:
        raise RuntimeError(f"cairo could not write {out_png} (status {rc})")


# ---------------------------------------------------------------- asset catalog helpers

INFO = {"author": "xcode", "version": 1}


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2, separators=(",", " : "))
        f.write("\n")


def color_components(hexstr):
    r, g, b, a = parse_hex(hexstr)
    return {"color-space": "srgb", "components": {
        "red": f"0x{round(r * 255):02X}", "green": f"0x{round(g * 255):02X}",
        "blue": f"0x{round(b * 255):02X}", "alpha": f"{a:.3f}"}}


def write_colorset(catalog, name, light, dark=None):
    colors = [{"color": color_components(light), "idiom": "universal"}]
    if dark:
        colors.append({"appearances": [{"appearance": "luminosity", "value": "dark"}],
                       "color": color_components(dark), "idiom": "universal"})
    write_json(os.path.join(catalog, f"{name}.colorset", "Contents.json"), {"colors": colors, "info": INFO})


def parse_adaptive_icon(path):
    root = ET.parse(path).getroot()
    bg = root.find("background").get(A + "drawable")
    fg = root.find("foreground").get(A + "drawable")
    return bg, fg


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--android-res", default=DEFAULT_RES)
    args = ap.parse_args()
    res = Resources(args.android_res)
    drawable = os.path.join(args.android_res, "drawable")

    app_cat = os.path.join(ROOT, "App", "Resources", "Assets.xcassets")
    widget_cat = os.path.join(ROOT, "Widgets", "Assets.xcassets")
    svg_dir = os.path.join(HERE, "svg")
    for d in (app_cat, widget_cat, svg_dir):
        if os.path.isdir(d):
            shutil.rmtree(d)
        os.makedirs(d)
    write_json(os.path.join(app_cat, "Contents.json"), {"info": INFO})
    write_json(os.path.join(widget_cat, "Contents.json"), {"info": INFO})

    tmp = tempfile.mkdtemp()
    for color in COLORS:
        flats = {}
        for variant in VARIANTS:
            # ---- app icon: adaptive icon background + foreground, inner 72 of 108 dp
            adaptive = os.path.join(args.android_res, "mipmap-anydpi-v26", f"ic_launcher_{color}_{variant}.xml")
            bg_ref, fg_ref = parse_adaptive_icon(adaptive)
            if not bg_ref.startswith("@color/"):
                raise SystemExit(f"{adaptive}: only color backgrounds are supported ({bg_ref})")
            bg = res.color(bg_ref)
            bg = (bg[0], bg[1], bg[2], 1.0)
            fg = Vector(os.path.join(drawable, fg_ref.split("/", 1)[1] + ".xml"), res)
            inset = fg.vw * 18.0 / 108.0
            crop = (inset, inset, fg.vw - 2 * inset, fg.vh - 2 * inset)
            with open(os.path.join(svg_dir, fg.name + ".svg"), "w") as f:
                f.write(to_svg(fg))
            with open(os.path.join(svg_dir, f"AppIcon_{color}_{variant}.svg"), "w") as f:
                f.write(to_svg(fg, viewbox=crop, background=bg, size=(1024, 1024)))
            raw = os.path.join(tmp, f"{color}_{variant}.png")
            render_png(fg, raw, 1024, 1024, viewbox=crop, background=bg)
            im = Image.open(raw).convert("RGBA")
            flat = Image.new("RGB", im.size, tuple(round(v * 255) for v in bg[:3]))
            flat.paste(im, mask=im.split()[3])
            flats[variant] = flat
            if variant == "dark":
                flats["tinted"] = tinted_icon(flat)

            # ---- logo image sets (transparent), base 120 pt
            logo = Vector(os.path.join(drawable, f"ic_app_logo_{color}_{variant}.xml"), res)
            with open(os.path.join(svg_dir, logo.name + ".svg"), "w") as f:
                f.write(to_svg(logo))
            logo_name = f"Logo{color.capitalize()}{variant.capitalize()}"
            for cat in (app_cat, widget_cat):
                iset = os.path.join(cat, logo_name + ".imageset")
                os.makedirs(iset)
                images = []
                for scale in (1, 2, 3):
                    fn = f"{logo_name}@{scale}x.png" if scale > 1 else f"{logo_name}.png"
                    render_png(logo, os.path.join(iset, fn), 120 * scale, 120 * scale)
                    images.append({"filename": fn, "idiom": "universal", "scale": f"{scale}x"})
                write_json(os.path.join(iset, "Contents.json"), {"images": images, "info": INFO})

        # ---- app icon sets: one set per accent with any/dark/tinted appearances; any and
        # dark both use the dark icon (brand look). Switching only on an explicit accent choice.
        for set_name in (icon_set_name(color, "dark"), icon_set_name(color, "light")):
            set_dir = os.path.join(app_cat, set_name + ".appiconset")
            os.makedirs(set_dir)
            images = []
            for key, src, fn in (("any", "dark", "icon-1024.png"), ("dark", "dark", "icon-1024-dark.png"),
                                 ("tinted", "tinted", "icon-1024-tinted.png")):
                flats[src].save(os.path.join(set_dir, fn), optimize=True)
                entry = {"filename": fn, "idiom": "universal", "platform": "ios", "size": "1024x1024"}
                if key != "any":
                    entry = {"appearances": [{"appearance": "luminosity", "value": key}], **entry}
                images.append(entry)
            write_json(os.path.join(set_dir, "Contents.json"), {"images": images, "info": INFO})
    shutil.rmtree(tmp)

    for cat in (app_cat, widget_cat):
        write_colorset(cat, "AccentColor", ACCENT)
        write_colorset(cat, "WidgetBackground", WIDGET_BG["light"], WIDGET_BG["dark"])

    # sanity: icons opaque and 1024
    for name in ["AppIcon"] + ALTERNATE_ICON_NAMES:
        for fn in ("icon-1024.png", "icon-1024-dark.png", "icon-1024-tinted.png"):
            im = Image.open(os.path.join(app_cat, name + ".appiconset", fn))
            assert im.size == (1024, 1024) and im.mode == "RGB", (name, fn, im.size, im.mode)
    print("App icons:", ", ".join(["AppIcon"] + ALTERNATE_ICON_NAMES))
    print("Wrote", app_cat)
    print("Wrote", widget_cat)
    print("SVGs in", svg_dir)


if __name__ == "__main__":
    main()
