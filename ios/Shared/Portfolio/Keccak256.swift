import Foundation

/// Keccak-256 (die Ethereum-Variante, Polsterung 0x01 — NICHT SHA3-256 mit 0x06) für die
/// EIP-55-Prüfsumme von Ethereum-Adressen (`AddressValidation`). CryptoKit kennt kein Keccak.
/// Gleich wie `Keccak256.kt`.
enum Keccak256 {

    /// Keccak-256 (Ethereum).
    static func hash(_ input: [UInt8]) -> [UInt8] { sponge(input, pad: 0x01) }

    /// SHA3-256 (FIPS 202) — nur für Tests der Permutation gegen bekannte Werte.
    static func sha3(_ input: [UInt8]) -> [UInt8] { sponge(input, pad: 0x06) }

    static func hex(_ input: [UInt8]) -> String {
        hash(input).map { String(format: "%02x", $0) }.joined()
    }

    private static let rate = 136

    private static let rc: [UInt64] = [
        0x0000000000000001, 0x0000000000008082, 0x800000000000808A, 0x8000000080008000,
        0x000000000000808B, 0x0000000080000001, 0x8000000080008081, 0x8000000000008009,
        0x000000000000008A, 0x0000000000000088, 0x0000000080008009, 0x000000008000000A,
        0x000000008000808B, 0x800000000000008B, 0x8000000000008089, 0x8000000000008003,
        0x8000000000008002, 0x8000000000000080, 0x000000000000800A, 0x800000008000000A,
        0x8000000080008081, 0x8000000000008080, 0x0000000080000001, 0x8000000080008008,
    ]

    /// Rotation je Lane (Index x + 5y).
    private static let rot: [UInt64] = [
        0, 1, 62, 28, 27,
        36, 44, 6, 55, 20,
        3, 10, 43, 25, 39,
        41, 45, 15, 21, 8,
        18, 2, 61, 56, 14,
    ]

    private static func rotl(_ x: UInt64, _ n: UInt64) -> UInt64 {
        n == 0 ? x : (x << n) | (x >> (64 - n))
    }

    private static func sponge(_ input: [UInt8], pad: UInt8) -> [UInt8] {
        // Nachricht + Polsterung auf ein Vielfaches der Rate
        let blocks = input.count / rate + 1
        var padded = [UInt8](repeating: 0, count: blocks * rate)
        padded.replaceSubrange(0..<input.count, with: input)
        padded[input.count] ^= pad
        padded[padded.count - 1] ^= 0x80

        var state = [UInt64](repeating: 0, count: 25)
        for b in 0..<blocks {
            for i in 0..<(rate / 8) {
                var lane: UInt64 = 0
                for k in 0..<8 {
                    let index: Int = b * rate + i * 8 + k
                    let shift = UInt64(8 * k)
                    lane |= UInt64(padded[index]) << shift
                }
                state[i] ^= lane
            }
            permute(&state)
        }
        var out = [UInt8](repeating: 0, count: 32)
        for i in 0..<32 {
            out[i] = UInt8(truncatingIfNeeded: state[i / 8] >> UInt64(8 * (i % 8)))
        }
        return out
    }

    private static func permute(_ a: inout [UInt64]) {
        var c = [UInt64](repeating: 0, count: 5)
        var b = [UInt64](repeating: 0, count: 25)
        for round in 0..<24 {
            // θ
            for x in 0..<5 {
                // In Schritten mit festen Typen (lange Operator-Ketten sind für den Type-Checker teuer)
                let ab: UInt64 = a[x] ^ a[x + 5]
                let cd: UInt64 = a[x + 10] ^ a[x + 15]
                c[x] = ab ^ cd ^ a[x + 20]
            }
            for x in 0..<5 {
                let d = c[(x + 4) % 5] ^ rotl(c[(x + 1) % 5], 1)
                for y in 0..<5 { a[x + 5 * y] ^= d }
            }
            // ρ und π
            for x in 0..<5 {
                for y in 0..<5 {
                    let from: Int = x + 5 * y
                    let turn: Int = (2 * x + 3 * y) % 5
                    let to: Int = y + 5 * turn
                    b[to] = rotl(a[from], rot[from])
                }
            }
            // χ
            for x in 0..<5 {
                for y in 0..<5 {
                    let row: Int = 5 * y
                    let i0: Int = x + row
                    let i1: Int = (x + 1) % 5 + row
                    let i2: Int = (x + 2) % 5 + row
                    let masked: UInt64 = ~b[i1] & b[i2]
                    a[i0] = b[i0] ^ masked
                }
            }
            // ι
            a[0] ^= rc[round]
        }
    }
}
