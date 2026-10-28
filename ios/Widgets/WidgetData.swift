import Foundation
import os
import WidgetKit

/// Gemeinsame Daten der Widgets.
enum WidgetData {
    static let watchlistKind = SharedStorage.watchlistWidgetKind
    static let singleKind = SharedStorage.singleWidgetKind

    /// Gleiche Reihenfolge wie die App: Favoriten zuerst, dann eigene Ordnung, dann Id.
    static func sorted(_ watches: [Watch]) -> [Watch] {
        watches.sorted { a, b in
            if a.favorite != b.favorite { return a.favorite }
            if a.sortOrder != b.sortOrder { return a.sortOrder < b.sortOrder }
            return a.id < b.id
        }
    }

    static func sortedWatches() -> [Watch] {
        sorted(SharedStorage.loadSnapshot().watches)
    }

    /// Paare einer Gruppe in App-Reihenfolge; nil = alle. Eine umbenannte Gruppe folgt dem neuen
    /// Namen; gibt es die Gruppe nicht mehr (gelöscht, kein Paar mehr darin), wieder alle
    /// (`WidgetGroupSelection.resolve`) — wie `ListWidgetRows` (Android).
    static func watches(group selection: String?) -> [Watch] {
        groupWatches(group: selection).watches
    }

    /// Wirksame Gruppe und ihre Paare: `group` nil = «Alle» — auch wenn die gewählte Gruppe keine
    /// Paare (mehr) hat und das Widget deshalb alle zeigt (Titel dann «Merkliste»).
    static func groupWatches(group selection: String?) -> (group: String?, watches: [Watch]) {
        let all = sortedWatches()
        // «Alpha»: gespeicherte Listen nur dann und einmal lesen statt je Paar (wie `AppData.visibleWatches`)
        let alphaSelected = WatchFilter.isAlpha(selection)
        let tradFiPairs = alphaSelected ? CoinLogoStore.tradFiPairs : []
        let alphaSymbols = alphaSelected ? CoinLogoStore.alphaSymbols : []
        let picked = WidgetGroupSelection.pick(
            selection, items: all, groupName: { $0.groupName }, favorite: { $0.favorite },
            alpha: { $0.assetKind(tradFiPairs: tradFiPairs, alphaSymbols: alphaSymbols) == .ALPHA },
            renames: WidgetGroupSelection.renames)
        return (picked.group, picked.items)
    }

    /// Titel im Kopf des Merkliste-Widgets: «Merkliste» oder die gewählte Gruppe bzw. der Filter
    /// («Favoriten», «Token», «Alpha-Tokens», «TradFi», eigener Name — gekürzt).
    static func headerTitle(group: String?) -> String {
        WidgetGroupSelection.title(group, fallback: L("tab_watchlist")) { filter in
            if WatchFilter.isFavorites(filter) { return L("group_favorites") }
            if WatchFilter.isTokens(filter) { return L("group_tokens") }
            return L("group_alpha")
        }
    }

    /// Normales Neuladen (`WidgetCadence.reloadMinutes`).
    static var nextReload: Date { Date().addingTimeInterval(Double(WidgetCadence.reloadMinutes) * 60) }

    /// Tageszähler der Neuladen (alle Widget-Arten, App-Group-Defaults).
    static var reloadCounter: DailyReloadCounter { DailyReloadCounter(defaults: SharedStorage.defaults) }

    /// Neuladen mit gewünschtem frühem Neuversuch: höchstens einer je Takt und Widget-Art
    /// (`WidgetCadence.nextReload`) und nur unter der Tagesgrenze, sonst der normale Takt. Jede so gebaute
    /// Timeline zählt als ein Neuladen.
    static func reload(kind: String, wantsQuickRetry: Bool) -> Date {
        let key = "widget_quick_retry_at_" + kind
        let stored = SharedStorage.defaults.double(forKey: key)
        let now = Date()
        let today = reloadCounter.increment(now: now)
        let next = WidgetCadence.nextReload(now: now, wantsQuickRetry: wantsQuickRetry,
                                            lastQuickRetryAt: stored > 0 ? Date(timeIntervalSince1970: stored) : nil,
                                            reloadsToday: today)
        if next.quickRetry { SharedStorage.defaults.set(now.timeIntervalSince1970, forKey: key) }
        if wantsQuickRetry && !next.quickRetry {
            AppLog.widget.info("Früher Neuversuch ausgelassen (heute \(today, privacy: .public) Neuladen)")
        }
        return next.date
    }

    /// Andere Widget-Arten aus der Erweiterung neu laden (nur die, die geänderte Kurse zeigen) — gezählt.
    static func reloadKinds(_ kinds: [String], reason: String) {
        guard !kinds.isEmpty else { return }
        let today = reloadCounter.increment(by: kinds.count)
        AppLog.widget.info("Neuladen \(kinds.count, privacy: .public) Arten (\(reason, privacy: .public)), heute \(today, privacy: .public)")
        for kind in kinds { WidgetCenter.shared.reloadTimelines(ofKind: kind) }
    }

