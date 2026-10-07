import Foundation

/// Warteschlange der automatischen Ansagen — wie `AnnouncementQueue.kt`.
///
/// - Alarme werden nie verworfen und kommen vor wartenden Kursansagen, untereinander in der
///   Reihenfolge des Eintreffens (mehrere Alarme eines Durchlaufs: alle hörbar).
/// - Kursansagen werden je Paar zusammengefasst: Von einem Paar wartet höchstens die neueste
///   Ansage — so stauen sich im Live-Modus keine Ansagen auf.
struct AnnouncementQueue {
    enum Kind { case alarm, price }

    struct Item: Equatable {
        let text: String
        let rate: Double
        let kind: Kind
        /// Paar (Watch-Id) für Kursansagen; nil bei Alarmen.
        var key: Int64? = nil
    }

    private var alarms: [Item] = []
    /// Reihenfolge des (letzten) Eintreffens; je Paar höchstens ein Eintrag.
    private var prices: [Item] = []
    private var anonymousKey = Int64.min / 2

    var count: Int { alarms.count + prices.count }
    var isEmpty: Bool { count == 0 }

    /// - Returns: true, wenn dabei eine ältere Kursansage desselben Paars ersetzt wurde.
    @discardableResult
    mutating func offer(_ item: Item) -> Bool {
        if item.kind == .alarm {
            alarms.append(item)
            return false
        }
        var entry = item
        if entry.key == nil {
            // Kursansage ohne Paar: nicht zusammenfassen
            anonymousKey -= 1
            entry.key = anonymousKey
        }
        let before = prices.count
        prices.removeAll { $0.key == entry.key }
        // Neu anhängen: Der neue Kurs reiht sich hinten ein
        prices.append(entry)
        return prices.count == before
    }

    /// Nächste Ansage: zuerst Alarme, dann Kursansagen; nil, wenn leer.
    mutating func poll() -> Item? {
        if !alarms.isEmpty { return alarms.removeFirst() }
        if !prices.isEmpty { return prices.removeFirst() }
        return nil
    }

    mutating func clear() {
        alarms.removeAll()
        prices.removeAll()
    }
}
