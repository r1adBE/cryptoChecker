import CryptoKit
import Foundation

// Kurvenrechnung (Teil von `Bip32`): Prüfen (Punkt auf der Kurve), Kindschlüssel (CKDpub), Taproot-Tweak, Punkte
// lesen/schreiben und die eigene 256-Bit-Arithmetik für secp256k1 (Glieder, Feld, Jacobi-Koordinaten) — in `Bip32.kt`
// (Android) mit BigInteger.

extension Bip32 {
    /// Prüfen: nil = gültiger xpub/ypub/zpub (Mainnet); sonst das Problem für `AddressValidation`.
    static func check(_ s: String) -> AddressValidation.Problem? {
        guard let raw = AddressValidation.base58Decode(s) else { return .UNKNOWN_FORMAT }
        guard raw.count == 82 else { return .UNKNOWN_FORMAT }
        let payload = Array(raw[0..<78])
        let sum = sha256(sha256(payload))
        guard Array(sum[0..<4]) == Array(raw[78..<82]) else { return .BAD_CHECKSUM }
        let version = uint32(payload, 0)
        if privateVersions.contains(version) { return .SECRET }
        if testnet.contains(version) { return .TESTNET }
        if multisig.contains(version) { return .EXTENDED_KEY }
        guard version == xpubVersion || version == ypubVersion || version == zpubVersion else { return .UNKNOWN_FORMAT }
        guard decodePoint(Array(payload[45..<78])) != nil else { return .UNKNOWN_FORMAT }
        return nil
    }

    /// Kind `index` (nicht gehärtet, 0…2³¹−1) — CKDpub aus BIP32. Ungültiges Ergebnis (IL ≥ n oder Unendlich,
    /// Wahrscheinlichkeit < 2⁻¹²⁷) oder gehärteter Index = nil.
    static func child(_ key: ExtendedKey, index: Int64) -> ExtendedKey? {
        guard index >= 0, index <= maxIndex, let parent = decodePoint(key.publicKey) else { return nil }
        let data = key.publicKey + bytes32(UInt32(index))
        let mac = Array(HMAC<SHA512>.authenticationCode(for: data, using: SymmetricKey(data: key.chainCode)))
        guard let il = U256(bytes: Array(mac[0..<32])), il < Self.order else { return nil }
        guard let point = Curve.multiplyAdd(il, parent) else { return nil }
        return ExtendedKey(version: key.version, depth: key.depth &+ 1, parentFingerprint: Array(hash160(key.publicKey)[0..<4]),
                           childNumber: UInt32(index), chainCode: Array(mac[32..<64]), publicKey: encodePoint(point),
                           taproot: key.taproot)
    }

    /// Ausgabeschlüssel (32 Byte, x-only) eines Taproot-Kontos ohne Skriptbaum (BIP86/BIP341): P = Punkt mit geradem y
    /// zu x(Schlüssel), t = TaggedHash("TapTweak", x(P)), Q = P + t·G. t ≥ n oder Q unendlich (praktisch nie) = nil.
    static func taprootOutputKey(_ publicKey: [UInt8]) -> [UInt8]? {
        guard publicKey.count == 33 else { return nil }
        let x = Array(publicKey[1..<33])
        guard let p = decodePoint([2] + x), let t = U256(bytes: taggedHash("TapTweak", x)), t < Self.order,
              let q = Curve.multiplyAdd(t, p) else { return nil }
        return q.x.bytes
    }

    /// k·G + Q (k: 32 Byte big-endian, Q komprimiert); Unendlich oder ungültiges Q = nil. Gleicher Weg wie in `child`
    /// und `taprootOutputKey` — offen nur, damit `bip32_fuzz.json` die Kurvenrechnung an Randfällen (k nahe n,
    /// Verdoppeln, Unendlich) prüfen kann, die über HMAC/Tweak nicht gezielt erreichbar sind.
    static func multiplyAddG(_ k: [UInt8], _ q: [UInt8]) -> [UInt8]? {
        guard let scalar = U256(bytes: k), let point = decodePoint(q), let r = Curve.multiplyAdd(scalar, point) else { return nil }
        return encodePoint(r)
    }