    /// Wartet kurz (höchstens `maxWait` Sekunden), bis die Kurse zur Uhrzeit im Kopf auf der Platte
    /// liegen (`WidgetDataFreshness`) — sonst zeigte das Widget die neue Uhrzeit über alten Kursen.
    /// - Returns: true = Kurse passen zur Uhrzeit; false = noch nicht (dann bald neu laden).
    static func awaitPricesForRefreshTime(maxWait: Double = 3) async -> Bool {
        let deadline = Date().addingTimeInterval(maxWait)
        while WidgetDataFreshness.dataBehind(refreshAt: SharedStorage.lastRefreshAt,
                                             writtenAt: SharedStorage.watchlistWrittenAt) {
            guard Date() < deadline else { return false }
            try? await Task.sleep(nanoseconds: 250_000_000)
        }
        return true
    }

    /// App-Name; `app_name` ist in Android nicht übersetzbar und fehlt evtl. im Katalog.
    static var appName: String {
        let name = L("app_name")
        return name == "app_name" || name.isEmpty ? "Crypto Checker" : name
    }

    /// Kopfzeile: Uhrzeit zuerst (daran erkennt man alte Daten), dahinter die Dauer der
    /// letzten Aktualisierung — «06:42:15 · 840 ms». Fehlendes fällt samt Trenner weg.
    static func refreshMeta(at: Int64, duration: Int64) -> String {
        [PriceFormat.time(at), PriceFormat.duration(duration)]
            .filter { !$0.isEmpty && $0 != "—" }
            .joined(separator: " · ")
    }

    static func watchURL(_ id: Int64) -> URL? { URL(string: "cryptochecker://watch/\(id)") }
    static let addURL = URL(string: "cryptochecker://add")

    // MARK: Beispieldaten (Platzhalter / Galerie)

    static let sampleWatches: [Watch] = {
        let now = EpochTime.nowMillis
        return [
            Watch(id: 1, marketKey: "binance", marketName: "Binance", baseAsset: "BTC", quoteAsset: "USDT",
                  lastPrice: 83_142, previousPrice: 82_865, lastUpdate: now, favorite: true, change24h: 2.31),
            Watch(id: 2, marketKey: "binance", marketName: "Binance", baseAsset: "ETH", quoteAsset: "USDT",
                  lastPrice: 2_505.49, previousPrice: 2_495.5, lastUpdate: now, change24h: 1.84),
            Watch(id: 3, marketKey: "kraken", marketName: "Kraken", baseAsset: "SOL", quoteAsset: "EUR",
                  lastPrice: 94.18, previousPrice: 94.6, lastUpdate: now, change24h: -0.92),
            Watch(id: 4, marketKey: "coinbase", marketName: "Coinbase", baseAsset: "XRP", quoteAsset: "USD",
                  lastPrice: 1.394, previousPrice: 1.389, lastUpdate: now, change24h: 0.47),
            Watch(id: 5, marketKey: "bitvavo", marketName: "Bitvavo", baseAsset: "ADA", quoteAsset: "EUR",
                  lastPrice: 0.6812, previousPrice: 0.6794, lastUpdate: now, change24h: 0.41),
            Watch(id: 6, marketKey: "kucoin", marketName: "KuCoin", baseAsset: "DOGE", quoteAsset: "USDT",
                  lastPrice: 0.2134, previousPrice: 0.2109, lastUpdate: now, change24h: 2.05),
            Watch(id: 7, marketKey: "bybit", marketName: "Bybit", baseAsset: "AVAX", quoteAsset: "USDT",
                  lastPrice: 34.82, previousPrice: 35.3, lastUpdate: now, change24h: -1.73),
            Watch(id: 8, marketKey: "okex", marketName: "OKX", baseAsset: "LINK", quoteAsset: "USDT",
                  lastPrice: 22.15, previousPrice: 21.98, lastUpdate: now, change24h: 0.89),
        ]
    }()

    private static let sampleCloses: [Double] = [
        81_706, 81_985, 81_816, 82_171, 82_390, 82_314, 82_610, 82_534, 82_247, 82_340, 82_686, 82_872,
        82_762, 82_982, 82_922, 82_678, 82_830, 83_074, 83_176, 83_024, 82_956, 83_100, 83_243, 83_142,
    ]

    /// Beispielkerzen (Galerie/Platzhalter); der letzte Schluss entspricht dem Kurs des
    /// ersten Beispielpaars (83 142), damit Etikett und Schlagzeile übereinstimmen. Open = voriger Schluss, Dochte etwas darüber/darunter.
    static func sampleCandles(range: WidgetChartRangeOption = .day) -> [MarketCandle] {
        let step = range.candleMillis
        let end = (EpochTime.nowMillis / step) * step
        let count = Int64(sampleCloses.count)
        // In Schritten mit festen Typen (lange Ausdrücke in einer Closure sind für den Type-Checker teuer)
        var out: [MarketCandle] = []
        out.reserveCapacity(sampleCloses.count)
        for (i, close) in sampleCloses.enumerated() {
            let open: Double = i == 0 ? close * 0.998 : sampleCloses[i - 1]
            let top: Double = Swift.max(open, close)
            let bottom: Double = Swift.min(open, close)
            let wick: Double = top * 0.0012
            let back: Int64 = count - 1 - Int64(i)
            let openTime: Int64 = end - back * step
            out.append(MarketCandle(openTime: openTime, open: open, high: top + wick, low: bottom - wick,
                                    close: close, volume: 0))
        }
        return out
    }
}
