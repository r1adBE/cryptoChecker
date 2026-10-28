import Foundation

// Umrechnung in USDT (Teil von `CsvImport`): Kalendertag, USDT/USD-Tagesschluss und Preise, Gebühren und Tauschwerte
// der Zeilen in USDT — wie `CsvImportRow.kt` (Android).

extension CsvImport {
    static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b
        return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
    }

    /// Kalendertag (Tage seit 1970-01-01) des Zeitpunkts in `zone` — für die Tageskurse.
    static func epochDay(_ timeMillis: Int64, zone: String = CsvImportParser.defaultZone) -> Int64 {
        let offset = Int64(CsvImportParser.timeZone(zone).secondsFromGMT(for: Date(timeIntervalSince1970: Double(timeMillis) / 1000)))
        return floorDiv(floorDiv(timeMillis, 1000) + offset, 86_400)
    }

    /// USDT/USD-Tagesschluss am Tag `day` aus `usdtUsd` (Tag → Schluss), nur plausibel (`PortfolioStables.usdtUsd`);
    /// nil = keiner (kein Schluss eines anderen Tags) — wie `TaxExport.usdtUsdOn`. Wie Android.
    static func usdtUsdOn(_ usdtUsd: [Int: Decimal]?, day: Int64) -> Decimal? {
        guard let close = usdtUsd?[Int(day)], close > 0,
              !PortfolioStables.usdtUsd(PortfolioDecimal.double(close)).approximate else { return nil }
        return close
    }

    /// Preise in USDT umrechnen. `fx(Währung, Tag)` = Einheiten dieser Währung je 1 USD; nil = kein Kurs.
    /// `usdtUsd` = USDT/USD-Tagesschlüsse (Tag → Schluss; wie der Steuer-Export
    /// `TaxExport.rateOn`): USD bzw. Fiat → USDT = Betrag ÷ (Devisenkurs × USDT/USD DES Tags) — ein Kauf über 10 000 USD
    /// ergibt im Export wieder 10 000 USD Einstand. Fehlt der Schluss des Tags: × 1, Zeile als Näherung markiert
    /// (`usdtApproximate`). nil = USD gilt 1:1 als USDT (ohne Markierung). Wie Android.
    static func priceInUsdt(_ rows: [CsvImportRow], zone: String = CsvImportParser.defaultZone,
                            usdtUsd: [Int: Decimal]? = nil,
                            fx: (String, Int64) -> Decimal?) -> [CsvImportPriced] {
        rows.map { row -> CsvImportPriced in
            let day = epochDay(row.timeMillis, zone: zone)
            let close = usdtUsdOn(usdtUsd, day: day)
            var usdtApproximate = false
            var needsFx = false
            var priceUsdt: Decimal?
            if let p = row.price {
                if let q = row.quote, let r = usdtRate(q, day, fx, usdtUsd: usdtUsd, close: close) {
                    if r.approximate { usdtApproximate = true }
                    priceUsdt = toUsdt(p, r.rate)
                } else {
                    needsFx = true
                }
            }
            // Tausch: Wert aus der Datei (Fiat) zum Tageskurs
            var swapValueUsdt: Decimal?
            if let v = row.swapValue {
                if let q = row.quote, let r = usdtRate(q, day, fx, usdtUsd: usdtUsd, close: close) {
                    if r.approximate { usdtApproximate = true }
                    swapValueUsdt = toUsdt(v, r.rate)
                } else {
                    needsFx = true
                }
            }
            // Gebühr in der Preiswährung, in USD/USDT oder in einer anderen Fiat-Währung (Kauf in USD, Gebühr in EUR;
            // Tausch ohne Fiat-Wert mit EUR-Gebühr): zum Tageskurs ihrer Währung — ohne Kurs nie still weggelassen,
            // sondern needsFx wie Preis und Wert. Wie Android
            var feeUsdt: Decimal?
            if let fee = row.fee, let unit = row.feeUnit, unit == row.quote || usdLike.contains(unit) || isFiat(unit) {
                if let r = usdtRate(unit, day, fx, usdtUsd: usdtUsd, close: close) {
                    if r.approximate { usdtApproximate = true }
                    feeUsdt = toUsdt(fee, r.rate)
                } else {
                    needsFx = true
                }
            }
            return CsvImportPriced(row: row, priceUsdt: priceUsdt, feeUsdt: feeUsdt, needsFx: needsFx,
                                   missingPrice: row.price == nil && (row.kind == .BUY || row.kind == .SELL),
                                   swapValueUsdt: swapValueUsdt, usdtApproximate: usdtApproximate)
        }
    }

    /// Einheiten der Währung je 1 USDT am Tag (wie Android `rate` in `priceInUsdt`): USDT 1; sonst Devisenkurs
    /// (`baseRate`) × USDT/USD-Schluss `close`; mit `usdtUsd`, aber ohne Schluss des Tags × 1 und `approximate`;
    /// ohne `usdtUsd` der Devisenkurs allein. nil ohne Devisenkurs.
    private static func usdtRate(_ currency: String, _ day: Int64, _ fx: (String, Int64) -> Decimal?,
                                 usdtUsd: [Int: Decimal]?, close: Decimal?) -> (rate: Decimal, approximate: Bool)? {
        guard let r = baseRate(currency, day, fx) else { return nil }
        if currency == PortfolioStables.valuation || usdtUsd == nil { return (r, false) }
        guard let close else { return (r, true) }
        return (PortfolioDecimal.multiply(r, close), false)
    }

    /// Devisenkurs der Währung am Tag (Einheiten je 1 USD); USD/USDT = 1; ungültig = nil.
    private static func baseRate(_ currency: String, _ day: Int64, _ fx: (String, Int64) -> Decimal?) -> Decimal? {
        if usdLike.contains(currency) { return 1 }
        guard let r = fx(currency, day), r > 0 else { return nil }
        return r
    }

    private static func toUsdt(_ value: Decimal, _ rate: Decimal) -> Decimal {
        rate == 1 ? value : PortfolioDecimal.divide(value, rate)
    }
}
