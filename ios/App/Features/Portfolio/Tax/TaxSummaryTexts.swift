import Foundation

// Texte der Jahresübersicht (`TaxExport.yearSummary`) — wie `TaxSummaryTexts.kt` (Android).

/// Texte der Jahresübersicht (`TaxExport.summaryCsv`) — wie `TaxSummaryTexts` (Android).
struct TaxSummaryTexts: Sendable {
    var title: String
    var disclaimer: String
    var item: String
    var usdt: String
    var target: String
    var note: String
    var orientation: String
    var labels: [TaxLine: String]
    var below: String
    var reached: String
    var unknown: String
    var incomplete: String
    var noFx: String
    /// Kopf des Orientierungs-Blocks «Orientierung – keine Steuerberatung, keine Veranlagung»; leer = `orientation`.
    var orientationHeading: String = ""
    /// Posten im Orientierungs-Block «Orientierung: %s»; leer = «`orientation`: Posten».
    var orientationItem: String = ""
    /// Vermerk «nicht gerechnet – ESTV-Kursliste <Link>» (`TaxSummaryLine.notCalculated`); leer = keiner.
    var notCalculated: String = ""
    /// Status «Verlust» (`TaxLimitStatus.loss`); leer = `below`.
    var loss: String = ""
    /// Texte der Zusatz-Vermerke (`TaxSummaryLine.note`); fehlt einer = kein Vermerk.
    var notes: [TaxSummaryNote: String] = [:]
    /// CH Vermögenswert, Zeile je Coin: Text mit `TaxExport.wealthCoinToken`/`wealthAmountToken` (`wealthCoinLabel`);
    /// leer = «Coin Menge».
    var wealthCoin: String = ""
    /// «nicht gerechnet» bei einem Coin ohne Wert (`TaxWealthRow.missing`).
    var wealthMissing: String = ""
    /// Grund «kein Kurs» (`TaxWealthRow.noPrice`).
    var wealthNoPrice: String = ""
    /// Grund «ungeprüft» (`TaxWealthRow.unchecked`).
    var wealthUnchecked: String = ""
    /// Grund «Adresse nicht beobachtet» (`TaxWealthRow.addressUnobserved`).
    var wealthAddressUnobserved: String = ""
}

/// Posten im Orientierungs-Block — wie `taxOrientationLabel` (Android): `item` mit «%s» für `label`; ohne `item`
/// «`fallback`: `label`» (leer = nur `label`). Jeder Schluss einer Vorlage steht so sichtbar als Orientierung da.
func taxOrientationLabel(_ item: String, fallback: String, label: String) -> String {
    if item.contains("%s") { return item.replacingOccurrences(of: "%s", with: label) }
    if !fallback.isEmpty { return "\(fallback): \(label)" }
    return label
}

extension TaxExport {
    /// Platzhalter für das Datum in einem Vermerk (`.WINDOW_OPEN`) — wie `TAX_NOTE_DATE` (Android).
    static let noteDateToken = "{date}"
}
