"""Small image codec for gen_logo_packs.py — Python standard library only.

decode(bytes) -> (width, height, rgba bytearray): PNG (all colour types, bit depths 1–16, no interlace) and JPEG
(baseline and progressive Huffman, greyscale or YCbCr, any sampling). fit_square() scales an image into a square
(area average, premultiplied alpha, centred on transparency); encode_png() writes the smallest near-lossless PNG
it finds (true colour or palette, see there).
"""
import math
import struct
import zlib

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


class ImageError(Exception):
    pass


def decode(data):
    if data.startswith(PNG_SIGNATURE):
        return decode_png(data)
    if data[:2] == b"\xff\xd8":
        import logo_jpeg  # noqa: E402 (sibling module, path set by the generator)
        return logo_jpeg.decode_jpeg(data)
    raise ImageError("unsupported image format (PNG or JPEG expected)")


# --- PNG ------------------------------------------------------------------------------------------------------

def _chunks(data):
    pos = 8
    while pos + 8 <= len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        yield kind, data[pos + 8:pos + 8 + length]
        pos += 12 + length


def _unfilter(raw, height, stride, bpp):
    out = bytearray(height * stride)
    prev = bytearray(stride)
    pos = 0
    for y in range(height):
        ftype = raw[pos]
        line = bytearray(raw[pos + 1:pos + 1 + stride])
        pos += 1 + stride
        if ftype == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 255
        elif ftype == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 255
        elif ftype == 3:
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 255
        elif ftype == 4:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pred = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[i] = (line[i] + pred) & 255
        elif ftype != 0:
            raise ImageError(f"PNG: bad filter {ftype}")
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return out


