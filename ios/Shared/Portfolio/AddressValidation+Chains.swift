import CryptoKit
import Foundation

// Weitere Ketten (Teil von `AddressValidation`): Cardano (Shelley), XRP (Ripple-Base58) und TON (roh und
// benutzerfreundlich, Base64 mit CRC16) — wie `AddressValidation.kt` (Android).

extension AddressValidation {
    // MARK: Cardano

    /// Shelley-Adresse (Bech32, Länge bis `maxInput`): Kopfbyte Typ 0…7 und Netz-ID 1 (Mainnet). Wie Android.
    static func cardano(_ input: String) -> Check {
        let scalars = Array(input.unicodeScalars)
        let hasLower = scalars.contains { $0.value >= 97 && $0.value <= 122 }
        let hasUpper = scalars.contains { $0.value >= 65 && $0.value <= 90 }
        if hasLower && hasUpper { return invalid(.UNKNOWN_FORMAT) }
        let s = input.lowercased()
        let chars = Array(s)
        guard let sep = chars.lastIndex(of: "1"), sep >= 1, sep + 7 <= chars.count else { return invalid(.UNKNOWN_FORMAT) }
        let hrp = String(chars[0..<sep])
        var data: [Int] = []
        for c in chars[(sep + 1)...] {
            guard let v = charset.firstIndex(of: c) else { return invalid(.UNKNOWN_FORMAT) }
            data.append(v)
        }
        let hrpBytes = hrp.unicodeScalars.map { Int($0.value) }
        guard polymod(hrpBytes.map { $0 >> 5 } + [0] + hrpBytes.map { $0 & 31 } + data) == bech32Const else {
            return invalid(.BAD_CHECKSUM)
        }
        if hrp == "addr_test" || hrp == "stake_test" { return invalid(.TESTNET) }
        if hrp == "stake" { return invalid(.UNSUPPORTED_VERSION) }
        guard hrp == "addr" else { return invalid(.UNKNOWN_FORMAT) }
        guard let bytes = convertBits(Array(data[0..<(data.count - 6)])), bytes.count >= 29 else { return invalid(.UNKNOWN_FORMAT) }
        let type = bytes[0] >> 4
        let network = bytes[0] & 0x0f
        if type > 7 { return invalid(.UNKNOWN_FORMAT) }
        if network != 1 { return invalid(.TESTNET) }
        return Check(chain: .ADA, kind: .ADA, normalized: s, problem: nil)
    }

    /// «[1, 2, …]» mit 32 bis 64 Zahlen 0…255 (Schlüsseldatei).
    static func isByteArrayText(_ s: String) -> Bool {
        guard s.hasSuffix("]"), s.count >= 2 else { return false }
        let parts = s.dropFirst().dropLast().split(separator: ",", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespaces) }
        return (32...64).contains(parts.count) && parts.allSatisfy { p in
            !p.isEmpty && p.count <= 3 && p.allSatisfy { $0.isASCII && $0.isNumber } && (Int(p) ?? 999) <= 255
        }
    }

    // MARK: XRP

    private static let rippleAlphabet = Array("rpshnaf39wBUDNEGHJKLM4PQRST7VWXYZ2bcdeCg65jkm8oFqi1tuvAxyz")

    /// Ripple-Base58 mit gültiger Prüfsumme → Nutzdaten ohne Prüfsumme; sonst nil.
    static func rippleChecked(_ s: String) -> [UInt8]? {
        guard let raw = base58Decode(s, alphabet: rippleAlphabet), checksumOk(raw) else { return nil }
        return Array(raw[0..<(raw.count - 4)])
    }

    /// Klassische XRP-Adresse; nil = keine (weiter mit Base58).
    static func xrp(_ s: String) -> Check? {
        guard let raw = base58Decode(s, alphabet: rippleAlphabet), raw.count == 25, raw[0] == 0 else { return nil }
        guard checksumOk(raw) else { return invalid(.BAD_CHECKSUM) }
        return Check(chain: .XRP, kind: .XRP, normalized: s, problem: nil)
    }

    // MARK: TON

    /// «0:<64 Hex>» (Basis-Workchain); «-1:…» Masterchain nicht unterstützt; nil = keine rohe Adresse.
    static func tonRaw(_ s: String) -> Check? {
        guard let sep = s.firstIndex(of: ":") else { return nil }
        let wc = String(s[..<sep])
        let hex = String(s[s.index(after: sep)...])
        guard !wc.isEmpty, hex.count == 64, hex.allSatisfy(isHex), wc == "0" || wc == "-1" else { return nil }
        if wc == "-1" { return invalid(.UNSUPPORTED_VERSION) }
        return Check(chain: .TON, kind: .TON, normalized: "0:" + hex.lowercased(), problem: nil)
    }

    /// Benutzerfreundliche TON-Adresse (36 Byte: Kennung, Workchain, 32 Byte, CRC16); nil = keine.
    static func tonFriendly(_ s: String) -> Check? {
        guard let raw = base64Decode(s), raw.count == 36 else { return nil }
        let tag = Int(raw[0])
        guard tag & 0x7f == 0x11 || tag & 0x7f == 0x51 else { return nil }
        let crc = crc16(Array(raw[0..<34]))
        guard (Int(raw[34]) << 8 | Int(raw[35])) == crc else { return invalid(.BAD_CHECKSUM) }
        if tag & 0x80 != 0 { return invalid(.TESTNET) }
        switch raw[1] {
        case 0x00: return Check(chain: .TON, kind: .TON, normalized: s, problem: nil)
        case 0xff: return invalid(.UNSUPPORTED_VERSION)
        default: return invalid(.UNKNOWN_FORMAT)
        }
    }

    /// Base64 (Standard oder URL-sicher, ohne Auffüllen); ungültig = nil.
    static func base64Decode(_ s: String) -> [UInt8]? {
        var out: [UInt8] = []
        var acc = 0
        var bits = 0
        for scalar in s.unicodeScalars {
            let c = Int(scalar.value)
            let v: Int
            switch c {
            case 65...90: v = c - 65
            case 97...122: v = c - 97 + 26
            case 48...57: v = c - 48 + 52
            case 43, 45: v = 62
            case 47, 95: v = 63
            default: return nil
            }
            acc = (acc << 6) | v
            bits += 6
            if bits >= 8 {
                bits -= 8
                out.append(UInt8((acc >> bits) & 0xff))
            }
            acc &= 0xffff
        }
        return out
    }

    /// CRC16-XMODEM (Polynom 0x1021, Start 0) — TON-Adressen.
    static func crc16(_ data: [UInt8]) -> Int {
        var crc = 0
        for b in data {
            crc ^= Int(b) << 8
            for _ in 0..<8 { crc = crc & 0x8000 != 0 ? (crc << 1) ^ 0x1021 : crc << 1 }
            crc &= 0xffff
        }
        return crc
    }
}
