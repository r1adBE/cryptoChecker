import Foundation

/// Was einer importierten Zeile dauerhaft fehlt, weil ihr Tageskurs fehlt (`CsvImportTx.missingRate`) — wie Kotlin.
enum CsvMissingRate: String, Sendable {
    case PRICE
    case FEE
    case PRICE_AND_FEE
}

/// Ausdrückliche Bestätigung «Trotzdem importieren» der Vorschau (`accepted`) samt den Vermerken in der Sprache der App
/// («Preis ohne Tageskurs», «Gebühr ohne Tageskurs»), die die betroffenen Transaktionen beim Speichern in die Notiz
/// bekommen — wie Kotlin `CsvRateConsent`.
struct CsvRateConsent: Equatable, Sendable {
    var accepted = false
    var priceNote = ""
    var feeNote = ""
}

/// Was «Erneut versuchen» bei den Zeilen ohne Tageskurs ausrichten kann (`CsvImportRateGate.retry`): `currencies` =
/// Währungen, für die es keine Tageskurse gibt (kein Fiat der Kursquelle, z. B. ein Coin als Preiswährung wie bei ETH/BTC)
/// — ein neuer Abruf hilft dort nie, die Vorschau sagt es in einem eigenen Satz; `retryHelps` = mindestens ein fehlender
/// Kurs ist der einer Fiat-Währung (ein neuer Abruf kann ihn bringen). Wie Kotlin `CsvRateRetry`.
struct CsvRateRetry: Equatable, Sendable {
    var currencies: [String] = []
    var retryHelps = false
}

/// Kein stiller, dauerhafter Verlust beim CSV-Import (wie `CsvImportRateGate.kt`, gemeinsame Fälle `csv_import.json` ›
/// «rateGate»): Fehlt einer Zeile der Tageskurs, fehlt der Transaktion danach der Preis bzw. die Gebühr — ein zweiter
/// Import holt nichts nach (Schlüssel «bereits importiert»). Darum ist «Importieren» gesperrt, solange solche Zeilen in
/// der Vorschau stehen (`canImport`): erst «Erneut versuchen» mit Kurs oder die ausdrückliche Bestätigung; dann trägt jede
/// betroffene Transaktion einen bleibenden Vermerk in der Notiz (`mark`).
enum CsvImportRateGate {

    /// Höchstlänge der Notiz (wie `CsvImportMapping.noteMax`).
    private static let noteMax = 120

    /// Zwischen Vermerken und der Notiz aus der Datei.
    static let separator = " · "

    /// «Importieren» erlaubt? Mit Zeilen ohne Tageskurs (`missingFx` = Anzahl) nur nach ausdrücklicher Bestätigung.
    static func canImport(missingFx: Int, accepted: Bool) -> Bool { missingFx <= 0 || accepted }

    /// Was `p` ohne Tageskurs fehlt; nil = nichts. Gebühr-Zeilen und Staking-Umbuchungen führen keinen Preis
    /// (`CsvImportMapping.toTx`): bei ihnen zählt nur die Gebühr.
    static func missing(_ p: CsvImportPriced) -> CsvMissingRate? {
        let price = p.priceNeedsFx && p.row.kind != .FEE && p.row.kind != .TRANSFER
        if price && p.feeNeedsFx { return .PRICE_AND_FEE }
        if price { return .PRICE }
        return p.feeNeedsFx ? .FEE : nil
    }

    /// Notiz mit Vermerk: zuerst die Vermerke (`consent`), dann die bisherige `note` (Art aus der Datei), getrennt mit
    /// `separator`, auf `noteMax` Zeichen gekürzt — der Vermerk steht vorn und bleibt so immer lesbar. `missing` nil =
    /// `note` unverändert.
    static func note(_ note: String?, missing: CsvMissingRate?, consent: CsvRateConsent) -> String? {
        guard let missing else { return note }
        var parts: [String]
        switch missing {
        case .PRICE: parts = [consent.priceNote]
        case .FEE: parts = [consent.feeNote]
        case .PRICE_AND_FEE: parts = [consent.priceNote, consent.feeNote]
        }
        if let note { parts.append(note) }
        let joined = parts.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
            .joined(separator: separator)
        let cut = String(joined.prefix(noteMax)).trimmingCharacters(in: .whitespacesAndNewlines)
        return cut.isEmpty ? nil : cut
    }

    /// Zeilen ohne Tageskurs: Währungen ohne mögliche Tageskurse und ob ein neuer Abruf helfen kann (`CsvRateRetry`).
    /// Gezählt wird nur, was `missing` zählt (Preis: Preiswährung, Gebühr: Währung der Gebühr); unbekannte Währung: keins.
    static func retry(_ priced: [CsvImportPriced]) -> CsvRateRetry {
        var result = CsvRateRetry()
        for p in priced {
            guard let missing = missing(p) else { continue }
            let units: [String?] = switch missing {
            case .PRICE: [p.row.quote]
            case .FEE: [p.row.feeUnit]
            case .PRICE_AND_FEE: [p.row.quote, p.row.feeUnit]
            }
            for unit in units {
                let code = (unit ?? "").trimmingCharacters(in: .whitespaces).uppercased()
                if code.isEmpty { continue }
                if CsvImport.isFiat(code) {
                    result.retryHelps = true
                } else if !result.currencies.contains(code) {
                    result.currencies.append(code)
                }
            }
        }
        return result
    }

    /// Vermerk an jeder betroffenen Transaktion (`CsvImportTx.missingRate`); die übrigen unverändert.
    static func mark(_ txs: [CsvImportTx], consent: CsvRateConsent) -> [CsvImportTx] {
        txs.map { tx in
            guard let missing = tx.missingRate else { return tx }
            var marked = tx
            marked.note = note(tx.note, missing: missing, consent: consent)
            return marked
        }
    }
}
