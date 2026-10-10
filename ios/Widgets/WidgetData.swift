import Foundation
import WidgetKit

/// Gemeinsame Daten der Widgets.
enum WidgetData {
    static let watchlistKind = SharedStorage.watchlistWidgetKind
    static let singleKind = SharedStorage.singleWidgetKind

    /// Neu zeichnen spätestens nach so vielen Minuten.
    static let reloadMinutes: Double = 15

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

    /// Paare einer Gruppe in App-Reihenfolge; nil = alle. Gibt es die Gruppe
    /// nicht mehr (kein Paar mehr darin), wieder alle — wie `PriceWidgetService`.
    static func watches(group: String?) -> [Watch] {
        let all = sortedWatches()
        guard let group else { return all }
        let filtered = all.filter { WatchFilter.matches(group, $0) }
        return filtered.isEmpty ? all : filtered
    }

    /// Vorhandene Gruppen, alphabetisch ohne Gross/Klein (wie die App).
    static func groups() -> [String] {
        Array(Set(SharedStorage.loadSnapshot().watches.compactMap(\.groupName)))
            .sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }

    static var nextReload: Date { Date().addingTimeInterval(reloadMinutes * 60) }

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
        let now = TimeUtils.nowMillis
        return [
            Watch(id: 1, marketKey: "binance", marketName: "Binance", baseAsset: "BTC", quoteAsset: "USDT",
                  lastPrice: 64_231.5, previousPrice: 63_480.2, lastUpdate: now, favorite: true, change24h: 1.84),
            Watch(id: 2, marketKey: "binance", marketName: "Binance", baseAsset: "ETH", quoteAsset: "USDT",
                  lastPrice: 3_142.18, previousPrice: 3_171.9, lastUpdate: now, change24h: -0.94),
            Watch(id: 3, marketKey: "kraken", marketName: "Kraken", baseAsset: "SOL", quoteAsset: "EUR",
                  lastPrice: 142.37, previousPrice: 139.8, lastUpdate: now, change24h: 3.12),
            Watch(id: 4, marketKey: "coinbase", marketName: "Coinbase", baseAsset: "XRP", quoteAsset: "USD",
                  lastPrice: 0.5231, previousPrice: 0.5262, lastUpdate: now, change24h: -0.59),
            Watch(id: 5, marketKey: "bitvavo", marketName: "Bitvavo", baseAsset: "ADA", quoteAsset: "EUR",
                  lastPrice: 0.3412, previousPrice: 0.3398, lastUpdate: now, change24h: 0.41),
            Watch(id: 6, marketKey: "kucoin", marketName: "KuCoin", baseAsset: "DOGE", quoteAsset: "USDT",
                  lastPrice: 0.1244, previousPrice: 0.1219, lastUpdate: now, change24h: 2.05),
            Watch(id: 7, marketKey: "bybit", marketName: "Bybit", baseAsset: "AVAX", quoteAsset: "USDT",
                  lastPrice: 27.91, previousPrice: 28.4, lastUpdate: now, change24h: -1.73),
            Watch(id: 8, marketKey: "okex", marketName: "OKX", baseAsset: "LINK", quoteAsset: "USDT",
                  lastPrice: 13.62, previousPrice: 13.5, lastUpdate: now, change24h: 0.89),
        ]
    }()

    private static let sampleCloses: [Double] = [
        63_120, 63_340, 63_210, 63_480, 63_650, 63_590, 63_820, 63_760, 63_540, 63_610, 63_880, 64_020,
        63_940, 64_110, 64_060, 63_870, 63_990, 64_180, 64_260, 64_140, 64_090, 64_200, 64_310, 64_231.5,
    ]

    /// Beispielkerzen (Galerie/Platzhalter); der letzte Schluss entspricht dem Kurs des
    /// ersten Beispielpaars (64 231.5), damit Etikett und Schlagzeile übereinstimmen. Open = voriger Schluss, Dochte etwas darüber/darunter.
    static func sampleCandles(range: WidgetChartRangeOption = .day) -> [MarketCandle] {
        let step = range.candleMillis
        let end = (TimeUtils.nowMillis / step) * step
        let count = Int64(sampleCloses.count)
        return sampleCloses.enumerated().map { i, close in
            let open = i == 0 ? close * 0.998 : sampleCloses[i - 1]
            let wick = Swift.max(open, close) * 0.0012
            return MarketCandle(openTime: end - (count - 1 - Int64(i)) * step, open: open,
                                high: Swift.max(open, close) + wick, low: Swift.min(open, close) - wick,
                                close: close, volume: 0)
        }
    }
}

