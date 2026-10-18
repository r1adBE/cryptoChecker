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
    static let multisig: Set<UInt32> = [0x0295B43F, 0x02AA7ED3]
    /// Testnet tpub/upub/vpub/Upub/Vpub.
    static let testnet: Set<UInt32> = [0x043587CF, 0x044A5262, 0x045F1CF6, 0x024289EF, 0x02575483]
    /// Private Versionen (xprv … Vprv) — nie annehmen.
    static let privateVersions: Set<UInt32> = [
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

    /// BIP340: SHA-256(SHA-256(tag) ‖ SHA-256(tag) ‖ data).
    static func taggedHash(_ tag: String, _ data: [UInt8]) -> [UInt8] {
        let t = sha256(Array(tag.utf8))
        return sha256(t + t + data)
    }

    // MARK: Hilfen

    static func uint32(_ b: [UInt8], _ at: Int) -> UInt32 {
        UInt32(b[at]) << 24 | UInt32(b[at + 1]) << 16 | UInt32(b[at + 2]) << 8 | UInt32(b[at + 3])
    }

    static func bytes32(_ v: UInt32) -> [UInt8] {
        [UInt8(v >> 24 & 0xff), UInt8(v >> 16 & 0xff), UInt8(v >> 8 & 0xff), UInt8(v & 0xff)]
    }

    static func sha256(_ data: [UInt8]) -> [UInt8] { Array(SHA256.hash(data: data)) }

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
