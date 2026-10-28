import SwiftUI

// Daten des Wertverlaufs: benötigte Tagesschlüsse je Zeitraum (`PortfolioHistoryRequest`) und der Zustand für
// die Ansicht (`PortfolioHistoryUi`). Eigene Datei wie `PortfolioHistoryLoad.kt`.

/// Welche Tagesschlusskurse ein Zeitraum braucht: Coins und Tage bis heute — wie `HistoryRequest` (Android).
struct PortfolioHistoryRequest: Equatable {
    let coins: Set<String>
    let days: Int

    /// Deckt eine Ladung für diese Anfrage `needed` ab (alle Coins, genug Tage)?
    func covers(_ needed: PortfolioHistoryRequest) -> Bool {
        coins.isSuperset(of: needed.coins) && days >= needed.days
    }

    /// «Seit 1. Kauf» braucht je nach erstem Kauf mehr Tage (`PortfolioHistory.candleDays`).
    static func make(_ txs: [PortfolioTx], range: PortfolioHistoryRange) -> PortfolioHistoryRequest {
        let first = txs.filter { $0.amount > 0 && !$0.amount.isInfinite }.map(\.time).min()
        let days = PortfolioHistory.candleDays(range: range, firstTradeMillis: first,
                                               todayEpochDay: PortfolioHistory.utcToday())
        return PortfolioHistoryRequest(coins: Set(txs.flatMap(\.coins).map { PortfolioCalculator.normalizeCoin($0) }), days: days)
    }
}

/// Wertverlauf für die Karte über den Positionen: `series` in `unit` (`converted` = aus USDT
/// umgerechnet, je Tag mit dem Devisenkurs dieses Tags, siehe `PortfolioHistoryFx`;
/// `approximateFx` = Tageskurse fehlten, alles mit dem heutigen Kurs — die Karte sagt das unter
/// dem Chart). Mit Tageskursen umgerechnet zusätzlich `usdtSeries` (derselbe Verlauf unumgerechnet,
/// für «USDT») und `compare` (beide in Prozent, für «Vergleich»; nil ohne Ausgangswert, siehe
/// `PortfolioCompare`) — sonst beide nil und kein Umschalter. Wie `PortfolioHistoryUi` (Android).
struct PortfolioHistoryUi: Equatable, Sendable {
    let range: PortfolioHistoryRange
    let series: PortfolioHistorySeries
    let unit: String
    let converted: Bool
    var approximateFx: Bool = false
    var usdtSeries: PortfolioHistorySeries?
    var compare: PortfolioCompareSeries?
    /// Coins mit Binance-Verlauf, auf den Börsenkurs skaliert (`PortfolioExchangeRoute.scaledHint`); leer = keiner.
    var scaled: [String] = []
    /// Mit USDT zum Marktkurs umgerechnet, aber an mindestens einem Tag ohne USDT/USD-Kurs (dort 1 als Näherung,
    /// `PortfolioHistoryConverted.usdtApproximate`) — Hinweis «USDT = 1 angenommen». Wie Android.
    var usdtApproximate: Bool = false
    /// Eigene Börse nicht erreichbar, Verlauf aus einem älteren Stand: dessen Zeit (`PortfolioHistoryAsOf`) — die Karte
    /// sagt «Stand …»; nil = frisch. Wie Android.
    var staleSince: Int64?
    /// Coins mit Tageskursen aus der Ausweich-Kette → Anbieter (z. B. «Kraken», `PortfolioDailyPrices.chainSources`);
    /// die Karte nennt sie. Leer = keine. Wie Android.
    var chainSources: [String: String] = [:]

    /// In USDT: derselbe Verlauf unumgerechnet (Umschalter, Vergleich und Hinweise bleiben).
    func forView(_ view: PortfolioHistoryView) -> PortfolioHistoryUi {
        guard view == .usdt, let usdtSeries else { return self }
        var out = PortfolioHistoryUi(range: range, series: usdtSeries, unit: PortfolioFormat.usdt, converted: converted,
                                     approximateFx: approximateFx, usdtSeries: usdtSeries, compare: compare, scaled: scaled,
                                     usdtApproximate: usdtApproximate, staleSince: staleSince)
        out.chainSources = chainSources
        return out
    }
}
