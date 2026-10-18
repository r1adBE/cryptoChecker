import Foundation

// BLAKE2b für Argon2id. Eigene Datei, damit `Argon2id.swift` nur Argon2 selbst enthält.

@inline(__always)
func rotr(_ x: UInt64, _ n: UInt64) -> UInt64 {
    (x >> n) | (x << (64 - n))
}

/// BLAKE2b ohne Schlüssel (RFC 7693), Ausgabe 1–64 Bytes — nur für `Argon2id` (wie `Blake2b` in `Argon2id.kt`).
struct Blake2b {
    private var h: [UInt64]
    private var buffer = [UInt8](repeating: 0, count: 128)
    private var bufferLength = 0
    private var counter: UInt64 = 0 // Bytes bisher (Eingaben hier weit unter 2^64)
    private let outLength: Int

    init(outLength: Int) {
        precondition((1...64).contains(outLength))
        self.outLength = outLength
        h = Self.iv
        h[0] ^= 0x0101_0000 | UInt64(outLength)
    }

    mutating func update(le32 value: Int) {
        let v = UInt32(truncatingIfNeeded: value)
        update([UInt8(truncatingIfNeeded: v), UInt8(truncatingIfNeeded: v >> 8),
                UInt8(truncatingIfNeeded: v >> 16), UInt8(truncatingIfNeeded: v >> 24)])
    }

    mutating func update(_ input: [UInt8]) {
        var offset = 0
        var remaining = input.count
        while remaining > 0 {
            // Volle Pufferblöcke erst verarbeiten, wenn weitere Eingabe folgt (der letzte Block braucht die Endmarke)
            if bufferLength == 128 {
                counter &+= 128
                compress(last: false)
                bufferLength = 0
            }
            let take = min(128 - bufferLength, remaining)
            for i in 0..<take { buffer[bufferLength + i] = input[offset + i] }
            bufferLength += take
            offset += take
            remaining -= take
        }
    }

    mutating func digest() -> [UInt8] {
        counter &+= UInt64(bufferLength)
        for i in bufferLength..<128 { buffer[i] = 0 }
        compress(last: true)
        var out = [UInt8](repeating: 0, count: outLength)
        for i in 0..<outLength { out[i] = UInt8(truncatingIfNeeded: h[i / 8] >> (8 * UInt64(i % 8))) }
        for i in 0..<8 { h[i] = 0 }
        for i in 0..<128 { buffer[i] = 0 }
        return out
    }

    private mutating func compress(last: Bool) {
        var m = [UInt64](repeating: 0, count: 16)
        for i in 0..<16 {
            var w: UInt64 = 0
            for j in stride(from: 7, through: 0, by: -1) { w = (w << 8) | UInt64(buffer[i * 8 + j]) }
            m[i] = w
        }
        var v = h + Self.iv
        v[12] ^= counter
        // v[13]: oberer Teil des Zählers, bei diesen Längen immer 0
        if last { v[14] = ~v[14] }
        for round in 0..<12 {
            let s = Self.sigma[round % 10]
            Self.g(&v, 0, 4, 8, 12, m[s[0]], m[s[1]]); Self.g(&v, 1, 5, 9, 13, m[s[2]], m[s[3]])
            Self.g(&v, 2, 6, 10, 14, m[s[4]], m[s[5]]); Self.g(&v, 3, 7, 11, 15, m[s[6]], m[s[7]])
            Self.g(&v, 0, 5, 10, 15, m[s[8]], m[s[9]]); Self.g(&v, 1, 6, 11, 12, m[s[10]], m[s[11]])
            Self.g(&v, 2, 7, 8, 13, m[s[12]], m[s[13]]); Self.g(&v, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for i in 0..<8 { h[i] ^= v[i] ^ v[i + 8] }
        for i in 0..<16 { m[i] = 0; v[i] = 0 }
    }

    private static func g(_ v: inout [UInt64], _ a: Int, _ b: Int, _ c: Int, _ d: Int, _ x: UInt64, _ y: UInt64) {
        v[a] = v[a] &+ v[b] &+ x; v[d] = rotr(v[d] ^ v[a], 32)
        v[c] = v[c] &+ v[d]; v[b] = rotr(v[b] ^ v[c], 24)
        v[a] = v[a] &+ v[b] &+ y; v[d] = rotr(v[d] ^ v[a], 16)
        v[c] = v[c] &+ v[d]; v[b] = rotr(v[b] ^ v[c], 63)
    }

    private static let iv: [UInt64] = [
        0x6a09_e667_f3bc_c908, 0xbb67_ae85_84ca_a73b, 0x3c6e_f372_fe94_f82b, 0xa54f_f53a_5f1d_36f1,
        0x510e_527f_ade6_82d1, 0x9b05_688c_2b3e_6c1f, 0x1f83_d9ab_fb41_bd6b, 0x5be0_cd19_137e_2179,
    ]

    private static let sigma: [[Int]] = [
        [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
        [14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3],
        [11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4],
        [7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8],
        [9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13],
        [2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9],
        [12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11],
        [13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10],
        [6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5],
        [10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0],
    ]
}
