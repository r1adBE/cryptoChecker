"""JPEG decoder for gen_logo_packs.py — Python standard library only.

decode_jpeg(bytes) -> (width, height, rgba bytearray). Baseline/extended sequential and progressive Huffman JPEG,
1 (greyscale), 3 (YCbCr; Adobe transform 0 = RGB) or 4 (Adobe CMYK/YCCK, stored inverted) components, any
sampling factors, restart intervals. Not supported (raises ImageError): arithmetic coding, lossless.
"""
import math
import struct

from logo_image import ImageError

ZIGZAG = [0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7,
          14, 21, 28, 35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46,
          53, 60, 61, 54, 47, 55, 62, 63]
COS = [[(math.sqrt(0.5) if u == 0 else 1.0) * math.cos((2 * x + 1) * u * math.pi / 16) / 2 for u in range(8)]
       for x in range(8)]


class _Bits:
    def __init__(self, data, pos):
        self.data, self.pos, self.acc, self.n = data, pos, 0, 0

    def bit(self):
        if self.n == 0:
            if self.pos >= len(self.data):
                return 0
            b = self.data[self.pos]
            self.pos += 1
            if b == 0xFF:
                nxt = self.data[self.pos] if self.pos < len(self.data) else 0
                if nxt == 0:
                    self.pos += 1
                else:
                    self.pos -= 1  # marker: feed zeros, stay before it
                    return 0
            self.acc, self.n = b, 8
        self.n -= 1
        return (self.acc >> self.n) & 1

    def bits(self, k):
        v = 0
        for _ in range(k):
            v = (v << 1) | self.bit()
        return v

    def receive(self, k):
        if k == 0:
            return 0
        v = self.bits(k)
        return v if v >= 1 << (k - 1) else v - (1 << k) + 1

    def restart(self):
        self.acc = self.n = 0
        while self.pos + 1 < len(self.data):
            if self.data[self.pos] == 0xFF and 0xD0 <= self.data[self.pos + 1] <= 0xD7:
                self.pos += 2
                return
            self.pos += 1


def _huffman(counts, symbols):
    table, code, k = {}, 0, 0
    for length in range(1, 17):
        for _ in range(counts[length - 1]):
            table[(length, code)] = symbols[k]
            code += 1
            k += 1
        code <<= 1
    return table


def _decode_symbol(bits, table):
    code = 0
    for length in range(1, 17):
        code = (code << 1) | bits.bit()
        if (length, code) in table:
            return table[(length, code)]
    raise ImageError("JPEG: bad Huffman code")


def _idct(coef, q):
    f = [coef[i] * q[i] for i in range(64)]
    tmp = [0.0] * 64
    for y in range(8):
        for x in range(8):
            c = COS[x]
            tmp[y * 8 + x] = sum(c[u] * f[y * 8 + u] for u in range(8) if f[y * 8 + u])
    out = [0] * 64
    for x in range(8):
        for y in range(8):
            c = COS[y]
            v = sum(c[v_] * tmp[v_ * 8 + x] for v_ in range(8)) + 128
            out[y * 8 + x] = 0 if v < 0 else 255 if v > 255 else int(v + 0.5)
    return out


