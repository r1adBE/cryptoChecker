import Foundation

/// Reihenfolge der Kurs-Schreiber: Ein Ergebnis übernimmt seinen Kurs nur, wenn er nicht älter ist als der gespeicherte
/// (`Watch.lastUpdate`). So überschreibt eine langsame Einzelabfrage, die vor einer vollständigen Aktualisierung
/// begann, aber nach ihr fertig wird, deren neueren Kurs nicht. Gilt für den Stand im Speicher und auf der Platte
/// (`PriceRefresher.Outcome.apply(to:)`), wie Android `PriceWriteOrder` (Bedingung in `WatchDao.updatePriceForPair`).
/// Gemeinsame Testfälle: `Parity/price_write_order.json`.
enum PriceWriteOrder {
    /// Liegt der gespeicherte Zeitpunkt mehr als so weit in der Zukunft, gilt er als unplausibel (Uhr zurückgestellt)
    /// und sperrt keinen neuen Kurs — sonst stünde das Paar bis dahin still.
    static let futureToleranceMillis: Int64 = 3_600_000

    /// Spätester noch plausibler gespeicherter Zeitpunkt zu `now`.
    static func plausibleUntil(now: Int64) -> Int64 {
        now + futureToleranceMillis
    }

    /// - Parameters:
    ///   - newTime: Zeit des neuen Kurses (`PriceUpdate.time`).
    ///   - storedTime: Zeit des gespeicherten Kurses (`Watch.lastUpdate`); 0 = noch keiner.
    ///   - now: jetzt (ms seit 1970).
    /// - Returns: true = neuen Kurs übernehmen (gleich alt zählt als neu).
    static func accepts(newTime: Int64, storedTime: Int64, now: Int64) -> Bool {
        newTime >= storedTime || storedTime > plausibleUntil(now: now)
    }
}