    // MARK: Punkte

    /// Ordnung n der Kurve.
    fileprivate static let order = U256([0xBFD25E8CD0364141, 0xBAAEDCE6AF48A03B, 0xFFFFFFFFFFFFFFFE, 0xFFFFFFFFFFFFFFFF])

    /// Komprimierten Punkt lesen; nicht auf der Kurve = nil.
    fileprivate static func decodePoint(_ b: [UInt8]) -> (x: U256, y: U256)? {
        guard b.count == 33, b[0] == 2 || b[0] == 3, let x = U256(bytes: Array(b[1..<33])), x < Field.p else { return nil }
        let rhs = Field.add(Field.mul(Field.mul(x, x), x), U256([7, 0, 0, 0]))
        var y = Field.pow(rhs, Field.sqrtExponent)
        guard Field.mul(y, y) == rhs else { return nil }
        if (y.w[0] & 1 == 1) != (b[0] == 3) { y = Field.sub(U256.zero, y) }
        return (x, y)
    }

    fileprivate static func encodePoint(_ p: (x: U256, y: U256)) -> [UInt8] {
        let prefix: UInt8 = p.y.w[0] & 1 == 1 ? 3 : 2
        return [prefix] + p.x.bytes
    }
}

// MARK: - 256-Bit-Arithmetik (secp256k1)

/// 256-Bit-Zahl, vier 64-Bit-Glieder (niedrigstes zuerst).
fileprivate struct U256: Equatable, Comparable {
    var w: [UInt64]

    init(_ w: [UInt64]) { self.w = w }

    static let zero = U256([0, 0, 0, 0])
    static let one = U256([1, 0, 0, 0])

    /// 32 Byte big-endian; andere Länge = nil.
    init?(bytes: [UInt8]) {
        guard bytes.count == 32 else { return nil }
        var w = [UInt64](repeating: 0, count: 4)
        for i in 0..<32 {
            let limb = 3 - i / 8
            w[limb] = w[limb] << 8 | UInt64(bytes[i])
        }
        self.w = w
    }

    /// 32 Byte big-endian.
    var bytes: [UInt8] {
        var out: [UInt8] = []
        out.reserveCapacity(32)
        for limb in stride(from: 3, through: 0, by: -1) {
            for shift in stride(from: 56, through: 0, by: -8) { out.append(UInt8((w[limb] >> UInt64(shift)) & 0xff)) }
        }
        return out
    }

    func bit(_ i: Int) -> Bool { (w[i / 64] >> UInt64(i % 64)) & 1 == 1 }

    static func < (lhs: U256, rhs: U256) -> Bool {
        for i in stride(from: 3, through: 0, by: -1) where lhs.w[i] != rhs.w[i] { return lhs.w[i] < rhs.w[i] }
        return false
    }
}

