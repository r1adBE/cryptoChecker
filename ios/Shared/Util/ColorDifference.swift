import Foundation

/// Farbabstand nach CIEDE2000 (Sharma, Wu, Dalal 2005) — wie Android `ColorDifference.kt`.
/// Damit prüft `PriceColorScheme`, ob eine Kursfarbe neben der Akzentfarbe zu ähnlich aussieht
/// (ΔE2000 unter `PriceColorScheme.minAccentDeltaE`).
enum ColorDifference {

    /// CIELAB-Wert (D65).
    struct Lab: Equatable, Sendable {
        let l: Double
        let a: Double
        let b: Double
    }

    /// sRGB (0xRRGGBB) → CIELAB (D65).
    static func lab(_ rgb: UInt32) -> Lab {
        let r: Double = linear((rgb >> 16) & 0xFF)
        let g: Double = linear((rgb >> 8) & 0xFF)
        let bl: Double = linear(rgb & 0xFF)
        // Terme einzeln (Typprüfung des Swift-Compilers)
        let xs: Double = 0.4124 * r + 0.3576 * g + 0.1805 * bl
        let x: Double = xs / 0.95047
        let y: Double = 0.2126 * r + 0.7152 * g + 0.0722 * bl
        let zs: Double = 0.0193 * r + 0.1192 * g + 0.9505 * bl
        let z: Double = zs / 1.08883
        let fx: Double = f(x)
        let fy: Double = f(y)
        let fz: Double = f(z)
        return Lab(l: 116 * fy - 16, a: 500 * (fx - fy), b: 200 * (fy - fz))
    }

    /// ΔE2000 zweier sRGB-Farben.
    static func deltaE2000(_ rgb1: UInt32, _ rgb2: UInt32) -> Double {
        deltaE2000(lab(rgb1), lab(rgb2))
    }

    /// ΔE2000 zweier CIELAB-Werte (kL = kC = kH = 1).
    static func deltaE2000(_ p: Lab, _ q: Lab) -> Double {
        let rad: Double = Double.pi / 180
        let cBar: Double = (hypot(p.a, p.b) + hypot(q.a, q.b)) / 2
        let cBar7: Double = pow(cBar, 7)
        let g: Double = 0.5 * (1 - sqrt(cBar7 / (cBar7 + pow25to7)))
        let a1p: Double = (1 + g) * p.a
        let a2p: Double = (1 + g) * q.a
        let c1p: Double = hypot(a1p, p.b)
        let c2p: Double = hypot(a2p, q.b)
        let h1: Double = hue(p.b, a1p)
        let h2: Double = hue(q.b, a2p)
        let dL: Double = q.l - p.l
        let dC: Double = c2p - c1p
        let product: Double = c1p * c2p
        var dh: Double = h2 - h1
        if product == 0 { dh = 0 } else if dh > 180 { dh -= 360 } else if dh < -180 { dh += 360 }
        let dH: Double = 2 * sqrt(product) * sin(dh / 2 * rad)
        let lBar: Double = (p.l + q.l) / 2
        let cBarP: Double = (c1p + c2p) / 2
        let hBar: Double
        if product == 0 {
            hBar = h1 + h2
        } else if abs(h1 - h2) <= 180 {
            hBar = (h1 + h2) / 2
        } else if h1 + h2 < 360 {
            hBar = (h1 + h2 + 360) / 2
        } else {
            hBar = (h1 + h2 - 360) / 2
        }
        let k1: Double = 0.17 * cos((hBar - 30) * rad)
        let k2: Double = 0.24 * cos(2 * hBar * rad)
        let k3: Double = 0.32 * cos((3 * hBar + 6) * rad)
        let k4: Double = 0.20 * cos((4 * hBar - 63) * rad)
        let t: Double = 1 - k1 + k2 + k3 - k4
        let dTheta: Double = 30 * exp(-pow((hBar - 275) / 25, 2))
        let cBarP7: Double = pow(cBarP, 7)
        let rc: Double = 2 * sqrt(cBarP7 / (cBarP7 + pow25to7))
        let lOff: Double = pow(lBar - 50, 2)
        let sl: Double = 1 + 0.015 * lOff / sqrt(20 + lOff)
        let sc: Double = 1 + 0.045 * cBarP
        let sh: Double = 1 + 0.015 * cBarP * t
        let rt: Double = -sin(2 * dTheta * rad) * rc
        let tL: Double = dL / sl
        let tC: Double = dC / sc
        let tH: Double = dH / sh
        let sum: Double = tL * tL + tC * tC + tH * tH
        return sqrt(sum + rt * tC * tH)
    }

    /// 25^7
    private static let pow25to7: Double = 6_103_515_625

    private static func linear(_ channel: UInt32) -> Double {
        let s = Double(channel) / 255
        return s <= 0.04045 ? s / 12.92 : pow((s + 0.055) / 1.055, 2.4)
    }

    private static func f(_ t: Double) -> Double {
        t > 216.0 / 24389.0 ? cbrt(t) : (24389.0 / 27.0 * t + 16.0) / 116.0
    }

    /// Farbwinkel in Grad 0 ..< 360; ohne Buntheit 0.
    private static func hue(_ b: Double, _ a: Double) -> Double {
        if a == 0 && b == 0 { return 0 }
        let h: Double = atan2(b, a) * 180 / Double.pi
        return (h + 360).truncatingRemainder(dividingBy: 360)
    }
}
