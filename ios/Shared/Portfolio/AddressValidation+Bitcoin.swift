import CryptoKit
import Foundation

// Bitcoin-Kodierungen (Teil von `AddressValidation`): Base58Check (auch Ripple-Alphabet) sowie Bech32/Bech32m
// für Segwit und Taproot — wie `AddressValidation.kt` (Android).

extension AddressValidation {
    // MARK: Bitcoin Base58Check

    private static let base58Alphabet = Array("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")

    /// Base58 → Bytes (führende «1» = Null-Bytes); ungültiges Zeichen = nil.
    static func base58Decode(_ s: String) -> [UInt8]? { base58Decode(s, alphabet: base58Alphabet) }

    /// Base58 mit eigenem Alphabet (Ripple); führende Null-Ziffer = Null-Bytes.
    static func base58Decode(_ s: String, alphabet: [Character]) -> [UInt8]? {
        var number: [Int] = [] // Basis 256, niedrigstes Byte zuerst
        for ch in s {
            guard var carry = alphabet.firstIndex(of: ch) else { return nil }
            for i in number.indices {
                carry += number[i] * 58
                number[i] = carry & 0xff
                carry >>= 8
            }
            while carry > 0 {
                number.append(carry & 0xff)
                carry >>= 8
            }
        }
        let zeros = s.prefix(while: { $0 == alphabet[0] }).count
        return [UInt8](repeating: 0, count: zeros) + number.reversed().map { UInt8($0) }
    }

    private static func sha256(_ data: [UInt8]) -> [UInt8] { Array(SHA256.hash(data: data)) }

    static func checksumOk(_ raw: [UInt8]) -> Bool {
        guard raw.count >= 5 else { return false }
        let payload = Array(raw[0..<(raw.count - 4)])
        let sum = sha256(sha256(payload))
        return Array(sum[0..<4]) == Array(raw[(raw.count - 4)...])
    }

    static func base58(_ s: String) -> Check {
        guard let raw = base58Decode(s) else { return invalid(.UNKNOWN_FORMAT) }
        let version = raw.first.map { Int($0) } ?? -1
        switch raw.count {
        case 25:
            guard checksumOk(raw) else { return invalid(.BAD_CHECKSUM) }
            switch version {
            case 0x00: return Check(chain: .BTC, kind: .P2PKH, normalized: s, problem: nil)
            case 0x05: return Check(chain: .BTC, kind: .P2SH, normalized: s, problem: nil)
            case 0x30: return Check(chain: .LTC, kind: .P2PKH, normalized: s, problem: nil)
            case 0x32: return Check(chain: .LTC, kind: .P2SH, normalized: s, problem: nil)
            case 0x41: return Check(chain: .TRX, kind: .TRX, normalized: s, problem: nil)
            case 0x1e, 0x16: return invalid(.DOGE_UNSUPPORTED)
            case 0x6f, 0xc4, 0x3a: return invalid(.TESTNET)
            default: return invalid(.UNKNOWN_FORMAT)
            }
        // WIF: privater Schlüssel (0x80 Bitcoin, 0xb0 Litecoin, 0xef Testnet; 33 bzw. 34 Byte + Prüfsumme)
        case 37, 38:
            return (version == 0x80 || version == 0xb0 || version == 0x9e || version == 0xef) && checksumOk(raw) ? invalid(.SECRET) : invalid(.UNKNOWN_FORMAT)
        // Solana: öffentlicher Schlüssel (32 Byte, ohne Prüfsumme) bzw. privater Schlüssel (64 Byte)
        case 32:
            return (32...44).contains(s.count) ? Check(chain: .SOL, kind: .SOL, normalized: s, problem: nil) : invalid(.UNKNOWN_FORMAT)
        case 64:
            return invalid(.SECRET)
        default:
            return invalid(.UNKNOWN_FORMAT)
        }
    }

    // MARK: Bitcoin Bech32 / Bech32m

    static let charset = Array("qpzry9x8gf2tvdw0s3jn54khce6mua7l")
    static let bech32Const = 1
    private static let bech32mConst = 0x2bc830a3
    private static let gen = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]

    static func polymod(_ values: [Int]) -> Int {
        var chk = 1
        for v in values {
            let top = chk >> 25
            chk = ((chk & 0x1ffffff) << 5) ^ v
            for i in 0..<5 where (top >> i) & 1 == 1 { chk ^= gen[i] }
        }
        return chk
    }

    static func segwit(_ input: String) -> Check {
        let scalars = Array(input.unicodeScalars)
        guard scalars.count >= 8, scalars.count <= 90 else { return invalid(.UNKNOWN_FORMAT) }
        guard scalars.allSatisfy({ $0.value >= 33 && $0.value <= 126 }) else { return invalid(.UNKNOWN_FORMAT) }
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
        if hrp == "tb" || hrp == "bcrt" || hrp == "tltc" { return invalid(.TESTNET) }
        let chain: AddressChain
        switch hrp {
        case "bc": chain = .BTC
        case "ltc": chain = .LTC
        default: return invalid(.UNKNOWN_FORMAT)
        }

        let hrpBytes = hrp.unicodeScalars.map { Int($0.value) }
        let expanded = hrpBytes.map { $0 >> 5 } + [0] + hrpBytes.map { $0 & 31 } + data
        let bech32m: Bool
        switch polymod(expanded) {
        case bech32Const: bech32m = false
        case bech32mConst: bech32m = true
        default: return invalid(.BAD_CHECKSUM)
        }
        let values = Array(data[0..<(data.count - 6)])
        guard let version = values.first else { return invalid(.UNKNOWN_FORMAT) }
        if version > 16 { return invalid(.UNKNOWN_FORMAT) }
        guard let program = convertBits(Array(values.dropFirst())) else { return invalid(.UNKNOWN_FORMAT) }
        if program.count < 2 || program.count > 40 { return invalid(.UNKNOWN_FORMAT) }
        // v0 nur mit Bech32, ab v1 nur mit Bech32m (BIP350)
        if (version == 0) == bech32m { return invalid(.BAD_CHECKSUM) }
        switch (version, program.count) {
        case (0, 20): return Check(chain: chain, kind: .P2WPKH, normalized: s, problem: nil)
        case (0, 32): return Check(chain: chain, kind: .P2WSH, normalized: s, problem: nil)
        case (0, _): return invalid(.UNKNOWN_FORMAT)
        case (1, 32): return Check(chain: chain, kind: .P2TR, normalized: s, problem: nil)
        default: return invalid(.UNSUPPORTED_VERSION)
        }
    }

    /// 5-Bit-Gruppen → Bytes ohne Auffüllen; überzählige Bits müssen null sein.
    static func convertBits(_ data: [Int]) -> [Int]? {
        var acc = 0
        var bits = 0
        var out: [Int] = []
        for v in data {
            acc = ((acc << 5) | v) & 0xfff
            bits += 5
            if bits >= 8 {
                bits -= 8
                out.append((acc >> bits) & 0xff)
            }
        }
        if bits >= 5 || ((acc << (8 - bits)) & 0xff) != 0 { return nil }
        return out
    }
}
