import Foundation

// Mitgeliefertes Icon-Paket (Teil von `CoinLogos`) — wie `CoinLogosIconPack.kt` (Android).

extension CoinLogos {
    /// Mitgeliefertes Icon-Paket: die 64-px-PNGs (`32@2x/color`) der rund 480 grösseren Coins in einer
    /// Datei (`Shared/Resources`, App und Widgets; Android `assets/`; erzeugt mit
    /// `android/tools/gen_coin_assets.py`). Vorrang haben immer die geladenen Logos der Listen.
    static let iconPackName = "coin_icons"
    static let iconPackExtension = "bin"
    private static let iconPackMagic = "CCIP1"
    /// Höchstgrösse des Inhaltsverzeichnisses (Schutz gegen kaputte Dateien).
    private static let iconPackHeaderMax = 64 * 1024

    /// Stelle eines Icons im Paket: Anfang und Länge in Bytes.
    struct IconSlice: Equatable {
        let offset: Int
        let length: Int
    }

    /// Name eines Icons im Paket («btc»); nil bei TradFi oder ungültigem Kürzel — wie Android.
    static func bundledIconName(_ symbol: String) -> String? {
        if symbol.hasPrefix(tradFiPrefix) { return nil }
        let key = normalize(symbol)
        guard fileName(key) != nil else { return nil }
        return key.lowercased()
    }

    /// Inhaltsverzeichnis des Icon-Pakets: Zeile «CCIP1», je Icon eine Zeile «name länge», eine
    /// Leerzeile, dann die PNG-Bytes in derselben Reihenfolge. Ergebnis: Name → `IconSlice` (ab
    /// Anfang von `bytes`); nil = kein Paket oder kaputt — wie Android `parseIconPack`.
    static func parseIconPack(_ bytes: [UInt8]) -> [String: IconSlice]? {
        parseIconPack(header: bytes, totalCount: bytes.count)
    }

    /// Wie `parseIconPack(_:)` aus dem gemappten Paket `data`: nur der Anfang (Inhaltsverzeichnis) wird kopiert, die
    /// PNG-Bytes bleiben in der Datei (nicht je Prozess, auch je Widget, das ganze Paket auf dem Heap).
    static func parseIconPack(_ data: Data) -> [String: IconSlice]? {
        parseIconPack(header: [UInt8](data.prefix(iconPackHeaderMax)), totalCount: data.count)
    }

    /// Inhaltsverzeichnis aus dem Anfang `header` eines Pakets mit `totalCount` Bytes.
    private static func parseIconPack(header bytes: [UInt8], totalCount: Int) -> [String: IconSlice]? {
        let limit = min(bytes.count, iconPackHeaderMax)
        var end = -1
        var i = 0
        while i + 1 < limit {
            if bytes[i] == 10 && bytes[i + 1] == 10 {
                end = i
                break
            }
            i += 1
        }
        guard end >= 0 else { return nil }
        let header = String(decoding: bytes[0..<end], as: UTF8.self)
        let lines = header.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        guard lines.first == iconPackMagic else { return nil }
        var out: [String: IconSlice] = [:]
        var offset = end + 2
        for line in lines.dropFirst() {
            let parts = line.split(separator: " ", omittingEmptySubsequences: false)
            guard parts.count == 2, let length = Int(parts[1]), length > 0 else { return nil }
            let name = String(parts[0])
            guard !name.isEmpty, name.unicodeScalars.allSatisfy({ isIconNameScalar($0) }) else { return nil }
            guard length <= totalCount - offset else { return nil }
            if out[name] == nil { out[name] = IconSlice(offset: offset, length: length) }
            offset += length
        }
        return out
    }

    /// Erlaubtes Zeichen im Namen eines Icons: a–z, 0–9.
    private static func isIconNameScalar(_ c: Unicode.Scalar) -> Bool {
        let v: UInt32 = c.value
        return (97...122).contains(v) || (48...57).contains(v)
    }
}
