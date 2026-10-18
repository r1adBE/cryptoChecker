import Foundation

/// Argon2id nach RFC 9106 (Version 0x13), reines Swift ohne Fremdbibliothek — Zeile für Zeile wie `Argon2id.kt`,
/// damit beide Plattformen dieselben Schlüssel ableiten (Sicherungen v3, `BackupCrypto`). Geprüft mit dem Prüfwert
/// aus RFC 9106 Abschnitt 5.3 (`Argon2idTests`).
///
/// Die Bahnen (p) werden nacheinander gefüllt: Innerhalb eines Abschnitts greift keine Bahn auf den laufenden
/// Abschnitt einer anderen zu, das Ergebnis ist also dasselbe wie parallel. Speicher: m KiB über `calloc`
/// (64 MiB bei m = 65 536) — scheitert die Anforderung, folgt `Failure.outOfMemory` statt eines Absturzes.
/// Rechenzeit: einige 100 ms bis wenige Sekunden — nie auf dem Main-Thread aufrufen.
enum Argon2id {
    static let version = 0x13
    fileprivate static let typeId: UInt64 = 2 // Argon2id
    fileprivate static let blockWords = 128 // 1 KiB je Block
    fileprivate static let syncPoints = 4
    fileprivate static let addressesInBlock = 128

    enum Failure: Error, Equatable {
        /// Speicher für die Blöcke nicht verfügbar.
        case outOfMemory
        /// Parameter ausserhalb dessen, was Argon2 erlaubt.
        case invalidParameters
    }

    /// Tag der Länge `tagLength` Bytes. `memoryKiB` ≥ 8 · `parallelism`; `secret` und `ad` sind nur für den
    /// RFC-Prüfwert nötig (die Sicherung nutzt beide nicht).
    static func hash(password: [UInt8], salt: [UInt8], memoryKiB: Int, iterations: Int, parallelism: Int,
                     tagLength: Int, secret: [UInt8] = [], ad: [UInt8] = []) throws -> [UInt8] {
        guard (1...0xFF_FFFF).contains(parallelism), iterations >= 1, tagLength >= 4,
              memoryKiB >= 8 * parallelism, memoryKiB <= Int(UInt32.max), salt.count >= 8
        else { throw Failure.invalidParameters }

        // H0 (RFC 9106, 3.2 Schritt 1)
        var h0Hash = Blake2b(outLength: 64)
        h0Hash.update(le32: parallelism)
        h0Hash.update(le32: tagLength)
        h0Hash.update(le32: memoryKiB)
        h0Hash.update(le32: iterations)
        h0Hash.update(le32: version)
        h0Hash.update(le32: Int(typeId))
        h0Hash.update(le32: password.count); h0Hash.update(password)
        h0Hash.update(le32: salt.count); h0Hash.update(salt)
        h0Hash.update(le32: secret.count); h0Hash.update(secret)
        h0Hash.update(le32: ad.count); h0Hash.update(ad)
        var h0 = h0Hash.digest()
        defer { wipe(&h0) }

        // m' = 4 · p · floor(m / 4p); q Spalten je Bahn, 4 Abschnitte je Bahn
        let segmentLength = memoryKiB / (syncPoints * parallelism)
        let laneLength = segmentLength * syncPoints
        let blockCount = laneLength * parallelism
        let words = blockCount * blockWords
        let byteCount = words * MemoryLayout<UInt64>.size
        guard let raw = calloc(words, MemoryLayout<UInt64>.size) else { throw Failure.outOfMemory }
        defer {
            // Schlüsselmaterial nicht im freigegebenen Speicher liegen lassen
            _ = memset_s(raw, byteCount, 0, byteCount)
            free(raw)
        }
        let memory = raw.bindMemory(to: UInt64.self, capacity: words)

        // Erste zwei Blöcke jeder Bahn aus H0
        var seed = h0 + [UInt8](repeating: 0, count: 8)
        for lane in 0..<parallelism {
            for column in 0..<2 {
                putLE32(&seed, h0.count, column)
                putLE32(&seed, h0.count + 4, lane)
                var block = hashLong(seed, length: blockWords * 8)
                let offset = (lane * laneLength + column) * blockWords
                for w in 0..<blockWords { memory[offset + w] = getLE64(block, w * 8) }
                wipe(&block)
            }
        }
        wipe(&seed)

        let filler = Filler(memory: memory, laneLength: laneLength, segmentLength: segmentLength,
                            lanes: parallelism, blockCount: blockCount, passes: iterations)
        for pass in 0..<iterations {
            for slice in 0..<syncPoints {
                for lane in 0..<parallelism { filler.fillSegment(pass: pass, slice: slice, lane: lane) }
            }
        }

        // Letzte Blöcke aller Bahnen XOR → Tag
        var last = [UInt64](repeating: 0, count: blockWords)
        for lane in 0..<parallelism {
            let offset = (lane * laneLength + laneLength - 1) * blockWords
            for w in 0..<blockWords { last[w] ^= memory[offset + w] }
        }
        var lastBytes = [UInt8](repeating: 0, count: blockWords * 8)
        for w in 0..<blockWords { putLE64(&lastBytes, w * 8, last[w]) }
        for w in 0..<blockWords { last[w] = 0 }
        let tag = hashLong(lastBytes, length: tagLength)
        wipe(&lastBytes)
        return tag
    }

