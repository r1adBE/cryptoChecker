import Foundation

/// «Paar bearbeiten» (Stift im Aktionsblatt): Börse, Paar und Kontrakt eines bestehenden
/// Eintrags ändern — wie `WatchEdit.kt`. Der Eintrag bleibt derselbe (Id, Gruppe, Favorit,
/// Notiz, Platz, Alarme); nur die Marktdaten des alten Paars werden verworfen.
enum WatchEdit {
    /// Wie der eindeutige Schlüssel der Merkliste: Börse, Basis, Quote, Kontrakt.
    struct Key: Hashable, Sendable {
        let marketKey: String
        let base: String
        let quote: String
        let contract: String

        init(marketKey: String, base: String, quote: String, contract: String) {
            self.marketKey = marketKey
            self.base = base
            self.quote = quote
            self.contract = contract
        }

        init(_ watch: Watch) {
            self.init(marketKey: watch.marketKey, base: watch.baseAsset, quote: watch.quoteAsset,
                      contract: String(watch.contractType.rawValue))
        }

        init(marketKey: String, pair: CurrencyPairInfo) {
            self.init(marketKey: marketKey, base: pair.base, quote: pair.quote,
                      contract: String(pair.contractType.rawValue))
        }
    }

    enum Outcome: Sendable {
        /// Gleiche Auswahl wie bisher: nichts zu tun.
        case unchanged
        /// Diese Börse/dieses Paar steht schon als anderer Eintrag in der Merkliste.
        case duplicate
        /// Speichern: derselbe Eintrag mit neuem Paar.
        case changed
    }

    /// `existingId` = Id des Eintrags, der `target` schon führt (nil = keiner). Findet die
    /// Suche den Eintrag selbst (`selfId`), ist das kein Doppel.
    static func decide(selfId: Int64, current: Key, target: Key, existingId: Int64?) -> Outcome {
        if current == target { return .unchanged }
        if let existingId, existingId != selfId { return .duplicate }
        return .changed
    }

    /// Hinweis «Alarme bleiben bestehen – Schwellen prüfen»: nur, wenn eine andere Auswahl
    /// getroffen ist und das Paar Alarme hat (Schwellen gelten für den alten Kurs).
    static func warnAlarms(current: Key, target: Key?, alarmCount: Int) -> Bool {
        guard let target else { return false }
        return target != current && alarmCount > 0
    }

    /// Alarm-Arten, deren Bezug am alten Kurs hängt (wie `WatchDao.resetPriceReferences`).
    static let priceReferenceConditions: Set<AlarmCondition> = [
        .CHANGE_PERCENT_UP, .CHANGE_PERCENT_DOWN, .MOVE_PERCENT_WINDOW, .NEAR_HIGH, .NEAR_LOW,
        .FUNDING_ABOVE, .FUNDING_BELOW, .OI_UP, .OI_DOWN,
    ]

    /// Prozentalarme: messen ab dem ersten Kurs des neuen Paars.
    static let percentConditions: Set<AlarmCondition> = [.CHANGE_PERCENT_UP, .CHANGE_PERCENT_DOWN]
}
