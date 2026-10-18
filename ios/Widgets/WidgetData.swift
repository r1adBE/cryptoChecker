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

    /// Vorhandene Gruppen, alphabetisch ohne Gross/Klein (wie die App).
    static func groups() -> [String] {
        Array(Set(SharedStorage.loadSnapshot().watches.compactMap(\.groupName)))
            .sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
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
        let now = TimeUtils.nowMillis
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
        let end = (TimeUtils.nowMillis / step) * step
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

// MARK: Aktualisieren aus dem Widget

/// Holt die Kurse wie die App (Kurse, Kurs-Mitteilungen), speichert sie und prüft mit ihnen die
/// Kursalarme — gleicher Ablauf wie App und Hintergrund-Aufgabe: `AlarmLease`, Alarme frisch von der
/// Platte, Nachtruhe und Abklingzeit (`checkAlarms`), zuerst melden und erst nach bestätigter Mitteilung
/// speichern (`Outcome.deliverAlarms`). So meldet kein zweiter Prozess dieselbe Auslösung; die App
/// übernimmt sie beim Speichern (`SharedStorage.mergeNewerAlarmTriggers`) statt sie zurückzusetzen.
/// Alarme mit Zusatzdaten (Volumen, Funding/OI, Hoch/Tief, Umrechnung) prüfen weiter nur App und
/// Hintergrund-Aufgabe: Kerzen und Futures-Daten sprengten Zeit und Speicher der Erweiterung. Die Alarmtöne
/// liegen auch im Bundle der Erweiterung (`tools/gen_xcodeproj.py`), Ansagen gibt es im Widget nicht.
enum WidgetRefresh {
    /// Älter als das → beim Zeichnen zuerst neu holen (`WidgetCadence`).
    static let staleAfterMillis = WidgetCadence.staleAfterMillis
    /// Nach einem misslungenen Versuch so lange keinen neuen aus dem Zeichnen heraus: Sonst bleiben die
    /// Daten «alt», die Widgets laden sich gegenseitig neu und verbrauchen das Neuladen-Kontingent von
    /// WidgetKit (dann stehen sie den Rest des Tages still).
    static let retryAfterMillis = WidgetCadence.retryAfterMillis
    private static let runningKey = "widget_refresh_running_at"
    /// Zeitpunkt des letzten misslungenen Versuchs (App-Group-Speicher, gilt für alle Widgets).
    private static let failedAtKey = "widget_refresh_failed_at"
    /// So lange gilt eine laufende Aktualisierung als aktiv (danach verwaist).
    private static let runningTimeoutMillis: Int64 = 60_000
    /// Endzeit eines Durchlaufs: begrenzt Kurse, Lease und Kursalarme, damit gespeichert und gemeldet ist,
    /// bevor WidgetKit die Erweiterung beendet; Alarme mit Zusatzdaten stellt sie zurück (`deferredAlarms`).
    private static let runBudgetMillis: Int64 = 25_000

    /// Ergebnis von `run()`.
    enum RunResult {
        /// Es lief schon eine Aktualisierung (die zeichnet selbst neu, sobald sie gespeichert hat).
        case busy
        /// Mindestens ein Kurs geholt und gespeichert.
        case saved
        /// Kein Kurs oder nicht gespeichert — vermerkt, siehe `retryAfterMillis`.
        case failed
    }

    static var isStale: Bool {
        TimeUtils.nowMillis - SharedStorage.lastRefreshAt >= staleAfterMillis
    }

    /// Knopf gesperrt bis (Wanduhr, ms; 0 = nie) — nach einem Tipp in der 15-s-Sperre (`WidgetRefreshState`).
    /// Gilt für alle Merkliste-Widgets, wie die Sperre selbst.
    static var blockedUntil: Int64 {
        get { Int64(SharedStorage.defaults.double(forKey: blockedUntilKey)) }
        set { SharedStorage.defaults.set(Double(newValue), forKey: blockedUntilKey) }
    }
    private static let blockedUntilKey = "widget_refresh_blocked_until"

    /// Läuft gerade eine Aktualisierung aus den Widgets (in diesem Prozess oder, innerhalb der Frist, anderswo)?
    static var isRunning: Bool {
        runLock.lock()
        let inProcess = runningInProcess
        runLock.unlock()
        return inProcess || runningSince > 0
    }

    /// Beginn der laufenden Aktualisierung aus den Widgets (ms, innerhalb der Frist); 0 = keine. Das
    /// Merkliste-Widget unterscheidet so eine neu begonnene von der eben fertigen (`WidgetRefreshState.blockedUntil`).
    static var runningSince: Int64 {
        runLock.lock()
        defer { runLock.unlock() }
        let running = Int64(SharedStorage.defaults.double(forKey: runningKey))
        let age = TimeUtils.nowMillis - running
        return running > 0 && age >= 0 && age < runningTimeoutMillis ? running : 0
    }

    /// Schützt Prüfen und Setzen von `runningKey` im selben Prozess: zwei gleichzeitige Timeline-Anfragen sahen sonst
    /// beide «frei» und aktualisierten doppelt. Prozessübergreifend bleibt der Zeitstempel in den App-Group-Defaults.
    private static let runLock = NSLock()
    /// Läuft in diesem Prozess gerade eine Aktualisierung? Nur unter `runLock` lesen oder schreiben.
    // nonisolated(unsafe): jeder Zugriff nur unter `runLock` (NSLock)
    nonisolated(unsafe) private static var runningInProcess = false

    /// Aktualisierung übernehmen (atomar im Prozess); false = läuft schon (hier oder, innerhalb der Frist, anderswo).
    private static func claimRun(started: Int64) -> Bool {
        runLock.lock()
        defer { runLock.unlock() }
        guard !runningInProcess else { return false }
        let defaults = SharedStorage.defaults
        let running = Int64(defaults.double(forKey: runningKey))
        if running > 0, started - running >= 0, started - running < runningTimeoutMillis { return false }
        defaults.set(Double(started), forKey: runningKey)
        runningInProcess = true
        return true
    }

    /// Nur der Besitzer gibt frei: Den Zeitstempel löschen, solange es noch der eigene ist (hat nach Ablauf der Frist
    /// ein anderer Prozess übernommen, bleibt dessen Merker stehen).
    private static func releaseRun(started: Int64) {
        runLock.lock()
        defer { runLock.unlock() }
        runningInProcess = false
        let defaults = SharedStorage.defaults
        if Int64(defaults.double(forKey: runningKey)) == started { defaults.removeObject(forKey: runningKey) }
    }

    /// Ist der letzte misslungene Versuch jünger als `retryAfterMillis`? (Uhr zurückgestellt: nein.)
    static var recentlyFailed: Bool {
        let failedAt = Int64(SharedStorage.defaults.double(forKey: failedAtKey))
        let age = TimeUtils.nowMillis - failedAt
        return failedAt > 0 && age >= 0 && age < retryAfterMillis
    }

    /// Volle Aktualisierung. Der Knopf «Aktualisieren» ruft sie direkt auf, auch kurz nach einem
    /// misslungenen Versuch (`retryAfterMillis` gilt nur für `refreshIfStale`).
    @discardableResult
    static func run() async -> RunResult {
        let defaults = SharedStorage.defaults
        let started = TimeUtils.nowMillis
        guard claimRun(started: started) else { return .busy }
        defer { releaseRun(started: started) }

        let settings = SharedStorage.loadSettings()
        let snapshot = SharedStorage.loadSnapshot()
        // Speichergrenze der Erweiterung (~30 MB): keine grossen ungefilterten Sammellisten (`LowMemoryBulk`).
        // Mit Endzeit prüft `refresh` nur die Kursalarme; die mit Zusatzdaten (`deferredAlarms`) bleiben liegen.
        let outcome = await PriceRefresher.refresh(snapshot: snapshot, settings: settings,
                                                   deadline: RefreshDeadline(endMillis: started + runBudgetMillis),
                                                   lowMemory: true)

        // Frisch lesen und nur Kurse und Alarm-Zustand je Id übertragen — als ein abgestimmter Schritt mit der
        // App (sonst ginge ein dort gerade hinzugefügtes Paar verloren). Unlesbare oder nicht zugreifbare
        // Datei: nichts schreiben (`SharedStorage.updateSnapshot`) und nichts melden — die Alarme bleiben scharf.
        guard let saved = SharedStorage.updateSnapshot({ outcome.apply(to: &$0) }) else {
            outcome.releaseAlarmLease()
            defaults.set(Double(TimeUtils.nowMillis), forKey: failedAtKey)
            CheckRunStore.log(.widget, .failed, startedAt: started)
            return .failed
        }
        CheckRunStore.log(.widget, CheckRunLog.result(checked: outcome.checked, failed: outcome.failed), startedAt: started)
        let gotPrices = outcome.failed < outcome.checked
        if gotPrices {
            // Nur Kursalarme: `lastAlarmCheckAt` bleibt, sonst übersprünge die Hintergrund-Aufgabe die Alarme
            // mit Zusatzdaten
            SharedStorage.lastRefreshAt = TimeUtils.nowMillis
            SharedStorage.lastRefreshDuration = outcome.durationMillis
        }
        if let report = outcome.report { SharedStorage.lastRefreshReport = report }
        // Mit dieser %-Basis gerechnet (Pille, Widgets und Live-Aktivität prüfen das)
        if let stamp = outcome.changeStamp { SharedStorage.changeStamp = stamp }
        // Erst jetzt Kursalarme melden, je bestätigter Mitteilung speichern und die Lease freigeben — nach der
        // Uhrzeit, damit sie zur eben geschriebenen Datei passt (`WidgetDataFreshness`), auch wenn das Melden dauert
        _ = await outcome.deliverAlarms(settings: settings, current: saved.alarms,
                                        save: { PriceRefresher.saveAlarmOnDisk($0, $1) })
        // Ohne einen einzigen Kurs (offline, alle Börsen mit Fehler oder pausiert) bleiben die Daten
        // alt: vermerken, damit das Zeichnen nicht gleich wieder einen Versuch startet
        if gotPrices {
            defaults.removeObject(forKey: failedAtKey)
            return .saved
        }
        defaults.set(Double(TimeUtils.nowMillis), forKey: failedAtKey)
        return .failed
    }

    /// Wartezeit beim Zeichnen: danach zeichnet das Widget sofort mit den gespeicherten Kursen, die
    /// Aktualisierung läuft weiter und lädt bei Erfolg nach. Kurz gehalten, weil WidgetKit eine Timeline, die
    /// zu lange braucht, verwirft (dann bliebe das Widget leer oder auf dem alten Stand stehen).
    static let drawDeadlineSeconds: Double = 8

    /// Aktualisiert, wenn die Daten alt sind — wartet aber höchstens `deadline` Sekunden.
    /// Kein neuer Versuch innerhalb von `retryAfterMillis` nach einem misslungenen.
    /// Die anderen Widget-Arten werden nur neu gezeichnet, wenn wirklich Kurse gespeichert wurden:
    /// rechtzeitig → `otherKinds`; erst später fertig → `otherKinds` und die eigene Art (`ownKind`), die schon
    /// mit den alten Kursen gezeichnet hat. Keine Schleife: nach dem Speichern sind die Daten nicht mehr alt,
    /// das erneute Zeichnen holt also nichts. Andere Arten (Portfolio, Puls) zeigen diese Kurse nicht.
    static func refreshIfStale(deadline: Double = drawDeadlineSeconds, ownKind: String, otherKinds: [String]) async {
        guard isStale, !recentlyFailed, !SharedStorage.loadSnapshot().watches.isEmpty else { return }
        let gate = WidgetDeadlineGate()
        let savedInTime: Bool = await withCheckedContinuation { continuation in
            gate.set(continuation)
            Task {
                let saved = (await run()) == .saved
                if !gate.finish(saved), saved {
                    WidgetData.reloadKinds([ownKind] + otherKinds, reason: "spät gespeichert")
                }
            }
            Task {
                try? await Task.sleep(nanoseconds: UInt64(deadline * 1_000_000_000))
                _ = gate.finish(false)
            }
        }
        if savedInTime {
            WidgetData.reloadKinds(otherKinds, reason: "gespeichert")
        } else {
            AppLog.widget.info("Zeichnen mit gespeicherten Kursen (Netz langsamer als \(Int(deadline), privacy: .public) s)")
        }
    }
}

/// Lässt eine Fortsetzung genau einmal weiterlaufen (Ergebnis oder Zeitgrenze).
private final class WidgetDeadlineGate: @unchecked Sendable { // @unchecked: veränderlicher Zustand nur unter `lock` (NSLock)
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

/// Kerzen (OHLC mit Startzeit) für den Chart des Einzel-Widgets: zuerst von der EIGENEN Börse des
/// Paars (`OwnCandleDataSource`), sonst gleiches Paar über die Ausweich-Kette (Binance, Binance.US,
/// Coinbase), siehe `CandleDataSource`. 10 Minuten zwischengespeichert.
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

    /// Kerzen für den Chart eines Paars: zuerst von seiner eigenen Börse, sonst über die Ausweich-Kette
    /// (Futures-Paare zuerst USDⓈ-M) — je nur bei gleichem Token (`WidgetChartGeometry.pairCandles`).
    /// TradFi-Paare und DEX-Pools nie aus der Kette. Wie `SingleWidgetRenderer.chartCandles` (Android).
    /// `provider`: Börse der Kerzen (eigene Börse oder die der Ausweich-Kette; nil = unbekannt).
    static func candles(for watch: Watch, range: WidgetChartRangeOption) async -> Sourced<[MarketCandle]>? {
        let pair = CandlePair.of(watch)
        let price = watch.lastPrice
        if OwnCandleDataSource.supports(pair, interval: range.candleInterval) {
            let key = "widget_spark_own_" + pair.cacheKey + "|" + range.rawValue
            let now = TimeUtils.nowMillis
            let cached = load(key)
            if now - cached.at < cacheMillis, let hit = WidgetChartGeometry.pairCandles(cached.candles, price: price) {
                return Sourced(value: hit, provider: watch.marketName)
            }
            let own = await OwnCandleDataSource.shared.candles(pair, interval: range.candleInterval, limit: range.limit)
            if let accepted = WidgetChartGeometry.pairCandles(own?.value, price: price) {
                save(key, accepted)
                return Sourced(value: accepted, provider: watch.marketName)
            }
            if now - cached.at < staleFallbackMillis, let old = WidgetChartGeometry.pairCandles(cached.candles, price: price) {
                return Sourced(value: old, provider: watch.marketName)
            }
        }
        guard OwnCandlePolicy.allowsFallback(pair) else { return nil }
        let fallback = await sourcedCandles(base: watch.baseAsset, quote: watch.quoteAsset, range: range,
                                            preferFutures: pair.isFutures, price: price)
        guard let accepted = WidgetChartGeometry.pairCandles(fallback?.value, price: price) else { return nil }
        // Börse der Ausweich-Reihe (für «Chart: Binance»); klar anders benannter Token (K-8): kein Chart
        let provider: String? = fallback?.provider
        guard TokenIdentityStore.shared.fallbackSeriesAllowed(marketKey: watch.marketKey, base: watch.baseAsset,
                                                              provider: provider) else { return nil }
        return Sourced(value: accepted, provider: provider)
    }

    /// Kerzen im gewählten Zeitraum (aufsteigend); Zwischenspeicher je Paar und Zeitraum.
    /// Ältere Einträge (nur Schlusskurse unter «v») werden nicht mehr gelesen und beim
    /// nächsten Laden ersetzt. `price`: Kurs des Paars — passt die Reihe einer Quelle nicht
    /// (anderer Token), ist die nächste der Kette dran.
    static func candles(base: String, quote: String, range: WidgetChartRangeOption = .day,
                        preferFutures: Bool = false, price: Double? = nil) async -> [MarketCandle]? {
        await sourcedCandles(base: base, quote: quote, range: range, preferFutures: preferFutures, price: price)?.value
    }

    /// Wie `candles(base:…)`, dazu die Börse der Reihe (im Zwischenspeicher unter «p»; älterer Eintrag ohne = nil).
    static func sourcedCandles(base: String, quote: String, range: WidgetChartRangeOption = .day,
                               preferFutures: Bool = false, price: Double? = nil) async -> Sourced<[MarketCandle]>? {
        guard let symbol = symbol(base: base, quote: quote) else { return nil }
        let key = "widget_spark_" + symbol + "|" + range.rawValue + (preferFutures ? "|F" : "")
        let now = TimeUtils.nowMillis
        let cached = load(key)
        if cached.candles.count >= 2, now - cached.at < cacheMillis {
            return Sourced(value: cached.candles, provider: cached.provider)
        }

        // Fremde Reihe gleichen Kürzels: nächste Quelle der Kette
        var accept: (@Sendable ([MarketCandle]) async -> Bool)? = nil
        if let p = price { accept = { raw in WidgetChartGeometry.pairCandles(raw, price: p) != nil } }
        let sourced = await CandleDataSource.candlesSourced(base: base, quote: quote, interval: range.candleInterval,
                                                            limit: range.limit, preferFutures: preferFutures,
                                                            accept: accept)
        if let sourced, sourced.value.count >= 2 {
            save(key, sourced.value, provider: sourced.provider)
            return sourced
        }
        if cached.candles.count >= 2, now - cached.at < staleFallbackMillis {
            return Sourced(value: cached.candles, provider: cached.provider)
        }
        return nil
    }

    /// Gespeicherte Kerzen mit Zeitpunkt und Börse (leer/0/nil, wenn nichts da ist).
    private static func load(_ key: String) -> (at: Int64, candles: [MarketCandle], provider: String?) {
        let cached = SharedStorage.defaults.dictionary(forKey: key)
        let at = Int64((cached?["t"] as? Double) ?? 0)
        let candles = decode((cached?["c"] as? [Double]) ?? [])
        let provider = cached?["p"] as? String
        return candles.count >= 2 ? (at, candles, provider) : (0, [], nil)
    }

    private static func save(_ key: String, _ candles: [MarketCandle], provider: String? = nil) {
        // Einmal am Tag alte Einträge (entfernte Paare, andere Zeiträume) wegräumen
        WidgetSparkCache.pruneIfDue()
        var entry: [String: Any] = ["t": Double(TimeUtils.nowMillis), "c": encode(candles)]
        if let provider { entry["p"] = provider }
        SharedStorage.defaults.set(entry, forKey: key)
    }

    private static func encode(_ candles: [MarketCandle]) -> [Double] {
        var out: [Double] = []
        out.reserveCapacity(candles.count * fields)
        for c in candles {
            out.append(Double(c.openTime))
            out.append(c.open)
            out.append(c.high)
            out.append(c.low)
            out.append(c.close)
        }
        return out
    }

    private static func decode(_ flat: [Double]) -> [MarketCandle] {
        guard flat.count >= fields, flat.count % fields == 0 else { return [] }
        // In Schritten mit festen Typen: der lange Ausdruck in einer Closure war für den Compiler zu teuer
        var out: [MarketCandle] = []
        out.reserveCapacity(flat.count / fields)
        var i = 0
        while i + fields <= flat.count {
            let openTime = Int64(flat[i])
            let open: Double = flat[i + 1]
            let high: Double = flat[i + 2]
            let low: Double = flat[i + 3]
            let close: Double = flat[i + 4]
            out.append(MarketCandle(openTime: openTime, open: open, high: high, low: low, close: close, volume: 0))
            i += fields
        }
        return out
    }
}
