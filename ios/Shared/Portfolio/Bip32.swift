import CryptoKit
import Foundation

/// Erweiterte ÖFFENTLICHE Bitcoin-Schlüssel (xpub/ypub/zpub, BIP32) lesen und Adressen daraus ableiten — nur
/// öffentliche Ableitung (CKDpub, nicht gehärtet), nie private Schlüssel. Gleich wie `Bip32.kt`.
///
/// Adresstyp nach Präfix (Versionsbytes BIP32/BIP49/BIP84):
///  - xpub (0x0488B21E) → P2PKH «1…» (BIP44). Manche Wallets (Ledger Live) zeigen auch für Segwit-Konten eine xpub:
///    findet die Suche als P2PKH nichts, prüft sie P2SH-P2WPKH und P2WPKH desselben Schlüssels (`XpubScan.scripts`) und
///    merkt sich den Typ mit Verlauf (`XpubCache.script`).
///  - ypub (0x049D7CB2) → P2SH-P2WPKH «3…» (BIP49)
///  - zpub (0x04B24746) → P2WPKH «bc1q…» (BIP84)
///  - Taproot-Konto (BIP86) → P2TR «bc1p…»: für BIP86 gibt es kein SLIP-132-Präfix, Wallets geben einen Deskriptor
///    `tr([fp/86'/0'/0']xpub…)` mit den Ketten 0 und 1 aus (`AddressValidation` liest ihn). Gespeichert wird die
///    Normalform (`taprootDescriptor`); `parse` liest auch diese (`ExtendedKey.taproot`). Adresse: x-only-Schlüssel
///    mit BIP341-Tweak (TapTweak, Q = P + t·G), Bech32m Version 1 (`taprootOutputKey`).
/// Ketten: 0 = Empfang, 1 = Wechselgeld; Adresse i = Schlüssel/Kette/i.
/// Mathematik: secp256k1 mit eigener 256-Bit-Arithmetik (vier 64-Bit-Glieder, Jacobi-Koordinaten; Android rechnet mit
/// BigInteger), RIPEMD-160 selbst. Geprüft mit den offiziellen Testvektoren (BIP32 Vektor 1/2, BIP84, BIP49) in
/// `address_balance.json`, BIP86 und BIP350 in `taproot.json`.
enum Bip32 {

    /// Adresstyp; `code` ist die gespeicherte Form (`XpubCache.script`), gleich wie die Namen in Kotlin.
    enum Script: CaseIterable, Sendable {
        case p2pkh, p2shP2wpkh, p2wpkh, p2tr

        var code: String {
            switch self {
            case .p2pkh: return "P2PKH"
            case .p2shP2wpkh: return "P2SH_P2WPKH"
            case .p2wpkh: return "P2WPKH"
            case .p2tr: return "P2TR"
            }
        }

        init?(code: String) {
            guard let s = Script.allCases.first(where: { $0.code == code }) else { return nil }
            self = s
        }
    }

    static let xpubVersion: UInt32 = 0x0488B21E
    static let ypubVersion: UInt32 = 0x049D7CB2
    static let zpubVersion: UInt32 = 0x04B24746

    /// Multisig (Ypub/Zpub): nicht unterstützt.
    private static let multisig: Set<UInt32> = [0x0295B43F, 0x02AA7ED3]
    /// Testnet tpub/upub/vpub/Upub/Vpub.
    private static let testnet: Set<UInt32> = [0x043587CF, 0x044A5262, 0x045F1CF6, 0x024289EF, 0x02575483]
    /// Private Versionen (xprv … Vprv) — nie annehmen.
    private static let privateVersions: Set<UInt32> = [
        0x0488ADE4, 0x049D7878, 0x04B2430C, 0x0295B005, 0x02AA7A99,
        0x04358394, 0x044A4E28, 0x045F18BC, 0x024285B5, 0x02575048,
    ]

    /// Grösster nicht gehärteter Index (2³¹ − 1).
    static let maxIndex: Int64 = 0x7fffffff

