import Combine
import Foundation
import UIKit

// Logos anzeigen (Teil von `CoinLogoStore`): aus Speicher, Datei oder mitgeliefertem Icon-Paket — nie Netz, auch
// für die Widgets; dazu der Zähler, der die Anzeige nach neuen Logos auffrischt.

extension CoinLogoStore {
    /// Mitgelieferte Icons im Speicher (eigene Vorsilbe: Vorrang haben die geladenen Logos).
    private static let bundledMemoryPrefix = "B_"

    // MARK: Anzeigen (nie Netz; auch für Widgets)

    /// Sofort aus dem Speicher; nil = noch nicht geladen (mitgeliefertes Icon, falls nur das da ist).
    static func cached(_ symbol: String) -> UIImage? {
        guard let name = CoinLogos.fileName(symbol) else { return nil }
        if let image = memory.object(forKey: name as NSString) { return image }
        guard let icon = CoinLogos.bundledIconName(symbol) else { return nil }
        return memory.object(forKey: (bundledMemoryPrefix + icon) as NSString)
    }

    /// Aus Speicher oder Datei. Vorrang hat das geladene Logo der Listen (CoinGecko, Binance, Alpha),
    /// sonst das mitgelieferte Icon (`bundled`) — wie Android `logo`.
    static func stored(_ symbol: String) -> UIImage? {
        guard let name = CoinLogos.fileName(symbol) else { return nil }
        if let image = readImage(directory.appendingPathComponent(name), key: name) { return image }
        return bundled(symbol)
    }

    /// Bild aus Datei in den Speicher (`key`).
    private static func readImage(_ url: URL, key: String) -> UIImage? {
        if let image = memory.object(forKey: key as NSString) { return image }
        guard let data = try? Data(contentsOf: url), let image = UIImage(data: data) else { return nil }
        memory.setObject(image, forKey: key as NSString)
        return image
    }

    /// Mitgeliefertes Icon-Paket (App und Widgets): Bytes und Inhaltsverzeichnis, einmal gelesen.
    private static let iconPack: IconPack? = {
        guard let url = Bundle.main.url(forResource: CoinLogos.iconPackName, withExtension: CoinLogos.iconPackExtension),
              let data = try? Data(contentsOf: url, options: .mappedIfSafe)
        else { return nil }
        let bytes = [UInt8](data)
        guard let index = CoinLogos.parseIconPack(bytes) else { return nil }
        return IconPack(bytes: bytes, index: index)
    }()

    /// Mitgeliefertes Icon (CC0) für `symbol`; nil, wenn das Paket es nicht kennt.
    static func bundled(_ symbol: String) -> UIImage? {
        guard let name = CoinLogos.bundledIconName(symbol) else { return nil }
        let key = bundledMemoryPrefix + name
        if let image = memory.object(forKey: key as NSString) { return image }
        guard let pack = iconPack, let slice = pack.index[name] else { return nil }
        let data = Data(pack.bytes[slice.offset..<(slice.offset + slice.length)])
        guard let image = UIImage(data: data) else { return nil }
        memory.setObject(image, forKey: key as NSString)
        return image
    }

    /// Wie `stored`, aber ausserhalb des Haupt-Threads (Datei lesen).
    func logo(_ symbol: String) -> UIImage? {
        Self.stored(symbol)
    }

    /// Logos mehrerer Symbole vom Gerät (Widgets).
    static func storedLogos(_ symbols: [String]) -> [String: UIImage] {
        var out: [String: UIImage] = [:]
        for symbol in Set(symbols) { if let image = stored(symbol) { out[symbol] = image } }
        return out
    }
}

/// Mitgeliefertes Icon-Paket im Speicher (`CoinLogos.parseIconPack`).
private struct IconPack {
    let bytes: [UInt8]
    let index: [String: CoinLogos.IconSlice]
}

/// Zähler, der steigt, sobald neue Logos auf dem Gerät liegen; `CoinBadge` lädt dann fehlende neu.
@MainActor
final class CoinLogoRevision: ObservableObject {
    static let shared = CoinLogoRevision()
    @Published private(set) var value = 0

    /// Zeitpunkt der letzten Auffrischung (ms) für `bumpIfDue`.
    private static var lastBumpAt: Int64 = 0

    static func bump() {
        lastBumpAt = TimeUtils.nowMillis
        shared.value += 1
    }

    /// Während des Ladens vieler Logos: höchstens einmal pro Sekunde auffrischen.
    static func bumpIfDue() {
        if TimeUtils.nowMillis - lastBumpAt >= 1_000 { bump() }
    }
}