def decode_png(data):
    ihdr = None
    idat = []
    palette = b""
    trns = None
    for kind, body in _chunks(data):
        if kind == b"IHDR":
            ihdr = struct.unpack(">IIBBBBB", body)
        elif kind == b"PLTE":
            palette = body
        elif kind == b"tRNS":
            trns = body
        elif kind == b"IDAT":
            idat.append(body)
    if ihdr is None:
        raise ImageError("PNG: no IHDR")
    width, height, depth, ctype, _, _, interlace = ihdr
    if interlace:
        raise ImageError("PNG: interlaced images are not supported")
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ctype]
    bits = channels * depth
    stride = (width * bits + 7) // 8
    raw = _unfilter(zlib.decompress(b"".join(idat)), height, stride, max(1, bits // 8))
    rgba = bytearray(width * height * 4)
    maxv = (1 << depth) - 1
    for y in range(height):
        row = raw[y * stride:(y + 1) * stride]
        for x in range(width):
            if depth == 16:
                vals = [row[(x * channels + c) * 2] for c in range(channels)]
                raw16 = [struct.unpack(">H", row[(x * channels + c) * 2:(x * channels + c) * 2 + 2])[0] for c in range(channels)]
            elif depth == 8:
                vals = list(row[x * channels:(x + 1) * channels])
                raw16 = vals
            else:
                bit = x * depth
                v = (row[bit // 8] >> (8 - depth - bit % 8)) & maxv
                raw16 = [v]
                vals = [v if ctype == 3 else v * 255 // maxv]
            if ctype == 0:
                g = vals[0]
                a = 0 if trns and len(trns) >= 2 and raw16[0] == struct.unpack(">H", trns[:2])[0] else 255
                px = (g, g, g, a)
            elif ctype == 2:
                a = 255
                if trns and len(trns) >= 6 and tuple(raw16) == struct.unpack(">HHH", trns[:6]):
                    a = 0
                px = (vals[0], vals[1], vals[2], a)
            elif ctype == 3:
                i = vals[0]
                if 3 * i + 3 > len(palette):
                    raise ImageError("PNG: palette index out of range")
                a = trns[i] if trns and i < len(trns) else 255
                px = (palette[3 * i], palette[3 * i + 1], palette[3 * i + 2], a)
            elif ctype == 4:
                px = (vals[0], vals[0], vals[0], vals[1])
            else:
                px = tuple(vals)
            o = (y * width + x) * 4
            rgba[o:o + 4] = bytes(px)
    return width, height, rgba


# --- Scaling --------------------------------------------------------------------------------------------------

def _weights(src, dst, scale, offset):
    """Per destination pixel: [(source index, weight)] of the area it covers (box filter)."""
    out = []
    for d in range(dst):
        lo = (d - offset) / scale
        hi = (d + 1 - offset) / scale
        lo, hi = max(lo, 0.0), min(hi, float(src))
        parts = []
        s = int(math.floor(lo))
        while s < hi and s < src:
            w = min(hi, s + 1) - max(lo, s)
            if w > 0:
                parts.append((s, w))
            s += 1
        out.append(parts)
    return out


def fit_square(width, height, rgba, size):
    """Scale into size × size (aspect kept, centred, transparent margins), area average with premultiplied alpha."""
    scale = size / max(width, height)
    tw, th = width * scale, height * scale
    xs = _weights(width, size, scale, (size - tw) / 2)
    ys = _weights(height, size, scale, (size - th) / 2)
    # Horizontal pass (premultiplied)
    tmp = []
    for y in range(height):
        row = []
        base = y * width * 4
        for parts in xs:
            r = g = b = a = 0.0
            for s, w in parts:
                o = base + s * 4
                al = rgba[o + 3] * w
                r += rgba[o] * al
                g += rgba[o + 1] * al
                b += rgba[o + 2] * al
                a += al
            row.append((r, g, b, a))
        tmp.append(row)
    out = bytearray(size * size * 4)
    for dy, parts in enumerate(ys):
        for dx in range(size):
            r = g = b = a = 0.0
            for s, w in parts:
                pr, pg, pb, pa = tmp[s][dx]
                r += pr * w
                g += pg * w
                b += pb * w
                a += pa * w
            o = (dy * size + dx) * 4
            if a > 0:
                out[o:o + 4] = bytes((min(255, round(r / a)), min(255, round(g / a)), min(255, round(b / a)),
                                      min(255, round(a))))
    return out


# --- PNG writing ----------------------------------------------------------------------------------------------

def _chunk(kind, body):
    return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)


def _png(width, height, ctype, depth, rows, palette=None, trns=None):
    raw = b"".join(b"\x00" + r for r in rows)
    body = zlib.compress(raw, 9)
    out = PNG_SIGNATURE + _chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, depth, ctype, 0, 0, 0))
    if palette is not None:
        out += _chunk(b"PLTE", palette)
    if trns:
        out += _chunk(b"tRNS", trns)
    return out + _chunk(b"IDAT", body) + _chunk(b"IEND", b"")


def _clean(rgba):
    """Fully transparent pixels → one colour (0,0,0,0), so they share a palette entry."""
    out = bytearray(rgba)
    for o in range(0, len(out), 4):
        if out[o + 3] == 0:
            out[o:o + 3] = b"\x00\x00\x00"
    return out


def _palette_png(size, pixels, colours):
    """colours: list of RGBA tuples (≤ 256); pixels: list of palette indices."""
    order = sorted(range(len(colours)), key=lambda i: colours[i][3])  # transparent first → short tRNS
    remap = {old: new for new, old in enumerate(order)}
    pal = [colours[i] for i in order]
    depth = 8
    for d in (1, 2, 4):
        if len(pal) <= 1 << d:
            depth = d
            break
    rows = []
    for y in range(size):
        idx = [remap[p] for p in pixels[y * size:(y + 1) * size]]
        if depth == 8:
            rows.append(bytes(idx))
        else:
            per = 8 // depth
            line = bytearray((size + per - 1) // per)
            for x, v in enumerate(idx):
                line[x // per] |= v << (8 - depth - (x % per) * depth)
            rows.append(bytes(line))
    trns = bytes(c[3] for c in pal)
    trns = trns.rstrip(b"\xff")
    plte = b"".join(bytes(c[:3]) for c in pal)
    return _png(size, size, 3, depth, rows, plte, trns or None)


def _median_cut(colours, counts, target):
    boxes = [list(range(len(colours)))]
    while len(boxes) < target:
        best, axis, spread = None, 0, -1
        for bi, box in enumerate(boxes):
            if len(box) < 2:
                continue
            for c in range(4):
                vals = [colours[i][c] for i in box]
                if max(vals) - min(vals) > spread:
                    best, axis, spread = bi, c, max(vals) - min(vals)
        if best is None or spread <= 0:
            break
        box = sorted(boxes.pop(best), key=lambda i: colours[i][axis])
        total = sum(counts[i] for i in box)
        acc, cut = 0, 1
        for k, i in enumerate(box):
            acc += counts[i]
            if acc * 2 >= total:
                cut = max(1, min(len(box) - 1, k + 1))
                break
        boxes += [box[:cut], box[cut:]]
    pal, lookup = [], {}
    for box in boxes:
        w = sum(counts[i] for i in box)
        avg = tuple(round(sum(colours[i][c] * counts[i] for i in box) / w) for c in range(4))
        for i in box:
            lookup[colours[i]] = len(pal)
        pal.append(avg)
    return pal, lookup


def encode_png(size, rgba):
    """Smallest PNG that stays near-lossless: true colour, or a palette PNG with 16–256 colours (exact when the logo
    has that few, otherwise median cut) whose mean error per pixel stays within MAX_MEAN_ERROR."""
    rgba = _clean(rgba)
    px = [tuple(rgba[o:o + 4]) for o in range(0, len(rgba), 4)]
    counts = {}
    for p in px:
        counts[p] = counts.get(p, 0) + 1
    colours = list(counts)
    rows = [bytes(rgba[y * size * 4:(y + 1) * size * 4]) for y in range(size)]
    best = _png(size, size, 6, 8, rows)
    for target in PALETTE_SIZES:
        if len(colours) <= target:
            index = {c: i for i, c in enumerate(colours)}
            candidate = _palette_png(size, [index[p] for p in px], colours)
        else:
            pal, lookup = _median_cut(colours, [counts[c] for c in colours], target)
            err = sum(counts[c] * _distance(c, pal[lookup[c]]) for c in colours) / len(px)
            if err > MAX_MEAN_ERROR:
                continue
            candidate = _palette_png(size, [lookup[p] for p in px], pal)
        if len(candidate) < len(best):
            best = candidate
        if len(colours) <= target:
            break
    return best


# Palette sizes tried (smallest file wins); mean error per pixel (premultiplied, 0–255) a palette may add —
# at 2 the logos look the same at the displayed size (checked side by side at 2× zoom)
PALETTE_SIZES = (16, 32, 64, 128, 256)
MAX_MEAN_ERROR = 2.0


def _distance(p, q):
    return max(abs(a * p[3] - b * q[3]) / 255 for a, b in zip(p[:3], q[:3])) + abs(p[3] - q[3])
