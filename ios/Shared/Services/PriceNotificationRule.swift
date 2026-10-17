import Foundation

/// Kurs-Mitteilung nach einer bewussten Handlung (Paar-Mitteilung einschalten, «Alle an»): ein gemeinsamer
/// Entscheid, damit keine Stelle die Einstellungen übergeht. Gleich wie Android `PriceNotificationRule.kt`.
enum PriceNotificationRule {
    /// Jetzt zeigen? Nur mit eingeschalteten Kurs-Mitteilungen (`masterOn`, Einstellungen), Mitteilung
    /// des Paars an, Paar aktiv und gehandelt, mit gültigem Kurs.
    static func showNow(masterOn: Bool, pairOn: Bool, disabled: Bool, notTraded: Bool, price: Double?) -> Bool {
        guard masterOn, pairOn, !disabled, !notTraded, let price else { return false }
        return price > 0 && price.isFinite
    }

    /// Bezug der Melde-Schwelle nach dem Einschalten: gezeigt → der gezeigte Kurs; nicht gezeigt → keiner,
    /// damit die nächste Aktualisierung (mit Kurs-Mitteilungen an) sofort meldet.
    static func referenceAfterEnable(shown: Bool, price: Double?) -> Double? { shown ? price : nil }
}