// MARK: Aktualisieren aus dem Widget

/// Holt die Kurse wie die App (Kurse, Kurs-Mitteilungen) und speichert sie.
/// Alarme wertet das Widget NICHT aus (das tun App und Hintergrund-Aufgabe, unter der
/// `AlarmLease`); ein scharfer Alarm meldet beim nächsten Durchlauf dort.
/// Ansagen (Sprachausgabe) gibt es im Widget nicht.
enum WidgetRefresh {
    /// Älter als das → beim Zeichnen zuerst neu holen.
    static let staleAfterMillis: Int64 = 10 * 60_000
    private static let runningKey = "widget_refresh_running_at"
    /// So lange gilt eine laufende Aktualisierung als aktiv (danach verwaist).
    private static let runningTimeoutMillis: Int64 = 60_000

    static var isStale: Bool {
        TimeUtils.nowMillis - SharedStorage.lastRefreshAt >= staleAfterMillis
    }

    /// Volle Aktualisierung. false = es lief schon eine.
    @discardableResult
    static func run() async -> Bool {
        let defaults = SharedStorage.defaults
        let started = TimeUtils.nowMillis
        let running = Int64(defaults.double(forKey: runningKey))
        if running > 0, started - running >= 0, started - running < runningTimeoutMillis { return false }
        defaults.set(Double(started), forKey: runningKey)
        defer { defaults.removeObject(forKey: runningKey) }

        let settings = SharedStorage.loadSettings()
        let snapshot = SharedStorage.loadSnapshot()
        let outcome = await PriceRefresher.refresh(snapshot: snapshot, settings: settings, evaluateAlarms: false)

        // Frisch lesen und nur die Kurse je Id übertragen — als ein abgestimmter Schritt mit der App
        // (sonst ginge ein dort gerade hinzugefügtes Paar oder ein Alarm-Zustand verloren). Unlesbare
        // oder nicht zugreifbare Datei: nichts schreiben (`SharedStorage.updateSnapshot`).
        guard SharedStorage.updateSnapshot({ outcome.apply(to: &$0) }) != nil else { return true }
        if outcome.failed < outcome.checked {
            SharedStorage.lastRefreshAt = TimeUtils.nowMillis
            SharedStorage.lastRefreshDuration = outcome.durationMillis
        }
        if let report = outcome.report { SharedStorage.lastRefreshReport = report }
        // Mit dieser %-Basis gerechnet (Pille, Widgets und Live-Aktivität prüfen das)
        if let stamp = outcome.changeStamp { SharedStorage.changeStamp = stamp }
        return true
    }

    /// Aktualisiert, wenn die Daten alt sind — wartet aber höchstens `deadline` Sekunden.
    /// Wird die Aktualisierung erst später fertig, zeichnet sie alle Widgets neu.
    /// Bei rechtzeitigem Ende werden die anderen Widget-Arten neu gezeichnet.
    static func refreshIfStale(deadline: Double, otherKinds: [String]) async {
        guard isStale, !SharedStorage.loadSnapshot().watches.isEmpty else { return }
        let gate = WidgetDeadlineGate()
        let inTime: Bool = await withCheckedContinuation { continuation in
            gate.set(continuation)
            Task {
                let ran = await run()
                if !gate.finish(true), ran {
                    WidgetCenter.shared.reloadAllTimelines()
                }
            }
            Task {
                try? await Task.sleep(nanoseconds: UInt64(deadline * 1_000_000_000))
                _ = gate.finish(false)
            }
        }
        if inTime {
            for kind in otherKinds { WidgetCenter.shared.reloadTimelines(ofKind: kind) }
        }
    }
}

