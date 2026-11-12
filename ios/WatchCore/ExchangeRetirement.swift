import Foundation
#if DEBUG
import os
#endif

/// Börsen, deren öffentliche Kurs-Schnittstelle zu einem festen Zeitpunkt endet (Entscheid Inhaber, 12.11.2026): Bitpanda
/// bis 31. Januar 2027, ab 1. Februar 2027 00:00 UTC von selbst — ohne App-Update — still gelegt. Ab dann keine Anfrage
/// mehr an diese Börse (Kurse, Sammelliste, Paarliste), ihre Paare bleiben in der Merkliste grau mit Grund (`marker`,
/// gespeichert als `lastError`, zählt als «nicht gehandelt», `NotTraded`), Alarme bleiben gespeichert, prüfen aber
/// nichts; im Explorer nicht mehr angeboten. Der CSV-Import der Exporte dieser Börse bleibt (Dateiformat, keine
/// Schnittstelle). Ein Zeitpunkt (UTC), keine Zeitzone; jede Prüfung nimmt `now` (Tests) oder `clock()`. Gemeinsame Regel für iPhone,
/// Widgets und Apple Watch (WatchCore, dazu im Widget-Ziel); wie Android `ExchangeRetirement.kt`
/// (Paritäts-Fixture `exchange_retirement.json`). Nur Foundation (im Debug-Build dazu `os` für den Schalter «Stichtag
/// vorziehen», `debugOffsetMillis`: verschiebt nur die Uhr dieser Regel, nicht die des Geräts).
///
/// «Stillgelegt» entscheidet überall EINE Regel (`retiredNow`): gespeicherter Marker ODER Ende erreicht — Zeile, Paarseite,
/// Widgets, Uhr, iPhone → Uhr, Alarme, Explorer zeigen dann keinen Kurs mehr («—» mit kurzem Grund), auch offline und bei
/// deaktivierten Paaren. Ab `bitpandaNoticeMillis` (1. Januar 2027) kündigt die App das Ende an (`isAnnounced`); bis zum
/// Ende ändert sich sonst nichts (Kurse kommen, Alarme prüfen).
enum ExchangeRetirement {
    /// Schlüssel (= Name) der Börse Bitpanda.
    static let bitpanda = "Bitpanda"

    /// 1. Februar 2027 00:00:00 UTC (Epoch-ms): ab diesem Zeitpunkt liefert Bitpanda keine öffentlichen Kurse mehr.
    static let bitpandaEndMillis: Int64 = 1_801_440_000_000

    /// 1. Januar 2027 00:00:00 UTC (Epoch-ms): ab hier kündigt die App das Ende von Bitpanda an (Hinweis, Grund).
    static let bitpandaNoticeMillis: Int64 = 1_798_761_600_000

    /// Anfang des gespeicherten «Fehlers» (`lastError`) eines stillgelegten Paars; danach der Name der Börse.
    static let markerPrefix = "Exchange retired: "

    /// Ende je Börse (Schlüssel bzw. Name, Gross-/Kleinschreibung egal).
    private static let ends: [String: Int64] = [bitpanda: bitpandaEndMillis]

    /// Beginn der Ankündigung je Börse (wie `ends`).
    private static let notices: [String: Int64] = [bitpanda: bitpandaNoticeMillis]

    /// Uhr der Regel (Epoch-ms, Uhr des Geräts; im Debug-Build plus `debugOffsetMillis`); Tests geben `now` ausdrücklich mit.
    static func clock() -> Int64 {
        let device = Int64((Date().timeIntervalSince1970 * 1000).rounded())
        #if DEBUG
        return device + debugOffset.withLock { $0 }
        #else
        return device
        #endif
    }

    #if DEBUG
    /// Debug-Schalter «Stichtag vorziehen» (Entwickler-Einstellungen): Versatz der Uhr dieser Regel in ms, 0 = aus. Nur
    /// im Debug-Build; hinter einer Sperre (Widgets, Hintergrund und Oberfläche lesen gleichzeitig).
    private static let debugOffset = OSAllocatedUnfairLock(initialState: Int64(0))

    static var debugOffsetMillis: Int64 {
        get { debugOffset.withLock { $0 } }
        set { debugOffset.withLock { $0 = newValue } }
    }
    #endif

    /// Ende der öffentlichen Kurse der Börse `exchange` (Schlüssel oder Name); nil = kein Ende bekannt.
    static func endsAt(_ exchange: String?) -> Int64? {
        guard let name = exchange?.trimmingCharacters(in: .whitespaces), !name.isEmpty else { return nil }
        return ends.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
    }