def decode_jpeg(data):
    pos = 2
    qt, dc_t, ac_t = {}, {}, {}
    comps, frame, restart, progressive, adobe = [], None, 0, False, None
    coefs = {}
    while pos < len(data):
        if data[pos] != 0xFF:
            pos += 1
            continue
        marker = data[pos + 1]
        pos += 2
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
            continue
        if marker == 0xD9:
            break
        length = struct.unpack(">H", data[pos:pos + 2])[0]
        seg = data[pos + 2:pos + length]
        pos += length
        if marker == 0xDB:
            i = 0
            while i < len(seg):
                prec, tid = seg[i] >> 4, seg[i] & 15
                i += 1
                vals = [struct.unpack(">H", seg[i + 2 * k:i + 2 * k + 2])[0] for k in range(64)] if prec else list(seg[i:i + 64])
                i += 128 if prec else 64
                table = [0] * 64
                for k in range(64):
                    table[ZIGZAG[k]] = vals[k]
                qt[tid] = table
        elif marker == 0xC4:
            i = 0
            while i < len(seg):
                cls, tid = seg[i] >> 4, seg[i] & 15
                counts = list(seg[i + 1:i + 17])
                n = sum(counts)
                (ac_t if cls else dc_t)[tid] = _huffman(counts, list(seg[i + 17:i + 17 + n]))
                i += 17 + n
        elif marker in (0xC0, 0xC1, 0xC2):
            progressive = marker == 0xC2
            _, h, w, nc = struct.unpack(">BHHB", seg[:6])
            if nc not in (1, 3, 4):
                raise ImageError(f"JPEG: {nc} components not supported")
            for k in range(nc):
                cid, samp, tq = seg[6 + 3 * k:9 + 3 * k]
                comps.append({"id": cid, "h": samp >> 4, "v": samp & 15, "tq": tq})
            hmax = max(c["h"] for c in comps)
            vmax = max(c["v"] for c in comps)
            mcux, mcuy = -(-w // (8 * hmax)), -(-h // (8 * vmax))
            for c in comps:
                c["bw"], c["bh"] = mcux * c["h"], mcuy * c["v"]
                c["vis_w"] = -(-(-(-w * c["h"] // hmax)) // 8)
                c["vis_h"] = -(-(-(-h * c["v"] // vmax)) // 8)
                coefs[c["id"]] = [[0] * 64 for _ in range(c["bw"] * c["bh"])]
            frame = (w, h, hmax, vmax, mcux, mcuy)
        elif 0xC3 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
            raise ImageError("JPEG: lossless/arithmetic coding not supported")
        elif marker == 0xDD:
            restart = struct.unpack(">H", seg[:2])[0]
        elif marker == 0xEE and seg[:5] == b"Adobe" and len(seg) >= 12:
            adobe = seg[11]
        elif marker == 0xDA:
            if frame is None:
                raise ImageError("JPEG: scan before frame")
            pos = _scan(data, pos, seg, comps, frame, coefs, dc_t, ac_t, restart, progressive)
    if frame is None:
        raise ImageError("JPEG: no frame")
    return _to_rgba(frame, comps, coefs, qt, adobe)


def _scan(data, pos, seg, comps, frame, coefs, dc_t, ac_t, restart, progressive):
    ns = seg[0]
    scomps = []
    for k in range(ns):
        cid, tables = seg[1 + 2 * k], seg[2 + 2 * k]
        c = next(c for c in comps if c["id"] == cid)
        scomps.append((c, tables >> 4, tables & 15))
    ss, se, ah_al = seg[1 + 2 * ns], seg[2 + 2 * ns], seg[3 + 2 * ns]
    ah, al = ah_al >> 4, ah_al & 15
    if not progressive:
        ss, se, ah, al = 0, 63, 0, 0
    bits = _Bits(data, pos)
    pred = {c["id"]: 0 for c, _, _ in scomps}
    state = {"eobrun": 0}
    _, _, _, _, mcux, mcuy = frame

    def block(c, td, ta, coef):
        if ss == 0:
            if ah == 0:
                t = _decode_symbol(bits, dc_t[td])
                pred[c["id"]] += bits.receive(t)
                coef[0] = pred[c["id"]] << al
            elif bits.bit():
                coef[0] |= 1 << al
            if not progressive:
                _ac_sequential(bits, ac_t[ta], coef)
            return
        if ah == 0:
            _ac_first(bits, ac_t[ta], coef, ss, se, al, state)
        else:
            _ac_refine(bits, ac_t[ta], coef, ss, se, al, state)

    count = 0
    if ns == 1:
        c, td, ta = scomps[0]
        for by in range(c["vis_h"]):
            for bx in range(c["vis_w"]):
                if restart and count and count % restart == 0:
                    bits.restart()
                    pred[c["id"]] = 0
                    state["eobrun"] = 0
                block(c, td, ta, coefs[c["id"]][by * c["bw"] + bx])
                count += 1
    else:
        for my in range(mcuy):
            for mx in range(mcux):
                if restart and count and count % restart == 0:
                    bits.restart()
                    for k in pred:
                        pred[k] = 0
                    state["eobrun"] = 0
                for c, td, ta in scomps:
                    for v in range(c["v"]):
                        for h in range(c["h"]):
                            block(c, td, ta, coefs[c["id"]][(my * c["v"] + v) * c["bw"] + mx * c["h"] + h])
                count += 1
    # Continue after the entropy-coded data (next marker that is not a restart)
    p = bits.pos
    while p + 1 < len(data):
        if data[p] == 0xFF and data[p + 1] != 0 and not 0xD0 <= data[p + 1] <= 0xD7:
            break
        p += 1
    return p


def _ac_sequential(bits, table, coef):
    k = 1
    while k < 64:
        rs = _decode_symbol(bits, table)
        r, s = rs >> 4, rs & 15
        if s == 0:
            if r != 15:
                return
            k += 16
            continue
        k += r
        if k > 63:
            return
        coef[ZIGZAG[k]] = bits.receive(s)
        k += 1


def _ac_first(bits, table, coef, ss, se, al, state):
    if state["eobrun"] > 0:
        state["eobrun"] -= 1
        return
    k = ss
    while k <= se:
        rs = _decode_symbol(bits, table)
        r, s = rs >> 4, rs & 15
        if s == 0:
            if r < 15:
                state["eobrun"] = (1 << r) - 1 + (bits.bits(r) if r else 0)
                return
            k += 16
            continue
        k += r
        if k > 63:
            return
        coef[ZIGZAG[k]] = bits.receive(s) * (1 << al)
        k += 1


def _ac_refine(bits, table, coef, ss, se, al, state):
    p1, m1 = 1 << al, -1 << al
    k = ss

    def refine(z):
        if bits.bit() and (coef[z] & p1) == 0:
            coef[z] += p1 if coef[z] >= 0 else m1

    if state["eobrun"] <= 0:
        while k <= se:
            rs = _decode_symbol(bits, table)
            r, s = rs >> 4, rs & 15
            value = 0
            if s == 0:
                if r < 15:
                    state["eobrun"] = (1 << r) + (bits.bits(r) if r else 0)
                    break
            else:
                value = p1 if bits.bit() else m1
            while k <= se:
                z = ZIGZAG[k]
                if coef[z] != 0:
                    refine(z)
                else:
                    if r == 0:
                        if value:
                            coef[z] = value
                        k += 1
                        break
                    r -= 1
                k += 1
    if state["eobrun"] > 0:
        while k <= se:
            z = ZIGZAG[k]
            if coef[z] != 0:
                refine(z)
            k += 1
        state["eobrun"] -= 1


def _to_rgba(frame, comps, coefs, qt, adobe):
    w, h, hmax, vmax, _, _ = frame
    planes = []
    for c in comps:
        pw, ph = c["bw"] * 8, c["bh"] * 8
        plane = bytearray(pw * ph)
        q = qt[c["tq"]]
        for by in range(c["bh"]):
            for bx in range(c["bw"]):
                coef = coefs[c["id"]][by * c["bw"] + bx]
                pix = _idct(coef, q) if any(coef) else [max(0, min(255, int(128.5)))] * 64
                for y in range(8):
                    o = (by * 8 + y) * pw + bx * 8
                    plane[o:o + 8] = bytes(pix[y * 8:y * 8 + 8])
        planes.append((plane, pw, hmax // c["h"], vmax // c["v"]))
    out = bytearray(w * h * 4)
    rgb_direct = adobe == 0 and len(comps) == 3
    for y in range(h):
        for x in range(w):
            vals = [p[(y // sy) * pw + x // sx] for p, pw, sx, sy in planes]
            if len(vals) == 1:
                r = g = b = vals[0]
            elif rgb_direct:
                r, g, b = vals
            elif len(vals) == 4:
                # Adobe CMYK (stored inverted) or YCCK (YCbCr of the plain ink values), times inverted K
                c, m, yv = vals[:3] if adobe != 2 else (255 - _clamp(v) for v in _ycc(vals[0], vals[1], vals[2]))
                k = vals[3] / 255
                r, g, b = c * k, m * k, yv * k
            else:
                r, g, b = _ycc(vals[0], vals[1], vals[2])
            o = (y * w + x) * 4
            out[o:o + 4] = bytes((_clamp(r), _clamp(g), _clamp(b), 255))
    return w, h, out


def _ycc(yy, cb, cr):
    cb, cr = cb - 128, cr - 128
    return yy + 1.402 * cr, yy - 0.344136 * cb - 0.714136 * cr, yy + 1.772 * cb


def _clamp(v):
    return 0 if v < 0 else 255 if v > 255 else int(v + 0.5)
