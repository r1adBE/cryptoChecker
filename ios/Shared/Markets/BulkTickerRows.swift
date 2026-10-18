import Foundation

// Sammelabfragen der Adapter (`Market.parseBulkTickersDetailed`): ausgewertete Antwort, Formatfehler, Zeilen-Regeln
// und Vollständigkeit je Teilabfrage — wie die gleichnamigen Typen auf Android.

/// Ausgewertete Sammelantwort: lesbare Kurse je Paar-Kennung und die Kennungen, deren Zeile zwar da, aber
/// unlesbar war (`failed`: Feld fehlt oder umbenannt, kein letzter Kurs). `complete` = false: die Börse hat diese
/// Antwort selbst als lückenhaft erkannt (`Market.bulkResponseComplete`), ein fehlendes Paar heisst dann nicht
/// «nicht mehr gehandelt». Wie `BulkTickerParse` auf Android.
struct BulkTickerParse: Sendable {
    let tickers: [String: Ticker]
    let failed: Set<String>
    var complete: Bool = true
}

/// Sammelantwort überwiegend unlesbar — vermutlich hat die Börse ihr Format geändert. Der Text beginnt mit
/// `BulkTickerRows.formatError`, damit der Abruf den Fall erkennt (angezeigt via `ConnectionErrors.display`).
struct BulkFormatError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Umgang mit einzelnen Zeilen einer Sammelantwort (wie `BulkTickerRows` auf Android): Eine unlesbare Zeile
/// lässt den alten Kurs nicht stillschweigend stehen, sondern macht das Paar zum Fehler (`rowError`); sind die
/// meisten Zeilen unlesbar, ist die ganze Antwort ein Formatfehler (`formatError`).
enum BulkTickerRows {
    /// Beginn des Fehlertexts, wenn die Mehrheit der Zeilen unlesbar ist.
    static let formatError = "Bulk format error"
    /// Fehlertext für ein Paar mit unlesbarer Zeile in einer sonst guten Antwort.
    static let rowError = "Bulk row unreadable"

    /// Letzter Kurs ist eine endliche Zahl und nicht «keine Daten» — dieselbe Hürde wie bei der Einzelabfrage.
    static func usable(_ ticker: Ticker) -> Bool {
        let last = ticker.last
        return last.isFinite && last > Ticker.missing
    }

    /// Das Kursfeld ist da, aber leer (null, "" oder 0): ein ruhendes oder nie gehandeltes Paar. Solche Zeilen
    /// werden übersprungen statt als unlesbar gezählt. Fehlt das Feld ganz (umbenannt) oder ist der Wert Unsinn,
    /// bleibt es ein Fehler. Wie Android `BulkTickerRows.inactivePrice`.
    static func inactivePrice(_ json: JObject, _ key: String) -> Bool {
        json.raw[key].map(holdsNoPrice) ?? false
    }

    /// Dasselbe für Zeilen in Listenform; eine Zeile, die zu kurz ist, gilt nicht als leer, sondern als unlesbar.
    static func inactivePrice(_ array: JArray, _ index: Int) -> Bool {
        guard array.raw.indices.contains(index) else { return false }
        return holdsNoPrice(array.raw[index])
    }

    /// null, leerer Text, Text «0» oder die Zahl 0 (Wahrheitswerte zählen nicht als Zahl).
    private static func holdsNoPrice(_ value: Any) -> Bool {
        switch value {
        case is NSNull:
            return true
        case let text as String:
            let cleaned = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return cleaned.isEmpty || Double(cleaned) == 0
        case let number as NSNumber:
            let isBool = CFGetTypeID(number) == CFBooleanGetTypeID()
            return !isBool && number.doubleValue == 0
        default:
            return false
        }
    }

    /// Formatfehler, sobald es unlesbare Zeilen gibt und sie die lesbaren übertreffen.
    static func isFormatFailure(parsed: Int, failed: Int) -> Bool { failed > max(parsed, 0) }

    static func formatErrorText(parsed: Int, failed: Int) -> String {
        let total = parsed + failed
        return "\(formatError): \(failed) of \(total) rows unreadable"
    }

    /// Ist dieser Fehlertext einer Sammelabfrage ein Formatfehler?
    static func isFormatError(_ error: String?) -> Bool {
        guard let error else { return false }
        return error.contains(formatError)
    }
}

/// Für welche Paare eine Sammelabfrage aus mehreren Teilen «vollständig» Auskunft gibt (Gruppen über
/// `Market.bulkTickersRequestGroup` / `bulkTickersPairGroup`; Binance: Spot 0, Alpha 1) — fällt Alpha aus,
/// werden fehlende Spot-Paare trotzdem erkannt. Ausserdem: unbekannte Paare einer abgelehnten gefilterten
/// Anfrage (Bithumb). Wie `BulkCompleteness.kt`.
enum BulkCompleteness {
    /// Angefragte Paare, deren Gruppe in `failedGroups` steckt (Teilabfrage gescheitert oder ausgelassen).
    static func incomplete(_ pairIds: [String], failedGroups: Set<Int>, groupOf: (String) -> Int) -> Set<String> {
        guard !failedGroups.isEmpty else { return [] }
        var hit = Set<String>()
        for id in pairIds where failedGroups.contains(groupOf(id)) { hit.insert(id) }
        return hit
    }

    /// Gibt die Liste über wenigstens ein angefragtes Paar verlässlich Auskunft? Ohne gescheiterte Teilabfrage
    /// immer; sonst nur, wenn ein Paar in einer fehlerfreien Gruppe liegt (ganz ohne Paare: nein).
    static func anyComplete(_ pairIds: [String], failedGroups: Set<Int>, incomplete: Set<String>) -> Bool {
        if failedGroups.isEmpty { return true }
        return Set(pairIds).count > incomplete.count
    }

    /// Angefragte Paare, die in der Paarliste der Börse (`known`) fehlen. nil, wenn keines fehlt oder die Liste
    /// leer ist — eine leere Liste beweist nichts, dann gilt der ursprüngliche Fehler.
    static func unknownPairs(_ pairIds: [String], known: Set<String>) -> Set<String>? {
        guard !known.isEmpty else { return nil }
        let missing = Set(pairIds).subtracting(known)
        return missing.isEmpty ? nil : missing
    }
}