    /// Erweiterter öffentlicher Schlüssel (78 Byte ohne Prüfsumme).
    struct ExtendedKey: Equatable, Sendable {
        let version: UInt32
        let depth: UInt8
        let parentFingerprint: [UInt8]
        let childNumber: UInt32
        let chainCode: [UInt8]
        /// Komprimiert, 33 Byte (0x02/0x03 + x).
        let publicKey: [UInt8]
        /// Taproot-Konto (BIP86, aus `tr(…)`): Adressen P2TR; die Versionsbytes sind die eines xpub.
        var taproot: Bool = false

        var script: Script {
            if taproot { return .p2tr }
            switch version {
            case Bip32.ypubVersion: return .p2shP2wpkh
            case Bip32.zpubVersion: return .p2wpkh
            default: return .p2pkh
            }
        }

        /// Adresstyp unbekannt (xpub ohne Deskriptor): die Suche prüft mehrere Typen (`XpubScan.scripts`).
        var detectsScript: Bool { version == Bip32.xpubVersion && !taproot }
    }

    private static let taprootSuffix = "/<0;1>/*"

    /// Normalform eines Taproot-Kontos: beide Ketten (BIP389), ohne Herkunft und Prüfsumme.
    static func taprootDescriptor(_ xpub: String) -> String { "tr(" + xpub + taprootSuffix + ")" }

    /// xpub aus der Normalform `taprootDescriptor`; anderes = nil.
    static func taprootAccount(_ s: String) -> String? {
        guard s.hasPrefix("tr("), s.hasSuffix(taprootSuffix + ")") else { return nil }
        let inner = s.dropFirst(3).dropLast(taprootSuffix.count + 1)
        return inner.isEmpty ? nil : String(inner)
    }

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

    /// Lesen; ungültig (auch Testnet, Multisig, privat) = nil. Auch die Normalform eines Taproot-Kontos (nur xpub).
    static func parse(_ s: String) -> ExtendedKey? {
        if let inner = taprootAccount(s) {
            guard var key = parse(inner), key.version == xpubVersion else { return nil }
            key.taproot = true
            return key
        }
        guard check(s) == nil, let raw = AddressValidation.base58Decode(s), raw.count == 82 else { return nil }
        return ExtendedKey(version: uint32(raw, 0), depth: raw[4], parentFingerprint: Array(raw[5..<9]),
                           childNumber: uint32(raw, 9), chainCode: Array(raw[13..<45]), publicKey: Array(raw[45..<78]))
    }