/// Rechnen modulo p = 2²⁵⁶ − 2³² − 977 (2²⁵⁶ ≡ C = 0x1000003D1). Wort für Wort gegen Python geprüft.
fileprivate enum Field {
    static let p = U256([0xFFFFFFFEFFFFFC2F, 0xFFFFFFFFFFFFFFFF, 0xFFFFFFFFFFFFFFFF, 0xFFFFFFFFFFFFFFFF])
    static let c: UInt64 = 0x1000003D1
    /// p − 2 (Kehrwert nach Fermat).
    static let inverseExponent = U256([0xFFFFFFFEFFFFFC2D, 0xFFFFFFFFFFFFFFFF, 0xFFFFFFFFFFFFFFFF, 0xFFFFFFFFFFFFFFFF])
    /// (p + 1) / 4 (Wurzel, p ≡ 3 mod 4).
    static let sqrtExponent = U256([0xFFFFFFFFBFFFFF0C, 0xFFFFFFFFFFFFFFFF, 0xFFFFFFFFFFFFFFFF, 0x3FFFFFFFFFFFFFFF])

    private static func addRaw(_ a: U256, _ b: U256) -> (U256, Bool) {
        var r = [UInt64](repeating: 0, count: 4)
        var carry: UInt64 = 0
        for i in 0..<4 {
            let (s1, o1) = a.w[i].addingReportingOverflow(b.w[i])
            let (s2, o2) = s1.addingReportingOverflow(carry)
            r[i] = s2
            let c1: UInt64 = o1 ? 1 : 0
            let c2: UInt64 = o2 ? 1 : 0
            carry = c1 + c2
        }
        return (U256(r), carry != 0)
    }

    private static func subRaw(_ a: U256, _ b: U256) -> (U256, Bool) {
        var r = [UInt64](repeating: 0, count: 4)
        var borrow: UInt64 = 0
        for i in 0..<4 {
            let (s1, o1) = a.w[i].subtractingReportingOverflow(b.w[i])
            let (s2, o2) = s1.subtractingReportingOverflow(borrow)
            r[i] = s2
            let b1: UInt64 = o1 ? 1 : 0
            let b2: UInt64 = o2 ? 1 : 0
            borrow = b1 + b2
        }
        return (U256(r), borrow != 0)
    }

    /// Kleinen Wert addieren; true = Überlauf über 2²⁵⁶.
    private static func addSmall(_ a: inout [UInt64], _ value: UInt64, from start: Int = 0) -> Bool {
        var c = value
        var i = start
        while c != 0 && i < 4 {
            let (s, o) = a[i].addingReportingOverflow(c)
            a[i] = s
            c = o ? 1 : 0
            i += 1
        }
        return c != 0
    }

    static func add(_ a: U256, _ b: U256) -> U256 {
        let (sum, carry) = addRaw(a, b)
        var r = sum
        if carry {
            // a + b − p = r + C (< p, kein weiterer Überlauf)
            _ = addSmall(&r.w, c)
        } else if !(r < p) {
            r = subRaw(r, p).0
        }
        return r
    }

    static func sub(_ a: U256, _ b: U256) -> U256 {
        let (r, borrow) = subRaw(a, b)
        // a − b + p = r − C (r ≥ C + 1, kein Borgen)
        return borrow ? subRaw(r, U256([c, 0, 0, 0])).0 : r
    }

    static func mul(_ a: U256, _ b: U256) -> U256 {
        var t = [UInt64](repeating: 0, count: 8)
        for i in 0..<4 {
            var carry: UInt64 = 0
            for j in 0..<4 {
                let (hi, lo) = a.w[i].multipliedFullWidth(by: b.w[j])
                let (s1, o1) = t[i + j].addingReportingOverflow(lo)
                let (s2, o2) = s1.addingReportingOverflow(carry)
                t[i + j] = s2
                // Schulbuch-Schranke: hi + Überträge passt in 64 Bit
                let c1: UInt64 = o1 ? 1 : 0
                let c2: UInt64 = o2 ? 1 : 0
                carry = hi &+ c1 &+ c2
            }
            t[i + 4] = carry
        }
        return reduce(t)
    }

    /// 512 Bit → mod p: lo + hi·C, Rest nochmals mit C, dann höchstens einige Male − p.
    private static func reduce(_ t: [UInt64]) -> U256 {
        var r = [UInt64](repeating: 0, count: 4)
        var carry: UInt64 = 0
        for i in 0..<4 {
            let (h, l) = t[i + 4].multipliedFullWidth(by: c)
            let (s1, o1) = t[i].addingReportingOverflow(l)
            let (s2, o2) = s1.addingReportingOverflow(carry)
            r[i] = s2
            let c1: UInt64 = o1 ? 1 : 0
            let c2: UInt64 = o2 ? 1 : 0
            carry = h &+ c1 &+ c2
        }
        let (h, l) = carry.multipliedFullWidth(by: c)
        let (s, o) = r[0].addingReportingOverflow(l)
        r[0] = s
        if addSmall(&r, h &+ (o ? 1 : 0), from: 1) {
            _ = addSmall(&r, c)
        }
        var out = U256(r)
        while !(out < p) { out = subRaw(out, p).0 }
        return out
    }

    static func pow(_ a: U256, _ e: U256) -> U256 {
        var r = U256.one
        for i in stride(from: 255, through: 0, by: -1) {
            r = mul(r, r)
            if e.bit(i) { r = mul(r, a) }
        }
        return r
    }

    static func inverse(_ a: U256) -> U256 { pow(a, inverseExponent) }
}

