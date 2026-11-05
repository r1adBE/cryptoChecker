import Foundation

/// Herkunft eines Devisen-Referenzkurses (Abschnitt «Referenzkurs» unter «Devisen»): welche Zentralbank, wie oft sie
/// veröffentlicht, und ob der gezeigte Wert umgerechnet ist. Jede Währung kommt aus genau einer Quelle, EZB vor NBU vor
/// NBP (`ReferenceRates.assign`). Rohwerte wie die Namen in Android `ReferenceSource.kt`, gleiche Fälle in
/// `Parity/reference_rates.json`.
enum ReferenceSource: String, CaseIterable, Sendable {
    case ecb = "ECB"
    case nbu = "NBU"
    case nbp = "NBP"

    /// Schlüssel der Quelle in `ExchangesConfig`.
    var exchangeKey: String {
        switch self {
        case .ecb: return "EcbReference"
        case .nbu: return "NbuReference"
        case .nbp: return "NbpReference"
        }
    }

    /// true: neue Kurse einmal pro Woche (NBP Tabelle B, mittwochs), sonst täglich.
    var weekly: Bool { self == .nbp }

    /// Währung, in der die Quelle ihre Kurse festlegt (EZB: Einheiten je 1 EUR; NBU: UAH je Einheit; NBP: PLN je Einheit).
    var home: String {
        switch self {
        case .ecb: return "EUR"
        case .nbu: return "UAH"
        case .nbp: return "PLN"
        }
    }

    /// Ist der gezeigte Wert von `base` umgerechnet oder eine selbst berechnete Veränderung (`hasChange`)? Dann sagt die
    /// Quellzeile «· umgerechnet». Unverändert ist nur der EZB-Kurs zur Basis EUR ohne Veränderung. Wie Android.
    func converted(base: String, hasChange: Bool) -> Bool {
        hasChange || base != home || self != .ecb
    }

    /// Quelle zum Börsen-Schlüssel; nil = kein Referenzkurs.
    static func of(_ exchangeKey: String?) -> ReferenceSource? {
        allCases.first { $0.exchangeKey == exchangeKey }
    }
}

/// Quelle, deren Kurse aus mehreren Antworten zusammen entstehen (NBP: Tabelle B mit Tabelle A desselben Tags) und
/// lange gleich bleiben: Die App holt alle `responseAddresses` gemeinsam (eine Anfrage je Adresse und Aktualisierung),
/// behält sie bis `validUntil` und reicht sie mit `separator` verbunden als Antwort von Anfrage 0 an die gewöhnliche
/// Einzel- bzw. Sammelabfrage weiter (`MultiResponses`). Wie Android `MultiResponseSource`.
protocol MultiResponseSource: Exchange {
    /// GET-Adressen, die zusammen gelesen werden, zum Zeitpunkt `now` (ms).
    func responseAddresses(now: Int64) -> [String]
    /// Bis wann (ms) die Antworten gültig bleiben; ≤ `now` = nicht behalten (z. B. unlesbar).
    func validUntil(bodies: [String], now: Int64) -> Int64
}

/// Verbinden und Trennen der Antworten einer `MultiResponseSource` (ASCII «Record Separator»). Wie Android.
enum MultiResponse {
    static let separator = "\u{1E}"

    static func join(_ bodies: [String]) -> String { bodies.joined(separator: separator) }

    static func split(_ body: String) -> [String] { body.components(separatedBy: separator) }
}