    /// Zurück in die Textform (Base58Check).
    static func serialize(_ key: ExtendedKey) -> String {
        var out: [UInt8] = []
        out.reserveCapacity(78)
        out += bytes32(key.version)
        out.append(key.depth)
        out += key.parentFingerprint
        out += bytes32(key.childNumber)
        out += key.chainCode
        out += key.publicKey
        return base58Check(out)
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

    /// Adresse eines komprimierten öffentlichen Schlüssels; nil nur bei ungültigem Taproot-Tweak (praktisch nie).
    static func address(_ script: Script, publicKey: [UInt8]) -> String? {
        let h = hash160(publicKey)
        switch script {
        case .p2pkh: return base58Check([0x00] + h)
        case .p2shP2wpkh: return base58Check([0x05] + hash160([0x00, 0x14] + h))
        case .p2wpkh: return bech32V0(hrp: "bc", program: h)
        case .p2tr: return taprootOutputKey(publicKey).map { segwitAddress(hrp: "bc", version: 1, program: $0) }
        }
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

    /// BIP340: SHA-256(SHA-256(tag) ‖ SHA-256(tag) ‖ data).
    static func taggedHash(_ tag: String, _ data: [UInt8]) -> [UInt8] {
        let t = sha256(Array(tag.utf8))
        return sha256(t + t + data)
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

    // MARK: Hilfen

    private static func uint32(_ b: [UInt8], _ at: Int) -> UInt32 {
        UInt32(b[at]) << 24 | UInt32(b[at + 1]) << 16 | UInt32(b[at + 2]) << 8 | UInt32(b[at + 3])
    }

    private static func bytes32(_ v: UInt32) -> [UInt8] {
        [UInt8(v >> 24 & 0xff), UInt8(v >> 16 & 0xff), UInt8(v >> 8 & 0xff), UInt8(v & 0xff)]
    }

    private static func sha256(_ data: [UInt8]) -> [UInt8] { Array(SHA256.hash(data: data)) }

    static func hash160(_ data: [UInt8]) -> [UInt8] { ripemd160(sha256(data)) }

    private static let base58Alphabet = Array("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")

    /// Bytes + 4 Byte Prüfsumme (doppeltes SHA-256) → Base58.
    static func base58Check(_ payload: [UInt8]) -> String {
        let data = payload + Array(sha256(sha256(payload))[0..<4])
        var digits: [Int] = [] // Basis 58, niedrigste Stelle zuerst
        for byte in data {
            var carry = Int(byte)
            for i in digits.indices {
                carry += digits[i] << 8
                digits[i] = carry % 58
                carry /= 58
            }
            while carry > 0 {
                digits.append(carry % 58)
                carry /= 58
            }
        }
        var out = String(repeating: "1", count: data.prefix(while: { $0 == 0 }).count)
        for d in digits.reversed() { out.append(base58Alphabet[d]) }
        return out
    }

    private static let charset = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l")
    private static let gen: [UInt32] = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]

    private static let bech32mConst: UInt32 = 0x2bc830a3

    /// Segwit v0 (Bech32, BIP173).
    static func bech32V0(hrp: String, program: [UInt8]) -> String { segwitAddress(hrp: hrp, version: 0, program: program) }

    /// Segwit-Adresse: Version 0 mit Bech32 (BIP173), ab Version 1 mit Bech32m (BIP350).
    static func segwitAddress(hrp: String, version: Int, program: [UInt8]) -> String {
        precondition((0...16).contains(version))
        var data: [Int] = [version]
        var acc = 0
        var bits = 0
        for b in program {
            acc = (acc << 8) | Int(b)
            bits += 8
            while bits >= 5 {
                bits -= 5
                data.append((acc >> bits) & 31)
            }
            acc &= 0xff
        }
        if bits > 0 { data.append((acc << (5 - bits)) & 31) }
        let hrpValues = hrp.unicodeScalars.map { Int($0.value) }
        // In Schritten mit festen Typen (Array-`+` mit Literalen und Closures ist für den Type-Checker teuer)
        let hrpHigh: [Int] = hrpValues.map { $0 >> 5 }
        let hrpLow: [Int] = hrpValues.map { $0 & 31 }
        var values: [Int] = hrpHigh
        values.append(0)
        values.append(contentsOf: hrpLow)
        values.append(contentsOf: data)
        values.append(contentsOf: [Int](repeating: 0, count: 6))
        var chk: UInt32 = 1
        for v in values {
            let top = chk >> 25
            chk = ((chk & 0x1ffffff) << 5) ^ UInt32(v)
            for i in 0..<5 where (top >> UInt32(i)) & 1 == 1 { chk ^= gen[i] }
        }
        let mod = chk ^ (version == 0 ? 1 : bech32mConst)
        var out = hrp + "1"
        for d in data { out.append(charset[d]) }
        for i in 0..<6 { out.append(charset[Int((mod >> UInt32(5 * (5 - i))) & 31)]) }
        return out
    }

    // MARK: RIPEMD-160

    private static let rl: [Int] = [
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
        3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12,
        1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2,
        4, 0, 5, 9, 7, 12, 2, 10, 14, 1, 3, 8, 11, 6, 15, 13,
    ]
    private static let rr: [Int] = [
        5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12,
        6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2,
        15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13,
        8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14,
        12, 15, 10, 4, 1, 5, 8, 7, 6, 2, 13, 14, 0, 3, 9, 11,
    ]
    private static let sl: [UInt32] = [
        11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8,
        7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12,
        11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5,
        11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12,
        9, 15, 5, 11, 6, 8, 13, 12, 5, 12, 13, 14, 11, 8, 5, 6,
    ]
    private static let sr: [UInt32] = [
        8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6,
        9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11,
        9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5,
        15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8,
        8, 5, 12, 9, 12, 5, 14, 6, 8, 13, 6, 5, 15, 13, 11, 11,
    ]
    private static let kl: [UInt32] = [0x00000000, 0x5A827999, 0x6ED9EBA1, 0x8F1BBCDC, 0xA953FD4E]
    private static let kr: [UInt32] = [0x50A28BE6, 0x5C4DD124, 0x6D703EF3, 0x7A6D76E9, 0x00000000]

    private static func rotl(_ x: UInt32, _ n: UInt32) -> UInt32 { (x << n) | (x >> (32 - n)) }

    private static func f(_ j: Int, _ x: UInt32, _ y: UInt32, _ z: UInt32) -> UInt32 {
        switch j / 16 {
        case 0: return x ^ y ^ z
        case 1: return (x & y) | (~x & z)
        case 2: return (x | ~y) ^ z
        case 3: return (x & z) | (y & ~z)
        default: return x ^ (y | ~z)
        }
    }

    static func ripemd160(_ message: [UInt8]) -> [UInt8] {
        // Auffüllen: 0x80, Nullen, Länge in Bit (64 Bit, little-endian)
        let bitLen = UInt64(message.count) * 8
        var data = message
        data.append(0x80)
        while data.count % 64 != 56 { data.append(0) }
        for i in 0..<8 { data.append(UInt8((bitLen >> UInt64(8 * i)) & 0xff)) }

        var h0: UInt32 = 0x67452301
        var h1: UInt32 = 0xEFCDAB89
        var h2: UInt32 = 0x98BADCFE
        var h3: UInt32 = 0x10325476
        var h4: UInt32 = 0xC3D2E1F0
        var x = [UInt32](repeating: 0, count: 16)
        var block = 0
        while block < data.count {
            for i in 0..<16 {
                let o = block + i * 4
                x[i] = UInt32(data[o]) | UInt32(data[o + 1]) << 8 | UInt32(data[o + 2]) << 16 | UInt32(data[o + 3]) << 24
            }
            var al = h0, bl = h1, cl = h2, dl = h3, el = h4
            var ar = h0, br = h1, cr = h2, dr = h3, er = h4
            for j in 0..<80 {
                var t = rotl(al &+ f(j, bl, cl, dl) &+ x[rl[j]] &+ kl[j / 16], sl[j]) &+ el
                al = el; el = dl; dl = rotl(cl, 10); cl = bl; bl = t
                t = rotl(ar &+ f(79 - j, br, cr, dr) &+ x[rr[j]] &+ kr[j / 16], sr[j]) &+ er
                ar = er; er = dr; dr = rotl(cr, 10); cr = br; br = t
            }
            let t = h1 &+ cl &+ dr
            h1 = h2 &+ dl &+ er
            h2 = h3 &+ el &+ ar
            h3 = h4 &+ al &+ br
            h4 = h0 &+ bl &+ cr
            h0 = t
            block += 64
        }
        var out: [UInt8] = []
        out.reserveCapacity(20)
        for h in [h0, h1, h2, h3, h4] {
            for i in 0..<4 { out.append(UInt8((h >> UInt32(8 * i)) & 0xff)) }
        }
        return out
    }
}

/// Adressen eines xpub/ypub/zpub (oder Taproot-Kontos in Normalform) der Reihe nach (Kette 0 = Empfang, 1 = Wechselgeld);
/// die Kettenschlüssel werden einmal abgeleitet. Wie `XpubDeriver` (Kotlin). Ungültiger Schlüssel: `init` liefert nil.
/// `script` = anderer Adresstyp als nach Präfix (nur für xpub sinnvoll, `XpubScan.scripts`).
final class XpubDeriver {
    private let root: Bip32.ExtendedKey
    private var chains: [Int: Bip32.ExtendedKey] = [:]
    /// Adresstyp: `script` (xpub mit Segwit-Konto), sonst nach Präfix (`ExtendedKey.script`).
    let script: Bip32.Script

    init?(_ xpub: String, script: Bip32.Script? = nil) {
        guard let root = Bip32.parse(xpub) else { return nil }
        self.root = root
        self.script = script ?? root.script
    }

    /// Adresse `index` der Kette `chain`; nil nur bei ungültiger Ableitung (praktisch nie).
    func address(chain: Int, index: Int) -> String? {
        guard chain == 0 || chain == 1, index >= 0 else { return nil }
        if chains[chain] == nil { chains[chain] = Bip32.child(root, index: Int64(chain)) }
        guard let chainKey = chains[chain], let key = Bip32.child(chainKey, index: Int64(index)) else { return nil }
        return Bip32.address(script, publicKey: key.publicKey)
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