/// Lässt eine Fortsetzung genau einmal weiterlaufen (Ergebnis oder Zeitgrenze).
private final class WidgetDeadlineGate: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Bool, Never>?
    private var done = false

    func set(_ c: CheckedContinuation<Bool, Never>) {
        lock.lock()
        continuation = c
        lock.unlock()
    }

    /// true, wenn dieser Aufruf die Fortsetzung ausgelöst hat.
    func finish(_ value: Bool) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard !done, let c = continuation else { return false }
        done = true
        continuation = nil
        c.resume(returning: value)
        return true
    }
}

// MARK: Mini-Chart

/// Kerzen (OHLC mit Startzeit) für den Chart des Einzel-Widgets — gleiches Paar, auch wenn
/// das Widget eine andere Börse zeigt. Quelle über die Ausweich-Kette (Binance,
/// Binance.US, Coinbase), siehe `CandleDataSource`. 10 Minuten zwischengespeichert.
enum WidgetSparkline {
    private static let cacheMillis: Int64 = 10 * 60_000
    /// Schlägt das Netz fehl, darf ein älterer Chart noch so lange gezeigt werden.
    private static let staleFallbackMillis: Int64 = 60 * 60_000
    /// Werte je Kerze im Zwischenspeicher: Startzeit, Open, High, Low, Close.
    private static let fields = 5

    static func symbol(base: String, quote: String) -> String? {
        var q = quote.uppercased()
        if q == "USD" { q = "USDT" }
        let s = base.uppercased() + q
        guard !s.isEmpty, s.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) else { return nil }
        return s
    }

    /// Kerzen im gewählten Zeitraum (aufsteigend); Zwischenspeicher je Paar und Zeitraum.
    /// Ältere Einträge (nur Schlusskurse unter «v») werden nicht mehr gelesen und beim
    /// nächsten Laden ersetzt.
    static func candles(base: String, quote: String, range: WidgetChartRangeOption = .day) async -> [MarketCandle]? {
        guard let symbol = symbol(base: base, quote: quote) else { return nil }
        let key = "widget_spark_" + symbol + "|" + range.rawValue
        let now = TimeUtils.nowMillis
        let cached = SharedStorage.defaults.dictionary(forKey: key)
        let cachedAt = Int64((cached?["t"] as? Double) ?? 0)
        let cachedCandles = decode((cached?["c"] as? [Double]) ?? [])
        if cachedCandles.count >= 2, now - cachedAt < cacheMillis { return cachedCandles }

        if let candles = await CandleDataSource.candles(base: base, quote: quote,
                                                        interval: range.candleInterval, limit: range.limit),
           candles.count >= 2 {
            let entry: [String: Any] = ["t": Double(TimeUtils.nowMillis), "c": encode(candles)]
            SharedStorage.defaults.set(entry, forKey: key)
            return candles
        }
        if cachedCandles.count >= 2, now - cachedAt < staleFallbackMillis { return cachedCandles }
        return nil
    }

    private static func encode(_ candles: [MarketCandle]) -> [Double] {
        candles.flatMap { [Double($0.openTime), $0.open, $0.high, $0.low, $0.close] }
    }

    private static func decode(_ flat: [Double]) -> [MarketCandle] {
        guard flat.count >= fields, flat.count % fields == 0 else { return [] }
        return stride(from: 0, to: flat.count, by: fields).map { i in
            MarketCandle(openTime: Int64(flat[i]), open: flat[i + 1], high: flat[i + 2], low: flat[i + 3],
                         close: flat[i + 4], volume: 0)
        }
    }
}
