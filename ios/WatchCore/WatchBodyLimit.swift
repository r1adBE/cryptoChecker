import Foundation

/// Antwort einer Börse höchstens `limit` Bytes, schon beim Lesen begrenzt: nach `limit` Bytes bricht das Lesen ab, statt
/// erst die ganze Antwort in den Speicher zu laden. Wie Android `WatchPriceSource` (`readNBytesLimited`, 4 MB).
enum WatchBodyLimit {
    /// Liest `bytes` bis zum Ende; nil, sobald mehr als `limit` Bytes kämen (der Rest wird nicht gelesen), wenn die
    /// angekündigte Länge `expected` schon darüber liegt, oder bei einem Lesefehler.
    static func collect<S: AsyncSequence>(_ bytes: S, limit: Int, expected: Int64 = -1) async -> Data?
        where S.Element == UInt8 {
        if expected > Int64(limit) { return nil }
        var data = Data()
        if expected > 0 { data.reserveCapacity(Int(expected)) }
        do {
            for try await byte in bytes {
                guard data.count < limit else { return nil }
                data.append(byte)
            }
        } catch {
            return nil
        }
        return data
    }
}
