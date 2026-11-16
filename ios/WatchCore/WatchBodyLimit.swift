import Foundation

/// Antwort einer Börse höchstens `limit` Bytes, schon beim Lesen begrenzt: nach `limit` Bytes bricht das Lesen ab, statt
/// erst die ganze Antwort in den Speicher zu laden. Wie Android `WatchPriceSource` (`readNBytesLimited`, 4 MB).
enum WatchBodyLimit {
    /// Grösse eines Blocks: Bytes erst in einem kleinen Puffer sammeln und blockweise an `Data` hängen (Byte für Byte mit
    /// Grenzprüfung je Byte war auf dem iPhone spürbar langsam, `ExchangeHTTP.collectLimited`).
    static let chunkBytes = 16 * 1024

    /// Liest `bytes` bis zum Ende; nil, sobald mehr als `limit` Bytes kämen (der Rest wird nicht gelesen), wenn die
    /// angekündigte Länge `expected` schon darüber liegt, oder bei einem Lesefehler bzw. Abbruch.
    static func collect<S: AsyncSequence>(_ bytes: S, limit: Int, expected: Int64 = -1,
                                          chunkSize: Int = chunkBytes) async -> Data?
        where S.Element == UInt8 {
        if expected > Int64(limit) { return nil }
        var data = Data()
        if expected > 0 { data.reserveCapacity(Int(expected)) }
        let size = max(1, chunkSize)
        var chunk: [UInt8] = []
        chunk.reserveCapacity(size)
        do {
            for try await byte in bytes {
                chunk.append(byte)
                if chunk.count >= size {
                    if data.count + chunk.count > limit { return nil }
                    data.append(contentsOf: chunk)
                    chunk.removeAll(keepingCapacity: true)
                    try Task.checkCancellation()
                }
            }
        } catch {
            return nil
        }
        if data.count + chunk.count > limit { return nil }
        data.append(contentsOf: chunk)
        return data
    }
}
