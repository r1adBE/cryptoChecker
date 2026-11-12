import Foundation

/// Texte zur Stilllegung einer Börse (`ExchangeRetirement`) mit Datum — wie Android `RetirementTexts.kt`.
enum RetirementText {
    /// Tag des Endes in der Sprache der App («1. Februar 2027»), als UTC-Datum (das Ende liegt um 00:00 UTC).
    static func endDate(_ exchange: String) -> String {
        guard let end = ExchangeRetirement.endsAt(exchange) else { return "" }
        let f = DateFormatter()
        f.dateStyle = .long
        f.timeStyle = .none
        f.timeZone = TimeZone(identifier: "UTC")
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(end) / 1000))
    }

    /// Grund zur Stilllegung der Börse des Paars: nach dem Ende (eine Regel, `Watch.retiredExchange`) «Bitpanda liefert
    /// seit 1. Februar 2027 keine Kurse mehr» — auf der Alarmseite (`alarms`) mit «… – Alarme dieses Paars prüfen nichts
    /// mehr» —, während der Ankündigung «Bitpanda liefert ab 1. Februar 2027 keine Kurse mehr» (sonst ändert sich davor
    /// nichts). nil = weder stillgelegt noch angekündigt. Wie Android `retirementReason`.
    /// Entschieden über den Schlüssel der Börse, gezeigt der Name aus dem Katalog.
    static func reason(_ watch: Watch, alarms: Bool) -> String? {
        let date = endDate(watch.exchangeKey)
        if let retired = watch.retiredExchange() {
            return alarms ? L("exchange_retired_alarms", retired, date) : L("exchange_retired_since", retired, date)
        }
        guard ExchangeRetirement.isAnnounced(watch.exchangeKey) else { return nil }
        return L("exchange_retiring_reason", watch.retirementExchangeName, date)
    }

    /// Ende der Börse des Paars angekündigt (vor dem Ende, über den Schlüssel)?
    static func announced(_ watch: Watch?) -> Bool {
        guard let watch else { return false }
        return ExchangeRetirement.isAnnounced(watch.exchangeKey)
    }

    /// Anzeigename aus dem Katalog zum Schlüssel (`ExchangesConfig`); sonst der Schlüssel.
    static func name(ofKey key: String) -> String { ExchangesConfig.exchange(key)?.name ?? key }
}