    /// Beginn der Ankündigung des Endes der Börse `exchange`; nil = keine.
    static func noticeFrom(_ exchange: String?) -> Int64? {
        guard let name = exchange?.trimmingCharacters(in: .whitespaces), !name.isEmpty else { return nil }
        return notices.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
    }

    /// Wird das Ende der Börse `exchange` zum Zeitpunkt `now` angekündigt (ab der Ankündigung, vor dem Ende)?
    static func isAnnounced(_ exchange: String?, now: Int64? = nil) -> Bool {
        guard let end = endsAt(exchange), let from = noticeFrom(exchange) else { return false }
        let at = now ?? clock()
        return at >= from && at < end
    }

    /// Die eine Regel «stillgelegt» für alles, was ein Paar zeigt oder prüft: gespeicherter Marker (`lastError`) ODER das
    /// Ende der Börse `exchange` (Name bzw. Schlüssel) ist erreicht. Liefert den Namen der Börse; nil = nicht stillgelegt.
    /// Der Marker zählt auch bei zurückgestellter Uhr, bis ein Abruf wieder einen Kurs bringt. Wie Android.
    static func retiredNow(exchange: String?, lastError: String?, now: Int64? = nil) -> String? {
        if let name = self.exchange(of: lastError) { return name }
        guard let name = exchange?.trimmingCharacters(in: .whitespaces), !name.isEmpty, isRetired(name, now: now) else { return nil }
        return name
    }

    /// Ist die Börse `exchange` zum Zeitpunkt `now` stillgelegt (ab dem Ende einschliesslich)?
    static func isRetired(_ exchange: String?, now: Int64? = nil) -> Bool {
        guard let end = endsAt(exchange) else { return false }
        return (now ?? clock()) >= end
    }

    /// Gespeicherter «Fehler» für ein Paar der stillgelegten Börse `exchangeName`.
    static func marker(_ exchangeName: String) -> String {
        markerPrefix + exchangeName.trimmingCharacters(in: .whitespaces)
    }

    /// Ist `lastError` der Marker einer stillgelegten Börse?
    static func isMarker(_ lastError: String?) -> Bool { lastError?.hasPrefix(markerPrefix) == true }

    /// Name der Börse aus dem Marker; nil, wenn `lastError` keiner ist.
    static func exchange(of lastError: String?) -> String? {
        guard let lastError, isMarker(lastError) else { return nil }
        let name = String(lastError.dropFirst(markerPrefix.count)).trimmingCharacters(in: .whitespaces)
        return name.isEmpty ? nil : name
    }

    /// Stillgelegte Börsen unter `exchanges` (in der Reihenfolge des ersten Vorkommens).
    static func retired(among exchanges: [String], now: Int64? = nil) -> [String] {
        let at = now ?? clock()
        var seen: Set<String> = []
        return exchanges.filter { isRetired($0, now: at) && seen.insert($0.lowercased()).inserted }
    }

    /// Hinweis in der Merkliste (mit ✕): die stillgelegten Börsen unter den Paaren (`exchanges`, Namen), ohne die schon
    /// mit ✕ geschlossenen (`dismissed`, klein geschrieben verglichen). Leer = kein Hinweis.
    static func noticeExchanges(_ exchanges: [String], dismissed: Set<String>, now: Int64? = nil) -> [String] {
        let closed = Set(dismissed.map { $0.trimmingCharacters(in: .whitespaces).lowercased() })
        return retired(among: exchanges, now: now).filter { !closed.contains($0.trimmingCharacters(in: .whitespaces).lowercased()) }
    }

    /// Ankündigung über der Merkliste (mit ✕): die Börsen unter `exchanges`, deren Ende angekündigt ist (`isAnnounced`),
    /// ohne die mit ✕ geschlossenen (`dismissed`, eigene Einträge `advanceKey` — das ✕ der Ankündigung schliesst nicht
    /// den Hinweis nach dem Ende). Wie Android.
    static func advanceNoticeExchanges(_ exchanges: [String], dismissed: Set<String>, now: Int64? = nil) -> [String] {
        let at = now ?? clock()
        let closed = Set(dismissed.map { $0.trimmingCharacters(in: .whitespaces).lowercased() })
        var seen: Set<String> = []
        return exchanges.filter {
            isAnnounced($0, now: at) && seen.insert($0.lowercased()).inserted && !closed.contains(advanceKey($0))
        }
    }

    /// Eintrag des ✕ der Ankündigung für `exchange` (klein, mit Zusatz; der Hinweis nach dem Ende nimmt nur den Namen).
    static func advanceKey(_ exchange: String) -> String {
        exchange.trimmingCharacters(in: .whitespaces).lowercased() + advanceSuffix
    }

    private static let advanceSuffix = "#advance"
}
