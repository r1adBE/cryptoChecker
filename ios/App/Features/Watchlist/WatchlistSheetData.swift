import SwiftUI
import UIKit

// Daten für Zeilen und Aktionsblatt: Quelle der Tagesänderung und des Mini-Charts (andere Börse, eigene
// Stundenkerzen, Ausweich-Reihe) — nur aus dem Speicher.
// Eigene Datei, weil es reine Abfragen ohne Ansicht sind (wie `WatchlistSheetData.kt`).

extension WatchlistRow {
    /// Was von einer anderen Börse kommt (%-Änderung, Mini-Chart) — nur aus dem Speicher, keine Abfrage.
    /// Steht nicht in der Zeile, sondern im Aktionsblatt unter dem Kurs (wie Android
    /// `WatchlistViewModel.rowSource`).
    static func rowSource(for watch: Watch) async -> DayChange.RowSource? {
        let change = await PriceRefresher.foreignCandleSource(watch)
        let chart = await foreignSparklineSource(for: watch)
        return DayChange.rowSource(change: change, chart: chart)
    }

    /// Börse des Mini-Charts, falls nicht die eigene (USDT-Reihe der Ausweich-Kette); nil = eigene Börse bzw. kein
    /// Chart. Nur aus dem Speicher — wie Android `WatchlistViewModel.foreignSparklineSource`.
    static func foreignSparklineSource(for watch: Watch) async -> String? {
        let base = watch.baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
        // Mini-Chart von der eigenen Börse: kein Hinweis; sonst Anbieter der USDT-Reihe
        let pair = CandlePair.of(watch)
        let chartProvider: String?
        if await ownSparkline(pair, price: watch.lastPrice, cachedOnly: true) != nil {
            chartProvider = OwnCandlePolicy.provider(pair)
        } else if OwnCandlePolicy.allowsFallback(pair) {
            let provider: String? = await DayReferenceStore.shared.cachedProvider(base: base, quote: "USDT")
            chartProvider = fallbackSeriesAllowed(watch, provider: provider) ? provider : nil
        } else {
            chartProvider = nil
        }
        return DayChange.foreignCandleSource(exchanges: [watch.exchangeKey, watch.exchangeName], provider: chartProvider)
    }

    /// Ausweich-Reihe von `provider` beim Paar `watch` erlaubt (kein klarer Namens-Widerspruch)?
    /// Wie Android `WatchlistViewModel.fallbackSeriesAllowed`.
    static func fallbackSeriesAllowed(_ watch: Watch, provider: String?) -> Bool {
        TokenIdentityStore.shared.fallbackSeriesAllowed(exchangeKey: watch.exchangeKey, base: watch.baseAsset, provider: provider)
    }

    /// Mini-Chart-Reihe (USDT, gemeinsam je Kürzel) beim Paar `watch` erlaubt? Nur aus dem Speicher.
    static func sparklineFallbackAllowed(_ watch: Watch) async -> Bool {
        let base: String = watch.baseAsset.trimmingCharacters(in: .whitespaces).uppercased()
        let provider: String? = await DayReferenceStore.shared.cachedProvider(base: base, quote: "USDT")
        return fallbackSeriesAllowed(watch, provider: provider)
    }

    /// «Änderung & Chart: Binance» bzw. «Änderung: …» / «Chart: …» (wie Android `rowSourceText`).
    static func rowSourceText(_ source: DayChange.RowSource) -> String {
        switch source.kind {
        case .changeAndChart: return L("watch_row_source_both", source.provider)
        case .change:
            let change: String = L("watch_row_source_change", source.provider)
            guard let chart = source.chartProvider else { return change }
            return change + " · " + L("watch_row_source_chart", chart)
        case .chart: return L("watch_row_source_chart", source.provider)
        }
    }

    /// Stundenkurse der EIGENEN Börse des Paars (in seiner Quote, Umrechnungsfaktor 1) — nur bei gleichem
    /// Token (`SheetChart.sparklineFor`); nil = keine (dann die USDT-Reihe). `cachedOnly`: ohne Netz.
    func ownSparkline(_ pair: CandlePair, cachedOnly: Bool) async -> [Double]? {
        await Self.ownSparkline(pair, price: watch.lastPrice, cachedOnly: cachedOnly)
    }

    static func ownSparkline(_ pair: CandlePair, price: Double?, cachedOnly: Bool) async -> [Double]? {
        guard OwnHourlyStore.supports(pair) else { return nil }
        let closes: [Double]?
        if cachedOnly {
            closes = await OwnHourlyStore.shared.cachedCloses(pair)
        } else {
            closes = await OwnHourlyStore.shared.closes(pair)
        }
        return SheetChart.sparklineFor(closes, price: price, quoteUsd: 1)
    }
}