    /// H' (RFC 9106, 3.3): Hash variabler Länge aus BLAKE2b.
    static func hashLong(_ input: [UInt8], length: Int) -> [UInt8] {
        if length <= 64 {
            var b = Blake2b(outLength: length)
            b.update(le32: length)
            b.update(input)
            return b.digest()
        }
        var out = [UInt8](repeating: 0, count: length)
        let r = (length + 31) / 32 - 2
        var first = Blake2b(outLength: 64)
        first.update(le32: length)
        first.update(input)
        var v = first.digest()
        out.replaceSubrange(0..<32, with: v[0..<32])
        for i in 1..<r {
            var b = Blake2b(outLength: 64)
            b.update(v)
            let next = b.digest()
            wipe(&v)
            v = next
            out.replaceSubrange((i * 32)..<(i * 32 + 32), with: v[0..<32])
        }
        var tailHash = Blake2b(outLength: length - 32 * r)
        tailHash.update(v)
        var tail = tailHash.digest()
        wipe(&v)
        out.replaceSubrange((32 * r)..<length, with: tail)
        wipe(&tail)
        return out
    }

    /// Bytes überschreiben (Passwort, Schlüssel, Zwischenwerte), bevor das Array freigegeben wird.
    static func wipe(_ bytes: inout [UInt8]) {
        for i in bytes.indices { bytes[i] = 0 }
    }

    private static func putLE32(_ b: inout [UInt8], _ off: Int, _ value: Int) {
        let v = UInt32(truncatingIfNeeded: value)
        for i in 0..<4 { b[off + i] = UInt8(truncatingIfNeeded: v >> (8 * UInt32(i))) }
    }

    private static func putLE64(_ b: inout [UInt8], _ off: Int, _ value: UInt64) {
        for i in 0..<8 { b[off + i] = UInt8(truncatingIfNeeded: value >> (8 * UInt64(i))) }
    }

    private static func getLE64(_ b: [UInt8], _ off: Int) -> UInt64 {
        var v: UInt64 = 0
        for i in stride(from: 7, through: 0, by: -1) { v = (v << 8) | UInt64(b[off + i]) }
        return v
    }
}

/// Füllt die Abschnitte (RFC 9106, 3.4); hält die Hilfsblöcke, damit je Block nichts neu angelegt wird.
/// Zeiger statt Arrays: ohne Kopien und Grenzprüfungen bleibt auch der Debug-Build erträglich schnell.
private final class Filler {
    private let memory: UnsafeMutablePointer<UInt64>
    private let laneLength: Int
    private let segmentLength: Int
    private let lanes: Int
    private let blockCount: Int
    private let passes: Int
    private static let scratchWords = 5 * Argon2id.blockWords
    private let scratch: UnsafeMutablePointer<UInt64>
    private let r: UnsafeMutablePointer<UInt64>
    private let tmp: UnsafeMutablePointer<UInt64>
    private let zero: UnsafeMutablePointer<UInt64>
    private let input: UnsafeMutablePointer<UInt64>
    private let address: UnsafeMutablePointer<UInt64>

    init(memory: UnsafeMutablePointer<UInt64>, laneLength: Int, segmentLength: Int, lanes: Int, blockCount: Int,
         passes: Int) {
        self.memory = memory
        self.laneLength = laneLength
        self.segmentLength = segmentLength
        self.lanes = lanes
        self.blockCount = blockCount
        self.passes = passes
        let words = Argon2id.blockWords
        let buffer = UnsafeMutablePointer<UInt64>.allocate(capacity: Filler.scratchWords)
        buffer.initialize(repeating: 0, count: Filler.scratchWords)
        scratch = buffer
        r = buffer
        tmp = buffer + words
        zero = buffer + 2 * words
        input = buffer + 3 * words
        address = buffer + 4 * words
    }

    deinit {
        scratch.initialize(repeating: 0, count: Filler.scratchWords)
        scratch.deallocate()
    }

