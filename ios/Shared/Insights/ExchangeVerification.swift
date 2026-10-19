import Foundation

/// «Börsen-Anbindungen zuletzt gegen echte Antworten geprüft» (Über die App): Tag der letzten Aufzeichnung echter
/// Börsen-Antworten (`android/tools/record_exchange_fixtures.py` → `Tests/Exchanges/Recorded`), gegen die alle Leser im
/// Test laufen. Nach jeder neuen Aufzeichnung nachführen — `ExchangeVerificationTests` schlägt fehl, solange das Datum vor
/// der neuesten Aufnahme (`recordedAt`, UTC-Datum) liegt. Wie `ExchangeVerification` (Android).
enum ExchangeVerification {
    /// UTC-Datum der neuesten aufgezeichneten Antwort.
    static let lastVerified = LocalDay(2026, 10, 19)
}