/// Punkte in Jacobi-Koordinaten (x = X/Z², y = Y/Z³), Kurve y² = x³ + 7.
fileprivate enum Curve {
    struct Jacobian {
        var x: U256
        var y: U256
        var z: U256
        var infinity: Bool
        static let infinityPoint = Jacobian(x: .zero, y: .one, z: .zero, infinity: true)
    }

    static let gx = U256([0x59F2815B16F81798, 0x029BFCDB2DCE28D9, 0x55A06295CE870B07, 0x79BE667EF9DCBBAC])
    static let gy = U256([0x9C47D08FFB10D4B8, 0xFD17B448A6855419, 0x5DA4FBFC0E1108A8, 0x483ADA7726A3C465])

    /// dbl-2009-l.
    static func double(_ p: Jacobian) -> Jacobian {
        if p.infinity || p.y == .zero { return .infinityPoint }
        let a = Field.mul(p.x, p.x)
        let b = Field.mul(p.y, p.y)
        let c = Field.mul(b, b)
        let xb = Field.add(p.x, b)
        var d = Field.sub(Field.sub(Field.mul(xb, xb), a), c)
        d = Field.add(d, d)
        let e = Field.add(Field.add(a, a), a)
        let f = Field.mul(e, e)
        let x3 = Field.sub(f, Field.add(d, d))
        var c8 = Field.add(c, c)
        c8 = Field.add(c8, c8)
        c8 = Field.add(c8, c8)
        let y3 = Field.sub(Field.mul(e, Field.sub(d, x3)), c8)
        let yz = Field.mul(p.y, p.z)
        return Jacobian(x: x3, y: y3, z: Field.add(yz, yz), infinity: false)
    }

    /// madd-2007-bl: Jacobi + affin.
    static func addAffine(_ p: Jacobian, _ qx: U256, _ qy: U256) -> Jacobian {
        if p.infinity { return Jacobian(x: qx, y: qy, z: .one, infinity: false) }
        let z1z1 = Field.mul(p.z, p.z)
        let u2 = Field.mul(qx, z1z1)
        let s2 = Field.mul(Field.mul(qy, p.z), z1z1)
        let h = Field.sub(u2, p.x)
        var r = Field.sub(s2, p.y)
        r = Field.add(r, r)
        if h == .zero { return r == .zero ? double(p) : .infinityPoint }
        let hh = Field.mul(h, h)
        var i = Field.add(hh, hh)
        i = Field.add(i, i)
        let j = Field.mul(h, i)
        let v = Field.mul(p.x, i)
        let x3 = Field.sub(Field.sub(Field.mul(r, r), j), Field.add(v, v))
        let y1j = Field.mul(p.y, j)
        let y3 = Field.sub(Field.mul(r, Field.sub(v, x3)), Field.add(y1j, y1j))
        let zh = Field.add(p.z, h)
        let z3 = Field.sub(Field.sub(Field.mul(zh, zh), z1z1), hh)
        return Jacobian(x: x3, y: y3, z: z3, infinity: false)
    }

    /// k·G + Q (affin); Unendlich = nil.
    static func multiplyAdd(_ k: U256, _ q: (x: U256, y: U256)) -> (x: U256, y: U256)? {
        var r = Jacobian.infinityPoint
        for i in stride(from: 255, through: 0, by: -1) {
            r = double(r)
            if k.bit(i) { r = addAffine(r, gx, gy) }
        }
        r = addAffine(r, q.x, q.y)
        if r.infinity { return nil }
        let zi = Field.inverse(r.z)
        let zi2 = Field.mul(zi, zi)
        return (Field.mul(r.x, zi2), Field.mul(Field.mul(r.y, zi2), zi))
    }
}