    func fillSegment(pass: Int, slice: Int, lane: Int) {
        let words = Argon2id.blockWords
        // Argon2id: im ersten Durchgang die ersten zwei Abschnitte datenunabhängig adressieren
        let independent = pass == 0 && slice < Argon2id.syncPoints / 2
        if independent {
            for i in 0..<words { input[i] = 0 }
            input[0] = UInt64(pass)
            input[1] = UInt64(lane)
            input[2] = UInt64(slice)
            input[3] = UInt64(blockCount)
            input[4] = UInt64(passes)
            input[5] = Argon2id.typeId
        }
        var start = 0
        if pass == 0 && slice == 0 {
            start = 2
            if independent { nextAddresses() }
        }
        var current = lane * laneLength + slice * segmentLength + start
        var previous = current % laneLength == 0 ? current + laneLength - 1 : current - 1
        for index in start..<segmentLength {
            if current % laneLength == 1 { previous = current - 1 }
            let pseudoRandom: UInt64
            if independent {
                if index % Argon2id.addressesInBlock == 0 { nextAddresses() }
                pseudoRandom = address[index % Argon2id.addressesInBlock]
            } else {
                pseudoRandom = memory[previous * words]
            }
            let refLane = pass == 0 && slice == 0 ? lane : Int((pseudoRandom >> 32) % UInt64(lanes))
            let refIndex = referenceIndex(pass: pass, slice: slice, index: index,
                                          j1: pseudoRandom & 0xFFFF_FFFF, sameLane: refLane == lane)
            compress(memory + previous * words, memory + (refLane * laneLength + refIndex) * words,
                     memory + current * words, withXor: pass > 0)
            current += 1
            previous += 1
        }
    }

    /// Position des Bezugsblocks in seiner Bahn (RFC 9106, 3.4.1.2).
    private func referenceIndex(pass: Int, slice: Int, index: Int, j1: UInt64, sameLane: Bool) -> Int {
        let area: Int
        if pass == 0 {
            if slice == 0 {
                area = index - 1
            } else if sameLane {
                area = slice * segmentLength + index - 1
            } else {
                area = slice * segmentLength + (index == 0 ? -1 : 0)
            }
        } else {
            area = sameLane ? laneLength - segmentLength + index - 1
                : laneLength - segmentLength + (index == 0 ? -1 : 0)
        }
        // j1 < 2^32: j1² passt ohne Überlauf in 64 Bit
        let x = (j1 &* j1) >> 32
        let y = (UInt64(area) &* x) >> 32
        let relative = area - 1 - Int(y)
        let startPosition = pass == 0 || slice == Argon2id.syncPoints - 1 ? 0 : (slice + 1) * segmentLength
        return (startPosition + relative) % laneLength
    }

    private func nextAddresses() {
        input[6] &+= 1
        compress(zero, input, address, withXor: false)
        compress(zero, address, address, withXor: false)
    }

    /// G(X, Y) (RFC 9106, 3.5); mit `withXor` wird der alte Inhalt von `out` zusätzlich eingerechnet (ab Durchgang 2).
    private func compress(_ x: UnsafeMutablePointer<UInt64>, _ y: UnsafeMutablePointer<UInt64>,
                          _ out: UnsafeMutablePointer<UInt64>, withXor: Bool) {
        let words = Argon2id.blockWords
        for i in 0..<words {
            let v = x[i] ^ y[i]
            r[i] = v
            tmp[i] = withXor ? v ^ out[i] : v
        }
        // Zeilen: 8 × 16 Wörter
        for i in 0..<8 {
            let b = 16 * i
            round(b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7,
                  b + 8, b + 9, b + 10, b + 11, b + 12, b + 13, b + 14, b + 15)
        }
        // Spalten: je 2 Wörter aus jeder Zeile
        for i in 0..<8 {
            let b = 2 * i
            round(b, b + 1, b + 16, b + 17, b + 32, b + 33, b + 48, b + 49,
                  b + 64, b + 65, b + 80, b + 81, b + 96, b + 97, b + 112, b + 113)
        }
        for i in 0..<words { out[i] = tmp[i] ^ r[i] }
    }

    @inline(__always)
    private func round(_ v0: Int, _ v1: Int, _ v2: Int, _ v3: Int, _ v4: Int, _ v5: Int, _ v6: Int, _ v7: Int,
                       _ v8: Int, _ v9: Int, _ v10: Int, _ v11: Int, _ v12: Int, _ v13: Int, _ v14: Int, _ v15: Int) {
        g(v0, v4, v8, v12); g(v1, v5, v9, v13); g(v2, v6, v10, v14); g(v3, v7, v11, v15)
        g(v0, v5, v10, v15); g(v1, v6, v11, v12); g(v2, v7, v8, v13); g(v3, v4, v9, v14)
    }

    /// BLAKE2b-G mit der Multiplikation aus Argon2 (fBlaMka).
    @inline(__always)
    private func g(_ a: Int, _ b: Int, _ c: Int, _ d: Int) {
        let v = r
        v[a] = blaMka(v[a], v[b]); v[d] = rotr(v[d] ^ v[a], 32)
        v[c] = blaMka(v[c], v[d]); v[b] = rotr(v[b] ^ v[c], 24)
        v[a] = blaMka(v[a], v[b]); v[d] = rotr(v[d] ^ v[a], 16)
        v[c] = blaMka(v[c], v[d]); v[b] = rotr(v[b] ^ v[c], 63)
    }

    @inline(__always)
    private func blaMka(_ x: UInt64, _ y: UInt64) -> UInt64 {
        x &+ y &+ 2 &* (x & 0xFFFF_FFFF) &* (y & 0xFFFF_FFFF)
    }
}
