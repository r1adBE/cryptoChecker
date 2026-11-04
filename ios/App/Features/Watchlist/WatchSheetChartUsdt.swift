import Foundation

// USDT → USD der Ausweich-Reihe im Chart des Aktionsblatts (Teil von `SheetChart`, WatchSheetChart.swift): je Kerze der
// Kurs ihres Tags nur, wenn es ihn für jede Kerze gibt, sonst ein Kurs für die ganze Reihe. Wie Android `SheetChart`.
extension SheetChart {
    /// USDT/USD je Kerze; `approximate` = USDT = 1, `historyMissing` = ganze Reihe mit dem Live-Kurs (`usdtSeries`).
    struct UsdtSeries: Equatable, Sendable {
        let rates: [Double]
        let approximate: Bool
        let historyMissing: Bool
    }

    /// USDT/USD je Kerze wie der Wertverlauf (`UsdtUsdRates.on`: ab `todayEpochDay` der Live-Kurs, davor der Tagesschluss
    /// DES Tags) — aber nur, wenn es den Kurs für JEDE Kerze gibt. Fehlt er an einem Tag (z. B. keine Tagesschlüsse, weil
    /// sie nicht zu laden waren) oder heute der Live-Kurs, gilt EIN Kurs für die ganze Reihe: der Live-Kurs, sonst 1
    /// (Näherung). So entsteht nie eine Stufe aus «früher 1, heute live», die wie gemessen aussähe; der Vermerk sagt, was
    /// fehlte (`usdtHistoryNote` bzw. `usdtParityNote`). Ohne `usdt` überall 1. Wie Android.
    static func usdtSeries(_ candles: [MarketCandle], usdt: UsdtUsdRates?, todayEpochDay: Int) -> UsdtSeries {
        let perDay = candles.map { usdtOn(usdt, openTime: $0.openTime, todayEpochDay: todayEpochDay) }
        if !perDay.contains(where: \.approximate) {
            return UsdtSeries(rates: perDay.map(\.rate), approximate: false, historyMissing: false)
        }
        let one = (usdt ?? UsdtUsdRates(live: nil)).current()
        return UsdtSeries(rates: Array(repeating: one.rate, count: candles.count), approximate: one.approximate,
                          historyMissing: !one.approximate)
    }

    /// Rechnet die Umrechnung mit USDT = 1 (`usdtSeries`)? Ohne `usdt` immer. Wie Android.
    static func usdtApproximate(_ candles: [MarketCandle], usdt: UsdtUsdRates?, todayEpochDay: Int) -> Bool {
        usdtSeries(candles, usdt: usdt, todayEpochDay: todayEpochDay).approximate
    }

    /// USDT/USD am (UTC-)Tag der Kerze (`UsdtUsdRates.on`); ohne `usdt` 1 als Näherung.
    private static func usdtOn(_ usdt: UsdtUsdRates?, openTime: Int64, todayEpochDay: Int) -> UsdtUsd {
        (usdt ?? UsdtUsdRates(live: nil)).on(utcEpochDay(openTime), todayEpochDay: todayEpochDay)
    }

    /// Vermerk «USDT = 1 angenommen» unter einem Fiat-Paar-Chart: die USDT-Reihe ist mit Devisenkursen USD → Fiat
    /// umgerechnet (`.daily` bzw. `.current`) und mit USDT = 1 USD (`usdtApproximate`). Nur bei Umrechnung aus USDT —
    /// wie Transaktionsblatt, Portfolio-Alarm und Android.
    static func usdtParityNote(_ fx: SheetChartFx, sourcePair: String?, usdtApproximate: Bool) -> Bool {
        usdtApproximate && convertsUsdt(fx, sourcePair: sourcePair)
    }

    /// Vermerk «USDT/USD-Verlauf nicht verfügbar»: Umrechnung aus USDT, ganze Reihe mit dem Live-Kurs (`usdtSeries`).
    static func usdtHistoryNote(_ fx: SheetChartFx, sourcePair: String?, historyMissing: Bool) -> Bool {
        historyMissing && convertsUsdt(fx, sourcePair: sourcePair)
    }

    /// Umrechnung der USDT-Reihe in Fiat (nicht die Kerzen des Paars, nicht eine andere Reihe)?
    private static func convertsUsdt(_ fx: SheetChartFx, sourcePair: String?) -> Bool {
        guard fx != .none else { return false }
        var source = "USDT"
        if let pair = sourcePair, let slash = pair.lastIndex(of: "/") {
            let tail = pair[pair.index(after: slash)...].trimmingCharacters(in: .whitespaces).uppercased()
            if !tail.isEmpty { source = tail }
        }
        return source == "USDT"
    }
}
